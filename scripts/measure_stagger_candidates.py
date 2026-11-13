#!/usr/bin/env python3
"""Candidate designs for the owner to rule on ortho / row-stagger / column-stagger by eye.

NOT A FACET DETECTOR. `has_splay`'s `row_staggered` veto already separates "many keys
share a row" from not, fitted against 21 of the owner's own splay verdicts
(`build_silhouettes.row_staggered`, scored in `c07e745`). But that one signal cannot
carry a three-way stagger split: an ortholinear board also puts most of its keys in
rows of four or more (rows AND columns both aligned), so "shares a row" alone does not
tell ortho from row-stagger apart. The mirror-image question -- do keys also line up in
COLUMNS -- is the thing nothing in this codebase measures yet.

So rather than guess a "columns aligned" threshold the way an early `has_splay` guessed
wrong ("row-staggered boards can't be splayed" was not obvious from the geometry alone
-- the owner had to say it), this script only SCORES and SAMPLES. It reports a
continuous column-alignment share next to the already-fitted row share, and picks a
small, score-diverse set of designs -- spanning high/low on each axis, plus every
design already ruled `splay = true` (since splay and column-stagger are NOT mutually
exclusive the way splay and row-stagger are, per the owner's own rule in
`row_staggered`'s docstring, so a splayed board is exactly where a naive "not
row-staggered -> column-staggered" rule would misfire). Write the picks to
`data/stagger-candidates.json` with a blank `stagger` field for the owner
to fill in by eye, the same shape `splay-overrides.toml` takes corrections in. Only
once that ruling exists should a `columns_staggered` function and a real
`measure_stagger.py` get written -- fitted against it, not against a guess.

Usage: measure_stagger_candidates.py [--designs FILE] [--positions FILE]
                                      [--splay-scan FILE] [--out FILE] [--per-bucket N]
"""
import argparse
import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent

# Mirrors `build_silhouettes.row_staggered`'s clustering exactly (0.2mm merge gap,
# rows/columns of >=4 count), just run on the other axis too. Kept LOCAL rather than
# added to `build_silhouettes.py`: that module's functions are fitted, scored facet
# machinery, and this one isn't fitted against anything yet -- see module docstring.


def _clustered_share(values_by_key, cells):
    coords = sorted(values_by_key(c) for c in cells)
    groups = []
    for v in coords:
        if groups and v - groups[-1][-1] <= 0.2:
            groups[-1].append(v)
        else:
            groups.append([v])
    return sum(len(g) for g in groups if len(g) >= 4) / len(cells)


def row_share(cells):
    return _clustered_share(lambda c: c[1], cells)


def column_share(cells):
    return _clustered_share(lambda c: c[0], cells)


def usable_files(design, boards):
    """Same rule as `measure_splay.verdict_for`: a file counts only if its key count
    matches a layout this design actually declares, whole or half."""
    declared = {l["keys"] for l in design["layouts"]}
    for name, cells in boards.items():
        if len(cells) in declared or len(cells) * 2 in declared:
            yield name, cells


def score_design(design, boards):
    """Best (max) row/column share across this design's usable board files, or None."""
    files = [(name, cells) for name, cells in usable_files(design, boards) if len(cells) >= 6]
    if not files:
        return None
    return {
        "row_share": round(max(row_share(cells) for _, cells in files), 2),
        "column_share": round(max(column_share(cells) for _, cells in files), 2),
        "files": len(files),
    }


def bucket_of(row_share, column_share, threshold=0.6):
    """A REFERENCE LINE for sampling diversity only -- `row_staggered`'s own fitted
    cutoff, borrowed because it's the only precedent in the codebase, not because 0.6
    is known to be right on the column axis too. Do not read a bucket label as a
    verdict."""
    row_hi = row_share >= threshold
    col_hi = column_share >= threshold
    if row_hi and col_hi:
        return "ortho?"
    if row_hi and not col_hi:
        return "row-stagger?"
    if col_hi and not row_hi:
        return "column-stagger?"
    return "neither axis clearly aligned"


def spread_sample(slugs_with_scores, key, n):
    """Up to `n` designs spread across the range of `key`, not just the extremes --
    percentile picks so the owner sees near-boundary cases too, since those are where a
    fitted threshold will actually need to draw its line."""
    ordered = sorted(slugs_with_scores, key=lambda s: s[1][key])
    if len(ordered) <= n:
        return ordered
    picks, seen = [], set()
    for i in range(n):
        idx = round(i * (len(ordered) - 1) / (n - 1)) if n > 1 else 0
        if idx not in seen:
            seen.add(idx)
            picks.append(ordered[idx])
    return picks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--designs", default="src/main/resources/seed/designs.json")
    ap.add_argument("--positions", default="data/position-scan.json")
    ap.add_argument("--splay-scan", default="data/splay-scan.json")
    ap.add_argument("--out", default="data/stagger-candidates.json")
    ap.add_argument("--per-bucket", type=int, default=6)
    args = ap.parse_args()

    designs = json.loads((REPO / args.designs).read_text())
    positions = json.loads((REPO / args.positions).read_text())
    splay_scan = json.loads((REPO / args.splay_scan).read_text())

    scored = {}
    for design in designs:
        slug = design["slug"]
        boards = {k: [tuple(q) for q in v]
                  for k, v in ((positions.get(slug) or {}).get("boards") or {}).items()}
        if not boards:
            continue
        s = score_design(design, boards)
        if s is not None:
            scored[slug] = s

    buckets = {}
    for slug, s in scored.items():
        buckets.setdefault(bucket_of(s["row_share"], s["column_share"]), []).append((slug, s))

    picked = {}
    for name, items in buckets.items():
        for slug, s in spread_sample(items, "row_share", args.per_bucket):
            picked[slug] = s

    # Every already-splayed design too: this is exactly where a naive column-stagger
    # rule would misfire, per the module docstring, so the owner needs to rule on these
    # regardless of which score bucket they landed in.
    for slug, splayed in splay_scan.items():
        if splayed and slug in scored:
            picked[slug] = scored[slug]

    print(f"{len(designs)} designs; {len(scored)} scored from usable board files")
    for name, items in sorted(buckets.items()):
        print(f"  {name:<28} {len(items):>4} designs")
    print(f"  already splayed = true      {sum(1 for v in splay_scan.values() if v):>4} designs")
    print(f"\n  {len(picked)} candidates picked for by-eye ruling -> {args.out}")

    out_rows = []
    for slug in sorted(picked):
        s = picked[slug]
        out_rows.append({
            "slug": slug,
            "row_share": s["row_share"],
            "column_share": s["column_share"],
            "bucket_guess": bucket_of(s["row_share"], s["column_share"]),
            "splay": splay_scan.get(slug),
            "stagger": None,  # owner fills in: "ortho" / "row" / "column" / other
        })

    out = REPO / args.out
    out.write_text(json.dumps(out_rows, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
