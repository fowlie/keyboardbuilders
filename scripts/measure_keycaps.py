#!/usr/bin/env python3
"""Every switch footprint on a board WITH THE KEYCAP WIDTH ITS OWN NAME STATES, so a
splayed board with wide keys can be drawn from the board -- backlog #81.

`build_silhouettes.py` refuses a board wherever the firmware draws a 2u-or-wider cap,
because "a PCB records where a switch is, never how wide its keycap is". That premise is
false for the libraries much of this corpus uses: `MX_Only:MXOnly-2.25U-NoLED` and
`MXOnly-ISO-ROTATED-ReversedStabilizers` state the cap in the name. #81 priced this route
and recorded the gate worth keeping; this file is the instrument it needed.

ALL OR NOTHING PER BOARD. A board's footprints are read only if EVERY switch footprint on
it names exactly one width (or is an ISO enter). One unnamed footprint and the board is
`None`, because a guess at one key's width is the thing this exists to avoid, and the
caller then keeps whatever it did before.

ALTERNATES ARE READ, NOT MERGED. A board offering a split backspace or ISO/ANSI places
footprints whose caps would overlap -- `redherring` carries 98 switch footprints for 89
keys. `alternates()` groups footprints whose caps overlap and lists every way of filling
each group with caps that do not; choosing between them is `build_silhouettes`'s job,
and `most_keys()` is the count the board can hold at once. For `redherring` that is 89,
plus one encoder, which is the 90 its firmware declares.

Reads over the network, like `measure_key_positions.py`, from the same
`design-boards.json`.

Usage: measure_keycaps.py [--out data/keycap-scan.json] [--jobs N]
                          [--only SLUG [--refresh]]
"""
import argparse
import collections
import itertools
import json
import math
import pathlib
import re
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from measure_extras import EXTRAS
from measure_key_pitch import PLACEMENT, fetch, nearest_neighbour_gaps, switch_families
from measure_key_positions import AT3
from scan_boards import STABIL

REPO = pathlib.Path(__file__).resolve().parent.parent

# `2.25U`, `1.00u`, `1U` -- a number immediately followed by a u that ends the token.
# `\b` alone would take the `5U` out of `P2.54mm`-style names only if one ever carried a
# trailing u, so the number must also START a token.
WIDTH = re.compile(r"(?<![\d.])(\d+(?:\.\d+)?)[uU](?![a-zA-Z])")
ISO = re.compile(r"iso", re.I)

# KEEBIO'S OWN SPELLING, `MX_PCB_225H`: three digits for the width in hundredths of a
# unit, H or V for how the cap lies. Read ONLY by `stated_width` below, which checks a
# person's ruled fill against the board, and deliberately not by `keycaps()`: `tg4x`
# names 2 of its 68 switch footprints wrongly (a `MX_PCB_100H` at a 1.25u spot, another
# as the 6u space bar's stem), so these names are evidence to check a ruling against,
# never a width to draw unchecked.
KEEBIO = re.compile(r"MX_PCB_(\d)(\d\d)([HV])(?![a-zA-Z\d])")


def stated_width(name):
    """The keycap width `(w, h)` a switch footprint's name states, or None.

    Either convention: `MXOnly-2.25U` or Keebio's `MX_PCB_225H` (`V` is the same cap
    stood on end, `(1, 2.25)`). None where the name states no width, or more than one.
    """
    bare = name.split(":")[-1]
    widths = {float(m) for m in WIDTH.findall(bare)}
    keebio = KEEBIO.findall(bare)
    if keebio and not widths and len(keebio) == 1:
        units, hundredths, lie = keebio[0]
        w = int(units) + int(hundredths) / 100
        return (w, 1.0) if lie == "H" else (1.0, w)
    if len(widths) == 1 and not keebio:
        return (widths.pop(), 1.0)
    return None
ENCODER = EXTRAS["encoder"]

# THE ISO ENTER'S SHAPE, as `build_silhouettes.notch_iso_enter` draws it from a firmware
# layout: a 1.25u x 2u lower leg with its top overhanging a quarter unit to the left. The
# switch sits at the leg's centre, which on `redherring` puts the enter's right edge at
# 341.3 mm -- exactly where the ANSI backslash beside it ends.
ISO_LEG = (1.25, 2.0)
ISO_OVERHANG = 0.25

