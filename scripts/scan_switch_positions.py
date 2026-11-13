#!/usr/bin/env python3
"""Count each board file's switches by the POSITIONS it places them at, not by how many
switch footprints it carries.

`extract_switch_footprints.py` counts footprints. That is the wrong count wherever a
board offers a choice AT ONE POSITION: `chouchou` carries an MX footprint and a Choc
footprint on every key and reads as **40 switches on a 20-key keyboard**. Measured
against the position reader over the 232 published designs that both instruments can
see, **83 disagree -- 36%** -- and the corpus has been running on the footprint number.

WHAT THIS COSTS US, measured. 50 of the 180 boards the build withholds for
`no firmware record fits this board's key count` have a record declaring a count and
place at least that many. Reading their positions instead reconciles **15 of the 50**
outright, and they are ordinary keyboards rather than edge cases:

    sweepv2            37 footprints -> 34 positions   record declares 34
    bakeneko 65        73            -> 68             record declares 66-69
    atreyu pcb2 v2     64            -> 60             record declares 60
    8pack (x7)          9            ->  8             record declares 8

NEITHER INSTRUMENT IS RIGHT ON ITS OWN, which is why this file adds a reading rather
than replacing one. The position reader returns nothing at all for `koca58` and six
switches for `ergoslab`, where the footprint counter reads them fine; and on a split
whose file holds one hand it returns half the keyboard, which is correct but has to be
doubled by the caller. So `build_seed.py` accepts a record that agrees with EITHER
reading. That can only admit designs, never withhold one, and every admission still has
to match a layout some record declares -- so no count is ever invented, only read a
second way.

THE MERGE RULE THAT DID NOT SURVIVE. Before this, the obvious fix was to merge switch
placements closer together than one key unit, on the theory that overlapping footprints
are alternates. Tested against the 232 published designs as a control: at 8 mm it changes
nothing at all, and at 12 mm -- the radius that reconciles `BMEK` -- it breaks 19 designs
that currently publish correctly, `bevi` 80 -> 79 among them. Recorded because it is the
first idea anyone will have again.

Reads over the network, like `scan_boards.py` and for the same reason: there is no clone
archive on this machine.

Usage: scan_switch_positions.py [--out data/switch-positions.json]
                                [--jobs 8] [--only REPO]
"""
import argparse
import json
import pathlib
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from draft_boards import load_boards  # noqa: E402
from measure_key_pitch import fetch  # noqa: E402
from measure_key_positions import switch_placements  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
SCAN = REPO/"data/board-scan.json"
OUT = REPO/"data/switch-positions.json"


def count_one(job):
    """`(key, n)` for one board file. `None` where the file cannot be read.

    A count of zero is recorded as zero rather than dropped: "we looked and the file
    places no `(at x y)` switch" is a different fact from "we never looked", and the
    caller needs to tell them apart to know whether to fall back.
    """
    repo, path = job
    # `fetch` answers `(text, error)`; a bare `text = fetch(...)` binds the pair and the
    # regex then matches against a tuple, which is a TypeError rather than a miss.
    text, err = fetch(repo, path)
    if text is None or err:
        return f"{repo}\t{path}", None
    return f"{repo}\t{path}", len(switch_placements(text))


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--scan", default=str(SCAN))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--jobs", type=int, default=8)
    ap.add_argument("--only", help="one repo, for a spot check")
    args = ap.parse_args()

    boards, _, _, _ = load_boards(args.scan)
    jobs = sorted({(repo, path) for repo, sets in boards.items()
                   for bset in sets for path in bset
                   if not args.only or repo == args.only})

    out = pathlib.Path(args.out)
    known = json.loads(out.read_text()) if out.exists() else {}
    todo = [j for j in jobs if f"{j[0]}\t{j[1]}" not in known]
    print(f"{len(jobs)} board files, {len(jobs) - len(todo)} already read, "
          f"{len(todo)} to fetch")

    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for i, (key, n) in enumerate(pool.map(count_one, todo), 1):
            if n is not None:
                known[key] = n
            if i % 50 == 0:
                print(f"  ...{i}/{len(todo)}", flush=True)

    out.write_text(json.dumps(known, indent=1, sort_keys=True), encoding="utf-8")
    unread = len(jobs) - sum(1 for j in jobs if f"{j[0]}\t{j[1]}" in known)
    print(f"\n{len(known)} board files counted, {unread} unreadable  ->  {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
