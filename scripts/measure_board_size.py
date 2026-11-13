#!/usr/bin/env python3
"""How big is each design's PCB, and how many copper layers does it have?

The owner's call, 2026-10-04: measure board size now, price the PCB fab later. Size and
layer count are the two facts a fab quote is built from, and both are in the board file.

THE SIZE IS THE EDGE.CUTS EXTENT, and nothing else. Every fab cuts the board along the
Edge.Cuts layer, so its bounding box is the panel the board is quoted on. Read from the
CENTRE of each drawn line, not its outer edge -- the line width is a drawing convenience
and fabs treat the centreline as the cut. What counts:

    gr_line gr_rect gr_poly gr_circle    their points, or the circle's full diameter
    gr_arc                               its ends AND every axis extreme it sweeps past,
                                         so a rounded corner reaches as far as it bulges
    gr_curve                             the curve itself, not its handles
    fp_* on a footprint                  the same shapes, moved and turned with the
                                         footprint -- outline footprints and cut-outs

Both arc spellings occur. KiCad 5 writes `(start CENTRE) (end FIRST-POINT) (angle SWEEP)`;
KiCad 6 and later write three points on the arc, `start`, `mid` and `end`. Which one a
file uses is told by `mid` versus `angle`, never by the version number, because KiCad
5.99 nightlies wrote the old arc into a new-style file.

AN EXTRACTOR PROVES PRESENCE, NOT ABSENCE. A board whose outline sits on `Dwgs.User`
or on no layer at all, a file GitHub will not serve, a file that is not a board: each is
Unknown with its reason, never 0 x 0 and never a guess.

AN OUTLINE IS CHECKED AGAINST THE BOARD'S OWN SWITCHES (`position-scan.json`), because
an extent is only a board's size if the outline is that one board. `board_of` has the
rules; in short, a file is one of three things, its `drawn`:

    board          a unibody's PCB
    half           one half of a split -- the size is ONE half, matching the design
                   page's per-half pins, whether the file is used for both hands or the
                   other hand has a file of its own
    both halves    a split with all its keys in one file. Halves drawn as two separate
                   outlines are each measured; halves joined by snap-off tabs or framed
                   as a panel (Ferris, Sweep, Corne v4) are ONE outline whose extent is
                   the pair's, so their size is Unknown

An outline that does not hold the switches, one where they fill only a corner (a PCB
panelled with its own plates: Soyuz, Framework rev0), and a unibody file with far more
switches than the layout has keys (several boards) are Unknown. A board drawn on its
side -- most switches at a quarter turn -- has width and height swapped back.

ONE SIZE PER DESIGN ONLY WHERE ITS FILES AGREE. A design can own several board files --
a split's left and right, a hotswap and a soldered variant, six revisions of Lotus58.
Within 0.5 mm of each other they are one size, given as the larger. Halves of different
sizes are kept as `half_sizes_mm`, left first, where they are surely the two halves: a
left file and a right file, or both drawn apart in one. Anything else that differs is
variants under one design, Unknown, and the reason lists each file's, for a person to
rule on.

PLATES ARE MEASURED SEPARATELY and attributed only where they cannot be misattributed:
a plate-named file (`draft_boards.PART`, or a scan `kind = "plate"`) in a repo that
holds exactly ONE design. In a shared repo a plate could be any design's, so it is left
out rather than guessed -- the same rule build_seed.py applies to boards. A plate-named
file is not always a switch plate: bottoms, covers and case layers are named plates
too. `role = "switch plate"` is given only to one with switch-sized holes; without them
its role is Unknown, never "not a switch plate".

Reads each file whole over the network (`measure_key_pitch.fetch`, which also reads
Ergogen-generated boards from the local cache), and caches results by `repo\\tpath` in
`data/board-size-scan.json`. A failed fetch is NOT cached, so a re-run
retries it. Writes `seed/board-sizes.toml`, which build_seed.py reads.

Usage: measure_board_size.py [--jobs 8] [--only SLUG] [--refresh]
"""
import argparse
import collections
import datetime
import json
import math
import pathlib
import re
import sys
from concurrent.futures import ThreadPoolExecutor

REPO = pathlib.Path(__file__).resolve().parent.parent
SCRATCH = REPO/"data"
CACHE = SCRATCH/"board-size-scan.json"
OUT = REPO/"seed/board-sizes.toml"

