#!/usr/bin/env python3
"""How each published design's switch positions are built, read off the PADS.

Backlog #107 step 9, the corpus measurement before any model change. A measurement only:
it writes no facet and decides nothing.

`measure_switch_sockets.py` reads footprint NAMES, so it proves a board takes sockets and
can never prove one does not. The pads can. For every switch footprint on a design's own
boards (`design-boards.json`), each NUMBERED pad (a pin the switch is wired through; an
unnumbered `np_thru_hole` is a post or a locating hole) is one of:

  plated   `thru_hole`: a switch pin can be soldered straight in
  surface  `smd` or `connect`: copper on one face with no hole, where a hotswap socket is
           soldered. A `connect` pad drawn ON a plated hole is that hole's copper and not
           counted: `mmccoyd/hillside` draws them that way, and its README says the boards
           take Mill-Max, "not the Kailh sockets" (corrected 2026-10-02).

and a footprint is then:

  solder   plated pins, no surface pads       -- solder only (or Mill-Max, if the holes
                                                 are wide enough; see `drills`)
  socket   surface pads, no plated pins        -- this footprint takes only a socket
  either   both                                -- a socket, or solder straight in
  neither  no numbered pads at all             -- the name said switch and the pads did not

These kinds are per FOOTPRINT. What a key POSITION accepts is `position_mounts`, which
reads every footprint stacked on one switch centre together: a socket is required only
where a position offers nothing else. Architeuthis Dux stacks a `socket` footprint on a
`solder` one turned 180 degrees (its Ergogen file, `architeuthis_dux.yml`, asks for
`choc_hotswap` plus `choc` with `rotate: 180`), so its positions take either.

Fetches every board file whole (about 765 MB for 403 files, 2026-10-01). Results cache per
file to --cache, so a re-run fetches only what is missing.

Usage: measure_switch_pads.py [--jobs N] [--cache FILE]
"""
import argparse
import collections
import json
import pathlib
import re
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from extract_switch_footprints import classify as families  # noqa: E402
from scan_boards import fetch, is_switch  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
SCRATCH = REPO / "data"

FOOTPRINT = re.compile(r'\((?:footprint|module)\s+(?:"([^"]*)"|([^\s()]+))')
PAD = re.compile(r'\(pad\s+(?:"([^"]*)"|([^\s()]*))\s+(smd|thru_hole|np_thru_hole|connect)\b')
DRILL = re.compile(r'\(drill\s+(?:oval\s+)?([\d.]+)(?:\s+([\d.]+))?')
AT = re.compile(r'\(at\s+([-\d.]+)\s+([-\d.]+)')

# KAILH SOCKET HOLES, read off the board rather than the name (#107 step 9.1). A Kailh
# hotswap socket has two plastic posts that need two ~3 mm holes, and the two families put
# them a different distance apart: MX at (-3.81,-2.54) and (2.54,-5.08), 6.84 mm; Choc
# (CPG1350) at (0,-5.9) and (5,-3.8), 5.42 mm (Corne Chocolate draws (5,-3.7), 5.46). A
# distance survives rotation and mirroring, so a reversible or flipped footprint reads
# the same. Without the pair there is no Kailh socket, whatever the name says:
# Hillside 46's `pg1350-R_hotswap` has none, and its README says "Mill-Max hotswap
# sockets, but not the Kailh sockets".
# WHAT A FOOTPRINT ACCEPTS, the interface a part fits (#107 step 9.3, owner 2026-10-02):
#   solder   at least two plated pin holes (or slots) a switch pin can be soldered into
#   millmax  at least two plated pin holes of 1.47-1.65 mm. 1.47 mm is the smaller of the
#            two standard MX holes, and Keebio's guide puts Mill-Max on standard MX boards
#            while "most PCBs designed for Chocs have switch pad holes that are too small"
#            (1.2-1.27 mm). The top keeps out encoder lugs (Hillside's rotary, 1.7 mm).
#   mx/choc  a Kailh socket's hole pair (below) and at least two surface pads to solder it to
# A hole's NARROW side: 3w6 and Ferris drill their Choc pins as 1.0 x 0.4 mm slots, which
# take a soldered pin and never a round Mill-Max sleeve. HNAH40 drills its MX pins 1.7 mm:
# loose for a pin, too loose to vouch for a Mill-Max sleeve.
PIN_HOLE = (0.3, 2.0)
MILLMAX_HOLE = (1.47, 1.65)
SOCKET_HOLE = (2.9, 3.3)
SOCKET_PAIR = {"mx": 6.84, "choc": 5.44}
PAIR_TOLERANCE = 0.15


