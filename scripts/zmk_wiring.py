#!/usr/bin/env python3
"""How a ZMK shield scans a keypress: its `kscan` node's row, column or direct GPIOs,
and which way its diodes point -- the ZMK side of `qmk_metadata.wiring()`.

Every design in this corpus with only ZMK firmware lacked `wiring` (34 of 34 on
2026-09-26, `tg4x` among them), because `build_seed.py` read wiring from QMK's
`matrix_pins` alone. A ZMK shield states the same two facts in its `.overlay`:

    kscan0: kscan {
        compatible = "zmk,kscan-gpio-matrix";
        diode-direction = "col2row";
        row-gpios = <&pro_micro 9 ...>, <&pro_micro 8 ...>, ...;
        col-gpios = <...>, ...;
    };

`zmk,kscan-gpio-direct` lists `input-gpios` instead, one wire per key.

`wiring` READS UNIBODY SHIELDS ONLY, and `split_wiring` reads splits by a rule of its
own (below). A split shield keeps its row and column GPIOs in per-half overlays
(`corne_left.overlay`, `corne_right.overlay`) over a shared `.dtsi`, so no one file
holds the whole scan and the unibody reader's "exactly one kscan node in the
`.overlay`" test cannot apply.

REFUSED, rather than guessed, wherever the shield is not plainly one board with one
scan: no `<shield>.overlay`, a left/right overlay beside it, a kscan node in a `.dtsi`,
more or fewer than exactly one kscan node, a kscan kind other than matrix or direct
(charlieplex, composite, a demux), or a matrix whose diode direction is not stated. A
ZMK matrix does have a default direction, but ticket 51's rule is that a matrix's
ghosting immunity depends on it, so an unstated one is not filled in from a default.

Returns the same shapes `qmk_metadata.wiring()` does, with `dd` spelled QMK's way
("COL2ROW" / "ROW2COL") so one caller converts both:

    ("direct", pins) | ("matrix", rows, cols, dd) | None

THE SPLIT RULE (`split_wiring`): READ BOTH HALVES, PUBLISH ONLY WHAT THEY AGREE ON. Each
half is what ZMK itself would compile for it -- `<shield>_left.overlay` with the files
it `#include`s inlined, then the kscan node as declared, then every `&kscan0 { ... }`
override applied in order. The answer is ONE HALF's matrix, because that is what the
corpus already publishes for a split: QMK's `matrix_pins` lists one half's pins
(`lily58` is 5 x 6 from QMK, not 5 x 12 or 10 x 6), and a ZMK reading that counted
both halves would put two different numbers on two identical keyboards depending on
which firmware happened to be read. Measured 2026-10-03 against upstream ZMK: 13 of 15
split shields in this corpus have halves that agree exactly.

THE HALVES MUST AGREE, OR NOTHING IS SAID. Two upstream shields have halves that do not:
`quefrency` (each half declares its own kscan node, 5 x 7 left and 6 x 8 right -- the
right half is a 65% with an extra row and column) and `fourier` (six columns left,
seven right). One "rows x columns" line cannot describe either honestly, and picking a
half would publish a number that is wrong for the other one, so both read None. That
is a refusal, not a finding: the design keeps `wiring` Unknown, never "no wiring".

REFUSED, too, wherever a half is not plainly one scan: either overlay missing, a
`<shield>.overlay` beside the halves (a shield that is both), a quoted `#include` that
does not resolve inside the shield directory, a `/delete-property/` or `/delete-node/`
anywhere in a half (this reader does not model devicetree taking a thing away), more
or fewer than exactly one kscan node per half, and every refusal `wiring` already makes
(a kind other than matrix or direct, a matrix with no stated diode direction).

DIRECT-PIN SPLITS FALL OUT OF THE SAME RULE. `cradio` (Sweep) declares one
`zmk,kscan-gpio-direct` node with 17 inputs in `cradio.dtsi` and both halves include it
unchanged, so both halves read ("direct", 17) -- the same per-half count QMK's
`matrix_pins.direct` gives for a split (`a_dux` publishes 17 direct pins).
"""
import pathlib
import re

KSCAN = re.compile(r'compatible\s*=\s*"zmk,kscan-gpio-(\w+)"\s*;')
# One GPIO is one `<&controller pin flags>` cell group; a property lists them
# comma-separated, either as one `<...>, <...>` list per property or (rarely) all
# inside a single pair of angle brackets. Both are counted by the phandles.
PHANDLE = re.compile(r'&\w+\s+\d+')
DIODES = {"col2row": "COL2ROW", "row2col": "ROW2COL"}


def _property(body, name):
    """The raw value of `name = ...;` inside one node body, or None."""
    m = re.search(r'(?<![\w-])' + re.escape(name) + r'\s*=\s*(.*?);', body, re.S)
    return m.group(1) if m else None


def _node_body(text, at):
    """The text of the node whose `compatible` sits at offset `at`: from the opening
    brace before it to its matching closing brace."""
    start = text.rfind("{", 0, at)
    depth, i = 0, start
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
        i += 1
    return None


