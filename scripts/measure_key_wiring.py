#!/usr/bin/env python3
"""Measure what the corpus declares about key wiring, from QMK's own metadata.

Backs the resolution of ticket 51.
This is a MEASUREMENT script, not the extractor: it reports what QMK states for each
confirmed seed row so the numbers in that ticket are reproducible.

Four findings this script exists to reproduce:

  * Wiring's source is the FIRMWARE, not the hardware. `info.json` declares it
    because it IS how the firmware scans, so the PCB-versus-hand-wire asymmetry that
    looks decisive for this facet is irrelevant to knowability: 389 of 406 confirmed
    QMK rows (95.8%) declare a matrix or direct pins.
  * QMK's `keyboard.json` is NOT strict JSON -- `//` comments and trailing commas,
    which QMK's own tooling parses leniently. A strict `json.load` silently drops 19
    of the 406 rows (4.7%), including every Scotto hand-wire, and reports 91.1%
    coverage instead of 95.8%. This is the trap the importer must not walk into.
  * Four of `architecture.md` §1.6's six variants have no instances worth modelling.
    Across all of QMK: Matrix 3177, DirectPin 161, DuplexMatrix 22, IoExpander 20,
    Charlieplex 1, Analog 0 -- every `analog` hit is a thumbstick or audio, never key
    sensing. Run with --qmk-wide to reproduce those counts.
  * `rows * cols >= keys` is NOT a safe invariant. It fails on 85 of 352 rows,
    because QMK declares the matrix per CONTROLLER rather than per design;
    multiplying by halves rescues 66 and still leaves the 19-strong Charybdis family,
    whose split flag is absent from the merged `info.json`. Hence no such check ships.

Scope: QMK only. The 31 confirmed ZMK rows declare wiring in a shield's `.overlay`
`kscan` node, which is a different parser and is not measured here.

Usage:  measure_key_wiring.py [--qmk <path>] [--seed <path>] [--qmk-wide]
"""

import argparse
import collections
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import trees as tree_registry  # noqa: E402

# QMK's keyboard.json is JSON5-ish. Strip comments outside strings, then trailing
# commas. Naive regex comment-stripping corrupts every "https://..." in the file,
# which is why this is a scanner and not a one-line `re.sub`.
def tolerant(text):
    out, i, n, instr, esc = [], 0, len(text), False, False
    while i < n:
        c = text[i]
        if instr:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                instr = False
            i += 1
            continue
        if c == '"':
            instr = True
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return re.sub(r",(\s*[}\]])", r"\1", "".join(out))


def load(path):
    """Returns (parsed_or_None, 'strict' | 'tolerant' | 'unparseable')."""
    raw = path.read_text(errors="ignore")
    try:
        return json.loads(raw), "strict"
    except Exception:
        pass
    try:
        return json.loads(tolerant(raw)), "tolerant"
    except Exception:
        return None, "unparseable"


def merged(keyboards, idpath):
    """QMK merges info.json up the directory tree; the child wins."""
    out, how = {}, set()
    parts = pathlib.PurePath(idpath).parts
    for i in range(len(parts)):
        for fn in ("info.json", "keyboard.json"):
            f = keyboards.joinpath(*parts[: i + 1]) / fn
            if f.is_file():
                parsed, mode = load(f)
                how.add(mode)
                if parsed:
                    out.update(parsed)
    return out, how


def seed_rows(seed):
    text = seed.read_text()
    blocks = text.split("[[entry]]")[1:]
    return [dict(re.findall(r'^(\w+)\s*=\s*"?([^"\n]*)"?', b, re.M)) for b in blocks]