def footprints(text):
    """(name, block) for every footprint, the block running to the next footprint.

    Footprints are not nested, and nothing between two of them (tracks, vias, zones) is a
    `(pad`, so the slice needs no paren matching."""
    starts = [(m.start(), m.group(1) if m.group(1) is not None else m.group(2) or "")
              for m in FOOTPRINT.finditer(text)]
    for i, (s, name) in enumerate(starts):
        end = starts[i + 1][0] if i + 1 < len(starts) else len(text)
        yield name, text[s:end]


def all_pads(block):
    """Every pad as (number, type, (x, y), drill-or-None), in footprint coordinates. A
    pad's position and drill are read up to the next pad."""
    out = []
    hits = list(PAD.finditer(block))
    for i, m in enumerate(hits):
        number = m.group(1) if m.group(1) is not None else m.group(2)
        seg = block[m.end(): hits[i + 1].start() if i + 1 < len(hits) else len(block)]
        at = AT.search(seg)
        xy = (float(at.group(1)), float(at.group(2))) if at else None
        d = DRILL.search(seg)
        drill = None
        if d and m.group(3) in ("thru_hole", "np_thru_hole"):
            drill = round(min(float(d.group(1)), float(d.group(2) or d.group(1))), 2)
        out.append((number, m.group(3), xy, drill))
    return out


def pads_of(block):
    """Numbered pads as (type, drill-or-None), with drills only for plated holes.

    A `connect` pad drawn ON a plated hole is part of that hole's copper, not a surface
    pad: Hillside's footprints lay a 0.9 mm custom shape over each pin hole, and reading
    those as socket pads called all three Hillsides "socket or solder"."""
    pads = all_pads(block)
    holes = {xy for _, t, xy, _ in pads if t == "thru_hole"}
    return [(t, d if t == "thru_hole" else None) for n, t, xy, d in pads
            if n and not (t == "connect" and xy in holes)]


