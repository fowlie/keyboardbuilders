#!/usr/bin/env python3
"""Does a design lay its components out on top, where the finished keyboard shows them?

The owner's request, 2026-10-04: an "exposed components" field and filter, "like Num
Discipline, Un Split or mysterium" -- "where diodes or other electrical components are
placed so they are visible in an artistic way". Discipline and Mysterium line every switch
diode up in a band above the top row; Un Split runs them down the middle beside its chip.

WHAT IS MEASURED: THE SWITCH DIODES. A design is `True` when at least `SHARE` of its
switch diodes sit on the front copper OUTSIDE every keycap. The diodes are the test
because they are the one part every matrix board places once per key, so the share
says whether the designer put them on show on purpose or merely let a few slip out.
A chip or a handful of passives beside the controller (Ferris, 3w6, the Dilemmas) is
not the look the owner named, and those boards come out `False` here.

"OUTSIDE A KEYCAP" IS A SQUARE OF `HALF` MM EITHER SIDE OF THE SWITCH CENTRE, turned by
the switch's own angle. 10.6 mm, not the 9 mm a keycap really covers: on an ordinary
board the diode sits in the 1 mm gap between two caps, 10 mm from a centre, and it is
hidden there. At 9.3 mm the first run called `largelad` and 30 other plain boards
exposed for exactly that reason.

MEASURED 2026-10-04 over the 338 designs with an attributed board, checked against each
candidate's KiCad render by eye: 29 at 75% or more (most at 95-100%), then setta21 0.67,
chocofly 0.55 (a few diodes in the gaps a splay leaves), and nothing else above 0.30.
`SHARE` sits in that gap.

DIODES ARE READ BY FOOTPRINT NAME, AND RGB LEDS ARE NOT DIODES. KiCad references both as
`D`; the Dilemma's per-key SK6812s read as 36 "exposed diodes" until the name had to
say diode. Keebio's `D3_SMD` family has to be named too -- missing it left 40 boards
looking diodeless.

UNKNOWN, NEVER `False`, WHERE THE MEASUREMENT CANNOT SEE. A board whose switch diodes the
name test does not recognise (a diodeless direct-pin board, or a footprint name this
lexicon lacks) proves nothing about where its parts sit, and an unreadable board less.
A design is `True` if any usable board is, `False` only if one was measured and none is.

Usage: measure_exposed_components.py [--designs FILE] [--out FILE] [--jobs N]
"""
import argparse
import json
import math
import pathlib
import re
import sys
import tomllib
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from measure_key_pitch import PLACEMENT, fetch  # noqa: E402
from measure_key_positions import AT3, MIN_SWITCHES, switch_placements  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent

SHARE = 0.7
HALF = 10.6

LAYER = re.compile(r'\(layer\s+"?([^")\s]+)"?')
REF = re.compile(r'\(fp_text\s+reference\s+"?([^"\s)]+)"?|\(property\s+"Reference"\s+"([^"]*)"')
DIODE = re.compile(r"sod|do-?35|do-?41|^d\d*_|diode|1n4148|melf|ll-?34", re.I)
LED = re.compile(r"led|sk68|ws28|rgb", re.I)


def diode_placements(text):
    """`(x, y, front)` per switch diode, de-duplicated on position, because a reversible
    board places the same diode on both sides at one spot."""
    spots = {}
    for _, quoted, unquoted, body in PLACEMENT.findall(text):
        name = (quoted or unquoted).strip().split(":")[-1]
        ref, layer, at = REF.search(body), LAYER.search(body), AT3.search(body)
        if not (ref and layer and at):
            continue
        if not re.match(r"D\d", ref.group(1) or ref.group(2) or ""):
            continue
        if not DIODE.search(name) or LED.search(name):
            continue
        spot = (round(float(at.group(1)), 1), round(float(at.group(2)), 1))
        spots[spot] = spots.get(spot, False) or layer.group(1) == "F.Cu"
    return [(x, y, front) for (x, y), front in spots.items()]


def under_a_keycap(x, y, keys):
    for kx, ky, rotation in keys:
        a = math.radians(rotation)
        dx, dy = x - kx, y - ky
        if (abs(dx * math.cos(a) + dy * math.sin(a)) <= HALF
                and abs(-dx * math.sin(a) + dy * math.cos(a)) <= HALF):
            return True
    return False


def measure(text):
    """`{keys, diodes, exposed}` for one board file."""
    keys = switch_placements(text)
    diodes = diode_placements(text)
    exposed = sum(1 for x, y, front in diodes if front and not under_a_keycap(x, y, keys))
    return {"keys": len(keys), "diodes": len(diodes), "exposed": exposed}


def verdict_for(boards):
    """`True`, `False` or `None` for one design, from its boards' measurements."""
    judged = [b["exposed"] >= SHARE * b["diodes"] for b in boards.values()
              if b.get("keys", 0) >= MIN_SWITCHES and b.get("diodes", 0) * 2 >= b["keys"]]
    if any(judged):
        return True
    return False if judged else None


def load_overrides(path):
    """`seed/exposed-components-overrides.toml` -- owner corrections, applied last."""
    if not path.exists():
        return {}
    return {e["slug"]: e["exposed"] for e in tomllib.loads(path.read_text()).get("entry", [])}


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--designs", default="data/design-boards.json")
    ap.add_argument("--boards", default="data/exposed-components-boards.json",
                    help="per-board cache; a cached design is not fetched again")
    ap.add_argument("--overrides", default="seed/exposed-components-overrides.toml")
    ap.add_argument("--out", default="data/exposed-components-scan.json")
    ap.add_argument("--jobs", type=int, default=12)
    args = ap.parse_args()

    mapping = json.loads((REPO/args.designs).read_text())
    cache_path = REPO/args.boards
    cache = json.loads(cache_path.read_text()) if cache_path.exists() else {}

    def work(item):
        slug, m = item
        boards = {}
        for path in m["paths"]:
            text, err = fetch(m["repo"], path)
            boards[path] = measure(text) if text else {"error": str(err)[:80]}
        return slug, boards

    todo = [(s, m) for s, m in mapping.items() if s not in cache]
    print(f"{len(mapping)} designs, {len(cache)} cached, {len(todo)} to read")
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for slug, boards in pool.map(work, todo):
            cache[slug] = boards
    cache_path.write_text(json.dumps(cache, indent=1, sort_keys=True))

    verdicts = {}
    for slug in mapping:
        v = verdict_for(cache.get(slug, {}))
        if v is not None:
            verdicts[slug] = v
    for slug, exposed in load_overrides(REPO/args.overrides).items():
        if verdicts.get(slug) != exposed:
            print(f"  override {slug}: {verdicts.get(slug)} -> {exposed}")
        verdicts[slug] = exposed

    (REPO/args.out).write_text(json.dumps(verdicts, indent=1, sort_keys=True))
    yes = sorted(s for s, v in verdicts.items() if v)
    print(f"  exposed      {len(yes):>4}   {' '.join(yes)}")
    print(f"  not exposed  {sum(1 for v in verdicts.values() if not v):>4}")
    print(f"  unknown      {len(mapping) - len(verdicts):>4}   -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