def _strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def wiring(shield_dir):
    """See the module docstring."""
    shield_dir = pathlib.Path(shield_dir)
    own = shield_dir/f"{shield_dir.name}.overlay"
    if not own.exists():
        return None
    if any(re.search(r"_(left|right)\.overlay$", p.name) for p in shield_dir.glob("*.overlay")):
        return None
    if any(KSCAN.search(_strip_comments(p.read_text(errors="ignore")))
           for p in shield_dir.glob("*.dtsi")):
        return None
    text = _strip_comments(own.read_text(errors="ignore"))
    nodes = list(KSCAN.finditer(text))
    if len(nodes) != 1:
        return None
    body = _node_body(text, nodes[0].start())
    if body is None:
        return None
    return _reading({name: _property(body, name) for name in SCAN_PROPERTIES},
                    nodes[0].group(1))


# The only properties a reading looks at. An override (`&kscan0 { col-gpios = ...; }`)
# replaces any of them; anything else it sets (`wakeup-source`, a debounce) is not read.
SCAN_PROPERTIES = ("row-gpios", "col-gpios", "input-gpios", "diode-direction")


def _reading(props, kind):
    """One kscan node's answer, from its kind and its resolved properties."""
    if kind == "direct":
        count = len(PHANDLE.findall(props.get("input-gpios") or ""))
        return ("direct", count) if count else None
    if kind != "matrix":
        return None
    rows = len(PHANDLE.findall(props.get("row-gpios") or ""))
    cols = len(PHANDLE.findall(props.get("col-gpios") or ""))
    dd = DIODES.get((props.get("diode-direction") or "").strip().strip('"'))
    if not rows or not cols or not dd:
        return None
    return ("matrix", rows, cols, dd)


INCLUDE = re.compile(r'^[ \t]*#include\s+"([^"]+)"[^\n]*$', re.M)
# The `label:` in front of a node's name, read back from the opening brace -- the
# handle an overlay's `&label { ... }` override refers to.
LABEL = re.compile(r'([A-Za-z_]\w*)\s*:\s*[\w,.@+-]+\s*$')


def _inline(path, root, seen=()):
    """`path` with comments stripped and every quoted `#include` replaced by the text of
    the file it names, recursively -- the order ZMK's preprocessor would read them in.

    Angle-bracket includes (`<dt-bindings/...>`, `<layouts/...>`) are ZMK's own headers
    and physical layouts, and stay unread: no kscan node lives in them. A quoted include
    that leaves the shield directory, or names no file, makes the whole half None --
    a half read without one of its own files is not that half."""
    path = path.resolve()
    if path in seen or root not in path.parents or not path.is_file():
        return None
    text = _strip_comments(path.read_text(errors="ignore"))
    out, at = [], 0
    for m in INCLUDE.finditer(text):
        inner = _inline(path.parent/m.group(1), root, seen + (path,))
        if inner is None:
            return None
        out += [text[at:m.start()], inner]
        at = m.end()
    out.append(text[at:])
    return "".join(out)


def _half(overlay, root):
    """What one half of a split scans, or None. See the module docstring's split rule."""
    text = _inline(overlay, root)
    if text is None or "/delete-property/" in text or "/delete-node/" in text:
        return None
    nodes = list(KSCAN.finditer(text))
    if len(nodes) != 1:
        return None
    kind = nodes[0].group(1)
    brace = text.rfind("{", 0, nodes[0].start())
    body = _node_body(text, nodes[0].start())
    label = LABEL.search(text[:brace])
    if body is None or label is None:
        return None
    props = {name: _property(body, name) for name in SCAN_PROPERTIES}
    # Every override of that label, in file order, AFTER the node itself: devicetree
    # applies them in the order it reads them, and a later one wins property by property.
    for ref in re.finditer(r"&" + re.escape(label.group(1)) + r"\s*\{", text):
        if ref.start() < brace:
            return None  # an override read before its node: not a shape seen upstream
        over = _node_body(text, ref.end())
        if over is None:
            return None
        if KSCAN.search(over):
            return None  # an override that changes the scan kind; not modelled
        for name in SCAN_PROPERTIES:
            value = _property(over, name)
            if value is not None:
                props[name] = value
    return _reading(props, kind)


def split_wiring(shield_dir, shield=None):
    """A split shield's wiring, one half's worth, where both halves agree; else None.

    `shield` is the shield's id, which defaults to the directory's name and differs from
    it where one directory holds several shields (`kyria/` holds `kyria_rev2_left`...).
    See the module docstring for the rule."""
    shield_dir = pathlib.Path(shield_dir).resolve()
    shield = shield or shield_dir.name
    left = shield_dir/f"{shield}_left.overlay"
    right = shield_dir/f"{shield}_right.overlay"
    if not left.is_file() or not right.is_file() or (shield_dir/f"{shield}.overlay").exists():
        return None
    a, b = _half(left, shield_dir), _half(right, shield_dir)
    return a if a is not None and a == b else None


if __name__ == "__main__":
    import sys
    for arg in sys.argv[1:]:
        print(arg, wiring(arg), split_wiring(arg))
