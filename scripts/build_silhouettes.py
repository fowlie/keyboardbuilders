#!/usr/bin/env python3
"""Emit each design's key positions, so the site can draw its layout silhouette.

Ticket 41, resolved 2026-08-21. Third-party photos are out of v1 for legal
exposure, so the generated silhouette is not a fallback -- it is the only image
the product has (ux.md §3.5).

THE BOARD FIRST, THEN THE FIRMWARE -- amended 2026-08-29 by Forgejo #31. Ticket 41 said
"QMK only", and that was wrong in a way nobody could see until the owner looked at a
picture: **a QMK `LAYOUT` is a matrix map, not a physical description.** KLOR states `y`
in {0,1,2,3} and is column-staggered with splay, so we drew a schematic and called it a
keyboard. Eight designs were flattened that way, while nine others drawn from an integer
grid genuinely ARE ortholinear -- Helix, Rhymestone, Fourier -- which is the control that
made the eight real rather than a threshold artefact.

So a design is now drawn from its own `.kicad_pcb` (`position-scan.json`) where taking
those coordinates loses nothing, and from the firmware otherwise. `pcb_geometry` holds
the two guards and the reasons; the short version is that a PCB knows where a switch is
and never how wide its keycap is, so a board with a 6.25u spacebar keeps the firmware.
Measured: 75 of 224 silhouettes come from the board, and the switch to it GAINED seven
pictures rather than costing any -- KLOR, Cantor Remix, Sofle Pico and four more, all of
which QMK could not draw at their true key count.

Helix still renders as a perfect grid, from its board. That is the check that this reads
the world rather than merely reading something different.

Three rulings from that ticket, the first now amended:

  * **QMK where the board cannot answer.** ZMK carries rotation and QMK does not, but ZMK cannot draw most
    of its own tree: 12 of 63 shields have physical-layout geometry, against QMK's
    3,672 keys of 3,690. Two renderers for a three-shield gain would make the same
    design look different depending on which tree drew it. Stated limitation:
    rotated boards render unrotated.

    Read "QMK always" literally: a design whose FIRMWARE row is ZMK still gets QMK
    geometry when QMK has the same board, and it usually does -- these are ports.
    Redox, Lily58, Helix and Ergodash are all in both trees. So a ZMK row is looked
    up in QMK by shield name across every path segment (`ergodash` lives at
    `omkbd/ergodash/rev1`), and the borrow is taken ONLY when the key counts match
    exactly. That guard is not ceremony: `quefrency` is 70 keys in ZMK and 82 or 85
    in QMK, and `tg4x` is 45 against 46 -- different configurations wearing the same
    name, and drawing one as the other would be a fabricated picture of a real
    board. 11 of the 14 ZMK designs pass; the other three keep the placeholder.
    (`tg4x` is drawn since 2026-09-26, from its own board through a person's ruled
    fill in seed/board-fills.toml -- the guard here still refuses it, as it should.)
  * **One silhouette per design, from the LARGEST layout** -- it is the superset,
    every position the design can have, and right for 1,383 of the 1,392 records
    that carry more than one.
  * **Except modular designs, which take the smallest.** A design whose layouts are
    near-exact multiples of the smallest is one module chained, not one board with
    optional keys, and the largest misrepresents it fourfold. `40percentclub/4x4`
    is [16, 32, 48, 64]. The test is multiples rather than spread, because the 114
    other wide-spread designs are optional-key variants -- `4pplet/waffling80` is
    [86, 87 ... 96], one TKL -- and a spread threshold catches the wrong set.

POSITIONS, not markup. The rendered size is a decision the page makes: ux.md's size
ladder is nominal, not fixed, because common scale means variable width, and the
"no gaps below 2px" detail level cannot be chosen here. So this emits geometry in
KEY UNITS and the renderer scales it.

Nothing here touches the domain. Ticket 41: "No facet, no type, no schema, no new
source." A silhouette is a rendering asset keyed by slug, not a fact about a design.

Usage: build_silhouettes.py [--designs FILE] [--out FILE] [--qmk DIR] [--positions FILE]
"""
import argparse
import collections
import itertools
import json
import tomllib
import math
import os
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import board_fills
import qmk_metadata
import trees as tree_registry
from measure_key_pitch import nearest_neighbour_gaps
from measure_keycaps import alternates, unit_of
from zmk_physical_layouts import physical_layouts as zmk_physical_layouts

REPO = pathlib.Path(__file__).resolve().parent.parent


def qmk_index(qmk_root):
    """Every QMK record that has geometry, reachable by ANY segment of its path.

    Indexing on the first segment only would miss `omkbd/ergodash` and
    `keebio/fourier`, which is most of what a cross-tree lookup is for.
    """
    index = {}
    for path in qmk_root.rglob("*.json"):
        if path.name not in ("info.json", "keyboard.json"):
            continue
        try:
            data = qmk_metadata.load(path)
        except ValueError:
            continue
        layouts = {k: v for k, v in (data.get("layouts") or {}).items() if v.get("layout")}
        if not layouts:
            continue
        record = str(path.parent.relative_to(qmk_root))
        largest = max(len(v["layout"]) for v in layouts.values())
        for segment in record.split("/"):
            index.setdefault(segment, set()).add((record, largest))
    return index


def borrow(name, key_count, index):
    """The QMK record for a design QMK does not name as its firmware, or None.

    Exact key count or nothing. A near miss is a different configuration of the
    same-named board, and drawing one as the other invents a picture.
    """
    exact = sorted(r for r, n in index.get(name, ()) if n == key_count)
    return exact[0] if exact else None


def merged_layouts(record, qmk_root):
    """QMK inherits metadata down the keyboard path, so merge from the top down."""
    out = {}
    parts = record.split("/")
    for i in range(len(parts)):
        for name in ("info.json", "keyboard.json"):
            path = qmk_root.joinpath(*parts[:i + 1])/name
            if not path.exists():
                continue
            try:
                out.update(qmk_metadata.load(path))
            except ValueError:
                continue
    return {k: v for k, v in (out.get("layouts") or {}).items() if v.get("layout")}


def is_modular(counts):
    """Are these layouts one module chained, rather than one board with options?

    Requires an actual doubling. Without it `4pplet/waffling80` [86 ... 96] passes,
    because 87 is within a key of 1x86 -- which is the false positive the ticket
    measured a spread threshold falling into.
    """
    smallest = min(counts)
    if smallest <= 0 or len(set(counts)) < 2 or max(counts) / smallest < 1.9:
        return False
    for count in counts:
        ratio = count / smallest
        if abs(ratio - round(ratio)) * smallest > 1:
            return False
    return True


def other_layouts(layouts, declared):
    """`{count: Geometry}` for every DECLARED count this tree states as its own
    complete, separate layout -- never inferred, never guessed, the same "exact key
    count or nothing" rule `borrow` has always applied. A count more than one macro
    states identically (an alias) draws whichever sorts first; that is a naming
    question (`seed/layout_names.toml`'s job), not a geometry one.
    """
    by_count = {}
    for macro_name, spec in sorted(layouts.items()):
        n = len(spec["layout"])
        if n in declared and n not in by_count:
            by_count[n] = macro_name
    # LESS THAN TWO IS NO EXTRA INFORMATION: a design whose tree states exactly the
    # count already drawn as the headline (and nothing else) would otherwise get a
    # `multi` entry that is a byte-for-byte redundant copy of a picture the page
    # already shows above it. Caught 2026-09-16 when this fired for 284 designs
    # instead of the genuine handful with a SECOND shape to offer.
    if len(by_count) < 2:
        return {}
    return {str(n): geometry(layouts[name]["layout"]) for n, name in by_count.items()}


def board_drawn_extra_layouts(design, declared, root):
    """[other_layouts] for a design whose HEADLINE picture came from its own board file
    (`paired`/`pts` below), restricted to THIS design's own QMK record -- never a
    cross-family borrow. A borrowed record is a different design that merely shares a
    name and one matching count; its other counts are not a stated fact about this one.

    `None` for a ZMK design with no QMK record of its own (`jorne`, e.g.) --
    [zmk_extra_layouts] is the ZMK counterpart, called alongside this at every site
    that calls it.
    """
    fw = first_firmware(design)
    if fw.get("family") != "qmk" or not fw.get("path"):
        return None
    layouts = merged_layouts(fw["path"], root)
    return other_layouts(layouts, declared) if layouts else None


def zmk_extra_layouts(design, declared, zmk_root):
    """[other_layouts]'s ZMK counterpart -- every DECLARED count a design's own ZMK
    shield states as a complete, separate `zmk,physical-layout`, geometry and all.

    `jorne` is the case this closes: its headline picture is drawn from its own board
    file (`board_drawn_extra_layouts` finds nothing, since it has no QMK record at
    all), yet its shield states three named, fully-positioned layouts -- 44, 42, 36 --
    that `merged_layouts` has never been able to read, being QMK-only. Restricted to a
    design naming a `shield` in its own ZMK firmware entry; a design with none is left
    alone rather than guessed at.
    """
    for fw in design["firmware"]:
        shield = fw.get("shield")
        if fw.get("family") != "zmk" or not shield:
            continue
        shield_dir = zmk_root/"app"/"boards"/"shields"/shield
        if not shield_dir.is_dir():
            continue
        by_count = zmk_physical_layouts(shield_dir, zmk_root)
        found = {str(n): geo for n, geo in by_count.items() if n in declared}
        if len(found) > 1:
            return found
    return {}


def choose(layouts, declared):
    """The one layout to draw, and why -- or `None` when the picture would contradict
    the row.

    THE DRAWING MUST HOLD AS MANY KEYS AS THE ROW SAYS. Only layouts whose key count is
    one the design actually publishes are candidates; if none is, nothing is drawn and
    §3.5's dashed placeholder says "we do not know this board's shape" -- which is true.

    This is the rule the BORROW path has always applied ("exact key-count match
    required", so `quefrency` at 70 never borrows QMK's 82). It was simply never applied
    to a design's own record, because until `without_encoder_positions` there was no way
    for a design's published count to differ from the layout it was drawn from.

    Now there is, and KLOR is the case: it publishes 42 keys, its QMK `LAYOUT` holds 44
    entries because one of them is the encoder, and drawing all 44 puts a 44-key picture
    above a row reading 42. A silhouette is the only image this product has; one that
    disagrees with the number beside it is worse than none.
    """
    counts = {name: len(spec["layout"]) for name, spec in layouts.items()}
    fits = {n: c for n, c in counts.items() if c in declared}
    if not fits:
        return None, None
    if is_modular(list(fits.values())):
        return min(fits, key=lambda n: fits[n]), "smallest (modular)"
    return max(fits, key=lambda n: fits[n]), "largest"


# A CAP THIS BIG LEAVES A HOLE. Dropping a firmware cap size to take the board costs
# something, and this is the size at which it starts to show. A cap under 2u sits
# entirely over its own switch position, so drawing it 1u square shortens it and does
# nothing else; a cap of 2u or more spans ground where another switch could have been,
# and drawing THAT one 1u opens a gap in the silhouette exactly where the eye expects a
# key. The line is that hole, not a number tuned until a set of boards came out right.
CAP_THAT_LEAVES_A_HOLE = 2.0


def board_turns(hands):
    """Does any of these board files place a switch off its own modal angle?

    The splay, the thumb arc, the rotated pinky column -- the whole of what a QMK
    `LAYOUT` cannot state, since it is a matrix map. Measured exactly as `pcb_geometry`
    measures it, against each file's own calibrated base, so this asks the same question
    the drawing is about to answer rather than a second, similar one.

    NO THRESHOLD, because the corpus turns out not to need one: of 218 board-drawable
    designs, 96 turn no key at all and the other 122 turn one by at least 5 degrees, with
    nothing whatever in between. "Any rotation" and "5 degrees or more" therefore select
    the same boards, and between two rules that cannot disagree the honest one to write
    down is the one with no number in it.
    """
    for points in hands:
        unit = board_unit([(p[0], p[1]) for p in points])
        if not unit:
            continue
        base = unrotated_angle(points) + column_calibration(points, unit)
        if any(round(-(((r - base + 90) % 180) - 90), 1) for _, _, r in points):
            return True
    return False


