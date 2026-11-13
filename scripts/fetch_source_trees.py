#!/usr/bin/env python3
"""Fill `source-trees.json` with the file listing of every repo `seed/sources.toml` names.

Backlog #104. `scan_boards.py`, `check_source_paths.py` and `scan_boms.py` all read this
cache and none of them fills it: `source_dossier.py` does, but only for DRAFTED rows,
because it was written for ticket 43's confirmation pass. The 387 repos in the dev
machine's copy were fetched while every row was still drafted. So:

  * on a fresh checkout (the VPS) the cache does not exist, and the scans crash;
  * a repo confirmed after that pass never got a listing, and `scan_boards.py` reads a
    repo missing from the cache as a repo with no board files -- silently. Measured
    2026-09-27: 2 of 389 confirmed repos, `pietptr/microdox-pcb` and
    `the-royal/the_royal_open-source-projects`, never scanned. (Two more looked missing
    and were not: see ONE SPELLING PER REPO.)

ONE SPELLING PER REPO. GitHub slugs are case-insensitive and sources.toml is not: three
repos are cited twice, `ladniy/jiran` by the ZMK row and `Ladniy/jiran` by the QMK rows
for the same keyboard (likewise Spaceboards, ErkHal). The build keys repos by the exact
string, so listing both spellings publishes every such board twice and the slug clash
then withholds both -- the VPS's first run went from 333 designs to 330 that way. Until
the build treats a repo case-insensitively, only one spelling is listed: the one
`board-scan.json` holds AT HEAD (tracked in git, so every machine picks the same), else
the first in sort order. The others are dropped from the cache and named in the output.

This fetches only what is missing, one `gh api` call per repo, so a nightly re-run is
free. It never re-fetches a cached repo: a listing that moves is `check_source_paths.py`'s
question, not this one's. The cache stays gitignored (5 MB, cheap to refetch).

Usage: fetch_source_trees.py [seed/sources.toml] [--cache PATH] [--jobs 8]
"""
import argparse
import json
import pathlib
import subprocess
import sys
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from check_source_reachability import parse  # noqa: E402
from source_dossier import tree  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("seed", nargs="?", default="seed/sources.toml")
    ap.add_argument("--cache", default="data/source-trees.json")
    ap.add_argument("--boards", default="data/board-scan.json")
    ap.add_argument("--jobs", type=int, default=8)
    args = ap.parse_args()

    # Every verdict but `source_gone`: a drafted row's repo is what the next question
    # about it will want, and a gone repo has nothing to list.
    cited = sorted({r["source"] for r in parse(args.seed)
                    if r.get("source") and r.get("verdict") != "source_gone"})
    # AS COMMITTED, not as on disk: a stopped run can leave the working copy holding
    # boards scanned under the wrong spelling, and then this rule would pick that one.
    committed = subprocess.run(["git", "show", f"HEAD:{args.boards}"],
                               capture_output=True, text=True)
    boards = committed.stdout if committed.returncode == 0 \
        else pathlib.Path(args.boards).read_text()
    scanned = {k.split("\t")[0] for k in json.loads(boards)}
    spellings = defaultdict(list)
    for slug in cited:
        spellings[slug.lower()].append(slug)
    want, dropped = [], []
    for group in spellings.values():
        pick = next((s for s in group if s in scanned), group[0])
        want.append(pick)
        dropped += [s for s in group if s != pick]
        if len(group) > 1:
            print(f"same repo, {len(group)} spellings: {', '.join(group)} -- listing {pick} "
                  f"only", file=sys.stderr)
    want.sort()
    path = pathlib.Path(args.cache)
    cache = json.loads(path.read_text()) if path.exists() else {}
    for slug in dropped:
        cache.pop(slug, None)
    todo = [s for s in want if s not in cache]
    failed = []
    if todo or dropped:
        print(f"fetching {len(todo)} of {len(want)} repo listings", file=sys.stderr)
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            for slug, (paths, truncated) in zip(todo, pool.map(tree, todo)):
                if paths is None:
                    # Not cached, so the next run tries again -- a network blip must not
                    # become a permanent "this repo has no files".
                    failed.append(slug)
                    continue
                cache[slug] = {"paths": paths, "truncated": truncated}
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(cache, indent=1, sort_keys=True))
    print(f"{len(want) - len(todo)} cached, {len(todo) - len(failed)} fetched, "
          f"{len(failed)} failed{': ' + ', '.join(failed) if failed else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
