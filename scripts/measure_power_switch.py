#!/usr/bin/env python3
"""Does a design's board carry an on/off switch -- yes, no, or nobody can tell?

The owner's request, 2026-10-04: a power switch, "detected on PCB level", as a filter and
a recorded fact, yes/no/not recorded. It is the slide switch a wireless board turns its
battery off with, read by footprint name off the design's own attributed board files.

WHY "NO" IS ALLOWED HERE, when a name-matching scan's silence is normally Unknown
(Forgejo #66 published 11 false "none" pages that way). The lexicon is not a guess at
names nobody has seen: `extract_switch_footprints.POWER_SWITCH` was built on 2026-10-04 by
reading every switch-like footprint name in the corpus, and the by-hand pass is what
found Alps' `switch_SSSS811101`, which the first list missed. So "no" means every one of
the design's board files was read whole and none places a name from a list that covers
every power switch this corpus has. A board added later with a new part can still read
"no" wrongly; the intake bot's new boards are where that would show.

UNKNOWN WHERE THE BOARD CANNOT SAY. A design with no readable board file, or whose board
carries a one-position DIP slide switch (`MAYBE_POWER_SWITCH`), which can as easily pick
a boot mode as cut the battery.

`True` if any of the design's files places one -- a split's switch is often on one hand.

Usage: measure_power_switch.py [--designs FILE] [--scan FILE] [--out FILE]
"""
import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from extract_switch_footprints import MAYBE_POWER_SWITCH, POWER_SWITCH  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent


def verdict_for(files):
    """`True`, `False` or `None` from the board-scan entries of one design's files."""
    if not files or any(f is None or f.get("kind") == "unreadable" for f in files):
        names = [n for f in files if f for n in (f.get("names") or {})]
        return True if any(POWER_SWITCH.search(n) for n in names) else None
    names = [n for f in files for n in (f.get("names") or {})]
    if any(POWER_SWITCH.search(n) for n in names):
        return True
    if any(MAYBE_POWER_SWITCH.search(n) for n in names):
        return None
    return False


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--designs", default="data/design-boards.json")
    ap.add_argument("--scan", default="data/board-scan.json")
    ap.add_argument("--out", default="data/power-switch-scan.json")
    args = ap.parse_args()

    mapping = json.loads((REPO/args.designs).read_text())
    scan = json.loads((REPO/args.scan).read_text())
    verdicts = {}
    for slug, m in mapping.items():
        v = verdict_for([scan.get(f"{m['repo']}\t{p}") for p in m["paths"]])
        if v is not None:
            verdicts[slug] = v

    (REPO/args.out).write_text(json.dumps(verdicts, indent=1, sort_keys=True))
    yes = sorted(s for s, v in verdicts.items() if v)
    print(f"  power switch     {len(yes):>4}   {' '.join(yes)}")
    print(f"  none on board    {sum(1 for v in verdicts.values() if not v):>4}")
    print(f"  unknown          {len(mapping) - len(verdicts):>4}   -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