def classify(info):
    mp = info.get("matrix_pins") or {}
    if "direct" in mp:
        return "DirectPin"
    if "rows" in mp and "cols" in mp:
        return "Matrix + diode_direction" if info.get("diode_direction") else "Matrix, diode_direction ABSENT"
    return "resolved, no matrix_pins" if info else "nothing resolved"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--qmk", type=pathlib.Path, default=pathlib.Path.home() / "code/qmk_firmware")
    ap.add_argument("--seed", type=pathlib.Path, default=pathlib.Path("seed/sources.toml"))
    ap.add_argument("--qmk-wide", action="store_true", help="also count variants across all of QMK")
    args = ap.parse_args()

    keyboards = args.qmk / "keyboards"
    if not keyboards.is_dir():
        sys.exit(f"no QMK checkout at {args.qmk}")

    # EACH ROW FROM ITS OWN TREE, backlog #64. `tree` is a key into `seed/trees.toml`,
    # and a fork of QMK reads with QMK's reader from a different checkout -- so the
    # question "is this a QMK record" is about the READER, and the root that answers it
    # is the row's own. Reading a fork-hosted record from upstream would count it as
    # `unparseable` and quietly lower the coverage number this instrument exists to state.
    reg = tree_registry.load(qmk=args.qmk)
    rows = [r for r in seed_rows(args.seed)
            if r.get("tree") in reg and reg.reader(r["tree"]) == "qmk"
            and r.get("verdict") == "confirmed"]
    verdict, tolerated, unparseable, invariant, still = collections.Counter(), [], [], collections.Counter(), []

    for r in rows:
        info, how = merged(reg.root(r["tree"]) or keyboards, r.get("id", ""))
        if "tolerant" in how:
            tolerated.append(r["id"])
        if "unparseable" in how:
            unparseable.append(r["id"])
        verdict[classify(info)] += 1

        mp = info.get("matrix_pins") or {}
        if "rows" in mp and "cols" in mp:
            cells = len(mp["rows"]) * len(mp["cols"])
            layouts = info.get("layouts") or {}
            keys = max((len(v.get("layout", [])) for v in layouts.values()), default=0)
            if keys:
                split = isinstance(info.get("split"), dict) and bool(info["split"].get("enabled"))
                if cells >= keys:
                    invariant["holds at x1"] += 1
                elif cells * 2 >= keys and split:
                    invariant["holds only at x2 (declared split)"] += 1
                else:
                    invariant["STILL violated"] += 1
                    still.append((r["id"], cells, keys))

    total = len(rows)
    print(f"{total} confirmed QMK seed rows\n")
    for k, n in verdict.most_common():
        print(f"{n:5d}  ({100 * n / total:5.1f}%)  {k}")
    declared = sum(v for k, v in verdict.items() if k.startswith(("Matrix", "DirectPin")))
    print(f"\n{declared}/{total} = {100 * declared / total:.1f}% declare their wiring")
    print(f"\nrows a STRICT json.load would lose: {len(tolerated)} ({100 * len(tolerated) / total:.1f}%)")
    print(f"rows unparseable even tolerantly:   {len(unparseable)}")

    print("\n--- would `rows * cols >= keys` be a safe invariant? no ---")
    for k, n in invariant.most_common():
        print(f"{n:5d}  {k}")
    print(f"still violated after the x2 correction: {len(still)}")
    for s in still[:6]:
        print(f"   {s[0]:40s} {s[1]} cells, {s[2]} keys")

    if args.qmk_wide:
        print("\n--- variant counts across all of QMK (files, not designs) ---")
        pats = {
            "Matrix (rows)": (r'"rows"', ("info.json", "keyboard.json")),
            "DirectPin": (r'"direct"', ("info.json", "keyboard.json")),
            "DuplexMatrix": (r"DUPLEX_MATRIX|duplex", None),
            "IoExpander": (r"io_expander|IO_EXPANDER|PCA9555|MCP23017", None),
            "Charlieplex": (r"CHARLIEPLEX|charlieplex", None),
            "Analog key sensing": (r"ANALOG_MATRIX|analog_matrix|ec_matrix|EC_MATRIX", None),
            "CUSTOM_MATRIX (the honest gap)": (r"CUSTOM_MATRIX", None),
        }
        for label, (pat, only) in pats.items():
            rx, hits = re.compile(pat), 0
            for f in keyboards.rglob("*"):
                if not f.is_file():
                    continue
                if only and f.name not in only:
                    continue
                if not only and f.suffix not in (".json", ".h", ".mk"):
                    continue
                try:
                    if rx.search(f.read_text(errors="ignore")):
                        hits += 1
                except OSError:
                    pass
            print(f"{hits:6d}  {label}")


if __name__ == "__main__":
    main()
