#!/usr/bin/env python3
"""The author's own word for each layout a ZMK shield declares, matched by key count.

Backlog #23 (named layouts): a break-off design offers several outcomes from one PCB
order, and `Layout.name` is the field this project had nowhere to put them. ZMK states
one directly -- a `zmk,physical-layout` node's own `display-name` -- for every shield
that declares more than one, e.g. `reviung34`: "Dual 1U (34 keys)" / "Single 2U (33 keys)".

READ ONLY WHERE A SHIELD DECLARES MORE THAN ONE PHYSICAL LAYOUT. A single-layout shield's
own display-name is almost always the generic "Default" (`a_dux`'s only node is exactly
that), which distinguishes nothing and would print as if it did. Silence stays silence.

NEVER THE `display-name` INSIDE A KEYMAP LAYER. ZMK reuses this exact property name for a
keymap's `layer { display-name = "Lower Layer"; }`, a different concept entirely -- a
keymap layer is a firmware-side remapping of the SAME physical keys, not a second
arrangement. Reading it here would print a layer name where a layout name belongs. This
scan only opens files matched by `*-layouts.dtsi` plus any file a shield's own dtsi
`#include`s from `app/dts/layouts/`, which is where `zmk,physical-layout` nodes live and
keymaps do not.

MATCHED TO A DESIGN'S OWN LAYOUT BY KEY COUNT, not by position or declaration order --
`design["layouts"]` and a shield's own physical-layout nodes are two independent lists
that happen to agree on the numbers, and key count is the only field both sides state.
Where two of a shield's own layouts share one key count, neither is named: this scan
cannot tell them apart and a guess is exactly the wrong-value-that-looks-right this
project exists to avoid.

Usage: measure_layout_names.py [--zmk ~/code/zmk]
"""
import argparse
import json
import os
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from attributed_boards import REPO, SCRATCH  # noqa: E402

# One `zmk,physical-layout` node, captured whole so its own display-name and its own
# key count never cross into a neighbouring node.
PHYSICAL_LAYOUT = re.compile(
    r'compatible\s*=\s*"zmk,physical-layout"\s*;(.*?)\n\s*\}', re.S)
DISPLAY_NAME = re.compile(r'display-name\s*=\s*"([^"]+)"')
KEY_ENTRY = re.compile(r"&key_physical_attrs")


def named_layouts(shield_dir, zmk_root):
    """`{keys: name}` for a shield's own physical layouts, or `{}` if fewer than two."""
    sources = sorted(shield_dir.glob("*-layouts.dtsi"))
    for dtsi in sorted(shield_dir.glob("*.dtsi")) + sorted(shield_dir.glob("*.overlay")):
        text = dtsi.read_text(errors="ignore")
        for inc in re.findall(r'#include\s+<(layouts/[^>]+)>', text):
            path = zmk_root/"app"/"dts"/inc
            if path.exists():
                sources.append(path)

    found = {}
    for path in sources:
        text = path.read_text(errors="ignore")
        for node in PHYSICAL_LAYOUT.findall(text):
            name = DISPLAY_NAME.search(node)
            n = len(KEY_ENTRY.findall(node))
            if name and n:
                found.setdefault(n, []).append(name.group(1))

    # A key count more than one node claims cannot be told apart -- drop it rather
    # than pick one arbitrarily.
    return {n: names[0] for n, names in found.items() if len(names) == 1 and len(found) > 1}


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                  formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--zmk", default=os.path.expanduser("~/code/zmk"))
    args = ap.parse_args()

    zmk = pathlib.Path(args.zmk)
    shields_dir = zmk/"app"/"boards"/"shields"
    if not shields_dir.is_dir():
        raise SystemExit(f"no shields under {shields_dir}")

    designs = json.loads((REPO/"src/main/resources/seed/designs.json").read_text())

    per_shield = {}
    for shield_dir in sorted(p.parent for p in shields_dir.rglob("*.zmk.yml")):
        shield_id = shield_dir.name
        names = named_layouts(shield_dir, zmk)
        if names:
            per_shield[shield_id] = names

    per_design = {}
    matched_designs, matched_layouts, ambiguous = 0, 0, 0
    for d in designs:
        shields = {fw["shield"] for fw in d.get("firmware") or []
                   if fw.get("family") == "zmk" and fw.get("shield")}
        found = {}
        for shield_id in shields:
            names = per_shield.get(shield_id)
            if not names:
                continue
            for layout in d["layouts"]:
                n = layout["keys"]
                if n in names:
                    found[n] = names[n]
        if found:
            per_design[d["slug"]] = found
            matched_designs += 1
            matched_layouts += len(found)

    print(f"  {len(per_shield)} ZMK shields declare more than one named physical layout")
    print(f"  {matched_designs} designs matched, {matched_layouts} layouts named\n")
    for slug, names in sorted(per_design.items()):
        print(f"  {slug:<26} " + ", ".join(f"{k}={v!r}" for k, v in sorted(names.items())))

    out = SCRATCH/"layout-names-scan.json"
    out.write_text(json.dumps(per_design, indent=1, sort_keys=True))
    print(f"\n  {len(per_design)} designs -> {out.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
