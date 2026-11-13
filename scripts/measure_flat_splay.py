#!/usr/bin/env python3
"""Which designs are drawn as a flat grid while their own board is splayed?

Backlog #81, measured 2026-09-10. The owner reported `redherring` and `adelheid` as
rendering wrong, and both turned out to be Alice-likes -- two halves splayed about the
centre -- drawn from the QMK `LAYOUT`, which is a matrix map and states no rotation. That
is Forgejo #31's failure again (KLOR drawn as an orthogonal grid) on a different family of
keyboard, so the question this file exists to answer is not "are those two wrong" but
**how many designs are in the same position**.

WHAT COUNTS AS SPLAYED, and the threshold is here rather than in `build_silhouettes`
because it is a question about the CORPUS, not about any one drawing. `board_turns` asks
whether the board turns ANY key off its own modal angle, which is the right question when
you are deciding whether the board has something to say that the firmware cannot. It is
the wrong question here: a numpad with one rotated key answers yes and is not a splayed
keyboard. So this tightens it to **at least four keys, and at least 15% of the board's
keys, turned by 5 degrees or more** -- which is what separates `arisu` (38 of 67) from
`numpad` (one key of 21). 57 designs pass the loose test; 16 pass this one.

WHY IT READS `silhouettes.json` RATHER THAN RE-DECIDING. Whether a design was drawn from
its board is recorded per slug as `from_board`, and re-deriving it here would mean a
second copy of the precedence rule that could drift from the one that actually draws. The
build's own output is the honest answer to "what did this draw".

The measurement it produced, over the 311 designs with board positions on file:

    57  splayed board (loose), not drawn from it
    16  splayed board (tightened), not drawn from it
         6 whose board count matches what the design publishes -- so the board was
           offered, and `CAP_THAT_LEAVES_A_HOLE` refused all six, the firmware claiming
           a key of 2.25u to 6.5u in each. `teaqueen` is refused twice over: it is an
           Alice-shaped SPLIT, and `pcb_geometry`'s panel guard wants each hand upright
           on its own modal angle within 5 degrees, where its two sit at 12.0 and 6.4
        10 whose board count misses, so the board was never offered at all

Usage: measure_flat_splay.py [--designs FILE] [--positions FILE] [--silhouettes FILE]
                             [--loose]
"""
import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import build_silhouettes as silhouettes

REPO = pathlib.Path(__file__).resolve().parent.parent

# At least this many keys, and at least this share of them, turned by at least this much.
# See the docstring: the point is to exclude a board with one rotated key, not to tune a
# population until it looks a certain size.
TURNED_DEGREES = 5.0
FEWEST_TURNED = 4
LEAST_SHARE = 0.15


def splay(points):
    """`(turned, total, widest angle)` for one board file, or `None` if unmeasurable.

    Measured against the board's OWN calibrated base, exactly as `pcb_geometry` measures
    it, so this asks the same question the drawing answers rather than a similar one.
    """
    unit = silhouettes.board_unit([(p[0], p[1]) for p in points])
    if not unit:
        return None
    base = (silhouettes.unrotated_angle(points)
            + silhouettes.column_calibration(points, unit))
    # FOLDED TO A QUARTER TURN, not a half. A key at 90 degrees is square to its row --
    # `aek2-usb` turns its numpad's tall keys that way and nothing else, and folding to
    # 180 counted each as turned 90, which put a flat board on the splayed list.
    off = [abs(round(((r - base + 45) % 90) - 45, 1)) for _, _, r in points]
    return sum(1 for d in off if d >= TURNED_DEGREES), len(off), max(off, default=0.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--designs", default="src/main/resources/seed/designs.json")
    ap.add_argument("--positions", default="data/position-scan.json")
    ap.add_argument("--silhouettes", default="src/main/resources/seed/silhouettes.json")
    ap.add_argument("--loose", action="store_true",
                    help="report every board that turns any key, not just splayed ones")
    args = ap.parse_args()

    designs = json.loads((REPO/args.designs).read_text())
    positions = json.loads((REPO/args.positions).read_text())
    drawn = json.loads((REPO/args.silhouettes).read_text())

    on_file, rows = 0, []
    for design in designs:
        slug = design["slug"]
        boards = {k: [tuple(q) for q in v]
                  for k, v in ((positions.get(slug) or {}).get("boards") or {}).items()}
        if not boards:
            continue
        on_file += 1
        if (drawn.get(slug) or {}).get("from_board"):
            continue

        # The same three ways `build_silhouettes` looks for a board that IS this keyboard:
        # one file holding the whole thing, one holding a hand, or two holding one each.
        declared = {l["keys"] for l in design["layouts"]}
        whole = [v for v in boards.values() if len(v) in declared]
        hand = [v for v in boards.values() if len(v)*2 in declared]
        offered = whole or hand or (
            list(boards.values()) if len(boards) == 2
            and sum(len(v) for v in boards.values()) in declared else [])

        best = max(boards.values(), key=len)
        profile = splay(best)
        if not profile:
            continue
        turned, total, widest = profile
        splayed = (turned >= FEWEST_TURNED and turned/total >= LEAST_SHARE
                   if not args.loose else turned > 0)
        if not splayed:
            continue
        rows.append((bool(offered), slug, sorted(declared),
                     sorted(len(v) for v in boards.values()), turned, total, widest))

    refused = [r for r in rows if r[0]]
    unoffered = [r for r in rows if not r[0]]
    print(f"{on_file} designs have board positions on file")
    print(f"{len(rows)} of them are not drawn from that board, and the board is "
          f"{'turned at all' if args.loose else 'splayed'}\n")
    for label, group in (("the board matched a declared count and a rule refused it",
                          refused),
                         ("the board's count missed, so it was never offered", unoffered)):
        print(f"  {len(group)} -- {label}")
        for _, slug, declared, counts, turned, total, widest in sorted(group):
            print(f"      {slug:<24} declared={declared} board={counts}  "
                  f"{turned}/{total} keys turned, widest {widest:g} degrees")
        print()


if __name__ == "__main__":
    main()