# Two files of one design within this of each other are one size. Half a millimetre is
# far under any fab's price step and well over the float noise a mirrored half carries.
AGREE_MM = 0.5
# A switch centre this far outside the outline means the outline is not this board.
CONTAINS_MM = 1.0
# A switch-sized hole, either side (see `is_switch_cutout`), and how many make a plate a
# switch plate rather than a bottom, a cover or a case layer.
CUTOUT_MM = (13.0, 16.0)
SWITCH_PLATE_CUTOUTS = 4
# A split file with this share of the layout's keys draws both halves. Across the corpus
# one-half files hold 0.10 to 0.61 of the keys and both-halves files 1.00.
BOTH_HALVES = 0.75
# A unibody file with this many times the layout's keys is several boards: sdra-kb00
# draws 24 switches for 12 keys, romac 47 for 12. Layout options stay under 1.3x.
PANEL_SHARE = 1.75
# Switches spanning less than this share of their outline (their centres' extent plus one
# key pitch) are a board in a panel. Measured 2026-10-04: the panels found by eye fill
# 0.24 to 0.30; the lowest real board, millipad, 0.43.
MIN_COVERAGE = 0.35
PITCH_MM = 19.05
# Most switches at a quarter turn: the board is drawn on its side.
TURNED_SHARE = 0.75

TOKEN = re.compile(r'\(|\)|"(?:[^"\\]|\\.)*"|[^\s()"]+')

# Subtrees nothing here reads, skipped while parsing because they are most of a large
# file: `koca58` is 20 MB, nearly all of it copper zones and tracks. `arc` is skipped at
# the top level only, where it is a copper track; inside a `gr_poly`'s `pts` it is part
# of the outline.
SKIP_ANYWHERE = {"zone", "segment", "via", "pad", "model", "fp_text", "gr_text",
                 "property", "net", "net_class", "dimension", "group", "title_block",
                 "setup", "image", "embedded_files", "generated", "target"}
SKIP_TOP = {"arc"}

OUTLINE_SHAPES = {"line", "arc", "circle", "rect", "poly", "curve"}
COPPER = re.compile(r"^(?:F|B|In\d+)\.Cu$")


class NotABoard(ValueError):
    pass


# -- parsing ---------------------------------------------------------------------------

def parse(text):
    """The file's top node as nested lists of strings, with skipped subtrees left out."""
    tokens = TOKEN.finditer(text)
    stack = [[]]
    for m in tokens:
        t = m.group()
        if t == "(":
            head = next(tokens, None)
            if head is None:
                break
            head = head.group()
            if head in ("(", ")"):
                raise NotABoard("a list with no head")
            depth = len(stack) - 1
            if head in SKIP_ANYWHERE or (depth == 1 and head in SKIP_TOP):
                level = 1
                for m2 in tokens:
                    t2 = m2.group()
                    if t2 == "(":
                        level += 1
                    elif t2 == ")":
                        level -= 1
                        if level == 0:
                            break
                continue
            node = [head]
            stack[-1].append(node)
            stack.append(node)
        elif t == ")":
            if len(stack) == 1:
                raise NotABoard("unbalanced parentheses")
            stack.pop()
        else:
            stack[-1].append(t[1:-1].replace('\\"', '"') if t.startswith('"') else t)
    top = stack[0]
    if not top or not isinstance(top[0], list) or top[0][0] != "kicad_pcb":
        raise NotABoard("not a KiCad board file")
    return top[0]


def kids(node, name):
    return [c for c in node[1:] if isinstance(c, list) and c[0] == name]


def kid(node, name):
    found = kids(node, name)
    return found[0] if found else None


def xy(node, name):
    """`(name x y)` as a point, or None."""
    k = kid(node, name)
    return (float(k[1]), float(k[2])) if k and len(k) >= 3 else None


# -- geometry --------------------------------------------------------------------------

def placer(at):
    """A function from a footprint's local (x, y) to the board's.

    KiCad's own `RotatePoint`: a positive angle turns +x towards -y as the file's
    coordinates run (y grows downward), so the local point is turned by MINUS the angle
    in ordinary maths. A pure rotation, so an arc keeps its sweep direction.
    """
    x0, y0 = float(at[1]), float(at[2])
    a = math.radians(float(at[3])) if len(at) > 3 and _num(at[3]) else 0.0
    c, s = math.cos(a), math.sin(a)
    return lambda p: (x0 + p[0]*c + p[1]*s, y0 - p[0]*s + p[1]*c)


def _num(t):
    try:
        float(t)
        return True
    except (TypeError, ValueError):
        return False