# Brute force is bounded by these, and a board past either is refused rather than
# searched: nothing in the corpus that motivated this comes near them.
MAX_GROUP = 12
MAX_COMBINATIONS = 100_000


def keycaps(text):
    """`([x, y, r, w, h, notch], ...)` per switch footprint, and the encoder count.

    Millimetres and degrees for the placement, key units for the cap. `None` for the caps
    where any switch footprint fails to name exactly one width.
    """
    caps, encoders, _ = read_switches(text)
    return caps, encoders


def read_switches(text):
    """`keycaps()`'s two answers, plus `[x, y, r, name]` for EVERY switch footprint,
    read or not.

    The third answer is what a person's ruled fill (`seed/board-fills.toml`) is checked
    against: a board whose names cannot be trusted to state its widths (`tg4x`'s Keebio
    `MX_PCB_100H` at a 1.25u position) still says exactly where each switch is, and
    what its footprint is called, so a ruling that names a spot the board does not
    place, or a width its name disagrees with, is caught rather than drawn.
    """
    byname = collections.defaultdict(list)
    encoders = 0
    for _, quoted, unquoted, body in PLACEMENT.findall(text):
        name = (quoted or unquoted).strip()
        if ENCODER.search(name):
            encoders += 1
        hit = AT3.search(body)
        if hit:
            byname[name].append((float(hit.group(1)), float(hit.group(2)),
                                 float(hit.group(3) or 0.0)))
    # THE STABILIZER-NAMED FOOTPRINTS ARE SWITCHES HERE, WITHOUT THE DIODE ARBITRATION
    # `measure_key_positions` applies. That arbitration exists to tell a self-contained
    # wide-key mount (`MXOnly-2.75U-ReversedStabilizers`) from a bare accessory
    # (`wren`'s `Stabilizer_MX_2u`) by footprint arithmetic, and on a board with
    # alternates it cannot: `redherring`'s four stabilized mounts tie, so it trusts none
    # and loses the space bar. #81 recorded the way out -- position decides it. An
    # accessory sits ON its switch, at the same coordinates; a mount does not. So a
    # stabilizer-named placement within half a millimetre of a plain switch is that
    # switch's accessory and is dropped, and every other one is a switch.
    plain = {n: p for n, p in byname.items() if switch_families(n)}
    spots = [(x, y) for p in plain.values() for x, y, _ in p]
    switches = dict(plain)
    for n, p in byname.items():
        if n in plain or not STABIL.search(n) or not switch_families(STABIL.sub("", n)):
            continue
        own = [(x, y, r) for x, y, r in p
               if not any(abs(x - sx) < 0.5 and abs(y - sy) < 0.5 for sx, sy in spots)]
        if own:
            switches[n] = own
    listed = sorted([round(x, 2), round(y, 2), r, name]
                    for name, placements in switches.items() for x, y, r in placements)
    if not switches:
        return None, encoders, listed
    caps = []
    for name, placements in switches.items():
        bare = name.split(":")[-1]
        if ISO.search(bare):
            w, h, notch = ISO_LEG[0], ISO_LEG[1], ISO_OVERHANG
        else:
            widths = {float(m) for m in WIDTH.findall(bare)}
            if len(widths) != 1:
                return None, encoders, listed
            w, h, notch = widths.pop(), 1.0, 0.0
        # A REVERSIBLE BOARD places each footprint twice at one spot, front and back.
        # Collapsed here so the twin is not read as an alternate of itself.
        for x, y, r in sorted(set((round(x, 2), round(y, 2), r) for x, y, r in placements)):
            caps.append([x, y, r, w, h, notch])
    unique, seen = [], set()
    for cap in caps:
        key = (cap[0], cap[1], cap[3], cap[4])
        if key not in seen:
            seen.add(key)
            unique.append(cap)
    return unique, encoders, listed


def unit_of(caps):
    """Millimetres per key unit, the board's modal nearest-neighbour gap -- the same
    reading `build_silhouettes.board_unit` makes."""
    gaps = [g for g in nearest_neighbour_gaps([(c[0], c[1]) for c in caps]) if g > 5]
    return collections.Counter(gaps).most_common(1)[0][0] if gaps else None


def _corners(cap, unit):
    """The cap's outline in millimetres, inset 1 mm so caps that merely touch do not
    overlap. Turned by the board's own angle, KiCad's counter-clockwise."""
    x, y, r, w, h = cap[:5]
    hw, hh = w * unit / 2 - 1.0, h * unit / 2 - 1.0
    a = math.radians(-r)
    c, s = math.cos(a), math.sin(a)
    return [(x + dx * c - dy * s, y + dx * s + dy * c)
            for dx, dy in ((-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh))]


