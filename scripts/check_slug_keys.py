#!/usr/bin/env python3
"""Every slug-keyed row whose slug names no design. Forgejo #38, piece 2.

A design's slug is its URL and its join key, and **seven artifacts are keyed by it.**
Rename a design and each one has to follow. Only the Kotlin tests that hardcode a URL
fail loudly; the rest detach silently and keep looking valid, which is the dangerous
half:

    `3w6` was renamed from `3-w6` on 2026-08-29 (`e3ec84f`). `position-scan.json`
    lost its entry, so the silhouette fell back from the board's own geometry to the
    QMK layout -- a WRONG picture, drawn confidently, found by hand afterwards.

Forgejo #38 lists three such files. There are **seven**, and four days after that
rename `build-difficulty-scan.json` still carried a `3-w6` row that nobody had found.

TWO CAUSES, AND ONLY ONE OF THEM WANTS A REDIRECT. An orphan is either

  * **a rename** -- the design still exists under a new slug, and
    ticket 07 decision 8
    says *"slugs are mutable and old ones keep resolving"*, so the old URL owes a `301`;
  * **a removal** -- the design stopped publishing, and a `404` is the right answer.

This script cannot tell them apart and does not try: it reports the orphan and names the
live slugs closest to it, so a person can say which. `seed/renames.toml` is where that
answer goes.

WHAT IS REGENERATED VERSUS WHAT ROTS. `design-boards.json` and `position-scan.json` are
emitted from the current design list on every build, so they cannot orphan for long.
`build-difficulty-scan.json`, `extras-scan.json` and `mcu-package-scan.json` are caches
keyed by slug that nothing prunes, and `seed/summaries.toml` and `seed/bodies.toml` are
human-authored files a rename silently disconnects from its design. Those five are the
ones this check exists for.

`seed/renames.toml` accounts for both: `now = "<slug>"` is a rename and owes a 301,
`now = false` is a ruled removal and owes a 404 -- `sources.toml`'s own convention for
"settled, and the answer is nothing".

Usage: check_slug_keys.py [--designs FILE] [--quiet]
Exits 1 only when a dead slug has NO row in seed/renames.toml -- i.e. when nobody has
decided the fate of a URL. A ruled slug in a stale cache is a cost, not a defect.
"""
import argparse
import difflib
import json
import pathlib
import sys
import tomllib

REPO = pathlib.Path(__file__).resolve().parent.parent
SCRATCH = REPO/"data"

# `(path, how to read its slugs)`. Ordered so the files a rename silently breaks come
# first -- a reader who stops after two lines should have seen the dangerous ones.
SOURCES = [
    ("seed/summaries.toml", "toml"),
    ("seed/bodies.toml", "toml"),
    ("data/build-difficulty-scan.json", "list"),
    ("data/extras-scan.json", "dict"),
    ("data/mcu-package-scan.json", "list"),
    ("data/position-scan.json", "dict"),
    ("data/design-boards.json", "dict"),
]


def slugs_in(path, kind):
    """The slugs one artifact is keyed by, or None when the file is absent."""
    full = REPO/path
    if not full.exists():
        return None
    if kind == "toml":
        return [e["slug"] for e in tomllib.loads(full.read_text())["entry"]
                if e.get("slug")]
    data = json.loads(full.read_text())
    if kind == "dict":
        return list(data)
    return [e["slug"] for e in data if e.get("slug")]


def ruled():
    """The fate of every slug a person has ruled on: a new slug, or `False` for removed."""
    path = REPO/"seed/renames.toml"
    if not path.exists():
        return {}
    return {e["was"]: e["now"] for e in tomllib.loads(path.read_text())["entry"]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--designs", default="src/main/resources/seed/designs.json")
    ap.add_argument("--quiet", action="store_true",
                    help="print nothing when every row resolves")
    args = ap.parse_args()

    designs = json.loads((REPO/args.designs).read_text())
    live = {d["slug"] for d in designs}
    known = ruled()

    found, total, unexplained = {}, 0, 0
    for path, kind in SOURCES:
        slugs = slugs_in(path, kind)
        if slugs is None:
            print(f"  {path}: absent, NOT checked", file=sys.stderr)
            continue
        total += 1
        orphans = sorted({s for s in slugs if s not in live})
        if orphans:
            found[path] = orphans
            unexplained += sum(1 for s in orphans if s not in known)

    if not found:
        if not args.quiet:
            print(f"every slug in {total} slug-keyed artifacts names one of "
                  f"{len(live)} designs")
        return 0

    print(f"SLUGS NAMING NO DESIGN, over {total} slug-keyed artifacts "
          f"and {len(live)} designs\n")
    for path, orphans in found.items():
        print(f"{path}  ({len(orphans)})")
        for slug in orphans:
            if known.get(slug) is False:
                verdict = "ruled REMOVED (seed/renames.toml) -- a 404 is correct"
            elif slug in known:
                verdict = f"renamed to {known[slug]!r} (seed/renames.toml)"
            else:
                near = difflib.get_close_matches(slug, sorted(live), n=3, cutoff=0.6)
                verdict = (f"nearest live slugs {near}" if near
                           else "nothing live resembles it -- likely a removal")
            print(f"    {slug:24s} {verdict}")
        print()

    if not unexplained:
        print(f"Every one has a row in seed/renames.toml, so the caches are stale "
              f"rather than wrong.\nThey rewrite wholesale from a local clone and "
              f"nobody has re-run them since;\nthat is a separate cost, not an "
              f"unruled slug.")
        return 0
    print(f"{unexplained} OF THESE HAVE NO ROW IN seed/renames.toml, and that is the")
    print("finding. A rename owes a 301 -- ticket 07 decision 8 settled that old slugs")
    print("keep resolving -- and a removal owes a 404 with `now = false`. This script")
    print("cannot tell them apart; a person can, and until one does the slug is a URL")
    print("nobody has decided the fate of.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