def board_may_draw(design, qmk_root, declared, hands):
    """May the board's switch positions draw this design, or does the firmware keep it?

    The guard that keeps a 6.25u spacebar from becoming a square: a PCB records where a
    switch is and never how wide its keycap is. When the firmware cannot be consulted --
    a ZMK design QMK does not carry -- the board draws, because there is no cap claim to
    lose.

    TWO-SIDED, amended 2026-09-06. The first version asked only whether the firmware
    claimed a non-1u key anywhere, and that question is too blunt in both directions.
    Owner-reported on Atreyu: `atreyu/rev1` states its two inner thumb keys as `h: 1.25`,
    which cost the design its board and left it drawn as QMK's flat matrix map -- an
    upright thumb column on a keyboard whose thumbs sit at 32 and 42 degrees, with the
    18-degree splay of all 60 keys gone along with them. Half a key of cap height was
    bought with the entire shape of the keyboard.

    So the guard now weighs both sides, and takes the board only where it WINS the trade:

      * **The cap being dropped leaves no hole** -- `CAP_THAT_LEAVES_A_HOLE`.
      * **The board says something the firmware cannot.** A flat board drawn from its own
        coordinates is the firmware's picture with the cap sizes thrown away, which is a
        pure loss however small those caps are. `slimorta` is the control: 58 switches,
        not one of them turned, four 1.25u caps -- it passes the cap test and correctly
        keeps the firmware, because there is nothing on that board to gain.

    Measured over the 70 designs the old guard held back: 32 sit on flat boards and are
    unaffected, and of the 38 on splayed boards, 18 pass the cap test -- every one an
    ergonomic board whose only oversize keys are 1u-wide thumb keys.
    """
    firmware = first_firmware(design)
    record = firmware.get("path")
    if firmware.get("family") != "qmk" or not record:
        return True
    layouts = merged_layouts(record, qmk_root)
    # NO LAYOUT AT THE DECLARED COUNT IS NOT "NO CLAIM". A count a person ruled
    # (`keys = 68`, the encoder's push dropped) or `without_encoder_positions` derived
    # sits one or two under every layout the record holds, and those layouts still
    # state this keyboard's caps: `basketweave` drew its 2.75u space bar as a 1u square
    # when this returned True here. So every layout the record holds is consulted.
    fits = ([v for v in layouts.values() if len(v["layout"]) in declared]
            or list(layouts.values()))
    if not fits:
        return True
    caps = [(k.get("w", 1), k.get("h", 1)) for v in fits for k in v["layout"]]
    if all(cap == (1, 1) for cap in caps):
        return True
    if max(max(cap) for cap in caps) >= CAP_THAT_LEAVES_A_HOLE:
        return False
    return board_turns(hands)


def board_unit(points):
    """Millimetres per key unit, from the board's own modal nearest-neighbour gap.

    `measure_key_pitch`'s instrument, not a second one: the mode is what survives a
    rotated thumb cluster or a lone key on a bracket. Gaps under 5 mm are dropped as
    co-located placements rather than neighbours.
    """
    gaps = [g for g in nearest_neighbour_gaps(points) if g > 5]
    if not gaps:
        return None
    return collections.Counter(gaps).most_common(1)[0][0]