def arc_points(centre, r, a0, sweep):
    """The ends of an arc and every axis extreme it passes, angles in degrees."""
    pts = [(centre[0] + r*math.cos(math.radians(a0)), centre[1] + r*math.sin(math.radians(a0))),
           (centre[0] + r*math.cos(math.radians(a0 + sweep)),
            centre[1] + r*math.sin(math.radians(a0 + sweep)))]
    lo, hi = (a0, a0 + sweep) if sweep >= 0 else (a0 + sweep, a0)
    k = math.ceil(lo / 90)
    while k * 90 <= hi:
        a = math.radians(k * 90)
        pts.append((centre[0] + r*math.cos(a), centre[1] + r*math.sin(a)))
        k += 1
    return pts


def three_point_arc(p1, pm, p2):
    """`(extreme points, ends)` of the arc from p1 through pm to p2."""
    ax, ay = p1
    bx, by = pm
    cx, cy = p2
    d = 2 * (ax*(by - cy) + bx*(cy - ay) + cx*(ay - by))
    if abs(d) < 1e-12:
        return [p1, pm, p2], (p1, p2)
    ux = ((ax*ax + ay*ay)*(by - cy) + (bx*bx + by*by)*(cy - ay) + (cx*cx + cy*cy)*(ay - by)) / d
    uy = ((ax*ax + ay*ay)*(cx - bx) + (bx*bx + by*by)*(ax - cx) + (cx*cx + cy*cy)*(bx - ax)) / d
    r = math.hypot(ax - ux, ay - uy)
    ang = lambda p: math.degrees(math.atan2(p[1] - uy, p[0] - ux))  # noqa: E731
    a_s, a_m, a_e = ang(p1), ang(pm), ang(p2)
    ccw = (a_e - a_s) % 360
    sweep = ccw if (a_m - a_s) % 360 <= ccw else ccw - 360
    if p1 == p2:
        sweep = 360.0
    return arc_points((ux, uy), r, a_s, sweep), (p1, p2)


def bezier_points(p0, p1, p2, p3):
    """The ends of a cubic Bezier and its extremes in x and in y."""
    pts = [p0, p3]
    for axis in (0, 1):
        a = -p0[axis] + 3*p1[axis] - 3*p2[axis] + p3[axis]
        b = 2 * (p0[axis] - 2*p1[axis] + p2[axis])
        c = p1[axis] - p0[axis]
        roots = []
        if abs(a) < 1e-12:
            if abs(b) > 1e-12:
                roots = [-c / b]
        else:
            disc = b*b - 4*a*c
            if disc >= 0:
                roots = [(-b + math.sqrt(disc)) / (2*a), (-b - math.sqrt(disc)) / (2*a)]
        for t in roots:
            if 0 < t < 1:
                u = 1 - t
                pts.append(tuple(u**3*p0[i] + 3*u*u*t*p1[i] + 3*u*t*t*p2[i] + t**3*p3[i]
                                 for i in (0, 1)))
    return pts


def shape(node, place):
    """`(extreme points, ends)` for one outline item, in board coordinates.

    `ends` is the pair of endpoints for an open stroke and None for a closed shape.
    """
    kind = node[0].split("_", 1)[1]
    if kind == "line":
        a, b = place(xy(node, "start")), place(xy(node, "end"))
        return [a, b], (a, b)
    if kind == "rect":
        (x1, y1), (x2, y2) = xy(node, "start"), xy(node, "end")
        return [place(p) for p in ((x1, y1), (x2, y1), (x2, y2), (x1, y2))], None
    if kind == "circle":
        c, e = xy(node, "center"), xy(node, "end")
        r = math.hypot(e[0] - c[0], e[1] - c[1])
        cx, cy = place(c)
        return [(cx - r, cy - r), (cx + r, cy + r)], None
    if kind == "arc":
        if kid(node, "mid"):
            return three_point_arc(place(xy(node, "start")), place(xy(node, "mid")),
                                   place(xy(node, "end")))
        # KiCad 5: start is the centre, end the arc's first point, angle the sweep.
        c, p = place(xy(node, "start")), place(xy(node, "end"))
        sweep = float(kid(node, "angle")[1])
        r = math.hypot(p[0] - c[0], p[1] - c[1])
        a0 = math.degrees(math.atan2(p[1] - c[1], p[0] - c[0]))
        pts = arc_points(c, r, a0, sweep)
        return pts, (pts[0], pts[1])
    if kind == "poly":
        pts = []
        for item in (kid(node, "pts") or [])[1:]:
            if item[0] == "xy":
                pts.append(place((float(item[1]), float(item[2]))))
            elif item[0] == "arc":
                pts += three_point_arc(place(xy(item, "start")), place(xy(item, "mid")),
                                       place(xy(item, "end")))[0]
        return pts, None
    if kind == "curve":
        ctrl = [place((float(i[1]), float(i[2]))) for i in (kid(node, "pts") or [])[1:]
                if i[0] == "xy"]
        if len(ctrl) != 4:
            return ctrl, (ctrl[0], ctrl[-1]) if ctrl else None
        return bezier_points(*ctrl), (ctrl[0], ctrl[3])
    raise ValueError(kind)