def _overlap(p, q):
    """Separating-axis test on two convex quadrilaterals."""
    for poly in (p, q):
        for i in range(4):
            (x1, y1), (x2, y2) = poly[i], poly[(i + 1) % 4]
            nx, ny = y2 - y1, x1 - x2
            a = [nx * x + ny * y for x, y in p]
            b = [nx * x + ny * y for x, y in q]
            if max(a) <= min(b) or max(b) <= min(a):
                return False
    return True


def alternates(caps, unit):
    """`(fixed, groups)`: the caps nothing overlaps, and for each group of caps that do,
    every MAXIMAL way to fill it without overlap -- as tuples of indices into `caps`.

    `None` where a group or the product of groups is past the search bounds.
    """
    corners = [_corners(c, unit) for c in caps]
    near = collections.defaultdict(set)
    for i, j in itertools.combinations(range(len(caps)), 2):
        if (abs(caps[i][0] - caps[j][0]) < 4 * unit and abs(caps[i][1] - caps[j][1]) < 4 * unit
                and _overlap(corners[i], corners[j])):
            near[i].add(j)
            near[j].add(i)
    fixed, groups, seen = [], [], set()
    for i in range(len(caps)):
        if i in seen:
            continue
        stack, group = [i], []
        while stack:
            k = stack.pop()
            if k not in seen:
                seen.add(k)
                group.append(k)
                stack.extend(near[k])
        if len(group) == 1:
            fixed.append(i)
            continue
        if len(group) > MAX_GROUP:
            return None
        ways = []
        for n in range(1, len(group) + 1):
            for pick in itertools.combinations(sorted(group), n):
                if any(b in near[a] for a, b in itertools.combinations(pick, 2)):
                    continue
                # MAXIMAL: nothing left out could have been added. A way that leaves a
                # free spot empty is a picture with a hole in it, not an alternate.
                if any(not (near[o] & set(pick)) for o in group if o not in pick):
                    continue
                ways.append(pick)
        groups.append(ways)
    if math.prod(len(g) for g in groups) > MAX_COMBINATIONS:
        return None
    return fixed, groups


def most_keys(caps):
    """The most keys this board holds at once, or `None` where it cannot be read."""
    unit = unit_of(caps) if caps else None
    split = alternates(caps, unit) if unit else None
    if not split:
        return None
    fixed, groups = split
    return len(fixed) + sum(max(len(w) for w in g) for g in groups)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--designs", default="data/design-boards.json")
    ap.add_argument("--out", default="data/keycap-scan.json")
    ap.add_argument("--jobs", type=int, default=8)
    ap.add_argument("--only")
    ap.add_argument("--refresh", action="store_true",
                    help="re-read the --only slug even where it is cached")
    args = ap.parse_args()

    mapping = json.loads((REPO/args.designs).read_text())
    if args.only:
        mapping = {k: v for k, v in mapping.items() if k == args.only}
    out_path = REPO/args.out
    cache = json.loads(out_path.read_text()) if out_path.exists() else {}
    todo = [(slug, m) for slug, m in mapping.items()
            if slug not in cache or (args.only and args.refresh)]
    print(f"{len(mapping)} designs, {len(cache)} cached, {len(todo)} to read")

    def work(item):
        slug, m = item
        boards, failures = {}, []
        for path in m["paths"]:
            text, err = fetch(m["repo"], path)
            if not text:
                failures.append(f"{path}: {err}")
                continue
            caps, encoders, listed = read_switches(text)
            boards[path] = {"caps": caps, "encoders": encoders, "switches": listed}
        return slug, {"repo": m["repo"], "boards": boards, "failures": failures}

    if todo:
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            for i, (slug, res) in enumerate(pool.map(work, todo), 1):
                cache[slug] = res
                if i % 25 == 0:
                    print(f"  ...{i}/{len(todo)}")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(cache, sort_keys=True, separators=(",", ":")))
    named = sum(1 for v in cache.values() if any(b["caps"] for b in v["boards"].values()))
    print(f"\n{len(cache)} designs -> {named} with a board whose every switch names its "
          f"keycap width -> {args.out}")


if __name__ == "__main__":
    main()
