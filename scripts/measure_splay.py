#!/usr/bin/env python3
"""Does a design's board fan its finger columns out from one another -- yes, no, or
nobody has measured it?

The owner's request: a `splay` facet, `Unknown`/`false`/`true`, with the thumb cluster
excluded from the question -- his words, "ignore thumb clusters for this detection,
otherwise 90% will have splay." That is exactly `build_silhouettes.has_splay`'s own
condition, already scored against 21 of his own by-eye verdicts and agreeing with all
21 (`c07e745`). It existed for eleven months with no caller; this is the caller.

READS `position-scan.json`, the same per-board `(x, y, r)` cache the silhouette
pipeline reads, and for the same reason it is trustworthy here: `measure_key_positions`
writes it from `design-boards.json`'s CONFIRMED attribution, not from an `rglob` over a
repo, so every board file under a slug genuinely belongs to that design
(`attributed_boards.py`'s own finding -- 15 of 228 designs were wrong the other way).

ONE VERDICT PER DESIGN, THOUGH A DESIGN MAY OFFER SEVERAL BOARD FILES. A reversible
split's one file holds both hands; a non-reversible one ships `_left.kicad_pcb` and
`_right.kicad_pcb` separately; a few designs carry both a current and a superseded
board that still passes attribution. So every usable file is measured and the verdicts
are combined: `True` wins if any file shows it (one splayed hand is enough to call the
keyboard splayed), `False` only if every usable file was actually judged and none did,
and anything else is `Unknown` -- never a guess averaged from a mix of yes/no/unjudged.

A FILE IS "USABLE" ONLY WHEN ITS KEY COUNT MATCHES A DECLARED LAYOUT, whole or half --
the same test `measure_flat_splay.py` applies for the same reason: a board file that
matches neither is not obviously a match to any layout this design actually publishes
(a superseded revision, a thumb-only sub-board, a panel with extra tooling holes), and
measuring it would attribute a angle to a shape the design does not claim to have.

Usage: measure_splay.py [--designs FILE] [--positions FILE] [--out FILE]
"""
import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import board_fills
import build_silhouettes as silhouettes

REPO = pathlib.Path(__file__).resolve().parent.parent


def verdict_for(design, boards):
    """`True`, `False` or `None` for one design, from its own attributed board files."""
    declared = {l["keys"] for l in design["layouts"]}
    judgements = []
    for cells in boards.values():
        if len(cells) in declared:
            judgements.append(silhouettes.has_splay(cells, one_hand=False))
        elif len(cells) * 2 in declared:
            judgements.append(silhouettes.has_splay(cells, one_hand=True))
        # Neither -- this file does not match a layout the design publishes, so it
        # contributes nothing rather than a guess (see the module docstring).

    if any(j is True for j in judgements):
        return True
    if any(j is not None for j in judgements):
        return False
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--designs", default="src/main/resources/seed/designs.json")
    ap.add_argument("--positions", default="data/position-scan.json")
    ap.add_argument("--out", default="data/splay-scan.json")
    args = ap.parse_args()

    designs = json.loads((REPO/args.designs).read_text())
    positions = json.loads((REPO/args.positions).read_text())

    # A RULED FILL REPLACES ITS BOARD FILE'S MERGED POSITIONS: see board_fills.py.
    ruled = board_fills.ruled_positions(designs)
    verdicts = {}
    for design in designs:
        slug = design["slug"]
        boards = {k: [tuple(q) for q in v]
                  for k, v in ((positions.get(slug) or {}).get("boards") or {}).items()}
        boards.update(ruled.get(slug) or {})
        if not boards:
            continue
        verdict = verdict_for(design, boards)
        if verdict is not None:
            verdicts[slug] = verdict

    yes = sum(1 for v in verdicts.values() if v)
    no = sum(1 for v in verdicts.values() if not v)
    print(f"{len(designs)} designs; {len(positions)} have board positions on file")
    print(f"  splayed              {yes:>4}   -> facet row `true`")
    print(f"  measured, not splayed {no:>4}   -> facet row `false`")
    print(f"  no verdict           {len(designs) - len(verdicts):>4}   -> NO ROW "
          f"(Unknown, never a guess)")

    out = REPO/args.out
    out.write_text(json.dumps(verdicts, indent=1, sort_keys=True))
    print(f"\n  {len(verdicts)} designs with a verdict -> {out.relative_to(REPO)} "
          f"(the owner's seed/splay-overrides.toml is applied by build_seed.py)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