def on_edge_cuts(node):
    layer = kid(node, "layer")
    return bool(layer) and len(layer) > 1 and layer[1] == "Edge.Cuts"


def is_outline_item(node, prefixes):
    if not isinstance(node, list) or "_" not in node[0]:
        return False
    prefix, kind = node[0].split("_", 1)
    return prefix in prefixes and kind in OUTLINE_SHAPES and on_edge_cuts(node)


def outline_items(board):
    """Every Edge.Cuts shape on the board, as `(extreme points, ends)`."""
    same = lambda p: p  # noqa: E731
    found = [shape(n, same) for n in board[1:] if is_outline_item(n, {"gr"})]
    for fp in kids(board, "footprint") + kids(board, "module"):
        at = kid(fp, "at")
        place = placer(at) if at else same
        found += [shape(n, place) for n in fp[1:] if is_outline_item(n, {"fp", "gr"})]
    # A shape with no extent cuts nothing: Sofle v1's stray zero-length line sits 75 mm
    # above its board, and KiCad's own outline ignores it.
    return [f for f in found if f[0] and _extent(f[0]) > 1e-6]


def _extent(points):
    x0, y0, x1, y1 = bbox(points)
    return max(x1 - x0, y1 - y0)


def bbox(points):
    xs, ys = [p[0] for p in points], [p[1] for p in points]
    return (min(xs), min(ys), max(xs), max(ys))


