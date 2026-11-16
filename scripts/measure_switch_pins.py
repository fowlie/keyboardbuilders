#!/usr/bin/env python3
"""Does the pin reader (`switch_pins.py`) agree with the names, where a name says?

A measurement only, written to decide whether `scan_boards.py` may trust the pins where a
footprint's name gives no family. For every board in `board-scan.json`, every distinct
switch-like footprint name is read once both ways -- the name lexicon and the pins -- and
the pair is recorded. Where the name names a family, the pins are checked against it; where
it names none, the pins are all there is.

Fetches every board file whole (about 1.5 GB, 2026-11-16). Results cache per file to
--cache, so a re-run fetches only what is missing.

Usage: measure_switch_pins.py [--jobs N] [--cache FILE]
"""
import argparse
import collections
import json
import pathlib
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from extract_switch_footprints import classify as name_families  # noqa: E402
from scan_boards import fetch, is_switch  # noqa: E402
from switch_pins import footprints, pin_families  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent


def measure(repo, path):
    text, err, _ = fetch(repo, path, cap=0)
    if text is None:
        return {"error": err}
    out = {}
    for name, block in footprints(text):
        if name in out or not is_switch(name):
            continue
        out[name] = {"name": sorted(name_families(name)), "pins": sorted(pin_families(block))}
    return {"footprints": out}


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jobs", type=int, default=8)
    ap.add_argument("--cache", type=pathlib.Path, default=REPO / "data/switch-pin-scan.json")
    args = ap.parse_args()

    scan = json.loads((REPO / "data/board-scan.json").read_text())
    files = sorted(k for k, v in scan.items() if v.get("kind") == "board")
    cache = json.loads(args.cache.read_text()) if args.cache.exists() else {}
    todo = [k for k in files if k not in cache]
    print(f"{len(files)} board files, {len(todo)} to fetch", file=sys.stderr)
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for n, (k, res) in enumerate(zip(todo, pool.map(lambda k: measure(*k.split("\t", 1)), todo)), 1):
            cache[k] = res
            if n % 25 == 0:
                args.cache.write_text(json.dumps(cache, indent=1, sort_keys=True))
                print(f"  {n}/{len(todo)}", file=sys.stderr)
    args.cache.write_text(json.dumps(cache, indent=1, sort_keys=True))

    agree, disagree, silent, filled = collections.Counter(), [], collections.Counter(), []
    for k, res in cache.items():
        for name, r in (res.get("footprints") or {}).items():
            named, pins = set(r["name"]), set(r["pins"])
            if named:
                checkable = named & {"MX", "ChocV1"}
                if not pins:
                    silent[bool(checkable)] += 1
                elif pins <= named:
                    agree[len(pins)] += 1
                else:
                    disagree.append((k, name, sorted(named), sorted(pins)))
            elif pins:
                filled.append((k, name, sorted(pins)))
    print(f"named footprints where pins agree: {sum(agree.values())}")
    print(f"named footprints where pins are silent: {silent[True]} (MX/ChocV1 name), "
          f"{silent[False]} (other families)")
    print(f"named footprints where pins DISAGREE: {len(disagree)}")
    for row in disagree:
        print("  ", *row)
    print(f"unnamed footprints the pins fill: {len(filled)}")
    for row in filled:
        print("  ", *row)


if __name__ == "__main__":
    main()