def socket_holes(block):
    """Which Kailh sockets the footprint's holes take: a sorted list of `mx`/`choc`."""
    holes = [xy for _, _, xy, d in all_pads(block)
             if xy and d is not None and SOCKET_HOLE[0] <= d <= SOCKET_HOLE[1]]
    found = set()
    for i, a in enumerate(holes):
        for b in holes[i + 1:]:
            dist = ((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2) ** 0.5
            found |= {k for k, v in SOCKET_PAIR.items() if abs(dist - v) <= PAIR_TOLERANCE}
    return sorted(found)


FP_AT = re.compile(r'\(at\s+([-\d.]+)\s+([-\d.]+)(?:\s+([-\d.]+))?\)')
CENTRE_HOLE = (3.3, 5.1)


def board_holes(block):
    """The footprint's holes and surface pads in BOARD coordinates, as
    (x, y, type, drill-or-None, number). KiCad stores pads relative to the footprint, which
    it rotates counter-clockwise on screen with y pointing down. Reading two stacked
    footprints in their LOCAL coordinates is how Architeuthis Dux was once ruled socket-only:
    its solder footprint is turned 180 degrees from its socket one, so holes that match
    locally sit on opposite sides of the switch on the board."""
    import math
    at = FP_AT.search(block)
    fx, fy = float(at.group(1)), float(at.group(2))
    a = math.radians(float(at.group(3) or 0))
    out = []
    for n, t, xy, d in all_pads(block):
        if xy is None or (t == "connect" and not n):
            continue
        x, y = xy
        out.append((round(fx + x * math.cos(a) + y * math.sin(a), 2),
                    round(fy - x * math.sin(a) + y * math.cos(a), 2), t, d, n))
    return out


# Switches with no round centre post: the footprint's own origin marks the key position.
NO_CENTRE_POST = {"Alps", "ChocMini", "KailhX"}


def position_mounts(blocks):
    """What each key POSITION accepts, from every switch-like footprint on one board file.

    A position is a switch's centre hole (unplated, 3.3-5.1 mm: MX 4.0, Choc 3.4 or 5.0),
    which leaves out TRRS jacks, LEDs, reset buttons and encoders whose names look like
    switches. A footprint named for a switch with no centre post ([NO_CENTRE_POST]) is
    placed by its origin instead. Footprints stacked on one centre hole are read together,
    and a plated pin hole that another footprint drills a socket hole over is gone, since
    the fab drills out the plating. Architeuthis Dux is NOT such a case: its solder
    footprint is turned 180 degrees from the socket one, so the holes miss (`board_holes`)."""
    positions = collections.defaultdict(list)
    for fams, block in blocks:
        holes = board_holes(block)
        centres = [(x, y) for x, y, t, d, _ in holes
                   if t == "np_thru_hole" and d and CENTRE_HOLE[0] <= d <= CENTRE_HOLE[1]]
        if not centres and fams & NO_CENTRE_POST:
            at = FP_AT.search(block)
            centres = [(float(at.group(1)), float(at.group(2)))]
        if centres:
            cx, cy = centres[0]
            positions[(round(cx * 2) / 2, round(cy * 2) / 2)].extend(holes)
    out = collections.Counter()
    for holes in positions.values():
        big = [(x, y) for x, y, _, d, _ in holes if d and d >= SOCKET_HOLE[0]]
        pins = [d for x, y, t, d, n in holes if t == "thru_hole" and n and d is not None
                and not any(abs(x - bx) < 0.3 and abs(y - by) < 0.3 for bx, by in big
                            if (bx, by) != (x, y))]
        # A `connect` pad ON a plated hole is that hole's copper (Hillside). An `smd` pad
        # with a via in it is still a pad (Masonry's MX sockets).
        plated = {(x, y) for x, y, t, _, _ in holes if t == "thru_hole"}
        surface = sum(1 for x, y, t, _, n in holes
                      if n and (t == "smd" or (t == "connect" and (x, y) not in plated)))
        m = set()
        if sum(PIN_HOLE[0] <= d <= PIN_HOLE[1] for d in pins) >= 2:
            m.add("solder")
        if sum(MILLMAX_HOLE[0] <= d <= MILLMAX_HOLE[1] for d in pins) >= 2:
            m.add("millmax")
        if surface >= 2:
            sh = [(x, y) for x, y, _, d, _ in holes if d and SOCKET_HOLE[0] <= d <= SOCKET_HOLE[1]]
            for i, (ax, ay) in enumerate(sh):
                for bx, by in sh[i + 1:]:
                    dist = ((ax - bx) ** 2 + (ay - by) ** 2) ** 0.5
                    m |= {k for k, v in SOCKET_PAIR.items() if abs(dist - v) <= PAIR_TOLERANCE}
        out["+".join(sorted(m)) or "-"] += 1
    return out


def mounts(block):
    """What the footprint accepts: a sorted list from solder, millmax, mx, choc."""
    pads = pads_of(block)
    pins = [d for t, d in pads if t == "thru_hole" and d is not None]
    out = set()
    if sum(PIN_HOLE[0] <= d <= PIN_HOLE[1] for d in pins) >= 2:
        out.add("solder")
    if sum(MILLMAX_HOLE[0] <= d <= MILLMAX_HOLE[1] for d in pins) >= 2:
        out.add("millmax")
    if sum(t in ("smd", "connect") for t, _ in pads) >= 2:
        out |= set(socket_holes(block))
    return sorted(out)


def kind(pads):
    plated = any(t == "thru_hole" for t, _ in pads)
    surface = any(t in ("smd", "connect") for t, _ in pads)
    return {(True, False): "solder", (False, True): "socket",
            (True, True): "either"}.get((plated, surface), "neither")


def measure(repo, path):
    text, err, _ = fetch(repo, path, cap=0)
    if text is None:
        return {"error": err}
    kinds, drills, names = collections.Counter(), collections.Counter(), collections.Counter()
    sockets, accepts = collections.Counter(), collections.Counter()
    switch_blocks = []
    unnamed = collections.Counter()
    for name, block in footprints(text):
        # A SWITCH IS A FOOTPRINT WHOSE NAME GIVES A FAMILY. `is_switch` alone also takes
        # anything in a library called "Kailh" -- Sweep's `Kailh:TRRS-PJ-320A` and its
        # `Kailh:ferris_broom` logo. Switch-like names with no family are counted apart,
        # so what this drops stays visible.
        fams = families(name)
        if not fams:
            if is_switch(name):
                # The holes are read anyway: a socket footprint named for no family is
                # still a socket. Ergoslab's `Kailh_socket_reversible` and tenkey_plusplus's
                # `hotswap_keyswitch` are where both boards' MX sockets are.
                unnamed[f"{name}\t{'+'.join(socket_holes(block)) or '-'}"] += 1
                if m := mounts(block):
                    accepts[f"{name}\t{'+'.join(m)}"] += 1
                switch_blocks.append((set(), block))
            continue
        switch_blocks.append((set(fams), block))
        pads = pads_of(block)
        k = kind(pads)
        kinds[k] += 1
        socket = "+".join(socket_holes(block)) or "-"
        sockets[socket] += 1
        accepts[f"{name}\t{'+'.join(mounts(block)) or '-'}"] += 1
        # The raw pad types too, so a change to `kind` re-derives without a re-fetch.
        names[f"{name}\t{k}\t{'+'.join(sorted({t for t, _ in pads}))}\t{socket}"] += 1
        for t, d in pads:
            if t == "thru_hole" and d is not None:
                drills[f"{'+'.join(sorted(fams))} {d}"] += 1
    return {"kinds": dict(kinds), "sockets": dict(sockets), "accepts": dict(accepts),
            "positions": dict(position_mounts(switch_blocks)),
            "drills": dict(drills),
            "names": dict(names),
            "unnamed": dict(unnamed)}


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jobs", type=int, default=8)
    ap.add_argument("--cache", type=pathlib.Path, default=SCRATCH / "switch-pad-scan.json")
    args = ap.parse_args()

    published = {d["slug"] for d in json.loads(
        (REPO / "src/main/resources/seed/designs.json").read_text())}
    boards = json.loads((SCRATCH / "design-boards.json").read_text())
    files = sorted({(v["repo"], p) for s, v in boards.items() if s in published
                    for p in v["paths"]})

    cache = json.loads(args.cache.read_text()) if args.cache.exists() else {}
    todo = [(r, p) for r, p in files if f"{r}\t{p}" not in cache]
    print(f"{len(files)} board files, {len(todo)} to fetch", file=sys.stderr)
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for n, ((r, p), res) in enumerate(zip(todo, pool.map(lambda t: measure(*t), todo)), 1):
            cache[f"{r}\t{p}"] = res
            if n % 25 == 0:
                args.cache.write_text(json.dumps(cache, indent=1, sort_keys=True))
                print(f"  {n}/{len(todo)}", file=sys.stderr)
    args.cache.write_text(json.dumps(cache, indent=1, sort_keys=True))


if __name__ == "__main__":
    main()