def outlines(items):
    """`(outer, inner)` boxes of the drawing's closed shapes: strokes joined end to end
    are one shape. `outer` are the separate outlines -- a split's two halves drawn side
    by side are two -- and `inner` everything sitting wholly inside one of them, which is
    a hole or a cut-out."""
    parent = list(range(len(items)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    seen = {}
    for i, (_, ends) in enumerate(items):
        for p in ends or ():
            gx, gy = round(p[0] * 50), round(p[1] * 50)   # a 0.02 mm grid
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    j = seen.get((gx + dx, gy + dy))
                    if j is not None:
                        parent[find(i)] = find(j)
            seen[(gx, gy)] = i
    groups = collections.defaultdict(list)
    for i, (pts, _) in enumerate(items):
        groups[find(i)] += pts
    boxes = [bbox(pts) for pts in groups.values()]

    def inside(a, b):
        e = 0.01
        return b[0] - e <= a[0] and b[1] - e <= a[1] and a[2] <= b[2] + e and a[3] <= b[3] + e

    outer, inner = [], []
    for i, a in enumerate(boxes):
        held = any(j != i and inside(a, b) and (a != b or j < i) for j, b in enumerate(boxes))
        (inner if held else outer).append(a)
    return outer, inner


def is_switch_cutout(b):
    """A hole a switch fits: MX and Choc plates cut 13.8 to 14 mm, with clip notches and
    kerf allowances reaching 15.6. A cut-out turned on the board is not counted -- this is
    a count of what is there, and silence is not "no cut-outs"."""
    return all(CUTOUT_MM[0] <= side <= CUTOUT_MM[1] for side in (b[2] - b[0], b[3] - b[1]))


def copper_layers(board):
    table = kid(board, "layers")
    if not table:
        return None
    n = sum(1 for row in table[1:] if isinstance(row, list) and len(row) > 1
            and COPPER.match(row[1]))
    return n or None


def measure(text):
    """One board file: `width_mm`, `height_mm`, `layers`, `pieces`, `bbox`, `boxes` (each
    separate outline's own) and `switch_cutouts`, or `unknown` with the reason the size
    could not be read."""
    try:
        board = parse(text)
    except NotABoard as e:
        return {"unknown": str(e)}
    out = {}
    layers = copper_layers(board)
    if layers:
        out["layers"] = layers
    try:
        items = outline_items(board)
    except (TypeError, ValueError, IndexError) as e:
        return {**out, "unknown": f"an Edge.Cuts shape could not be read ({type(e).__name__})"}
    if not items:
        return {**out, "unknown": "no outline drawn on Edge.Cuts"}
    box = bbox([p for pts, _ in items for p in pts])
    outer, inner = outlines(items)
    out.update({"width_mm": round(box[2] - box[0], 2), "height_mm": round(box[3] - box[1], 2),
                "pieces": len(outer), "bbox": _round(box),
                "boxes": [_round(b) for b in outer],
                "switch_cutouts": sum(1 for b in inner if is_switch_cutout(b))})
    return out


def _round(box):
    return [round(v, 3) for v in box]


# -- what a file draws -----------------------------------------------------------------

def _dedupe(switches):
    seen, out = set(), []
    for x, y, *rest in switches:
        k = (round(x), round(y))
        if k not in seen:
            seen.add(k)
            out.append((x, y, float(rest[0]) if rest else 0.0))
    return out


def _holds(b, x, y):
    return (b[0] - CONTAINS_MM <= x <= b[2] + CONTAINS_MM and
            b[1] - CONTAINS_MM <= y <= b[3] + CONTAINS_MM)


def quarter_turned(switches):
    """Is the board drawn on its side? Most of its switches turned 90 or 270 degrees."""
    turned = sum(1 for *_, r in switches if abs((r % 180) - 90) <= 10)
    return turned >= TURNED_SHARE * len(switches)


def _size(b, turned):
    w, h = round(b[2] - b[0], 2), round(b[3] - b[1], 2)
    return [h, w] if turned else [w, h]


def board_of(result, switches, split, keys):
    """What one file draws, checked against its own switches (`position-scan.json`).

    `{"drawn": "board" | "half" | "both halves", "sizes": [[w, h], ...]}`, one size per
    board in the file, left to right; or `unknown` with the reason, keeping `drawn` when
    that much is known. `split` is the design's body, `keys` its layout's key count.

    A SPLIT FILE HOLDING ALL THE KEYS DRAWS BOTH HALVES. Ferris, Sweep and Corne v4 put
    both halves in one file, joined by snap-off tabs or framed as a panel, and then the
    outline is ONE shape whose extent is the pair's. One half's size cannot be read off
    it, so it is Unknown; only halves drawn as two separate outlines are each measured.

    ONLY AN OUTLINE HOLDING SWITCHES IS A BOARD. A separate outline beside it with none
    is a plate or a bottom drawn in the same file. Where they are joined into one shape
    the switches fill a small part of it -- Soyuz's 20 switches fill a quarter of its
    265 mm, the rest its plate and bottom -- and below `MIN_COVERAGE` the file is a
    panel, Unknown. So is a unibody file with far more switches than the layout has keys.

    A BOARD DRAWN ON ITS SIDE IS TURNED BACK. When most switches sit at a quarter turn the
    file's x is the keyboard's height, so width and height swap.

    A split file whose switches were not found is Unknown: nothing then says whether it
    draws one half or both. A unibody file with none found is taken as its outline, the
    one reading open to it, as before this check existed.
    """
    out = {"layers": result["layers"]} if "layers" in result else {}
    if "bbox" not in result:
        return {**out, "unknown": result.get("unknown", "not measured")}
    boxes = result.get("boxes") or [result["bbox"]]
    if not switches:
        if split:
            return {**out, "unknown": "the file's switches were not found, so whether it "
                                      "draws one half or both is not known"}
        if len(boxes) > 1:
            return {**out, "unknown": f"{len(boxes)} separate outlines and no switches "
                                      "found to say which is the board"}
        return {**out, "drawn": "board", "sizes": [_size(boxes[0], False)]}
    sw = _dedupe(switches)
    if not all(any(_holds(b, x, y) for b in boxes) for x, y, _ in sw):
        return {**out, "unknown": "the Edge.Cuts outline does not contain the board's switches"}
    turned = quarter_turned(sw)
    held = sorted((b for b in boxes if any(_holds(b, x, y) for x, y, _ in sw)),
                  key=lambda b: (b[0], b[1]))
    if split and keys and len(sw) >= BOTH_HALVES * keys:
        out["drawn"] = "both halves"
        if len(held) == 1:
            return {**out, "unknown": f"both halves are drawn in one outline ({len(sw)} "
                                      f"switches for {keys} keys), joined by tabs or a "
                                      "frame, so one half's size cannot be read"}
        if len(held) != 2:
            return {**out, "unknown": f"both halves' {len(sw)} switches sit in "
                                      f"{len(held)} separate outlines"}
    elif not split and keys and len(sw) >= PANEL_SHARE * keys:
        return {**out, "unknown": f"the file places {len(sw)} switches for a {keys}-key "
                                  "layout: several boards, or a panel of them"}
    else:
        out["drawn"] = "half" if split else "board"
        if len(held) != 1:
            return {**out, "unknown": f"the switches sit in {len(held)} separate outlines"}
    for b in held:
        inside = [(x, y) for x, y, _ in sw if _holds(b, x, y)]
        fill = ((max(x for x, _ in inside) - min(x for x, _ in inside) + PITCH_MM) *
                (max(y for _, y in inside) - min(y for _, y in inside) + PITCH_MM) /
                max((b[2] - b[0]) * (b[3] - b[1]), 1e-9))
        if fill < MIN_COVERAGE:
            return {**out, "unknown": f"its switches fill {fill:.0%} of the outline: the "
                                      "board in a panel with its plates or other parts"}
    if held == [result["bbox"]]:
        # The whole drawing: its size as measured, before the boxes were rounded.
        w, h = result["width_mm"], result["height_mm"]
        return {**out, "sizes": [[h, w] if turned else [w, h]]}
    return {**out, "sizes": [_size(b, turned) for b in held]}


# -- one design, several files ----------------------------------------------------------

HAND = re.compile(r"(?:^|[/_\-\s.])(left|right|l|r)(?=$|[/_\-\s.])", re.I)


def hand(path):
    """`left`, `right`, or None: which hand a path names, by word or by `_L`/`_R`."""
    found = {m.group(1).lower()[0] for m in HAND.finditer(path)}
    return {"l": "left", "r": "right"}[found.pop()] if len(found) == 1 else None


def _agree(sizes):
    return all(max(s[i] for s in sizes) - min(s[i] for s in sizes) <= AGREE_MM for i in (0, 1))


def combine(files):
    """A design's size from its own files' `board_of` verdicts, `{path: verdict}`.

    One size where every board agrees within `AGREE_MM`, the larger. Halves of different
    sizes -- Fourier's right hand is wider than its left -- are kept as `half_sizes_mm`,
    left first, but only where that is what they are: two files of one half each, named
    for opposite hands, or one file with both halves drawn apart. Anything else that
    differs is variants under one design, Unknown with each file's size for a person.
    """
    results = list(files.values())
    out = {}
    layers = {r.get("layers") for r in results}
    if len(layers) == 1 and None not in layers:
        out["layers"] = layers.pop()
    drawn = {r.get("drawn") for r in results}
    if len(drawn) == 1 and None not in drawn:
        out["drawn"] = next(iter(drawn))
    missing = [p for p, r in sorted(files.items()) if "sizes" not in r]
    if missing:
        return {**out, "unknown": "; ".join(f"{p}: {files[p].get('unknown', 'not measured')}"
                                            for p in missing)}
    if "drawn" not in out:
        return {**out, "unknown": "the board files draw different things: " + ", ".join(
            f"{p} {r['drawn']}" for p, r in sorted(files.items()))}
    sizes = [s for r in results for s in r["sizes"]]
    if _agree(sizes):
        out.update({"width_mm": max(s[0] for s in sizes), "height_mm": max(s[1] for s in sizes)})
        return out
    hands = {hand(p): r["sizes"][0] for p, r in files.items()}
    if out["drawn"] == "half" and len(files) == 2 and set(hands) == {"left", "right"}:
        return {**out, "half_sizes_mm": [hands["left"], hands["right"]]}
    if out["drawn"] == "both halves" and len(files) == 1:
        return {**out, "half_sizes_mm": sizes}
    return {**out, "unknown": "board files differ in size: " + ", ".join(
        f"{p} " + " and ".join(f"{w} x {h}" for w, h in r["sizes"])
        for p, r in sorted(files.items()))}


def plate_row(result, pcb, split=False, keys=None):
    """A plate file's row: its measure, `role = "switch plate"` where it has switch-sized
    holes, and turned to the PCB's orientation where it is the PCB's size turned a
    quarter (xenon-e draws its plates on their side). `pcb` is the design's entry.

    A split's switch plate with a hole for most of the layout's keys draws both halves,
    as a PCB with most of its switches does; one with fewer, one half."""
    row = {k: v for k, v in result.items() if k not in ("bbox", "boxes")}
    if row.get("switch_cutouts", 0) >= SWITCH_PLATE_CUTOUTS:
        row["role"] = "switch plate"
        if split and keys:
            row["drawn"] = ("both halves" if row["switch_cutouts"] >= BOTH_HALVES * keys
                            else "half")
    if pcb and "width_mm" in row and "width_mm" in pcb:
        w, h, pw, ph = row["width_mm"], row["height_mm"], pcb["width_mm"], pcb["height_mm"]
        if not _agree([[w, h], [pw, ph]]) and _agree([[h, w], [pw, ph]]):
            row.update({"width_mm": h, "height_mm": w, "turned": True})
    return row


def plates_by_slug(scan, boards, designs_per_repo):
    """`{slug: [plate path, ...]}` for designs whose repo holds that design alone."""
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    from draft_boards import kind as path_kind

    slugs_per_repo = collections.defaultdict(list)
    for slug, info in boards.items():
        slugs_per_repo[info["repo"]].append(slug)
    out = collections.defaultdict(list)
    for key, entry in scan.items():
        repo, path = key.split("\t", 1)
        owners = slugs_per_repo.get(repo) or []
        if len(owners) != 1 or designs_per_repo.get(repo) != 1:
            continue
        pk = path_kind(path)
        if pk == "part" or (entry.get("kind") == "plate" and pk == "board"):
            out[owners[0]].append(path)
    return {s: sorted(p) for s, p in out.items()}


# -- the seed file ---------------------------------------------------------------------

HEADER = """\
# seed/board-sizes.toml -- each design's PCB size and copper layer count, measured from
# its own board files by scripts/measure_board_size.py. MACHINE-WRITTEN: a re-run
# replaces this file, so a correction goes in the script or the board attribution, not
# here. The script's module doc has the rules; in short:
#
#   drawn                 what one board file holds: "board" (a unibody), "half" (one
#                         half of a split) or "both halves" (a split's two in one file)
#   width_mm, height_mm   the size of EACH board a build needs -- for a split, one half
#                         -- from the Edge.Cuts outline's extent at line centres, in mm
#   half_sizes_mm         a split whose halves differ: [[w, h] left, [w, h] right]
#   layers                copper layers in the board's own layer table
#   unknown               why the size is missing -- a field that is absent is Unknown,
#                         never zero
#   paths                 the board files read, in `repo`
#
# [[plate]] rows are plate-named files in a repo that holds exactly one design. `role`
# is "switch plate" where the file has switch-sized holes, and absent (Unknown) where it
# has none: a bottom, a cover or a case layer, or a plate cut some other way. `turned`
# is a plate drawn a quarter turn from its PCB, given here in the PCB's orientation.
"""


def _value(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, str):
        return json.dumps(v, ensure_ascii=False)
    if isinstance(v, list):
        return "[" + ", ".join(_value(x) for x in v) + "]"
    return repr(v) if isinstance(v, float) else str(v)


ENTRY_KEYS = ("slug", "repo", "drawn", "width_mm", "height_mm", "half_sizes_mm", "layers",
              "unknown", "paths")
PLATE_KEYS = ("slug", "repo", "path", "role", "drawn", "width_mm", "height_mm", "turned", "pieces",
              "switch_cutouts", "layers", "unknown")


def to_toml(entries, plates, measured_on):
    lines = [HEADER]
    for table, rows, keys in (("entry", entries, ENTRY_KEYS), ("plate", plates, PLATE_KEYS)):
        for row in rows:
            lines.append(f"[[{table}]]")
            width = max(len(k) for k in ENTRY_KEYS + PLATE_KEYS + ("measured_on",))
            for k in keys:
                if row.get(k) is not None:
                    lines.append(f"{k:<{width}} = {_value(row[k])}")
            lines.append(f"{'measured_on':<{width}} = {measured_on}")
            lines.append("")
    return "\n".join(lines)


# -- the run ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jobs", type=int, default=8)
    ap.add_argument("--only", help="one design's files, for a spot check")
    ap.add_argument("--refresh", action="store_true", help="re-read cached files")
    ap.add_argument("--out", default=str(OUT))
    args = ap.parse_args()

    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    from draft_boards import load_boards
    from measure_key_pitch import fetch

    designs = json.loads((REPO/"src/main/resources/seed/designs.json").read_text())
    boards = json.loads((SCRATCH/"design-boards.json").read_text())
    scan = json.loads((SCRATCH/"board-scan.json").read_text())
    positions = json.loads((SCRATCH/"position-scan.json").read_text())
    board_sets, _, _, _ = load_boards(str(SCRATCH/"board-scan.json"))
    designs_per_repo = {r: len(sets) for r, sets in board_sets.items()}
    plates = plates_by_slug(scan, boards, designs_per_repo)

    jobs = set()
    for slug, info in boards.items():
        if args.only and slug != args.only:
            continue
        jobs |= {(info["repo"], p) for p in info["paths"]}
        jobs |= {(info["repo"], p) for p in plates.get(slug, [])}

    cache = json.loads(CACHE.read_text()) if CACHE.exists() else {}
    todo = sorted(j for j in jobs if args.refresh or "\t".join(j) not in cache)
    print(f"{len(jobs)} files, {len(jobs) - len(todo)} cached, {len(todo)} to read")

    failed = {}

    def work(job):
        text, err = fetch(*job)
        if text is None or err:
            return job, {"unknown": f"could not fetch ({err})"}, False
        return job, measure(text), True

    with ThreadPoolExecutor(args.jobs) as pool:
        for i, (job, result, ok) in enumerate(pool.map(work, todo), 1):
            key = "\t".join(job)
            if ok:
                cache[key] = result
            else:
                failed[key] = result
            if i % 25 == 0:
                print(f"  {i}/{len(todo)}", flush=True)
                CACHE.write_text(json.dumps(cache, indent=1, sort_keys=True) + "\n")
    CACHE.write_text(json.dumps(cache, indent=1, sort_keys=True) + "\n")

    def result(repo, path):
        return dict(failed.get(f"{repo}\t{path}") or cache.get(f"{repo}\t{path}")
                    or {"unknown": "not measured"})

    entries, plate_rows = [], []
    why = collections.Counter()
    for d in sorted(designs, key=lambda d: d["slug"]):
        slug = d["slug"]
        info = boards.get(slug)
        if args.only and slug != args.only:
            continue
        if not info:
            entries.append({"slug": slug, "unknown": "no attributed board file"})
            why["no attributed board file"] += 1
            continue
        split = (d.get("body") or {}).get("kind") == "split"
        # The most keys any of its layouts has: a half file with options for a smaller
        # layout must not read as both halves of it.
        keys = max((lay["keys"] for lay in d.get("layouts") or [] if lay.get("keys")),
                   default=None)
        switches = (positions.get(slug) or {}).get("boards") or {}
        got = combine({p: board_of(result(info["repo"], p), switches.get(p), split, keys)
                       for p in info["paths"]})
        entries.append({"slug": slug, "repo": info["repo"], **got, "paths": info["paths"]})
        if "unknown" in got:
            reason = got["unknown"].split("; ")[0]
            reason = re.sub(r"^.*?\.kicad_pcb: ", "", reason)
            reason = re.sub(r"\(.*?\)|\d+(\.\d+)?%?", "#", reason.split(":")[0])
            why[reason] += 1
        for path in plates.get(slug, []):
            plate_rows.append({"slug": slug, "repo": info["repo"], "path": path,
                               **plate_row(result(info["repo"], path), got, split, keys)})

    today = datetime.date.today().isoformat()
    if args.only:
        for e in entries + plate_rows:
            print(json.dumps(e))
        return
    pathlib.Path(args.out).write_text(to_toml(entries, plate_rows, today))

    measured = sum(1 for e in entries if "width_mm" in e or "half_sizes_mm" in e)
    print(f"\n{len(entries)} designs: {measured} measured, {len(entries) - measured} Unknown")
    for reason, n in why.most_common():
        print(f"  {n:>4}  {reason}")
    print(f"layers known for {sum(1 for e in entries if 'layers' in e)}: "
          f"{dict(sorted(collections.Counter(e['layers'] for e in entries if 'layers' in e).items()))}")
    print(f"drawn: {dict(collections.Counter(e.get('drawn') for e in entries).most_common())}")
    print(f"halves of different sizes: {sum(1 for e in entries if 'half_sizes_mm' in e)}")
    pm = sum(1 for p in plate_rows if "width_mm" in p)
    sp = sum(1 for p in plate_rows if p.get("role") == "switch plate")
    print(f"{len(plate_rows)} plate files for {len({p['slug'] for p in plate_rows})} designs: "
          f"{pm} measured, {len(plate_rows) - pm} Unknown; {sp} switch plates, "
          f"{sum(1 for p in plate_rows if p.get('turned'))} turned to their PCB")
    if failed:
        print(f"{len(failed)} files could not be fetched (not cached; a re-run retries)")
    print(f"-> {args.out}")


if __name__ == "__main__":
    main()
