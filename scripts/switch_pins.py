"""Which switch family a footprint takes, read off where its pins are.

For a footprint whose NAME gives no family -- `Biacco42/Ergo42` places 28 of
`library:key_switch` -- the name lexicon in `extract_switch_footprints.py` has nothing to
go on, and the board-first pass defers the board as "no switch footprint". The pins say
it instead. Every switch family puts its two pins in a fixed place around its centre post:

  MX       (-3.81, -2.54) and (2.54, -5.08), centre post 4 mm
  Choc v1  (0, -5.9) and (5, -3.8), centre post 3.2 mm, and two side posts at (+/-5.5, 0)
           (Kailh's CPG1350 drawing)

Two pins and the centre make a triangle, and a triangle's three sides do not change when
the footprint is turned or mirrored, so reversible and upside-down footprints read the same
as the drawing. MX's sides are 4.58, 5.68 and 6.84 mm; Choc v1's 5.90, 6.28 and 5.42.

WHAT COUNTS AS A PIN. Two numbered plated holes with DIFFERENT numbers ([[pads-are-not-pins]]:
a switch's two pins carry two signals, and one number twice is one net), or the two ~3 mm
holes of a Kailh hotswap socket's posts when the footprint also has at least two numbered
surface pads to solder that socket to.

WHAT IT WILL NOT CLAIM. Only families with a round centre post, because the centre is the
origin of the triangle: Alps, Kailh X and Choc Mini have none. MX needs a 4 mm centre hole.
Choc v1 needs a centre hole of 3.3 to 4.2 mm (its own 3.4, or Ergo42's 4.0 shared with MX)
AND its two side posts, 11 mm apart: a hybrid with many pins can land one pair on Choc's
spacing by chance, and the posts are what a Choc footprint always drills. Choc v2 (PG1353)
is NOT read at all: it keeps v1's pins and widens the post to 5 mm, and a v1 fits that hole
too, so Choc pins round a 5 mm hole could be either. The first corpus run (2026-11-16) read
cyril279's three `pg1353` boards as v1 before this was learned.

"v2 keeps v1's pins" was checked 2026-11-16 on footprints, not on Kailh's own PG1353 drawing
(not found): kiswitch's `SW_Hotswap_Kailh_Choc_V1V2` puts its socket holes where its `_V1`
does, (0, -5.9) and (5, -3.8), with a 5.05 mm centre hole against 3.45; Env-KB65M's
`SW_Kailh_Choc_V2` and cyril279's `pg1353` drill the same pins round a 5.0 and 4.9 mm hole.

A reading, not a ruling: it fills only what the name left empty, never overrides it.
"""
import math
import re

FOOTPRINT = re.compile(r'\((?:footprint|module)\s+(?:"([^"]*)"|([^\s()]+))')
PAD = re.compile(r'\(pad\s+(?:"([^"]*)"|([^\s()]*))\s+(smd|thru_hole|np_thru_hole|connect)\b')
DRILL = re.compile(r'\(drill\s+(?:oval\s+)?([\d.]+)(?:\s+([\d.]+))?')
AT = re.compile(r'\(at\s+([-\d.]+)\s+([-\d.]+)')

PIN_HOLE = (0.3, 2.0)
SOCKET_HOLE = (2.9, 3.3)
TOLERANCE = 0.15


def _triangle(p, q):
    return (math.dist((0, 0), p), math.dist((0, 0), q), math.dist(p, q))


# family -> (its two pins in the maker's drawing, the centre-hole drills it fits)
FAMILIES = {
    "MX": (_triangle((-3.81, -2.54), (2.54, -5.08)), (3.8, 4.2)),
    "ChocV1": (_triangle((0, -5.9), (5, -3.8)), (3.3, 4.2)),
}
SIDE_POSTS = {"ChocV1": 11.0}


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
    pad's position and drill are read up to the next pad. A slot's drill is its narrow side."""
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


def _matches(centre, p, q, triangle):
    a, b, c = _triangle((p[0] - centre[0], p[1] - centre[1]), (q[0] - centre[0], q[1] - centre[1]))
    ta, tb, tc = triangle
    if abs(c - tc) > TOLERANCE:
        return False
    return (abs(a - ta) <= TOLERANCE and abs(b - tb) <= TOLERANCE) or \
           (abs(a - tb) <= TOLERANCE and abs(b - ta) <= TOLERANCE)


def pin_families(block):
    """The switch families this footprint's pins fit, as a set. Empty when they fit none."""
    pads = [p for p in all_pads(block) if p[2] is not None]
    centres = [(xy, d) for n, t, xy, d in pads if t == "np_thru_hole" and d and 3.3 <= d <= 5.1]
    pins = [(n, xy) for n, t, xy, d in pads
            if t == "thru_hole" and n and d is not None and PIN_HOLE[0] <= d <= PIN_HOLE[1]]
    pairs = [(p, q) for i, (np_, p) in enumerate(pins) for nq, q in pins[i + 1:] if np_ != nq]
    if sum(1 for n, t, _, _ in pads if n and t in ("smd", "connect")) >= 2:
        sockets = [xy for n, t, xy, d in pads if d and SOCKET_HOLE[0] <= d <= SOCKET_HOLE[1]]
        pairs += [(p, q) for i, p in enumerate(sockets) for q in sockets[i + 1:]]
    posts = [xy for n, t, xy, d in pads if t == "np_thru_hole" and d and 1.5 <= d <= 2.1]

    def has_posts(family, c):
        span = SIDE_POSTS.get(family)
        if span is None:
            return True
        return any(abs(math.dist(a, b) - span) <= TOLERANCE
                   and abs(math.dist(a, c) - span / 2) <= TOLERANCE
                   and abs(math.dist(b, c) - span / 2) <= TOLERANCE
                   for i, a in enumerate(posts) for b in posts[i + 1:])

    found = set()
    for family, (triangle, (lo, hi)) in FAMILIES.items():
        if any(lo <= d <= hi and has_posts(family, c) and _matches(c, p, q, triangle)
               for c, d in centres for p, q in pairs):
            found.add(family)
    return found
