#!/usr/bin/env python3
"""Where every switch physically sits on a design's board, so a silhouette can be true.

Forgejo #31, owner-reported on KLOR: *"just by looking at the SVG it looks like the
layout is orthogonal, but in reality it is column staggered with splay."*

He was right, and the cause is the same one that made KLOR read 44 keys (#23, `7ebdcd0`):
**a QMK `LAYOUT` is a matrix map, not a physical description.** KLOR's record states
`y` in {0,1,2,3} and integer `x`, so `build_silhouettes.py` faithfully drew a schematic.
Measured over the 217 drawn designs, eight are column-staggered or splayed boards drawn
as flat grids -- and nine others drawn from an integer grid genuinely ARE ortholinear
(Helix, Rhymestone, Fourier), which is the control that says the eight are real.

The fix is a change of SOURCE, not of renderer: a `.kicad_pcb` places each switch at
`(at x y)` in millimetres, and those coordinates are the truth the firmware is
approximating. KLOR's own board yields distinct rows at 0.00, 0.47, 0.55, 0.63, 1.00,
1.31 key units -- the stagger the integer grid erases.

POSITIONS AND ROTATION. `(at x y r)` carries a per-switch angle, and on a splayed board
that angle is the splay -- KLOR's 21 switches sit at six distinct angles (180, 176, 170,
-135, -150, -165). Emitting it lets the renderer turn each keycap the way the board does.
The angle is stored RAW here; `build_silhouettes.py` normalises it against the board's own
modal angle, because a footprint placed at a uniform 180 degrees is not a rotated key.

WHAT THIS DOES NOT DECIDE. It writes a cache, nothing else. `build_silhouettes.py` rules
on precedence, and it only takes this geometry when the key count agrees with what the
design publishes -- the same guard the borrow path has always applied.

THE MIRROR, and it is the one piece of synthesis here. A split design's board file often
holds ONE HAND: KLOR places 21 switches for a 42-key keyboard. Drawing 21 would show half
a keyboard, which is a worse picture than a grid. So where the board holds exactly half
the published keys, the hand is mirrored about its own right edge with a one-key gap --
the same `placed` vs `placed * 2` rule `build_seed.py` already uses to decide whether a
file is one hand or two. Where the board holds the full count, nothing is synthesised.

Reads `design-boards.json` (written by `build_seed.py`) rather than re-deriving which
board belongs to which design, because that derivation is the naming rule and it should
live in exactly one place.

Usage: measure_key_positions.py [--out data/position-scan.json]
                               [--designs ...] [--jobs N] [--only SLUG [--refresh]]
"""
import argparse
import collections
import json
import pathlib
import re
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from measure_key_pitch import PLACEMENT, fetch, switch_families
from scan_boards import stabilizer_switch_names

# `(at x y r)` -- the third number is the footprint's ROTATION in degrees, and it is the
# splay. `measure_key_pitch.AT` stops at two because pitch is a distance; this file needs
# the angle, so it reads the same placement with one more group rather than defining a
# second idea of where a footprint sits.
AT3 = re.compile(r"\(at\s+(-?[\d.]+)\s+(-?[\d.]+)(?:\s+(-?[\d.]+))?")

REPO = pathlib.Path(__file__).resolve().parent.parent

# Below this many switches a "board" is a daughterboard or a fragment, and its geometry
# would draw something that is not the keyboard. Six is the smallest real macropad in
# the corpus.
MIN_SWITCHES = 6


