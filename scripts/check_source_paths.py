#!/usr/bin/env python3
"""Check every `path` in seed/sources.toml still exists in its repo.

`check_source_reachability.py` asks whether the REPO is alive. Nothing asked
whether the DIRECTORY inside it is, and the two decay independently: a repo
stays at the same slug and answers 200 for years while its owner reorganises
the tree under it. Found on ticket 43, 2026-08-27, on four rows that were
already `confirmed`:

    3keyecosystem/2key2   `2key2`              -> moved to `2key/2key2`
    horrortroll/paws60    `paws60/pcb/Hotswap` -> now `paws60/pcb/rev1`, `rev2`
    the_royal/schwann     `PCBs/Schwann_PCB`   -> now `02 - PCBs/Schwann_PCB`
    dyz/* (x3)            `dyz40`, `dyz_tkl`   -> never existed; layouts are .svg

That is silent data loss rather than a wrong-looking row. `path` is the
importer's answer to *which design in this repo*, so a stale one makes the
board scan find nothing, and the record-without-hardware rule (ticket 48) then
drops the design -- correctly, for the wrong reason, with no warning anywhere.

Reads the tree cache `source_dossier.py` already writes, so it costs no
network. A repo absent from the cache is reported as unchecked, never as clean.

Usage: check_source_paths.py [seed/sources.toml] [--cache PATH] [--quiet]
"""
import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from check_source_reachability import parse  # noqa: E402
from source_dossier import HARDWARE  # noqa: E402


def under(paths, prefix):
    """Every tree path at or below `prefix`. Exact-match or slash-boundary only.

    The boundary matters: `Splite-Keyboard-v3` must not swallow the sibling
    `Splite-Keyboard-v3.output`, which is that design's gerber output and not
    part of the directory the row names.
    """
    stem = prefix.rstrip("/")
    return [p for p in paths if p == stem or p.startswith(stem + "/")]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("seed", nargs="?", default="seed/sources.toml")
    ap.add_argument("--cache", default="data/source-trees.json")
    ap.add_argument("--quiet", action="store_true", help="print only the failures")
    args = ap.parse_args()

    trees = json.loads(pathlib.Path(args.cache).read_text())
    rows = [r for r in parse(args.seed) if r.get("path") and r.get("source")]

    gone, empty, unchecked, ok = [], [], [], []
    for r in rows:
        cached = trees.get(r["source"])
        if not cached or cached.get("paths") is None:
            unchecked.append(r)
            continue
        scope = under(cached["paths"], r["path"])
        if not scope:
            gone.append(r)
        elif not any(HARDWARE.search(p) for p in scope):
            empty.append((r, len(scope)))
        else:
            ok.append(r)

    if not args.quiet:
        print(f"{len(rows)} rows carry a path -- {len(ok)} hold hardware, "
              f"{len(empty)} hold none, {len(gone)} do not exist, "
              f"{len(unchecked)} unchecked\n")

    if gone:
        print(f"PATH DOES NOT EXIST ({len(gone)}) -- the board scan will find nothing:")
        for r in gone:
            print(f"  {r['tree']}:{r['id']:38} {r['source']:38} path={r['path']!r}")
    if empty:
        print(f"\nPATH EXISTS, NO CAD OR PCB FILE UNDER IT ({len(empty)}) -- "
              f"check the note says so:")
        for r, n in empty:
            print(f"  {r['tree']}:{r['id']:38} {r['source']:38} "
                  f"path={r['path']!r} ({n} files)")
    if unchecked:
        print(f"\nUNCHECKED ({len(unchecked)}) -- repo not in the tree cache; "
              f"re-run source_dossier.py:")
        for r in unchecked:
            print(f"  {r['tree']}:{r['id']:38} {r['source']}")

    return 1 if gone else 0


if __name__ == "__main__":
    sys.exit(main())
