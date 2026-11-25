#!/usr/bin/env python3
"""Read a QMK `keyboard.json` / `info.json` the way QMK itself does.

**QMK's metadata is not strict JSON and never claimed to be.** Its own loader accepts
comments and trailing commas, so 85 of the 4,039 files in the tree -- 2.1% -- throw on
`json.loads`. A reader that wraps that in `except ValueError: continue` does not fail
loudly, it goes MISSING: no key count, no layout, no url, and a design that quietly
declines or draws a placeholder instead of a keyboard.

THIS CODE IS NOT NEW. It is `build_qmk_corpus.py`'s `_tolerant`, moved here unchanged so
that the scripts which did not have it can stop going blind, and so there is exactly ONE
answer to "what is a QMK metadata file" -- the same reason `scan_boards.py` takes its
family tables from `extract_switch_footprints.py` rather than restating them. Its own
history is worth keeping, because it is the argument for centralising it:

    The bug this replaced was mine, found 2026-08-20 while measuring the Scotto
    boards: 15 of 28 `handwired/scottokeebs/*` records reported zero layouts, which
    read as "declares no layout" when the truth was "the file has // comments in
    it". Eighth instrument fault on this map, and the first self-inflicted one.

Moved 2026-08-29, chasing `reviung34`, which draws a dashed outline on the site because
`build_silhouettes.py` and `build_seed.py` each had their own strict `json.loads`. Its
`keyboard.json` declares `LAYOUT_2x1u_left` (34 keys) and `LAYOUT_1x2u_left` (33) --
exactly the counts our design carries -- behind one trailing comma.

Measured over the whole tree: **67 of the 85 recover, 18 do not**, and the 18 are
genuinely broken (Jinja placeholders, unquoted keys) rather than merely JSON5. Callers
still get a `ValueError` for those, because a file QMK cannot read either is a fact about
the file and not something to paper over.

THE REGEX IS STRING-AWARE, which is the only subtle thing here: `"url": "https://..."`
contains `//`, so the alternation matches a whole string literal FIRST and puts it back
untouched, and only then treats `//` as a comment. Verified on this tree: of the 3,954
files that already parse strictly, the stripper changes the meaning of **none**.

WHY NOT A LIBRARY. `json5` and `hjson` both do this properly and neither is installed;
these scripts are deliberately runnable offline against nothing but the standard library,
the same reason `draft_sources.py` writes its dev-seed paths out by hand.
"""
import json
import re

_LINE_COMMENT = re.compile(r'("(?:[^"\\]|\\.)*")|//[^\n]*|/\*.*?\*/', re.S)
_TRAILING_COMMA = re.compile(r',(\s*[}\]])')


def tolerant(text):
    """Strip JSON5 comments and trailing commas without touching string bodies."""
    text = _LINE_COMMENT.sub(lambda m: m.group(1) or '', text)
    return _TRAILING_COMMA.sub(r'\1', text)


def loads(text):
    """Parse QMK metadata, tolerating what QMK tolerates.

    Strict first, so the 97.9% that are ordinary JSON take the ordinary path and cannot
    be affected by the stripper at all.
    """
    try:
        return json.loads(text)
    except ValueError:
        return json.loads(tolerant(text))


def load(path):
    """Parse the file at [path], which may be a `pathlib.Path` or a string."""
    with open(path, errors="ignore") as handle:
        return loads(handle.read())


def dev_board(kb_id, qmk_root):
    """The `development_board` QMK declares for a keyboard, or None.

    Read here rather than in `facts` because it answers a different question and has a
    different inheritance rule. `facts` ACCUMULATES -- a parent's layouts and a child's
    are all layouts of the same keyboard -- while this is a single value the most
    specific directory owns: `kin80/blackpill401` and `kin80/blackpill411` are two dev
    boards under one parent, and an outward walk that let the parent win would give both
    the same answer or none.

    So the walk goes INWARD-OUT and stops at the first hit, which is the most specific
    file. QMK's own metadata loader inherits the same way.
    """
    parts = kb_id.split("/")
    for i in range(len(parts), 0, -1):
        for fn in ("keyboard.json", "info.json"):
            path = qmk_root.joinpath(*parts[:i])/fn
            if not path.exists():
                continue
            try:
                data = load(path)
            except ValueError:
                continue
            if data.get("development_board"):
                return data["development_board"]
    return None


