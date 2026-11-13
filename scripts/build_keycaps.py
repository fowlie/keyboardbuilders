#!/usr/bin/env python3
"""Turn seed/keycaps.toml into the JSON the running site reads at startup.

Same split `build_controllers.py` draws, for the same reason: `seed/keycaps.toml` is what
a human (here, a research pass) states, and the JSON under `resources` is a build output
nobody hand-edits. Its own file rather than folded into `build_seed.py` because the keycap
catalogue is independent of the corpus -- it does not change when a design is added, only
when the research itself does.

The summary it prints is by (stem, caps) PAIR, not by stem, because the pair is the whole
point of the file: `choc_v1` splits across both cap footprints, and a catalogue that looked
complete per stem while missing a quadrant is exactly the failure backlog #61 was filed
about (docs/research/keycap-profiles.md §2.3).

Usage: build_keycaps.py [--seed seed/keycaps.toml]
                        [--out src/main/resources/seed/keycaps.json]
"""
import argparse
import json
import pathlib
import tomllib

REPO = pathlib.Path(__file__).resolve().parent.parent

# The wire values `KeycapCatalog.kt` maps off its own enums. Checked here rather than left
# to a startup exception: a typo in this file's `stem` is a page that silently answers
# "nothing catalogued" for every design of that stem, which reads as a research gap
# instead of a build error.
STEMS = {"mx_cross", "choc_v1", "alps"}
CAPS = {"full", "reduced"}


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed", default="seed/keycaps.toml")
    ap.add_argument("--out", default="src/main/resources/seed/keycaps.json")
    args = ap.parse_args()

    rows = tomllib.loads((REPO/args.seed).read_text())["entry"]

    slugs = [row["slug"] for row in rows]
    if len(set(slugs)) != len(slugs):
        raise SystemExit(f"duplicate slug in {args.seed}: a URL is a promise")
    for row in rows:
        if row["stem"] not in STEMS:
            raise SystemExit(f"{row['slug']}: unknown stem '{row['stem']}', not in {sorted(STEMS)}")
        if row["caps"] not in CAPS:
            raise SystemExit(f"{row['slug']}: unknown caps '{row['caps']}', not in {sorted(CAPS)}")

    out = REPO/args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rows, indent=1))

    print(f"{len(rows)} keycap profiles -> {args.out}")
    by_pair = {}
    for row in rows:
        by_pair.setdefault((row["stem"], row["caps"]), []).append(row["name"])
    for (stem, caps), names in sorted(by_pair.items()):
        print(f"  {stem:<9} {caps:<8} {len(names)}  {', '.join(names)}")
    for stem in sorted(STEMS):
        for caps in sorted(CAPS):
            if (stem, caps) not in by_pair:
                print(f"  {stem:<9} {caps:<8} -   no rows (see docs/research/keycap-profiles.md §4)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
