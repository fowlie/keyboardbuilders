#!/usr/bin/env python3
"""Is a design's board ortholinear, row-staggered or column-staggered -- or has nobody
measured it?

The caller for `build_silhouettes.stagger_kind`, which `measure_stagger_candidates.py`
sampled 38 designs to fit: the owner ruled each by eye against a plot of its own key
positions, and 37 of the 38 agree with the fitted rule (see that function's docstring
for the two signals and their margins). The one disagreement, `choc-v`, is
`seed/stagger-overrides.toml`'s, which build_seed.py applies over this script's raw scan
(see measured_fields.py), exactly as `seed/splay-overrides.toml` is for the same reason -- a real, local exception the owner named himself, not grounds
to move the rule for the rest of the corpus.

READS `position-scan.json`, the same per-board `(x, y, r)` cache `measure_splay.py`
reads and for the same reason it is trustworthy here: written from `design-boards.json`'s
CONFIRMED attribution, not an `rglob` over a repo.

ONE VERDICT PER DESIGN, combined across however many board files it offers.
`stagger_kind` is not a yes/no question `measure_splay.py`'s "any hand wins" rule can
apply to, so the combination here is unanimity: every usable file has to agree, or the
verdict is `Unknown` rather than a guess at which file to trust. A design offering only
one usable file needs no agreement to reach, of course, and that is the common case.

A FILE IS "USABLE" ONLY WHEN ITS KEY COUNT MATCHES A DECLARED LAYOUT, whole or half --
the same rule `measure_splay.py` applies, for the same reason: a board file that
matches neither is not obviously a match to any layout this design actually publishes.

Usage: measure_stagger.py [--designs FILE] [--positions FILE] [--out FILE]
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
    """A stagger kind, or `None`, from a design's own attributed board files.

    A WHOLE-BOARD FILE IS SPLIT INTO ITS TWO HANDS FIRST, same as `measure_splay.py`'s
    `has_splay(cells, one_hand=False)` already does and for the same reason: a reversible
    split's one file holds both hands side by side (or stacked), and grouping keys into
    rows by raw y mixes both hands' columns into the same y-bands. Owner-reported,
    3w6/span/42/2026-09-24: each file's few y-bands that happen to hold four or more keys
    are, on a bilaterally-symmetric board, mirror-image pairs from both hands rather than
    one hand's real row -- and those pairs lined up in x by the accident of symmetry, not
    because the board is ortholinear. `two_hands` is the same split `has_splay` trusts;
    when it fails to divide a file (a genuine single-hand file, or one macro/numpad shape
    two_hands can't separate), the whole cell list is judged as one hand, unchanged from
    before. A file already sized to one hand (`len(cells) * 2 in declared`) is judged
    as-is, exactly as before -- there is nothing left to split.
    """
    declared = {l["keys"] for l in design["layouts"]}
    judgements = []
    for cells in boards.values():
        if len(cells) in declared:
            hands = silhouettes.two_hands(cells) or [cells]
        elif len(cells) * 2 in declared:
            hands = [cells]
        else:
            # Neither -- this file does not match a layout the design publishes, so it
            # contributes nothing rather than a guess (see the module docstring).
            continue
        for hand in hands:
            kind = silhouettes.stagger_kind(hand)
            if kind is not None:
                judgements.append(kind)

    if not judgements:
        return None
    if len(set(judgements)) == 1:
        return judgements[0]
    return None  # the design's own board files (or hands) disagree -- Unknown, never a guess


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--designs", default="src/main/resources/seed/designs.json")
    ap.add_argument("--positions", default="data/position-scan.json")
    ap.add_argument("--out", default="data/stagger-scan.json")
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

    counts = {}
    for v in verdicts.values():
        counts[v] = counts.get(v, 0) + 1
    print(f"{len(designs)} designs; {len(positions)} have board positions on file")
    for kind in ("ortho", "row", "column"):
        print(f"  {kind:<8}              {counts.get(kind, 0):>4}   -> facet row `{kind}`")
    print(f"  no verdict           {len(designs) - len(verdicts):>4}   -> NO ROW "
          f"(Unknown, never a guess)")

    out = REPO/args.out
    out.write_text(json.dumps(verdicts, indent=1, sort_keys=True))
    print(f"\n  {len(verdicts)} designs with a verdict -> {out.relative_to(REPO)} "
          f"(the owner's seed/stagger-overrides.toml is applied by build_seed.py)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