def switch_placements(text):
    """One `(x, y, rotation)` per key, in millimetres and degrees.

    `measure_key_pitch.switch_positions` with the angle kept. Its two correctness rules
    are reproduced exactly, because they are the reason that function is right:

      * ONE FOOTPRINT FAMILY. A key is often placed as several footprints at nearly the
        same spot -- a socket beside its own switch, 5.08 mm apart -- so measuring across
        all of them reads that gap as a key. The single most-placed switch footprint
        gives one point per key.
      * CO-LOCATED PLACEMENTS ARE ONE KEY. A reversible PCB carries the same footprint on
        both sides at identical coordinates, so a 17-key board places 34.

    Rounded before de-duplicating so the two sides of a reversible board collapse even
    when their coordinates differ in the last decimal.
    """
    byname = collections.defaultdict(list)
    for _, quoted, unquoted, body in PLACEMENT.findall(text):
        name = (quoted or unquoted).strip()
        hit = AT3.search(body)
        if hit:
            byname[name].append((float(hit.group(1)), float(hit.group(2)),
                                 float(hit.group(3) or 0.0)))
    # THE SAME DIODE-ARBITRATED EXCEPTION `scan_boards`'s switch COUNT takes, and for
    # the same reason: a footprint naming both hotswap and a stabilizer is sometimes a
    # self-contained wide-key mount and sometimes an alternate for an already-counted
    # position, and only the board's own diode count tells the two apart -- see
    # `scan_boards.stabilizer_switch_names`. Without this, `gtips/reviung`'s wide keys
    # were invisible to the picture even after the switch count itself was fixed --
    # `switch_families` excludes them the same way `is_switch` does, upstream of this
    # file entirely.
    trusted = stabilizer_switch_names({n: len(pts) for n, pts in byname.items()})
    byname = {n: pts for n, pts in byname.items() if switch_families(n) or n in trusted}
    if not byname:
        return []

    # EVERY SWITCH FOOTPRINT, THEN COLLAPSED BY DISTANCE -- not the single most-placed
    # name. `measure_key_pitch` takes one name because it is measuring a DISTANCE and a
    # socket sitting 5.08 mm from its own switch would be read as the pitch. This file
    # wants every KEY, and a board that places three of its keys with a different
    # footprint loses those three under the one-name rule: `chocofly` came back with 60
    # keys against a declared 61 and `unicorn-f072` with 41 against 44, so both fell back
    # to a flat firmware grid for want of a few footprints. Owner-reported on both.
    #
    # Collapsing at 8 mm is safe in both directions: the socket-and-switch stacks that
    # rule exists to defeat sit ~5 mm apart, and the tightest real key spacing in this
    # corpus is Choc at 17 mm. Nothing legitimate falls in between.
    every = [(round(x, 2), round(y, 2), round(r, 1))
             for name in byname for x, y, r in byname[name]]
    every.sort()
    clusters = []
    for x, y, r in every:
        for c in clusters:
            if abs(c[0][0] - x) <= 8.0 and abs(c[0][1] - y) <= 8.0:
                c.append((x, y, r))
                break
        else:
            clusters.append([(x, y, r)])

    # WITHIN A CLUSTER, THE PLACEMENT NEAREST THE BOARD'S MODAL ANGLE WINS. Taking the
    # first member instead is arbitrary -- it may be a reversible board's flipped twin, or
    # a socket whose library orients it differently -- and it showed: `architeuthis-dux`
    # went from splay angles of -25, -20, -15, -10, -5 to a ragged -29, -19, -16, -6, -4.
    # Real boards splay in round numbers, and that raggedness was the tell.
    #
    # Compared modulo 180 because a rectangle is 180-degree symmetric, so a twin placed
    # half a turn away is the SAME orientation and must not be treated as 180 degrees off.
    mode = collections.Counter(r % 180 for _, _, r in every).most_common(1)[0][0]

    def distance_from_mode(r):
        return abs((r % 180 - mode + 90) % 180 - 90)

    placed = [min(c, key=lambda p: distance_from_mode(p[2])) for c in clusters]
    placed = [(c[0][0], c[0][1], p[2]) for c, p in zip(clusters, placed)]

    # DE-DUPLICATE ON POSITION, NEVER ON POSITION-AND-ANGLE. A reversible PCB carries the
    # same footprint front and back at identical coordinates and 180 degrees apart, so
    # keying on the angle keeps both and doubles the key count: `tapioki/cephalopoda`
    # went from 17 keys to 34, which is the exact failure `switch_positions` documents
    # and which this file reintroduced the moment it started reading rotation.
    #
    # Where both sides exist, the one nearer the board's MODAL angle wins -- the mode is
    # how this library orients the part, so the other placement is the flipped twin.
    mode = collections.Counter(r for _, _, r in placed).most_common(1)[0][0]

    def distance_from_mode(r):
        return abs((r - mode + 180) % 360 - 180)

    byxy = {}
    for x, y, r in placed:
        prior = byxy.get((x, y))
        if prior is None or distance_from_mode(r) < distance_from_mode(prior):
            byxy[(x, y)] = r
    return sorted((x, y, r) for (x, y), r in byxy.items())


# Forgejo #49, owner-reported on Junco: "the inner top-most switch is drawn as a plain
# switch but it is an ENCODER, and so are the keys in the outermost lower corners." The
# pattern is `measure_extras`' own, the one `build_seed.without_encoder_positions` and the
# `encoder` extension already read, so "this board has an encoder" means one thing.
from measure_extras import EXTRAS  # noqa: E402

ENCODER = EXTRAS["encoder"]

# THE SAME 8 MM `switch_placements` COLLAPSES A KEY'S STACK AT, and for the same reason:
# a footprint within it of a key sits on that key, and the tightest real key spacing in
# this corpus is Choc's 17 mm.
SAME_SPOT_MM = 8.0