_DEFAULTS = {}


def board_processors(qmk_root):
    """QMK's OWN `development_board` -> processor table, read from the tree.

    `data/mappings/defaults.hjson` is where QMK defines what each shorthand expands to,
    and reading it is the difference between a definition and a guess. `promicro` means
    `atmega32u4` because QMK says so in that file, not because a Pro Micro usually has
    one -- the distinction this codebase keeps having to relearn, most recently when a
    footprint scan's silence was read as "this board has no encoder".

    Read from the tree rather than transcribed, so a board QMK adds next month resolves
    without anyone noticing it needed to. Cached per root; `{}` if the file is missing,
    which makes the fallback in [processor] a no-op rather than an error.
    """
    key = str(qmk_root)
    if key not in _DEFAULTS:
        f = qmk_root.parent/"data/mappings/defaults.hjson"
        try:
            table = load(f)
        except (OSError, ValueError):
            table = {}
        # The map is NESTED under its own key -- the file's whole top level is
        # `{"development_board": {...}}` -- so iterating the top level yields one
        # entry whose value is the table, and an empty result that fails silently.
        boards = table.get("development_board") or {}
        _DEFAULTS[key] = {k: str(v["processor"]).lower()
                          for k, v in boards.items()
                          if isinstance(v, dict) and v.get("processor")}
    return _DEFAULTS[key]


def processor(kb_id, qmk_root):
    """The chip QMK says a keyboard runs, or None.

    Same inheritance as [dev_board] and for the same reason -- one value the most
    specific directory owns.

    FALLS BACK TO `development_board`, which is not a second-best guess: the two keys are
    mutually exclusive by construction (measured over all 3,690 records, exactly 0 declare
    both -- ticket 16), so a record using the shorthand declares its processor in the only
    way it can. QMK's own expansion table decides what it means; see [board_processors].
    Worth 84 records in this corpus that otherwise read as silent, and it is what takes
    socketed designs from 55 to about 115.
    """
    parts = kb_id.split("/")
    for i in range(len(parts), 0, -1):
        for fn in ("keyboard.json", "info.json"):
            path = qmk_root.joinpath(*parts[:i])/fn
            if not path.exists():
                continue
            try:
                data = load(path)
            except ValueError:
                continue
            if data.get("processor"):
                return str(data["processor"]).lower()
            if data.get("development_board"):
                # The most specific file wins whichever key it used, so this returns
                # from inside the walk rather than after it -- a parent's `processor`
                # must not beat a child's `development_board`.
                return board_processors(qmk_root).get(data["development_board"])
    return None


def wiring(kb_id, qmk_root):
    """How QMK scans a keypress for this keyboard: `matrix_pins` and `diode_direction`.

    Same inheritance rule as [dev_board] and [processor] -- each of the two keys is a
    value the most specific directory owns -- applied to BOTH keys independently rather
    than to `matrix_pins` as one atomic value, because they are not always declared at
    the same level: a `keyboard.json` can state a shared `matrix_pins` once for every
    revision while each revision's own `info.json` states its own `diode_direction`
    (or the reverse). `scripts/measure_key_wiring.py` validates this reading against
    508 confirmed QMK rows: 442 resolve as a matrix, 42 as direct pins, matching this
    function's own count when run over the same rows.

    Returns one of:
      ("direct", pins)             -- `pins` counts only the non-null entries in
                                       `matrix_pins.direct`; QMK pads a direct matrix's
                                       row-major table with `null` for unused slots,
                                       and those are not wires.
      ("matrix", rows, cols, dd)   -- `dd` is QMK's own string, "COL2ROW" or "ROW2COL"
      None                         -- nothing resolved, or a matrix with no stated
                                       diode direction (ticket 51: a matrix's ghosting
                                       immunity depends on it, so it is not guessed)

    QMK only. ZMK states this in a shield's `.overlay` `kscan` node, a different
    parser: `zmk_wiring.wiring`, which returns these same shapes.
    """
    parts = kb_id.split("/")
    matrix_pins, diode_direction = None, None
    for i in range(len(parts), 0, -1):
        for fn in ("keyboard.json", "info.json"):
            path = qmk_root.joinpath(*parts[:i])/fn
            if not path.exists():
                continue
            try:
                data = load(path)
            except ValueError:
                continue
            if matrix_pins is None and data.get("matrix_pins"):
                matrix_pins = data["matrix_pins"]
            if diode_direction is None and data.get("diode_direction"):
                diode_direction = data["diode_direction"]
        if matrix_pins is not None and diode_direction is not None:
            break
    if not matrix_pins:
        return None

    direct = matrix_pins.get("direct")
    if direct:
        pins = sum(1 for row in direct for pin in row if pin)
        return ("direct", pins) if pins else None

    rows, cols = matrix_pins.get("rows"), matrix_pins.get("cols")
    if rows and cols and diode_direction in ("COL2ROW", "ROW2COL"):
        return ("matrix", len(rows), len(cols), diode_direction)
    return None