def column_calibration(points, unit):
    """How far the modal angle sits from the angle that lines keys up with their columns.

    Owner-reported on Architeuthis Dux: the keys "should be rotated a few degrees inward,
    so that they line up with its column". Measured, every one of its five columns is off
    by EXACTLY 5.0 degrees -- a constant, which means the reference angle is wrong rather
    than the keys. Dux's modal footprint angle is 175 and the angle that aligns caps to
    columns is 180; the mode had six keys and the truth had three.

    A COLUMN'S OWN DIRECTION IS THE FALSIFIER. Take each column of three or more keys,
    measure the lean from its topmost key to its lowest, and compare that with the
    rotation the modal angle would emit. A cap is meant to sit square in its column, so
    those should agree.

    ONLY A CONSTANT OFFSET IS CORRECTED, and that restraint is the whole of it, because
    a varying one is a real design rather than an error. `hillside52` disagrees by 0, 0,
    -1.5, -7, -9.6 and -9.1 across its columns: its inner columns are offset diagonally
    while their keys stay upright, which is a choice its designer made and not something
    to average away. Dux's spread is 0.0, hillside's is 9.6, and the threshold sits
    between them with room. KLOR needs no correction and gets none -- its median is zero.
    """
    columns = []
    for p in sorted(points):
        for c in columns:
            if abs(sum(q[0] for q in c)/len(c) - p[0]) <= 0.6 * unit:
                c.append(p)
                break
        else:
            columns.append([p])
    base = unrotated_angle(points)
    offsets = []
    for column in columns:
        if len(column) < 3:
            continue
        column = sorted(column, key=lambda p: p[1])
        dx = column[-1][0] - column[0][0]
        dy = column[-1][1] - column[0][1]
        if dy <= 0:
            continue
        lean = -math.degrees(math.atan2(dx, dy))
        emitted = -(((column[0][2] - base + 90) % 180) - 90)
        offsets.append(lean - emitted)
    if len(offsets) < 3 or max(offsets) - min(offsets) > 2.0:
        return 0.0
    offsets.sort()
    median = offsets[len(offsets)//2]
    # ADDED, not subtracted: `emitted` moves WITH the base, so closing a gap of +5
    # means raising the base by 5. Getting this backwards doubled Dux's error to 10
    # instead of clearing it, which the same column check caught immediately.
    return round(median, 1) if abs(median) >= 1.0 else 0.0


def unrotated_angle(points):
    """The angle this board treats as "straight", against which splay is measured.

    THE MODE IS NOT ALWAYS THE ANSWER, and assuming it was produced a visible bug on
    every board that carries BOTH HALVES. Such a board's angles come in mirrored pairs,
    so the two most common values tie -- `bad-wings-bold` is ten keys at -15 and ten at
    +15, `bw2-v2` ten at 165 and ten at -165 -- and taking either as the reference tilts
    the entire keyboard by 15 degrees. Owner-reported on both.

    So: a clear plurality wins, and a tie falls back to the axis the angles are symmetric
    about. KLOR is the case that needs the plurality branch -- 10 keys at 180 against 5 at
    170 -- where its flat columns really are the reference and the axial mean would drag
    the board a few degrees off true.

    The fallback is the standard AXIAL mean: angles are doubled before averaging, because
    a rectangle is 180-degree symmetric and 179 degrees is one degree from zero, not 179.
    For +/-15 it returns 0, which is the axis those two mirror about.
    """
    counts = collections.Counter(round(p[2], 1) % 180 for p in points)
    ranked = counts.most_common()
    if len(ranked) == 1:
        return ranked[0][0]

    doubled = [math.radians(2 * a) for a, n in counts.items() for _ in range(n)]
    mean = math.atan2(sum(math.sin(d) for d in doubled) / len(doubled),
                      sum(math.cos(d) for d in doubled) / len(doubled))
    axial = round(math.degrees(mean) / 2, 1)

    # THE TEST IS SYMMETRY, NOT MARGIN. Two earlier versions asked whether the commonest
    # angle won by enough, and both were wrong because "enough" is not the question.
    # What actually distinguishes the two cases is whether the top two angles are a
    # MIRRORED PAIR:
    #
    #   `k34`       160 x16 and 20 x16  -- that is -20 and +20 about zero, a board whose
    #               two halves are angled. Neither is "straight"; the axis between them
    #               is, and the axial mean finds it.
    #   `hillside52`  0 x10 and 10 x9   -- a ONE-KEY margin, but not a mirrored pair.
    #               Zero is straight and ten degrees is a splayed column. A margin test
    #               called this a tie and shifted every key by half a degree, which is
    #               how the owner spotted it: the splay came out at 0.5, 4.5, 9.5, 15.5
    #               where the board plainly means 0, 5, 10, 15.
    #
    # `buzzard` is the same shape as hillside (0 x6 against -20 x5) and was ragged for the
    # same reason.
    def offset(angle):
        return (angle - axial + 90) % 180 - 90

    first, second = offset(ranked[0][0]), offset(ranked[1][0])
    mirrored = (first * second < 0
                and abs(abs(first) - abs(second)) <= 2.0
                and abs(first) > 2.0)
    return axial if mirrored else ranked[0][0]


def halves_side_by_side(points):
    """WITHDRAWN, and kept only as the record of why. Not called.

    This tried to tell a keyboard-shaped board file from a fabrication panel, so that a
    split carrying both hands could still be drawn. It is not sufficient, and the way it
    failed is the reason full-count splits are now refused outright: `kata0510/lily58`
    PASSES it -- two hands, 29 keys each, side by side, sharing rows -- and is still
    wrong, because each HAND is rotated a quarter turn within the panel. Every hand is
    81 mm across and 114 mm down where a Lily58 hand is plainly wider than tall.

    A whole-board counter-rotation does not reach that either; it turns the pair and
    leaves the hands as they were. Undoing an arbitrary per-hand placement is not
    something the coordinates can be made to answer, so the honest move is to stop
    guessing and let the firmware's schematic draw those designs.

    On a board holding BOTH hands, are they laid out as the keyboard or as a panel?

    A `.kicad_pcb` is a FABRICATION drawing, and nesting the two halves to waste less
    fibreglass is ordinary practice -- `elephant42` puts its right half below the left and
    turned around, `lily58` stacks them. Drawing that arrangement produces a keyboard
    nobody has ever held. Owner-reported on both.

    So a split whose board carries the full key count has to prove the file is laid out
    the way the keyboard is: one clear vertical gap, two halves of roughly equal size,
    and the same rows on each side. Anything else falls back to the firmware layout, which
    is a schematic but is at least a schematic OF A KEYBOARD.

    A design whose board holds ONE hand is not asked -- we place the second half ourselves
    and know where it goes.
    """
    xs = sorted({round(p[0], 2) for p in points})
    if len(xs) < 4:
        return False
    gaps = [(xs[i+1] - xs[i], xs[i]) for i in range(len(xs) - 1)]
    widest, at = max(gaps)
    left = [p for p in points if p[0] <= at]
    right = [p for p in points if p[0] > at]
    if not left or not right:
        return False
    # The gap between hands is a real space, not the gap between two columns.
    span = xs[-1] - xs[0]
    if widest < span * 0.08:
        return False
    if abs(len(left) - len(right)) > max(2, 0.15 * len(points)):
        return False
    # And the hands sit BESIDE each other, so they occupy the same rows.
    ly = (min(p[1] for p in left), max(p[1] for p in left))
    ry = (min(p[1] for p in right), max(p[1] for p in right))
    overlap = min(ly[1], ry[1]) - max(ly[0], ry[0])
    shorter = min(ly[1] - ly[0], ry[1] - ry[0])
    return shorter > 0 and overlap >= 0.6 * shorter


def _thumbs_say_right_hand(cells):
    """Handedness from the thumb cluster -- the FALLBACK, used only without a reference.

    The lowest three keys against the hand's own centroid: thumbs left of centre make a
    right hand. It is right for KLOR, whose firmware layout does not match its key count
    and which therefore has nothing better, and it is a guess wherever a board drops its
    outer column as low as its thumbs -- which is how it got `hillside52` wrong.
    """
    floor = sorted(cells, key=lambda c: -c[1])[:3]
    return sum(c[0] for c in floor)/len(floor) < sum(c[0] for c in cells)/len(cells)


def two_hands(points):
    """Split a both-hands board file into its two hands, or None if it does not divide.

    The separation is whichever axis actually separates them: `lily58` sits the hands
    side by side, `elephant42` stacks one below the other. Take the widest gap on each
    axis and use the one that yields two halves of near-equal size.
    """
    best = None
    for axis in (0, 1):
        values = sorted({round(p[axis], 2) for p in points})
        if len(values) < 4:
            continue
        widest, at = max((values[i+1] - values[i], values[i])
                         for i in range(len(values) - 1))
        first = [p for p in points if p[axis] <= at]
        second = [p for p in points if p[axis] > at]
        if not first or not second:
            continue
        if abs(len(first) - len(second)) > max(2, 0.15 * len(points)):
            continue
        if best is None or widest > best[0]:
            best = (widest, first, second)
    if best:
        return best[1], best[2]

    return None


def undo_half_turn(hands):
    """The two hands of one board file, with a hand the panel turned half round put back.

    Owner-reported 2026-10-04 on Dilemma v2 and v3 hotswap: "the right half is upside
    down". Their files stack the two hands one above the other, one of them laid with its
    thumb cluster pointing up. The checks below fold angles modulo 180 (a keycap looks the
    same either way), so that hand passed as upright and was drawn with its thumbs on top.

    THE THUMB KEYS SAY WHICH HAND, not the switch angle. A first version turned whichever
    hand had its switches at 180 degrees, and turned the wrong one: on Dilemma v2 that is
    the upright hand, and the other is laid at 0 with its thumbs above it. The thumb keys
    are the ones angled away from the hand's own modal angle, and in a hand as you hold it
    they sit below its centre. Two signals must agree before anything moves: the hands'
    modal angles are half a turn apart, and their angled keys sit on opposite sides of
    their centres. A library that places every switch at 180 (KLOR, the Sweep Bling LP)
    turns both hands alike and is left alone.
    """
    def modal(hand):
        return collections.Counter(round(r) % 360 for _, _, r in hand).most_common(1)[0][0]

    def off_zero(angle):
        return abs((angle + 180) % 360 - 180)

    def thumbs_above(hand):
        """True if the angled keys sit above the hand's centre, None without any."""
        m = modal(hand)
        angled = [y for _, y, r in hand if off_zero(round(r) % 360 - m) > 2]
        if not angled:
            return None
        centre = sum(y for _, y, _ in hand) / len(hand)
        return sum(angled) / len(angled) < centre      # KiCad's y grows downward

    first, second = hands
    if off_zero(modal(first) - modal(second)) < 175:
        return hands
    up = thumbs_above(first), thumbs_above(second)
    if None in up or up[0] == up[1]:
        return hands

    def turned(hand):
        cx = sum(x for x, _, _ in hand) / len(hand)
        cy = sum(y for _, y, _ in hand) / len(hand)
        return [(2 * cx - x, 2 * cy - y, (r - 180) % 360) for x, y, r in hand]

    return (turned(first), second) if up[0] else (first, turned(second))


def upright(hand):
    """One hand, rotated so its own modal switch angle is level and moved to the origin.

    THE PANEL IS NOT THE KEYBOARD, and this is what makes that recoverable rather than
    fatal. A `.kicad_pcb` is a fabrication drawing: `lily58` turns each hand a quarter
    turn to fit the panel, `elephant42` puts its right hand below the left and upside
    down. Refusing those boards -- which is what this file did for a while -- threw away
    the splay and stagger that are the whole reason for reading a board at all.

    A hand's own modal angle is its sense of "level", exactly as for a single-hand board,
    so undoing it per HAND undoes the panel. That is the step the earlier whole-board
    counter-rotation could not reach: turning the pair leaves each hand as it was.
    """
    base = unrotated_angle(hand)
    radians = math.radians(-base)
    cos, sin = math.cos(radians), math.sin(radians)
    turned = [(x*cos - y*sin, x*sin + y*cos, r - base) for x, y, r in hand]
    x0 = min(p[0] for p in turned)
    y0 = min(p[1] for p in turned)
    return [(x - x0, y - y0, r) for x, y, r in turned]


def thumbs_left_of_centre(hand):
    """Is this hand's thumb cluster on its left -- i.e. is it a RIGHT hand?

    The lowest three keys against the hand's own centroid. Scored against the two hands
    whose answer is known: KLOR is a right hand, Architeuthis Dux a left one.
    """
    floor = sorted(hand, key=lambda p: -p[1])[:3]
    return sum(p[0] for p in floor)/len(floor) < sum(p[0] for p in hand)/len(hand)


def row_share(cells):
    """Fraction of keys living in a row of four or more -- the raw number `row_staggered`
    thresholds at 0.6. Split out from it because `stagger_kind` needs the number itself,
    not just that one boolean call: a board can sit well below 0.6 and still be neither
    row-staggered nor ortho, and the classifier needs to tell those apart too.
    """
    ys = sorted(c[1] for c in cells)
    rows = []
    for y in ys:
        if rows and y - rows[-1][-1] <= 0.2:
            rows[-1].append(y)
        else:
            rows.append([y])
    return sum(len(r) for r in rows if len(r) >= 4) / len(cells)


def row_staggered(cells):
    """Is this a ROW-staggered board -- rows of keys offset sideways, as on a typewriter?

    The owner's rule, and it is structural: *"I think it's kinda contradicting that a
    keyboard with row staggered layout can have splay, I don't think they can be both."*
    He is right. Row stagger and splay are alternative answers to the same problem, and
    no board does both.

    A row-staggered board puts many keys on one horizontal line; a column-staggered one
    cannot, because every column sits at its own height. Measured as the share of keys
    living in rows of four or more, the two populations separate cleanly and with room to
    spare: every board he called splayed scores 54% or less (Dux 47, KLOR 43, Hillside
    54, Chouchou 50, and Buzzard and Sepia at 0), while every row-staggered board he
    corrected me on scores 80% or more (Sesame 80, Adalyn 88, Teaqueen 95).
    """
    return row_share(cells) >= 0.6


def has_splay(cells, one_hand):
    """Does a hand's finger columns fan out from one another? Yes, no, or None.

    A count of distinct angles was the first attempt and the owner rejected it: `k34`
    carries four and is not splayed. Four rules, each of which he supplied or corrected:

      * **Row-staggered boards are out**, per `row_staggered`. This is what removed the
        last five false positives -- Adalyn, Basketweave, Sesame, Teaqueen and
        `keyboar-layout` are angled row-staggered boards, not splayed ones.
      * **One hand at a time.** Nearly every false positive was really this: a board file
        holding both hands shows every angle mirrored, so `andean-condor`'s -15 x18 and
        +15 x18 looked like a fan and is two halves of one keyboard. A design whose file
        holds a single hand is read whole; anything else is cut in two first.
      * **The thumb cluster does not count**, his condition. A thumb arc is rotated on
        nearly every ergonomic board, so counting it answers "yes" everywhere. The lowest
        three keys of a hand go.
      * **Two keys make a column, one makes a stray.** An angle carried by a single key
        is a reset switch or an oddity, not a splayed column.

    Scored against 21 designs he ruled on by eye, and it agrees with all 21. That is a
    small sample for five constants, so treat the thresholds as fitted rather than
    derived -- the row-stagger veto is the one with real daylight either side of it.

    `judged` TRACKS WHETHER ANY HAND EVER HAD ENOUGH KEYS TO RULE ON, separately from the
    verdict itself -- added for `measure_splay.py`, the facet detector, which this
    function had no caller until then. Every hand can fail the `len(hand) < 4` guard on a
    small board (a 2x2 macropad has nothing left after its thumb three keys are dropped,
    if it even has any), and the loop finishing with no verdict is a DIFFERENT fact from
    "measured, and it does not splay" -- the same distinction `Recorded.Unknown` makes
    everywhere else in this codebase. Silently returning `False` was harmless while
    nothing read the return value; it would have been a fabricated "no splay" on every
    design too small to judge the moment something did.
    """
    if not cells or len(cells) < 6:
        return None
    if row_staggered(cells):
        return False
    judged = False
    for hand in ([cells] if one_hand else (two_hands(cells) or [cells])):
        hand = sorted(hand, key=lambda c: -c[1])[3:]
        if len(hand) < 4:
            continue
        judged = True
        base = unrotated_angle(hand)
        angles = collections.Counter(round(((r - base + 90) % 180) - 90, 1)
                                     for _, _, r in hand)
        columns = [a for a, n in angles.items() if n >= 2]
        if len(columns) >= 2 and max(columns) - min(columns) > 2.0:
            return True
    return False if judged else None


def _rows(cells):
    """Keys grouped top-to-bottom by y-proximity -- the same chained 0.2mm join
    `row_share` counts group sizes from, kept here because `adjacent_row_alignment`
    needs the groups themselves, not just their sizes."""
    ordered = sorted(cells, key=lambda c: c[1])
    rows = []
    for c in ordered:
        if rows and c[1] - rows[-1][-1][1] <= 0.2:
            rows[-1].append(c)
        else:
            rows.append([c])
    return rows


def adjacent_row_alignment(cells):
    """Do this board's rows sit on top of one another, or does each one slide sideways?

    `row_share` alone answers "are there rows" -- true of an ortholinear grid AND of a
    typewriter-style row-staggered board, since both put most keys on a shared
    horizontal line. It cannot tell those two apart, because it never looks at x. This
    does: for each pair of vertically adjacent rows, what share of one row's keys have a
    same-column neighbour in the row below, within 15% of a key unit (`board_unit`,
    scaled rather than a fixed millimetre count so it holds across MX and Choc pitch
    alike)? A grid answers close to 1 -- every column recurs row after row. A stagger
    answers close to 0 -- shifting each row sideways by even a quarter unit moves every
    key past its neighbour's tolerance window, which is the whole point of staggering it.

    Fitted against the 38-design sample `measure_stagger_candidates.py` drew and the
    owner ruled by eye: every ortho board scores 0.6 or above (most hit 1.0 exactly;
    `dropout`'s offset thumb row is the low end at 0.6), and every row-staggered or
    column-staggered board scores 0.09 or below. `choc-v` and `gummy-key` are the two
    boards that do not fit -- both real, local exceptions the owner named himself (one
    outer column carrying splay, two outer columns carrying their own vertical offset)
    -- and stay overrides rather than bending the rule for them.

    SPARSE ROWS ARE DROPPED BEFORE PAIRING, owner-reported on three false "row" verdicts
    (EUISOpcb, Setta21, Lily58 Lite Rev3, 2026-09-24): an encoder key or a
    differently-pitched thumb row sitting between two real matrix rows was being paired
    with each of its neighbours as if it were a row itself, and two degenerate near-zero
    matches for every one genuine match dragged a perfectly recurring grid's average
    below the threshold. Rows of four or fewer keys are dropped first, UNLESS THAT WOULD
    LEAVE FEWER THAN TWO ROWS TO COMPARE -- `3x3macropad`'s three rows of three are all
    the board has, and comparing nothing would silently `Unknown` a design `has_splay`'s
    own `len(cells) < 6` guard already lets through.
    """
    all_rows = _rows(cells)
    substantial = [r for r in all_rows if len(r) >= 4]
    rows = substantial if len(substantial) >= 2 else all_rows
    if len(rows) < 2:
        return None
    xy = [(c[0], c[1]) for c in cells]
    unit = board_unit(xy) or 19.05
    tolerance = 0.15 * unit
    shares = []
    for above, below in zip(rows, rows[1:]):
        xs_above = sorted(c[0] for c in above)
        xs_below = sorted(c[0] for c in below)
        claimed = set()
        matched = 0
        for x in xs_above:
            for j, y in enumerate(xs_below):
                if j not in claimed and abs(x - y) <= tolerance:
                    claimed.add(j)
                    matched += 1
                    break
        shares.append(matched / min(len(xs_above), len(xs_below)))
    return sum(shares) / len(shares)


def stagger_kind(cells):
    """Ortholinear, row-staggered or column-staggered -- or `None` if too few keys to say.

    Two signals, checked in the order that resolves the actual ambiguity: `has_splay`'s
    own veto only separates "rows exist" from not, and an ortholinear grid has rows
    exactly as much as a typewriter-style one does. `adjacent_row_alignment` is the
    signal that tells them apart (see its own docstring for the fitted margin), so it is
    checked first. Only once a board fails that -- its rows, if it has any, do not stack
    -- does `row_share` decide row-stagger from column-stagger, at a stricter 0.8 than
    `row_staggered`'s own 0.6: the highest column-staggered board in the fitting sample
    (`korris`, sharing rows for 64% of its keys) sits well under every row-staggered one
    (94% or above), with the gap between them wide enough to raise the bar and still
    clear both sides.
    """
    if not cells or len(cells) < 6:
        return None
    alignment = adjacent_row_alignment(cells)
    if alignment is not None and alignment >= 0.5:
        return "ortho"
    return "row" if row_share(cells) >= 0.8 else "column"


def unit_box(points):
    """Points scaled into a unit square, so two layouts can be compared for shape."""
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    w = (max(xs) - min(xs)) or 1.0
    h = (max(ys) - min(ys)) or 1.0
    return [((x - min(xs))/w, (y - min(ys))/h) for x, y in points]


def fits_reference(candidate, reference):
    """How badly `candidate` matches the shape of `reference`. Lower is better."""
    a = unit_box(candidate)
    return sum(min((bx - ax)**2 + (by - ay)**2 for ax, ay in a)
               for bx, by in unit_box(reference)) / len(reference)


def pcb_geometry(points, declared, split=False, reference=None, encoders=None):
    """Key rectangles in key units, taken from where the switches physically sit.

    Forgejo #31. A QMK `LAYOUT` is a matrix map, so a column-staggered or splayed board
    can arrive as a perfect grid -- KLOR states `y` in {0,1,2,3} and is neither. The
    `.kicad_pcb` places every switch at `(at x y)` in millimetres, and that is the truth
    the firmware is approximating.

    TAKEN ONLY WHERE NOTHING IS LOST BY IT, which is the whole of the precedence rule
    and the reason this is not simply "the board always wins":

      * **Every key is 1u in the firmware's own layout.** A PCB records where a switch
        is, never how wide its keycap is, so drawing from it turns a 6.25u spacebar into
        a 1u square. Where the firmware says the design has a wide key, the firmware
        keeps the job. Ergonomic splits -- exactly the boards that get flattened -- are
        all-1u, so the guard costs nothing where it matters.
      * **The board's switch count is what the design publishes, or half it.** An exact
        match is strong evidence this geometry is this keyboard rather than a sibling or
        a fragment. Same `placed` vs `placed * 2` rule `build_seed.py` uses.

    THE MIRROR IS THE ONE SYNTHESIS. A split's board file often holds one hand -- KLOR
    places 21 switches for a 42-key keyboard -- and drawing 21 shows half a keyboard,
    which is worse than a grid. So a half-count board is reflected about its own right
    edge with a one-key gap. Ticket 41 preferred no synthesis because a QMK split already
    carries the gap in its coordinates; a board file does not, and half a keyboard is not
    an option.

    ENCODERS RIDE ALONG, Forgejo #49. `encoders` is the board's own
    `measure_key_positions.encoder_placements` for this file, or None where the scan never
    looked. Each placement is moved by exactly the steps that move the keys -- the hand
    re-spacing, the mirror, the origin -- and by nothing of its own, so a knob lands where
    the board puts it relative to its neighbours. It never enters the unit, the base
    angle, the hand split or any count: those are measurements of the SWITCHES.
    """
    unit = board_unit([(p[0], p[1]) for p in points])
    if not unit:
        return None
    # `(x, y, kind)` in the board's millimetres, kind "only" or "or_key".
    extras = [(x, y, kind) for kind in ("only", "or_key")
              for x, y in ((encoders or {}).get(kind) or [])]

    # A BOTH-HANDS BOARD IS CHECKED, NOT REBUILT. A `.kicad_pcb` is a fabrication
    # drawing and may nest the halves however the panel packs best, so the arrangement
    # in the file is not always the arrangement in your hands.
    #
    # I TRIED TO RECOVER THOSE AND IT DOES NOT WORK. Splitting the file into two hands
    # and straightening each on its own modal angle produces something with a plausible
    # aspect ratio and scrambled keys -- `elephant42` came out at a respectable 2.28 and
    # was nonsense, `lily58` at 2.67 with two halves that did not match each other.
    # Aspect ratio is not evidence; looking at it is, and it said no.
    #
    # So the file has to already BE the keyboard: it divides on a real gap into two
    # hands of near-equal size, each already upright on its own modal angle, and the two
    # are opposite hands. Anything else keeps the firmware's flat schematic, which is a
    # worse picture but not a wrong one.
    if split and len(points) in declared:
        hands = two_hands(points)
        if not hands:
            return None
        hands = undo_half_turn(hands)
        if any(abs((unrotated_angle(h) + 90) % 180 - 90) > 5.0 for h in hands):
            return None
        if thumbs_left_of_centre(hands[0]) == thumbs_left_of_centre(hands[1]):
            return None
        # ONE GAP FOR EVERY SPLIT, wherever the geometry came from. A mirrored board gets
        # a one-key gap because we place the second hand; a both-hands file got whatever
        # the panel happened to use, so `splitreus` came out with its halves touching
        # while `swoop-mx` had clear air. Owner-reported. Re-spacing is a translation and
        # changes nothing about either hand.
        #
        # WHICH HAND IS THIS? -- the SAME QUESTION THE MIRROR-SYNTHESIS BRANCH BELOW
        # ALREADY ASKS, and this branch was answering it with the wrong instrument.
        # `thumbs_left_of_centre` was computed two lines up ONLY to confirm the two
        # clusters are opposite hands, then thrown away -- placement fell back to
        # "whichever cluster starts at the smaller x", which is a fact about how the
        # designer happened to lay out the fabrication panel, not about which hand is
        # which. Owner-reported on `sweepbling-lp` and `3x5-2-dilemma`: thumb clusters
        # pointing OUTWARD, at the outer column, because the file places the RIGHT
        # hand's geometry at the smaller x -- confirmed directly, `sweepbling-lp`'s
        # min-x cluster answers `thumbs_left_of_centre() == True`, which its own
        # docstring states means "is a RIGHT hand". The x-ordering heuristic drew it on
        # the image's left anyway. A left hand's thumb sits toward ITS OWN larger x
        # (reaching right, toward centre); a right hand's toward its own smaller x
        # (reaching left, toward centre) -- so `thumbs_left_of_centre` names the
        # correct side directly, and does not need x-position as a proxy for it at all.
        first, second = hands
        if thumbs_left_of_centre(first):
            first, second = second, first
        shift = (max(p[0] for p in first) + 2 * unit) - min(p[0] for p in second)
        # A PANEL THAT STACKS THE HANDS (the Dilemmas) says nothing about their heights:
        # its vertical offset is fibreglass layout. Hands that share no rows are set
        # level by their tops; hands laid side by side keep the file's own offset.
        lift = 0.0
        if (max(p[1] for p in first) < min(p[1] for p in second)
                or max(p[1] for p in second) < min(p[1] for p in first)):
            lift = min(p[1] for p in first) - min(p[1] for p in second)
        points = first + [(x + shift, y + lift, r) for x, y, r in second]

        # AN ENCODER MOVES WITH ITS OWN HAND -- the hand holding the switch nearest it.
        def in_second(x, y):
            nearest = min(first + second, key=lambda p: (p[0] - x) ** 2 + (p[1] - y) ** 2)
            return any(p is nearest for p in second)
        extras = [(x + shift if in_second(x, y) else x, y, kind) for x, y, kind in extras]
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    x0, y0 = min(xs), min(ys)

    # ROTATION IS RELATIVE TO THE BOARD'S OWN MODAL ANGLE. A footprint placed at a
    # uniform 180 degrees is not a rotated key -- it is how that library orients its
    # part, and KLOR places 10 of 21 switches at exactly 180. The splay is the DEVIATION,
    # so the mode becomes zero and the rest are measured against it. Folded to
    # (-180, 180] so a board whose mode sits near the wrap does not read as 350 degrees
    # of splay.
    base = unrotated_angle(points) + column_calibration(points, unit)


    def turn(r):
        """The key's splay, in the DRAWING's convention, not the board's.

        NEGATED, and the sign is the whole of it. **KiCad measures rotation
        counter-clockwise; SVG `rotate()` measures it clockwise.** Both put y downward,
        so nothing else needs flipping and the mismatch hides well: the first version
        emitted the board's own number and the two conventions agreed on the digits while
        meaning opposite directions. Owner-reported -- "the outer 3 columns are rotated
        the wrong way".

        Checkable against the board rather than argued about, which is how it was found.
        KLOR's outer column runs (202.70, 84.52) -> (196.08, 122.09): going DOWN it moves
        LEFT, and a downward line leans left under a POSITIVE SVG rotation. The board
        states 170 against a 180 base, i.e. -10. So the drawing wants +10 and the file
        says -10, for every splayed column on the board.
        """
        # Folded to (-90, 90] as well, because a RECTANGLE IS 180-DEGREE SYMMETRIC: a key
        # placed half a turn from the board's modal angle draws identically to one at
        # zero, and emitting 180 is a number that means "no rotation" while looking like
        # a lot of it. Every board-drawn key is 1u square, so nothing is lost.
        return round(-(((r - base + 90) % 180) - 90), 1)

    # A PCB places a switch at its CENTRE; a rect is drawn from its corner.
    cells = [((x - x0)/unit - 0.5, (y - y0)/unit - 0.5, turn(r)) for x, y, r in points]
    # An encoder as the 1u cell it occupies, so every step below moves it as it moves a key.
    extra_cells = [((x - x0)/unit - 0.5, (y - y0)/unit - 0.5, kind) for x, y, kind in extras]

    if len(points) * 2 in declared:
        # WHICH HAND IS THIS? Owner-reported: the mirrored halves came out swapped. On an
        # ergonomic split the thumb cluster sits at the INNER edge, so the hand's lowest
        # keys tell you which side it belongs on -- thumbs left of centre is a RIGHT hand
        # and it goes on the right, thumbs right of centre is a LEFT hand. KLOR's board is
        # a right hand (its thumbs sit at x 129.7 against a centroid of 156.4), and it was
        # being drawn on the left.
        #
        # Measured on the lowest QUARTILE rather than the single lowest key, so one
        # outlying thumb or a reset switch cannot decide a whole keyboard's handedness.
        # THE FIRMWARE LAYOUT DECIDES WHERE THE HAND GOES, when there is one. A board
        # file holding one hand cannot say which hand it is, and every geometric proxy
        # for it is a guess about thumb clusters. The firmware layout is not a guess: it
        # is an independent statement of the whole keyboard's arrangement, and though it
        # is a flat schematic its LEFT-RIGHT shape is exactly the thing in question. So
        # build the pair both ways and keep whichever matches it.
        #
        # Owner-reported on `hillside52`, which the thumb rule got wrong by 0.22 of a key
        # on a board seven keys wide -- noise deciding a keyboard. Its lowest three keys
        # sit at x 7.0, 0.5 and 1.6, spanning both edges, because Hillside drops its outer
        # column as low as its thumbs. The reference separates the two arrangements by
        # more than threefold, and agrees with every hand already verified by eye.
        if reference and len(reference) == len(cells) * 2:
            lo_x = min(c[0] for c in cells)
            hi_x = max(c[0] for c in cells)
            flip = [(hi_x + lo_x - x, y) for x, y, _ in cells]
            step = hi_x - lo_x + 2
            on_left = [(x, y) for x, y, _ in cells] + [(x + step, y) for x, y in flip]
            on_right = flip + [(x + step, y) for x, y, _ in cells]
            this_hand_is_right = fits_reference(on_right, reference) < \
                                 fits_reference(on_left, reference)
        else:
            this_hand_is_right = _thumbs_say_right_hand(cells)


        # THE SIDE IS THE X COORDINATE, NOT THE LIST ORDER. The first version of this
        # decided handedness correctly and then "moved" the hand by reordering the list,
        # which changes nothing a renderer can see -- the halves stayed swapped and the
        # owner reported them swapped twice.
        #
        # BOTH BRANCHES SPACE THE HANDS IDENTICALLY, which the version after that did not:
        # the left-hand branch left a one-key gap and the right-hand branch left none, so
        # `splitreus` (a right hand) had its halves touching while `swoop-mx` (a left one)
        # did not. Owner-reported, and it was pure arithmetic -- two expressions written
        # separately instead of one reflection and one shift.
        #
        # The angle mirrors with the hand: a splayed left half leans the opposite way to
        # its twin, so `r` negates on the reflected copy.
        lo = min(c[0] for c in cells)
        hi = max(c[0] for c in cells)
        reflected = [(hi + lo - x, y, -r) for x, y, r in cells]
        mirrored = [(hi + lo - x, y, kind) for x, y, kind in extra_cells]
        shift = hi - lo + 2                       # a key is 1 wide, so this is 1 clear
        if this_hand_is_right:
            cells = reflected + [(x + shift, y, r) for x, y, r in cells]
            extra_cells = mirrored + [(x + shift, y, k) for x, y, k in extra_cells]
        else:
            cells = cells + [(x + shift, y, r) for x, y, r in reflected]
            extra_cells = extra_cells + [(x + shift, y, k) for x, y, k in mirrored]

    # The origin covers the knobs as well as the keys, so one outside every key's reach
    # cannot land at a negative coordinate and be cut off by the drawing's own frame.
    cx = min(c[0] for c in cells + extra_cells)
    cy = min(c[1] for c in cells + extra_cells)
    # Five elements, not four. Kotlin destructures `(x, y, w, h)` off a longer list
    # happily, so the renderer keeps working while it learns to read the angle.
    rects = [[round(x - cx, 2), round(y - cy, 2), 1, 1, r] for x, y, r in cells]
    # CENTRES, not corners: a knob is round, and a centre is what it is drawn from.
    knobs = {kind: [[round(x - cx + 0.5, 2), round(y - cy + 0.5, 2)]
                    for x, y, k in extra_cells if k == kind]
             for kind in ("only", "or_key")}
    reach = rects + [[x - 0.5, y - 0.5] for x, y in knobs["only"]]
    drawn = {
        "w": round(max(r[0] + 1 for r in reach), 2),
        "h": round(max(r[1] + 1 for r in reach), 2),
        "keys": rects,
        # MILLIMETRES PER KEY UNIT, backlog #47 -- the number every coordinate above was
        # divided by, so multiplying back gives the board's own millimetres in BOTH axes.
        # That is why it is this and not the design's `pitch` fact: the modal gap is the
        # smaller axis (17 on Choc's 18 x 17), and Choc's 18 mm columns already sit 1.06
        # units apart here. One measurement, so the picture and its scale cannot disagree.
        "mm": round(unit, 2),
    }
    # WRITTEN ONLY WHERE THE BOARD NAMED ONE, and absent otherwise -- which means Unknown,
    # never "no encoder": the scan reads footprint names, and its silence proves nothing
    # (see `measure_key_positions.encoder_placements`). Forgejo #49.
    #
    #   `encoders`        a knob and nothing else, NOT in `keys` -- so `keys` stays the
    #                     switch count the page prints beside the picture.
    #   `key_or_encoder`  the centre of a key IN `keys` that the board also lets you fit
    #                     a knob at. Still a key, because it holds a switch unless the
    #                     builder chooses otherwise.
    if knobs["only"]:
        drawn["encoders"] = knobs["only"]
    if knobs["or_key"]:
        drawn["key_or_encoder"] = knobs["or_key"]
    return drawn


def _normalize_hand(points):
    """One board file's own switches, in key units, at its own origin -- no gap, no side.

    The same unit/rotation treatment `pcb_geometry` gives a whole board, pulled out so
    `paired_hand_geometry` can apply it to two files independently. Not shared the other
    direction (`pcb_geometry` inlines its own copy) to avoid touching a function nothing
    exercises except by inspection.
    """
    unit = board_unit([(p[0], p[1]) for p in points])
    if not unit:
        return None
    base = unrotated_angle(points) + column_calibration(points, unit)

    def turn(r):
        return round(-(((r - base + 90) % 180) - 90), 1)

    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    x0, y0 = min(xs), min(ys)
    return [((x - x0)/unit - 0.5, (y - y0)/unit - 0.5, turn(r)) for x, y, r in points]


HAND_HINT = re.compile(r"(?:^|[/_\-])(left|right)(?:[/_\-]|\.)", re.I)


def paired_hand_geometry(files):
    """Two SEPARATE board files, each one hand, unioned -- Forgejo #43 item 2.

    `pcb_geometry`'s own docstring says "ONE BOARD FILE, NEVER A UNION": `beekeeb/piantor`
    tried concatenating two files' raw coordinates and got a 5.15 mm nearest-neighbour
    against a true 17.0 mm pitch, because a hand-authored split's two files are each drawn
    near their own local origin, not offset from each other in a shared frame --
    `reviung34-split`'s `-L` and `-R` both span x 61.9-138.1 in their own file, which is
    that exact trap.

    The fix is not to trust either file's offset at all: normalize EACH HAND
    independently (own unit, own rotation, own origin), the same way the "both hands in
    one file" branch above already re-derives the gap between hands rather than trusting
    the file's — then place them side by side with one clear key of air. Verified
    2026-09-03 on `reviung34-split` (18 + 15 keys, neither alone matching a declared
    count): nearest-neighbour comes out at 0.997 units, not 0.27 -- a real layout, not
    piantor's overlay.

    Restricted to callers that already hold exactly two files (see `main`), so this
    never becomes a combinatorial guess across a design with several board revisions.
    """
    (path1, pts1), (path2, pts2) = files
    hand1, hand2 = _normalize_hand(pts1), _normalize_hand(pts2)
    if not hand1 or not hand2:
        return None
    # LEFT BEFORE RIGHT WHERE THE FILENAME SAYS SO, purely cosmetic -- the key TOPOLOGY
    # is identical either order, only which side of the picture each hand draws on.
    m1, m2 = HAND_HINT.search(path1), HAND_HINT.search(path2)
    if m2 and m2.group(1).lower() == "left" and not (m1 and m1.group(1).lower() == "left"):
        hand1, hand2 = hand2, hand1
    lo = min(c[0] for c in hand1)
    hi = max(c[0] for c in hand1)
    shift = hi - lo + 2                        # a key is 1 wide, so this is 1 clear
    cells = hand1 + [(x + shift, y, r) for x, y, r in hand2]
    cx = min(c[0] for c in cells)
    cy = min(c[1] for c in cells)
    rects = [[round(x - cx, 2), round(y - cy, 2), 1, 1, r] for x, y, r in cells]
    drawn = {
        "w": round(max(r[0] + 1 for r in rects), 2),
        "h": round(max(r[1] + 1 for r in rects), 2),
        "keys": rects,
    }
    # Each hand was divided by ITS OWN unit, so one `mm` is only true when the two agree.
    # Where they do not, the picture is still right in key units and simply has no scale.
    units = [board_unit([(p[0], p[1]) for p in pts]) for pts in (pts1, pts2)]
    if all(units) and abs(units[0] - units[1]) <= 0.02 * max(units):
        drawn["mm"] = round(sum(units) / 2, 2)
    return drawn


EPS = 1e-6

# The lower leg of an ISO enter, which is 1.25u on every board in the corpus. Kept as a
# band rather than an equality because a keycap width is a decimal somebody typed.
ISO_ENTER_LEG = (1.2, 1.6)

# THE KEYCAP'S OWN STEP. An ISO enter is 1.5u over 1.25u, so the overhang is a quarter
# unit on every one that exists -- it is a moulded plastic dimension, not a layout choice.
ISO_ENTER_STEP = 0.25

# How wide a gap will still be read as that step. Wider than the step itself, because the
# gap is what gets MEASURED and a board is free to leave more air than the cap occupies:
# `marksard/leftover30` and `keychron/q10` leave half a unit, `kakunpc/rabbit_capture_plan`
# 0.37. All three are real ISO enters -- their layouts are named `LAYOUT_isoenter` and
# `LAYOUT_iso_90` -- so refusing them would draw a slab where a reverse-L belongs. Past
# this the strip has caught something that is not the step at all.
ISO_ENTER_MAX_GAP = 0.5


def _band(rects, key, top, bottom):
    """Every other key sharing a horizontal band with `key`, however tall those keys are."""
    return [k for k in rects
            if k is not key and k[1] < bottom - EPS and k[1] + k[3] > top + EPS]


def _left_edge(rects, key, top, bottom, limit):
    """The rightmost edge in that band at or left of `limit`, or None if the band is empty
    to the left."""
    edges = [k[0] + k[2] for k in _band(rects, key, top, bottom) if k[0] + k[2] <= limit + EPS]
    return max(edges) if edges else None


def _occupied(rects, key, x0, y0, x1, y1):
    """Does any other key intersect this rectangle of space?"""
    return any(k[0] < x1 - EPS and k[0] + k[2] > x0 + EPS
               and k[1] < y1 - EPS and k[1] + k[3] > y0 + EPS
               for k in _band(rects, key, y0, y1))


def width_error(board, firmware):
    """How much keycap width, in key units, a picture drawn with `board`'s caps gets
    wrong against `firmware`'s -- the cheapest way to pair the two lists of wide caps.

    Only caps wider than 1u are listed, and the shorter list is padded with 1u caps, so
    an encoder the firmware counts as a key costs nothing and a 1.25u cap drawn as 1u
    costs a quarter. Sorted pairing is the optimal matching for this cost in one
    dimension, so this is a minimum, not a heuristic.
    """
    a, b = sorted(board), sorted(firmware)
    pad = [1.0] * abs(len(a) - len(b))
    a, b = (pad + a, b) if len(a) < len(b) else (a, pad + b)
    return round(sum(abs(x - y) for x, y in zip(a, b)), 2)


def misfit(caps, unit):
    """How far, in millimetres summed over every cap, each one sits from touching its
    nearest neighbour in the same row -- zero on a board laid out on a keycap grid.

    A neighbour is a cap at the same angle (within a degree) whose centre is less than
    a third of a key off this cap's row, measured in the cap's own turned frame. A cap
    with no such neighbour costs nothing, so a lone key cannot decide anything.
    """
    total = 0.0
    for i, (x, y, r, w, *_rest) in enumerate(caps):
        a = math.radians(-r)
        c, s = math.cos(a), math.sin(a)
        best = None
        for j, (x2, y2, r2, w2, *_more) in enumerate(caps):
            if i == j or abs((r - r2 + 180) % 360 - 180) > 1.0:
                continue
            dx, dy = x2 - x, y2 - y
            along, across = dx * c + dy * s, -dx * s + dy * c
            if abs(across) > unit / 3:
                continue
            gap = abs(abs(along) - (w + w2) / 2 * unit)
            best = gap if best is None else min(best, gap)
        total += best or 0.0
    return total


def keycap_geometry(caps, declared, fits):
    """Key rectangles AT THEIR OWN WIDTHS, from a board whose footprints name them --
    backlog #81, for the splayed boards `board_may_draw` refuses because the firmware
    has a wide key.

    `caps` is one board file from `keycap-scan.json`: every switch footprint with the
    width its name states, alternates included. `fits` are the firmware layouts this
    board could be (their count is declared, or declared plus the board's encoders --
    a QMK `LAYOUT` counts an encoder's push as a key, which is how `redherring`'s 89
    switches read as 90).

    CHOOSING BETWEEN ALTERNATES IS THE ONLY DECISION, and the firmware makes it. A
    board offering ISO or ANSI, a split backspace or a 2.25u shift places every option;
    the firmware draws one of them. So every way of filling the board without two caps
    overlapping is tried, and the one whose widths are nearest the firmware's
    (`width_error`) wins. Refused, keeping today's picture, where:

      * **two choices tie** and `misfit` cannot separate them either -- neither is known;
      * **a cap of `CAP_THAT_LEAVES_A_HOLE` or wider is not matched exactly**, in
        width and height, by the firmware -- the one loss the old rule existed to
        prevent is still refused, only now checked rather than assumed;
      * **no key is turned** off a right angle -- a flat board says nothing its
        firmware cannot, the same trade `board_may_draw` weighs.

    On `redherring` the winner gets exactly one cap wrong: a 1u switch the firmware
    draws at 1.25u, at the far left of the bottom-but-one row. Its runner-up draws caps
    lock at 1.25u where the firmware says 1.75u, 0.5 wrong against 0.25.

    Returns `(error, geometry)`, so a caller holding several revisions of one board can
    take the one the firmware describes.
    """
    unit = unit_of(caps)
    split = alternates(caps, unit) if unit else None
    if not split or not fits:
        return None
    fixed, groups = split
    wide = [[k.get("w", 1) for k in layout if k.get("w", 1) > 1] for layout in fits]
    big = [sorted((k.get("w", 1), k.get("h", 1)) for k in layout
                  if max(k.get("w", 1), k.get("h", 1)) >= CAP_THAT_LEAVES_A_HOLE)
           for layout in fits]
    scored = []
    for combo in itertools.product(*groups):
        chosen = fixed + [i for way in combo for i in way]
        if len(chosen) not in declared:
            continue
        mine = sorted((caps[i][3], caps[i][4]) for i in chosen
                      if max(caps[i][3], caps[i][4]) >= CAP_THAT_LEAVES_A_HOLE)
        if mine not in big:
            continue
        error = min(width_error([caps[i][3] for i in chosen if caps[i][3] > 1], w)
                    for w in wide)
        scored.append((error, chosen))
    if not scored:
        return None
    # EQUALLY TRUE FILLS AT DIFFERENT COUNTS: the most keys, `choose()`'s own "largest"
    # rule. `reviung34`'s board places both its 34-key thumb row and its 33-key one with
    # a 2u, and each matches its own firmware layout exactly.
    scored.sort(key=lambda s: (s[0], -len(s[1])))
    tied = [s for s in scored if s[0] == scored[0][0] and len(s[1]) == len(scored[0][1])]
    if len(tied) > 1:
        # A TIE THE BOARD CAN STILL BREAK: whichever way sits its caps flush against
        # their neighbours. `adelheid` offers caps lock as `MXOnly-1.75U` and
        # `MXOnly-1.75U-FLIPPED`, the same cap with its switch moved 4.8 mm for a
        # stepped keycap. The widths are identical, so the firmware cannot choose. But
        # centred on the plain footprint the cap meets the next key edge to edge
        # (62.94 mm both sides), and centred on the flipped one it leaves a 4.75 mm gap
        # no keycap set has. Still refused unless exactly one way is flushest.
        loose = [(round(misfit([caps[i] for i in s[1]], unit), 2), s) for s in tied]
        loose.sort(key=lambda t: t[0])
        if loose[1][0] == loose[0][0]:
            return None
        scored = [loose[0][1]]
    chosen = [caps[i] for i in scored[0][1]]

    points = [(c[0], c[1], c[2]) for c in chosen]
    base = unrotated_angle(points) + column_calibration(points, unit)

    def turn(r):
        # `pcb_geometry`'s own convention, negated for SVG and folded to (-90, 90] --
        # still safe for a wide cap, because a rectangle is 180-degree symmetric.
        return round(-(((r - base + 90) % 180) - 90), 1)

    if not any(turn(c[2]) % 90 for c in chosen):
        return None
    x0 = min(c[0] for c in chosen)
    y0 = min(c[1] for c in chosen)
    # A PCB places a switch at its cap's CENTRE; a rect is drawn from its corner.
    rects = [[(x - x0) / unit - w / 2, (y - y0) / unit - h / 2, w, h, turn(r), notch]
             for x, y, r, w, h, notch in chosen]
    cx = min(r[0] for r in rects)
    cy = min(r[1] for r in rects)
    keys = []
    for x, y, w, h, r, notch in rects:
        key = [round(x - cx, 2), round(y - cy, 2), w, h, r]
        if notch:
            key.append(notch)
        keys.append(key)
    return scored[0][0], {
        "w": round(max(k[0] + k[2] for k in keys), 2),
        "h": round(max(k[1] + k[3] for k in keys), 2),
        "keys": keys,
        "mm": round(unit, 2),
    }


def ruled_fill_geometry(caps, unit, hand=None):
    """Key rectangles from a person's ruled fill (`seed/board-fills.toml`), already
    checked against the board by `board_fills.load`: the footprints a builder fits, at
    the widths the ruling states, turned by the footprint's own angle.

    `keycap_geometry`'s own maths from there on, minus the choosing and minus its
    refusal of a flat board -- that refusal weighs a firmware drawing against the board,
    and here there is no firmware drawing that states this layout (tg4x's ZMK shield has
    no physical layout, and QMK's is a different key count).

    ONE HAND, MIRRORED, where the row says `hand`: the board file holds one hand of a
    split (`squiggle-lopro`'s 17 of 34), so the other is its reflection, every angle
    negated -- `pcb_geometry`'s one synthesis, for the same reason: half a keyboard is
    not a picture of it. The gap is ONE KEY OF CLEAR AIR between the two hands' outlines
    as drawn, each cap measured at its own angle. For 1u unturned keys that is exactly
    `pcb_geometry`'s `shift`; a turned 2u thumb at the inner edge reaches further across
    than a 1u square does, and spacing its centre instead would close the gap.
    """
    points = [(c[0], c[1], c[2]) for c in caps]
    base = unrotated_angle(points) + column_calibration(points, unit)

    def turn(r):
        # `+ 0.0` so an unturned key reads 0.0, not the -0.0 the negation leaves.
        return round(-(((r - base + 90) % 180) - 90), 1) + 0.0

    def half_x(c):
        a = math.radians(c[4])
        return (c[2] * abs(math.cos(a)) + c[3] * abs(math.sin(a))) / 2

    def half_y(c):
        a = math.radians(c[4])
        return (c[2] * abs(math.sin(a)) + c[3] * abs(math.cos(a))) / 2

    # CENTRES, in key units: a PCB places a switch at its cap's centre, and a turned cap
    # is turned about it.
    cells = [((x - caps[0][0]) / unit, (y - caps[0][1]) / unit, w, h, turn(r))
             for x, y, r, w, h in caps]
    if hand:
        other = [(-x, y, w, h, -r + 0.0) for x, y, w, h, r in cells]
        left, right = (cells, other) if hand == "left" else (other, cells)
        shift = (max(c[0] + half_x(c) for c in left) + 1
                 - min(c[0] - half_x(c) for c in right))
        cells = left + [(x + shift, y, w, h, r) for x, y, w, h, r in right]
    # The origin and the frame are the caps' outlines AT THEIR ANGLES, the same box the
    # renderer's `extent()` draws; for unturned keys that is the plain rectangles.
    cx = min(c[0] - half_x(c) for c in cells)
    cy = min(c[1] - half_y(c) for c in cells)
    keys = [[round(x - w / 2 - cx, 2), round(y - h / 2 - cy, 2), w, h, r]
            for x, y, w, h, r in cells]
    return {
        "w": round(max(c[0] + half_x(c) for c in cells) - cx, 2),
        "h": round(max(c[1] + half_y(c) for c in cells) - cy, 2),
        "keys": keys,
        "mm": round(unit, 2),
    }


def notch_iso_enter(rects):
    """Give each ISO enter the width of its TOP-LEFT OVERHANG, in key units.

    ISO enter is the only non-rectangular keycap anywhere in this corpus, and QMK cannot
    say so: `info.json` carries one `x/y/w/h` per key, so the reverse-L arrives as
    `[13.75, 1, 1.25, 2]` -- the lower leg, extended up over a row it does not cover.
    Drawn as that rectangle it is a plain 1.25x2 slab, which is what the owner saw.

    DETECTION IS UNAMBIGUOUS AND MEASURED, not a guess about which key is enter. Two
    facts do it:

      * **Tall AND wider than a unit.** 16 keys in the corpus are `w=1.25, h=2` and every
        one is an ISO enter. Every other tall key -- 51 at 2u, 70 at 1.5u, a handful of
        odd heights -- is `w=1`, a numpad enter or plus, and genuinely rectangular. There
        is no width to overlap on, so nothing has to be arbitrated.
      * **The board states the overhang itself.** The key left of the enter's TOP band
        stops a quarter unit further left than the enter does (13.5 against 13.75; prkl30
        12.0 against 12.25; stoutgatv2 14.75 against 15.0), while the key left of its
        BOTTOM band stops exactly at it. That difference IS the step, so the shape is
        read off the layout rather than assumed from a keycap catalogue -- which is the
        same rule as the rest of this file: draw the board, do not draw the convention.
        16 of 16 measure 0.25 on top and 0.0 below.

    So `overhang` is emitted, not a shape name, and a consumer that ignores it draws
    QMK's own rectangle -- exactly today's picture. The step sits ONE KEY UNIT down from
    the key's top, because that is where the row boundary is that makes the two legs
    different widths.

    Anything that fails a check keeps its rectangle. A board whose row above is missing,
    or already full, is one whose step this cannot measure, and inventing a quarter unit
    of keycap there would be a fabricated picture of a real board.
    """
    for key in rects:
        x, y, w, h = key[0], key[1], key[2], key[3]
        if not (abs(h - 2) < 0.01 and ISO_ENTER_LEG[0] <= w <= ISO_ENTER_LEG[1]):
            continue
        above = _left_edge(rects, key, y, y + 1, x)
        below = _left_edge(rects, key, y + 1, y + h, x)
        if above is None or below is None:
            continue
        gap = round(x - above, 2)
        if not 0 < gap <= ISO_ENTER_MAX_GAP:
            continue
        # The step is at the BOTTOM left. A board where the row below also stops short is
        # saying something else -- a gap, or a key this does not understand.
        if abs(x - below) > EPS:
            continue
        # THE GAP IS MEASURED, THE KEYCAP IS DRAWN, and they are not always the same
        # number. Where a board leaves more air beside its enter than the cap fills --
        # `leftover30` and `keychron/q10` leave half a unit against the cap's quarter --
        # drawing the whole gap would widen the tab to 1.75u, which is not a keycap
        # anybody makes. So the cap's own step is the ceiling and the rest stays air.
        #
        # A gap NARROWER than the step is drawn as measured: there the board really has
        # put the rows that close together, and a 1.5u cap would not fit.
        overhang = min(gap, ISO_ENTER_STEP)
        if _occupied(rects, key, x - overhang, y, x, y + 1):
            continue
        # Six elements. The fifth is rotation, which no firmware-drawn key has ever
        # carried, and Kotlin reads both by position -- so it is filled with the zero it
        # has always meant rather than left as a hole.
        key.extend([0] * (5 - len(key)))
        key.append(overhang)
    return rects


def geometry(keys):
    """Key rectangles in KEY UNITS, origin at the top-left of the bounding box.

    A QMK split already carries the gap between halves in its coordinates -- crkbd
    jumps x from 6 to 8 -- so splitness is legible without synthesising anything.
    """
    min_x = min(k["x"] for k in keys)
    min_y = min(k["y"] for k in keys)
    rects = [[round(k["x"] - min_x, 2), round(k["y"] - min_y, 2),
              round(k.get("w", 1), 2), round(k.get("h", 1), 2)] for k in keys]
    # The bounding box does not move: an ISO enter's overhang reaches LEFT, and it is only
    # measured where a key already sits further left than the overhang reaches.
    return {
        "w": round(max(r[0] + r[2] for r in rects), 2),
        "h": round(max(r[1] + r[3] for r in rects), 2),
        "keys": notch_iso_enter(rects),
    }


def left_hand_edge(keys):
    """The x, in key units, between a split's two hands -- or None if no clear gap divides it.

    Keys are projected onto the x axis as intervals, a turned key by the width it
    actually covers at its angle, and the widest run of empty x between them is the gap.
    It has to be real air (a quarter key or more) and leave a third of the keys on each
    side: `reviung34-split` is 18 against 15, so equal halves cannot be demanded, but a
    gap that leaves a lone column on one side is the gap between two columns, not hands.
    """
    spans = []
    for k in keys:
        x, _, w, h = k[:4]
        turn = math.radians(k[4] if len(k) > 4 else 0.0)
        half = (w * abs(math.cos(turn)) + h * abs(math.sin(turn))) / 2
        centre = x + w / 2
        spans.append((centre - half, centre + half, centre))
    spans.sort()
    widest, at, reach = 0.0, None, spans[0][1]
    for start, end, _ in spans[1:]:
        if start - reach > widest:
            widest, at = start - reach, (start + reach) / 2
        reach = max(reach, end)
    if at is None or widest < 0.25:
        return None
    left = sum(1 for _, _, centre in spans if centre < at)
    if min(left, len(spans) - left) < len(spans) / 3:
        return None
    return round(at, 2)


def keycap_reading(design, declared, keycap_scan, zmk_root, root, panel_checked):
    """The design's board at its own keycap widths (`keycap_geometry`), with the firmware
    choosing between alternates, or None. The shared body of both callers below.

    `panel_checked` says whether the position path has already accepted this board file
    as the keyboard in place. If it has not, a split is drawn only where a cap of
    `CAP_THAT_LEAVES_A_HOLE` or wider vouches for it (see the comment at the call). If it
    has, that guard has nothing left to catch: `sweeq-mx`'s nested panel never gets this
    far, because `pcb_geometry` refused it first.
    """
    firmware = first_firmware(design)
    widths = {}
    if firmware.get("family") == "qmk" and firmware.get("path"):
        widths = {n: v["layout"] for n, v in merged_layouts(firmware["path"], root).items()}
    elif firmware.get("family") == "zmk" and firmware.get("shield"):
        shield_dir = zmk_root/"app"/"boards"/"shields"/firmware["shield"]
        if shield_dir.is_dir():
            widths = {n: [{"w": k[2], "h": k[3]} for k in geo["keys"]]
                      for n, geo in zmk_physical_layouts(shield_dir, zmk_root).items()}
    if not widths:
        return None
    drawn = None
    for path, board in sorted(((keycap_scan.get(design["slug"]) or {})
                               .get("boards") or {}).items()):
        if not board.get("caps"):
            continue
        reach = declared | {n + board["encoders"] for n in declared}
        fits = [layout for layout in widths.values() if len(layout) in reach]
        if design["body"]["kind"] == "split" and not panel_checked:
            fits = [f for f in fits if any(max(k.get("w", 1), k.get("h", 1))
                                           >= CAP_THAT_LEAVES_A_HOLE for k in f)]
        read = keycap_geometry(board["caps"], declared, fits)
        # SEVERAL REVISIONS, THE ONE THE FIRMWARE DESCRIBES. `redherring` ships three
        # board files; the hotswap one draws a 1.25u cap at 1u and the solenoid one
        # matches the firmware exactly, so sorted order alone would have drawn the worse
        # of two true pictures. Ties keep sorted order -- both are revisions of this
        # design, and either picture is true.
        if read and (drawn is None or read[0] < best):
            best, drawn = read
    return drawn


def breakoff_geometry(drawn, drops):
    """A board's silhouette with break-off parts removed, per hand. See `drops` in
    seed/layout_names.toml. The hands are split at the drawing's midline, which is
    right for a split drawn as two hands side by side (KLOR); the remaining keys are
    moved back to the origin so the picture is not offset by the gap."""
    keys = [list(k) for k in drawn["keys"]]
    mid = drawn["w"] / 2
    hands = [[k for k in keys if k[0] + k[2] / 2 < mid], [k for k in keys if k[0] + k[2] / 2 >= mid]]
    kept = []
    for side, hand in enumerate(hands):
        outward = (lambda k: -k[0]) if side == 0 else (lambda k: k[0])
        for drop in drops:
            if drop == "outer_column":
                # Measured across the column's own tilt, not straight sideways: a splayed
                # column slants, and the Hillside 52's four-key outer column drifts 0.52u
                # inward from top to bottom, past a 0.5u reach measured along x.
                outer = max(hand, key=outward)
                turn = math.radians(outer[4] if len(outer) > 4 else 0)
                sign = -1 if side == 0 else 1
                across = lambda k: sign * ((k[0] + k[2] / 2) * math.cos(turn)
                                           + (k[1] + k[3] / 2) * math.sin(turn))
                edge = max(across(k) for k in hand)
                hand = [k for k in hand if edge - across(k) > 0.5]
            elif drop == "outer_thumb":
                lowest = max(hand, key=lambda k: k[1])
                hand = [k for k in hand if k is not lowest]
            else:
                raise SystemExit(f"layout_names.toml: unknown drop '{drop}'")
        kept += hand
    x0 = min(k[0] for k in kept)
    y0 = min(k[1] for k in kept)
    moved = [[round(k[0] - x0, 2), round(k[1] - y0, 2)] + k[2:] for k in kept]
    out = {"w": round(max(k[0] + k[2] for k in moved), 2),
           "h": round(max(k[1] + k[3] for k in moved), 2),
           "keys": moved}
    # THE ENCODER MARKS COME ALONG, moved as the keys are. An encoder goes with the part
    # it sits beside: kept only if the key nearest it was kept, so snapping off a column
    # that carried one takes the mark with it. Owner-reported 2026-09-28: Corne v4's
    # Mini lost its four inner switch-or-encoder dots.
    if drawn.get("encoders"):
        centre = lambda k: (k[0] + k[2] / 2, k[1] + k[3] / 2)
        survived = {id(k) for k in kept}
        marks = []
        for x, y in drawn["encoders"]:
            nearest = min(keys, key=lambda k: math.dist(centre(k), (x, y)))
            if id(nearest) in survived:
                marks.append([round(x - x0, 2), round(y - y0, 2)])
        if marks:
            out["encoders"] = marks
    return out


def first_firmware(design):
    """The design's first firmware record, or `{}` for one that publishes none.

    Six designs publish with no firmware since it left the gate (2026-10-02): cypher, the
    grooveboards, pancake-v2, unicorn-f072. An empty record reads as "no QMK record",
    which every caller already handles by drawing from the board or skipping, and the
    nightly run crashed here on 2026-10-04 before this existed.
    """
    return (design.get("firmware") or [{}])[0]


def firmware_reference(design, declared, root, index, centres=False):
    """The firmware layout's key corners `[(x, y), ...]` at a declared count (or their
    centres), or None -- used ONLY to say which hand a one-hand board file is. It is a
    flat schematic and never draws anything."""
    fw = first_firmware(design)
    record = fw.get("path") or borrow(fw.get("shield") or design["slug"], max(declared), index)
    layouts = merged_layouts(record, root) if record else {}
    name, _ = choose(layouts, declared) if layouts else (None, None)
    if not name:
        return None
    return [(k["x"] + (k.get("w", 1) / 2 if centres else 0),
             k["y"] + (k.get("h", 1) / 2 if centres else 0)) for k in layouts[name]["layout"]]


def key_centres(drawn):
    """Each key's centre, for comparing a drawing's shape with `fits_reference`."""
    return [(k[0] + k[2] / 2, k[1] + k[3] / 2) for k in drawn["keys"]]


def mirrored(drawing):
    """One silhouette flipped left to right: each key's x from the other edge, its angle
    negated. Refuses a drawing with encoders, whose anchor this has not needed yet."""
    if drawing.get("encoders") or drawing.get("key_or_encoder"):
        raise SystemExit("silhouette-mirrors.toml: mirroring encoders is not written yet")
    width = drawing["w"]
    keys = []
    for key in drawing["keys"]:
        x, y, w, h = key[:4]
        flipped = [round(width - x - w, 4), y, w, h]
        if len(key) > 4:
            flipped.append(-key[4] + 0.0)
        keys.append(flipped)
    return {**drawing, "keys": keys}


def apply_mirrors(out, multi, path):
    """Flip the silhouettes a person has ruled mirrored, in place; the slugs flipped.

    A REVERSIBLE BOARD IS BUILT EITHER FACE UP (owner, Forgejo #131, Eternal Keypad: "the
    svg layout silhouette is mirrored"). Its KiCad file draws one face, and nothing in the
    file says which face the usual build shows, so a person rules it, with the photo or
    README that settles it as the note.
    """
    if not path.exists():
        return []
    done = []
    for entry in tomllib.loads(path.read_text()).get("entry", []):
        slug = entry["slug"]
        if slug not in out:
            raise SystemExit(f"silhouette-mirrors.toml: {slug} has no silhouette to mirror")
        out[slug] = mirrored(out[slug])
        if slug in multi:
            multi[slug] = {k: mirrored(v) for k, v in multi[slug].items()}
        done.append(slug)
    return done


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--designs", default="src/main/resources/seed/designs.json")
    ap.add_argument("--out", default="src/main/resources/seed/silhouettes.json")
    ap.add_argument("--layouts-out", default="src/main/resources/seed/layout_silhouettes.json")
    ap.add_argument("--qmk", default=os.path.expanduser("~/code/qmk_firmware"))
    ap.add_argument("--zmk", default=os.path.expanduser("~/code/zmk"))
    ap.add_argument("--positions", default="data/position-scan.json")
    ap.add_argument("--trees", default="data/design-trees.json")
    ap.add_argument("--keycaps", default="data/keycap-scan.json")
    args = ap.parse_args()

    # A DESIGN'S RECORD IS READ FROM ITS OWN TREE, backlog #64. `designs.json` says
    # `family = "qmk"` for a record hosted in an author's unmerged fork -- correctly, a
    # QMK fork still runs QMK -- so the family alone cannot say WHICH checkout holds it,
    # and asking upstream drew the dashed placeholder for the Cheapino with the honest
    # but useless reason "no layout in the QMK tree". `build_seed.py` writes the answer
    # per slug beside `design-boards.json`; missing file means every design reads
    # upstream, which is what this script did before and is right for all but one.
    reg = tree_registry.load(qmk=args.qmk)
    zmk_root = pathlib.Path(args.zmk)
    upstream = reg.root("qmk")
    trees_path = REPO/args.trees
    design_trees = json.loads(trees_path.read_text()) if trees_path.exists() else {}

    def root_of(design):
        """The checkout holding this design's first firmware record."""
        named = design_trees.get(design["slug"]) or []
        tree = named[0] if named else None
        return reg.root(tree) if tree in reg and reg.reader(tree) == "qmk" else upstream

    designs = json.loads((REPO/args.designs).read_text())

    # UPSTREAM ONLY, deliberately. This index is what `borrow` searches for a SUBSTITUTE
    # board when a design's own firmware carries no QMK layout, so it must hold one
    # entry per keyboard. A QMK fork is a whole second copy of upstream at whatever
    # vintage it branched -- indexing `tompi/vial-qmk` would add three thousand stale
    # `crkbd`s and `lily58`s for `borrow` to pick from.
    index = qmk_index(upstream)
    out, reasons, skipped = {}, {"largest": 0, "smallest (modular)": 0}, []
    borrowed = []
    # Backlog #23's "easy case": a design offering several DECLARED layouts (a
    # break-off variant, an author's optional column) whose OWN firmware states more
    # than one of them as a complete, separate key-position list -- crkbd's own
    # `LAYOUT_split_3x5_3` (36) and `LAYOUT_split_3x6_3` (42), not one inferred from the
    # other. `choose()` below still picks exactly ONE of these for `out` -- the
    # headline picture every existing caller and test already expects, UNCHANGED -- so
    # this is purely additive: every count `choose()` did NOT pick, drawn anyway,
    # written to its own file, and read by nobody until a template asks for it.
    multi = {}
    positions = {}
    pos_path = REPO/args.positions
    if pos_path.exists():
        positions = json.loads(pos_path.read_text())
    from_board = 0
    panel_rejects = []
    keycap_path = REPO/args.keycaps
    keycap_scan = json.loads(keycap_path.read_text()) if keycap_path.exists() else {}
    from_keycaps = []
    encoder_drops = []
    # A PERSON'S RULED FILL FIRST, where one exists: see seed/board-fills.toml. Checked
    # against the board as it loads, and tried before every other path so that a later
    # scan change cannot quietly draw something else in its place.
    fills = board_fills.load(designs, keycap_scan, unit_of=unit_of)
    from_fills = []

    for design in designs:
        declared = {l["keys"] for l in design["layouts"]}
        fill = fills.get(design["slug"])
        if fill:
            drawn = ruled_fill_geometry(fill["caps"], fill["unit"], fill["hand"])
            if fill["hand"]:
                # THE RULED HAND IS CHECKED, the way `pcb_geometry` decides one: against
                # the firmware's own left-right arrangement, where the record states the
                # whole keyboard. A ruling the firmware contradicts stops the build.
                reference = firmware_reference(design, declared, root_of(design), index,
                                               centres=True)
                if reference and len(reference) == len(drawn["keys"]):
                    swapped = ruled_fill_geometry(
                        fill["caps"], fill["unit"],
                        "right" if fill["hand"] == "left" else "left")
                    ruled_fit = fits_reference(key_centres(drawn), reference)
                    other_fit = fits_reference(key_centres(swapped), reference)
                    if ruled_fit >= other_fit:
                        raise SystemExit(
                            f"seed/board-fills.toml: {design['slug']}: hand = "
                            f"{fill['hand']!r}, but the firmware's layout fits the other "
                            f"hand better ({other_fit:.4f} against {ruled_fit:.4f})")
            drawn["from_board"] = keycap_scan[design["slug"]]["repo"]
            out[design["slug"]] = drawn
            from_board += 1
            from_fills.append(design["slug"])
            extra = board_drawn_extra_layouts(design, declared, root_of(design)) or {}
            extra.update(zmk_extra_layouts(design, declared, zmk_root))
            # A RULED ALTERNATE is the same board filled another way, so it is drawn the
            # same way as the fill and owns its layout's picture outright.
            # Its fill's own count then takes the fill's drawing too: every layout here
            # is one board filled differently, and a firmware grid beside them would
            # swap the board's own picture for a schematic in the headline row.
            for n, caps in fill["alternates"].items():
                other = ruled_fill_geometry(caps, fill["unit"], fill["hand"])
                other["from_board"] = drawn["from_board"]
                extra[str(n)] = other
            if fill["alternates"]:
                extra[str(len(drawn["keys"]))] = drawn
            if extra:
                multi[design["slug"]] = extra
            continue
        # THE BOARD FIRST, where taking it loses nothing -- see `pcb_geometry`.
        entry = positions.get(design["slug"]) or {}
        # ONE BOARD FILE, NEVER A UNION. A split shipping `pcb/left/` and `pcb/right/`
        # draws each hand in its own coordinate space, both near the origin, so unioning
        # lays one on top of the other -- `beekeeb/piantor` came out with a 5.15 mm
        # nearest-neighbour against a true 17.0 pitch. Prefer the file that holds the
        # whole keyboard; failing that, the one holding a hand.
        boards = {k: [tuple(q) for q in v] for k, v in (entry.get("boards") or {}).items()}
        whole = [(k, v) for k, v in boards.items() if len(v) in declared]
        hand = [(k, v) for k, v in boards.items() if len(v) * 2 in declared]
        pts_path, pts = (max(whole, key=lambda kv: len(kv[1])) if whole else
                         (max(hand, key=lambda kv: len(kv[1])) if hand else (None, [])))
        paired = None
        # TWO FILES, NEITHER ALONE. `reviung34-split` ships `-L` (18) and `-R` (15) --
        # 18*2 and 15*2 both miss the declared 33, but 18+15 hits it. Guarded to exactly
        # two files so this cannot become a combinatorial guess across several revisions.
        if not pts and len(boards) == 2:
            items = list(boards.items())
            if sum(len(v) for _, v in items) in declared:
                paired = items
        # Forgejo #43 item 1: this floor used to read `>= 6`, attributed in review to
        # "made the eight real rather than a threshold artefact" -- that comment is
        # module-level, about preferring board over firmware at all, not about this
        # number. There was no reason on record for 6 specifically. `board_unit` already
        # refuses (returns `None`) wherever there are too few points to trust a modal
        # gap, which is the real guard; verified directly on `milk` (2 pts -> a correct
        # 1x2), `anavi-arrows` (4 pts -> the arrow-cluster shape its own name promises)
        # and `pix` (4 pts -> a correct 1x4) before trusting it.
        if paired:
            drawn = paired_hand_geometry(paired)
            if drawn and board_may_draw(design, root_of(design), declared,
                                        [v for _, v in paired]):
                drawn["from_board"] = entry.get("repo")
                out[design["slug"]] = drawn
                from_board += 1
                extra = board_drawn_extra_layouts(design, declared, root_of(design)) or {}
                extra.update(zmk_extra_layouts(design, declared, zmk_root))
                if extra:
                    multi[design["slug"]] = extra
                continue
        elif pts:
            is_split = design["body"]["kind"] == "split"
            # The firmware's own layout, used ONLY to say which hand a one-hand board
            # file is. It is a flat schematic and never draws anything here.
            reference = firmware_reference(design, declared, root_of(design), index)
            drawn = pcb_geometry(pts, declared, split=is_split, reference=reference,
                                 encoders=(entry.get("encoders") or {}).get(pts_path))
            panelised = drawn is None and is_split and len(pts) in declared
            if drawn and board_may_draw(design, root_of(design), declared, [pts]):
                # AND AT ITS OWN CAP WIDTHS, where the same board's footprints name them.
                # `board_may_draw` lets a turned board keep its shape at the price of a
                # sub-2u cap drawn square (Atreyu's ruling). Where the keycap scan reads
                # this board, nothing needs to be paid: it has the same positions, the
                # same angles, and the widths too. Corne v4's innermost thumb is a 1.5u
                # footprint turned 66 degrees off its column, and it drew as a 1u square
                # (owner-reported 2026-09-28). The panel guard is already passed here,
                # since `pcb_geometry` accepted the file as the keyboard in place.
                wide = keycap_reading(design, declared, keycap_scan, zmk_root,
                                      root_of(design), panel_checked=True)
                if wide and any(k[2:4] != [1, 1] for k in wide["keys"]):
                    # THE ENCODER MARKS COME ALONG, in the one frame both drawings share:
                    # the left hand lands on the same corners, and the right hand moves
                    # only by the rounding in the gap between halves (Corne: 0.02u). A
                    # bigger shift means the frames are not the same, and the marks are
                    # dropped rather than placed somewhere they are not.
                    shift = wide["w"] - drawn["w"]
                    if drawn.get("encoders") and abs(shift) < 0.1 \
                            and abs(wide["h"] - drawn["h"]) < 0.1:
                        mid = drawn["w"] / 2
                        wide["encoders"] = [[round(x + (shift if x >= mid else 0), 2), y]
                                            for x, y in drawn["encoders"]]
                    elif drawn.get("encoders"):
                        encoder_drops.append(design["slug"])
                    drawn = wide
                    from_keycaps.append(design["slug"])
                drawn["from_board"] = entry.get("repo")
                out[design["slug"]] = drawn
                from_board += 1
                extra = board_drawn_extra_layouts(design, declared, root_of(design)) or {}
                extra.update(zmk_extra_layouts(design, declared, zmk_root))
                # THE BOARD'S PICTURE OWNS ITS OWN COUNT, as a ruled fill's does above,
                # WHERE THE FIRMWARE'S IS A FLAT GRID: that would show the headline
                # layout as a schematic beside the board's real shape. Corne v4's 42
                # did, once the Mini gave it a second layout (piantor's and
                # bartlesplit's 42 too). A firmware picture that turns keys already
                # has a shape, and it may have widths the board lacks: ZMK draws
                # Jorne's 44 with its two 1.5u thumbs, and the board file does not.
                if extra:
                    own = str(len(drawn["keys"]))
                    if not any(len(k) > 4 and k[4] for k in (extra.get(own) or {}).get("keys", [])):
                        extra[own] = drawn
                    multi[design["slug"]] = extra
                continue
            if panelised:
                panel_rejects.append(design["slug"])
        # THE BOARD AGAIN, AT ITS OWN KEYCAP WIDTHS, backlog #81 -- for a splayed
        # board the two paths above refused, most often because the firmware has a
        # wide key. Tried only after them, so nothing either one draws can change.
        #
        # A SPLIT IS ALLOWED ONLY WHERE A WIDE KEY VOUCHES FOR IT. `keycap_geometry`
        # accepts a fill only at a declared count, so a file holding one hand never
        # passes; a file holding both does -- in place, as `teaqueen`'s does, or nested
        # for fabrication, as `sweeq-mx`'s does. The position path's panel guard tells
        # those apart by each hand being upright, which an Alice split is not. What does
        # tell them apart here is a cap of `CAP_THAT_LEAVES_A_HOLE` or wider that the
        # firmware states and the board matches exactly: an all-1u split matches ANY
        # firmware's widths, panel or not (`sweeq-mx` drew as two hands on top of each
        # other), so it keeps whatever the position path decided.
        #
        # A ZMK SHIELD'S WIDTHS COUNT AS THE FIRMWARE'S, where it states a
        # `zmk,physical-layout`: `reviung34` has no QMK record, and its shield names the
        # 33-key variant's 2u key.
        firmware = first_firmware(design)
        drawn = keycap_reading(design, declared, keycap_scan, zmk_root, root_of(design),
                               panel_checked=False)
        if drawn:
            drawn["from_board"] = entry.get("repo") or keycap_scan[design["slug"]]["repo"]
            out[design["slug"]] = drawn
            from_board += 1
            from_keycaps.append(design["slug"])
            extra = board_drawn_extra_layouts(design, declared, root_of(design)) or {}
            extra.update(zmk_extra_layouts(design, declared, zmk_root))
            if extra:
                multi[design["slug"]] = extra
            continue
        record, source = firmware.get("path"), None
        if firmware.get("family") != "qmk" or not record:
            key_count = max(l["keys"] for l in design["layouts"])
            record = borrow(firmware.get("shield") or design["slug"], key_count, index)
            if not record:
                skipped.append((design["slug"],
                                f"{firmware.get('family') or 'no firmware'}, and QMK has no matching board"))
                continue
            source = record
        # `source` is set when the record was BORROWED from upstream's index, so it
        # is upstream's record and must be read from upstream, not from this design's
        # own tree.
        layouts = merged_layouts(record, upstream if source else root_of(design))
        if not layouts:
            skipped.append((design["slug"], "no layout in the QMK tree"))
            continue
        declared = {l["keys"] for l in design["layouts"]}
        name, why = choose(layouts, declared)
        if not name:
            skipped.append((design["slug"],
                            f"no QMK layout holds {sorted(declared)} keys "
                            f"(the tree offers {sorted({len(v['layout']) for v in layouts.values()})})"))
            continue
        reasons[why] += 1
        drawn = geometry(layouts[name]["layout"])
        # A QMK unit is a KEYCAP unit, so it is a millimetre figure only where the board's
        # own measured spacing is square and standard: MX, 19.05 both ways. On Choc's
        # 18 x 17 one number cannot say both axes, and on anything else the firmware's
        # 1u is not what the board measures -- so those stay unscaled, and the overlay
        # says so rather than inventing 19.05 (backlog #47).
        if (design.get("pitch") or {}).get("kind") == "mx_19.05":
            drawn["mm"] = 19.05
        if source:
            # Recorded so the borrow is auditable rather than invisible.
            drawn["from"] = source
            borrowed.append((design["slug"], source))
        out[design["slug"]] = drawn

        # EVERY OTHER DECLARED COUNT THIS TREE ALSO STATES EXACTLY, drawn the same way.
        extra = other_layouts(layouts, declared)
        extra.update(zmk_extra_layouts(design, declared, zmk_root))
        if extra:
            multi[design["slug"]] = extra

    # BREAK-OFF LAYOUTS, backlog #23: KLOR's PCB places all 42 keys and the README draws
    # three smaller arrangements you get by snapping parts off. No firmware states them,
    # so they are cut from the board's own drawing by the `drops` a person wrote in
    # seed/layout_names.toml, and must land on the stated count exactly.
    breakoff_count = 0
    for entry in tomllib.loads((REPO/"seed/layout_names.toml").read_text()).get("entry", []):
        if not entry.get("breakoff"):
            continue
        slug, want = entry["slug"], entry["keys"]
        if slug not in out:
            raise SystemExit(f"layout_names.toml: {slug} has no silhouette to cut {want} keys from")
        drawn = breakoff_geometry(out[slug], entry.get("drops") or [])
        if len(drawn["keys"]) != want:
            raise SystemExit(f"layout_names.toml: {slug} drops {entry.get('drops')} leave "
                             f"{len(drawn['keys'])} keys, not {want}")
        # Cut from the board's own drawing, so it is in the board's units and keeps its scale.
        if "mm" in out[slug]:
            drawn["mm"] = out[slug]["mm"]
        multi.setdefault(slug, {})[str(want)] = drawn
        breakoff_count += 1
    print(f"  {breakoff_count} break-off layouts cut from their board's own drawing")

    # WHERE THE LEFT HAND ENDS, for every split that can say -- backlog #47. Comparing two
    # splits means comparing HANDS: the gap between halves is whatever the drawing chose
    # (one key of air for a board-drawn split, the author's own for a QMK one), so two
    # whole splits overlaid mostly compare that. Emitted here and not guessed in the
    # renderer, because this is where the picture was made.
    body_kind = {d["slug"]: d["body"]["kind"] for d in designs}
    hands = 0
    for slug, drawn in out.items():
        if body_kind.get(slug) == "split":
            edge = left_hand_edge(drawn["keys"])
            if edge is not None:
                drawn["split"] = edge
                hands += 1
    splits = sum(1 for slug in out if body_kind.get(slug) == "split")
    print(f"  {hands} of {splits} drawn splits divide into two hands on a clear gap")

    # EVERY OTHER LAYOUT GETS THE SAME TWO FIELDS, so a reader can compare KLOR's Konrad
    # rather than only its Polydactyl (owner-reported, 2026-09-25). A break-off already
    # took its board's `mm` above. The rest are firmware drawings -- a design's own other
    # QMK layouts, or ZMK's -- and take the one firmware rule: 19.05 on a board measured
    # MX, nothing otherwise. A firmware drawing beside a board-drawn picture is NOT given
    # the board's `mm`: its units are keycap units, not that board's modal gap.
    pitch_kind = {d["slug"]: (d.get("pitch") or {}).get("kind") for d in designs}
    scaled = 0
    for slug, layouts in multi.items():
        for drawn in layouts.values():
            if "mm" not in drawn and pitch_kind.get(slug) == "mx_19.05":
                drawn["mm"] = 19.05
            scaled += "mm" in drawn
            if body_kind.get(slug) == "split":
                edge = left_hand_edge(drawn["keys"])
                if edge is not None:
                    drawn["split"] = edge
    print(f"  {scaled} of {sum(len(v) for v in multi.values())} other layouts carry a scale")

    iso = sorted(slug for slug, drawn in out.items()
                 if any(len(k) > 5 and k[5] for k in drawn["keys"]))
    print(f"  {from_board} drawn from the board's own switch positions")
    if from_keycaps:
        print(f"  {len(from_keycaps)} of them at the keycap widths their footprints name: "
              f"{', '.join(sorted(from_keycaps))}")
    if encoder_drops:
        print(f"  {len(encoder_drops)} lost their encoder marks moving to keycap widths "
              f"(the two frames differ): {', '.join(sorted(encoder_drops))}")
    if from_fills:
        print(f"  {len(from_fills)} of them from a person's ruled fill "
              f"(seed/board-fills.toml): {', '.join(sorted(from_fills))}")
    if iso:
        print(f"  {len(iso)} carry an ISO enter, drawn as its reverse-L: {', '.join(iso)}")
    if panel_rejects:
        print(f"  {len(panel_rejects)} split boards rejected as fabrication panels: "
              f"{', '.join(sorted(panel_rejects))}")
    mirrored = apply_mirrors(out, multi, REPO/"seed/silhouette-mirrors.toml")
    if mirrored:
        print(f"  {len(mirrored)} mirrored by a person's ruling "
              f"(seed/silhouette-mirrors.toml): {', '.join(mirrored)}")

    path = REPO/args.out
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(out, sort_keys=True, separators=(",", ":")))

    multi_path = REPO/args.layouts_out
    multi_path.parent.mkdir(parents=True, exist_ok=True)
    multi_path.write_text(json.dumps(multi, sort_keys=True, separators=(",", ":")))

    print(f"{len(designs)} designs -> {len(out)} silhouettes "
          f"({100*len(out)/len(designs):.0f}%), {path.stat().st_size // 1024} KB")
    extra = sum(len(v) for v in multi.values())
    print(f"{len(multi)} designs additionally have every declared layout drawn "
          f"({extra} pictures total) -> {multi_path.relative_to(REPO)}")
    for why, n in reasons.items():
        print(f"  {n:>4}  drawn from the {why} layout")
    if borrowed:
        print(f"\n  {len(borrowed)} borrowed QMK geometry for a non-QMK design "
              f"(exact key-count match required):")
        for slug, source in borrowed:
            print(f"        {slug} <- {source}")
    if skipped:
        print(f"\nno silhouette ({len(skipped)}) -- these render the dashed placeholder:")
        for slug, why in skipped[:6]:
            print(f"  {slug}: {why}")
        if len(skipped) > 6:
            print(f"  ... and {len(skipped) - 6} more")


if __name__ == "__main__":
    main()