def encoder_placements(text, keys):
    """Where this board places a rotary encoder, as `{"only": [...], "or_key": [...]}`.

    TWO KINDS, because the board states two different things and drawing them alike
    would be wrong about one of them:

      * **`only`** -- an encoder footprint that sits on no key: a knob and nothing else.
        Junco's `EC11_Rotary_Encoder` beside each thumb pair. `switch_placements` never
        takes these, so they are not in `keys` and never were. `(x, y)` in mm.
      * **`or_key`** -- a KEY position where the board also lets you fit an encoder: a
        switch footprint whose own name says it is an encoder too (Junco's
        `SW_MX_Hotswap_Rotary_Switch`, described as "Hotswap Socket ... option to use an
        EC11 rotary encoder instead"), or an encoder footprint stacked on a switch
        footprint. It IS in `keys`, and stays there, because it holds a switch unless
        somebody chooses otherwise. Given as the KEY's own `(x, y)` from `keys`, so the
        build can find it exactly.

    PRESENCE, NEVER ABSENCE. This reads footprint NAMES, and a name-matching scan's
    silence is Unknown -- a board whose encoder footprint is called `SW_Push_5pin` has an
    encoder this cannot see. An empty result says nothing was found, not that there is
    none, and nothing downstream may read it as "no encoder".
    """
    found = []
    for _, quoted, unquoted, body in PLACEMENT.findall(text):
        name = (quoted or unquoted).strip()
        if not ENCODER.search(name):
            continue
        hit = AT3.search(body)
        if hit:
            found.append((round(float(hit.group(1)), 2), round(float(hit.group(2)), 2)))

    def key_at(x, y):
        near = [k for k in keys
                if abs(k[0] - x) <= SAME_SPOT_MM and abs(k[1] - y) <= SAME_SPOT_MM]
        return min(near, key=lambda k: (k[0] - x) ** 2 + (k[1] - y) ** 2) if near else None

    only, or_key = [], []
    for x, y in sorted(found):
        key = key_at(x, y)
        if key is not None:
            spot = [key[0], key[1]]
            if spot not in or_key:
                or_key.append(spot)
        # A reversible board places the same encoder front and back: one knob.
        elif not any(abs(p[0] - x) <= SAME_SPOT_MM and abs(p[1] - y) <= SAME_SPOT_MM
                     for p in only):
            only.append([x, y])
    return {"only": only, "or_key": sorted(or_key)}


def positions_for(repo, paths):
    """Switch placements for each of a design's board files, in millimetres, KEPT APART.

    An earlier version unioned them, and that was wrong in the way that matters: a split
    whose repo ships `pcb/left/keyboard_pcb.kicad_pcb` and `pcb/right/...` draws each hand
    in ITS OWN coordinate space, both starting near the origin. Unioning them lays one
    hand on top of the other and invents key spacings that exist on neither board --
    `beekeeb/piantor` came out with a nearest-neighbour of 5.15 mm against a true pitch of
    17.0, so every key was drawn four times too far from its neighbour.

    So the scan measures per file and `build_silhouettes.py` decides which one is the
    keyboard. That is the same division this file already keeps for the key count: the
    cache states what is on each board and rules on nothing.
    """
    per_board, encoders, failures = {}, {}, []
    for path in paths:
        text, err = fetch(repo, path)
        if not text:
            failures.append(f"{path}: {err}")
            continue
        per_board[path] = [list(p) for p in switch_placements(text)]
        placed = encoder_placements(text, per_board[path])
        if placed["only"] or placed["or_key"]:
            encoders[path] = placed
    return per_board, encoders, failures


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--designs", default="data/design-boards.json")
    ap.add_argument("--out", default="data/position-scan.json")
    ap.add_argument("--jobs", type=int, default=8)
    ap.add_argument("--only")
    # RE-READ a cached slug. The cache is incremental and never re-reads what it holds, so
    # an entry written before this file learned to record encoders (Forgejo #49) has no
    # `encoders` key -- which means "never looked", not "none" -- until it is refreshed.
    ap.add_argument("--refresh", action="store_true",
                    help="re-read the selected designs even if cached (use with --only)")
    args = ap.parse_args()

    mapping = json.loads((REPO/args.designs).read_text())
    if args.only:
        mapping = {k: v for k, v in mapping.items() if k == args.only}

    out_path = REPO/args.out
    cache = json.loads(out_path.read_text()) if out_path.exists() else {}

    todo = [(slug, m) for slug, m in mapping.items()
            if slug not in cache or args.refresh]
    print(f"{len(mapping)} designs, {len(cache)} cached, {len(todo)} to read")

    def work(item):
        slug, m = item
        per_board, encoders, failures = positions_for(m["repo"], m["paths"])
        return slug, {"repo": m["repo"], "paths": m["paths"],
                      "boards": per_board, "encoders": encoders, "failures": failures}

    if todo:
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            for i, (slug, res) in enumerate(pool.map(work, todo), 1):
                cache[slug] = res
                if i % 25 == 0:
                    print(f"  ...{i}/{len(todo)}")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(cache, sort_keys=True, separators=(",", ":")))

    usable = sum(1 for v in cache.values()
                 if any(len(b) >= MIN_SWITCHES for b in v["boards"].values()))
    empty = sum(1 for v in cache.values() if not any(v["boards"].values()))
    print(f"\n{len(cache)} designs -> {usable} with >= {MIN_SWITCHES} switch positions, "
          f"{empty} with none  -> {args.out}")


if __name__ == "__main__":
    main()