def facts(kb_id, qmk_root):
    """What QMK DECLARES about a keyboard: its key counts, and whether it is split.

    Moved here from `build_seed.py` 2026-09-01 so that `draft_boards.py` can read the
    record side too -- it needs it to tell one keyboard's controller variants from its
    successive revisions, and it cannot import `build_seed`, which imports it.

    QMK inherits metadata up the keyboard path, so the walk goes from the most specific
    directory outward and the first answer at each level wins.
    """
    counts, split, found = set(), False, False
    parts = kb_id.split("/")
    for i in range(len(parts), 0, -1):
        for fn in ("keyboard.json", "info.json"):
            path = qmk_root.joinpath(*parts[:i])/fn
            if not path.exists():
                continue
            try:
                data = load(path)
            except ValueError:
                continue
            found = True
            for layout in (data.get("layouts") or {}).values():
                if layout.get("layout"):
                    counts.add(len(layout["layout"]))
            if data.get("split", {}).get("enabled"):
                split = True
    return sorted(counts, reverse=True), split, found


def pointing_device_driver(kb_id, qmk_root):
    """QMK's own `POINTING_DEVICE_DRIVER`, or None.

    Backlog #21's follow-on: the value lives in `rules.mk`, a Makefile, not in
    `keyboard.json` or `info.json` -- QMK's build reads pointing-device sensor choice
    from the same place it reads any other feature driver. Same inheritance as
    [dev_board] and for the same reason -- one value the most specific directory owns,
    walked inward-out, first hit wins -- but the file and the syntax are both different:
    a line is read literally as `NAME = value` or `NAME += value`, with a trailing `#`
    comment stripped first. Nothing here evaluates a conditional or an `include`, so a
    driver set inside an `ifeq` block is missed rather than guessed -- a silent absence
    stays silent instead of becoming a false one.
    """
    parts = kb_id.split("/")
    for i in range(len(parts), 0, -1):
        path = qmk_root.joinpath(*parts[:i])/"rules.mk"
        if not path.exists():
            continue
        for line in path.read_text(errors="ignore").splitlines():
            line = line.split("#", 1)[0].strip()
            m = re.match(r"POINTING_DEVICE_DRIVER\s*[:+]?=\s*(\S+)", line)
            if m:
                return m.group(1).lower()
    return None


def joystick_enabled(kb_id, qmk_root):
    """True when QMK's record turns a joystick on; False is silence, never "none".

    Owner, 2026-11-25, on Lime: a joystick on a generic 5-pin connector, which no
    footprint name can show. QMK states it in `JOYSTICK_ENABLE = yes` (a `rules.mk`) or
    `"features": {"joystick": true}`, at the keyboard's own levels or in its DEFAULT
    keymap -- the author's shipped build. Lime's lives only there. Any other keymap is
    one user's build, not the board's, and is not read. Same literal reading as
    [pointing_device_driver]: no conditional or `include` is evaluated.
    """
    parts = kb_id.split("/")
    for i in range(len(parts), 0, -1):
        base = qmk_root.joinpath(*parts[:i])
        for rules in (base/"rules.mk", base/"keymaps"/"default"/"rules.mk"):
            if not rules.exists():
                continue
            for line in rules.read_text(errors="ignore").splitlines():
                line = line.split("#", 1)[0].strip()
                if re.match(r"JOYSTICK_ENABLE\s*[:+]?=\s*yes\b", line, re.I):
                    return True
        for name in ("keyboard.json", "info.json"):
            path = base/name
            if not path.exists():
                continue
            try:
                features = json.loads(path.read_text(errors="ignore")).get("features") or {}
            except ValueError:
                continue
            if features.get("joystick") is True:
                return True
    return False
