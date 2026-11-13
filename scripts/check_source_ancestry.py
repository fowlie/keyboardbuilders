#!/usr/bin/env python3
"""Check every `source` in seed/sources.toml against its fork ancestry.

`check_source_reachability.py` answers *is the repo there*. This answers the
question the confirmation pass on ticket 43 actually costs its 272 glances on:

    is this the design's own repo, or an ancestor's?

That is the failure ticket 22 found on `handwired/dactyl_lightcycle`, and
`draft_sources.py`'s `owner_matches` cannot catch it -- a record citing the repo
it was forked from passes the owner check whenever the ancestor is also the
maintainer. GitHub states fork parentage outright, so the check is a lookup
rather than a judgement.

It does NOT rule. A fork is not automatically wrong: an author who forks a
predecessor and lays their own hardware into it holds the hardware, and the row
is right. What this produces is the list of rows where a human's glance is
load-bearing, so the rest can be confirmed in bulk.

Needs an authenticated `gh`. Responses cache to --cache, so re-runs are free.

Usage: check_source_ancestry.py [seed/sources.toml] [--jobs N] [--cache PATH]
"""
import argparse
import json
import pathlib
import subprocess
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from check_source_reachability import parse

FIELDS = ("full_name", "fork", "archived", "description", "pushed_at",
          "stargazers_count", "parent", "source")


def fetch(slug):
    """One `gh api repos/<slug>`. Returns a dict, or {'error': ...} on failure."""
    try:
        out = subprocess.run(
            ["gh", "api", f"repos/{slug}"],
            capture_output=True, text=True, timeout=60,
        )
    except subprocess.TimeoutExpired:
        return {"error": "timeout"}
    if out.returncode != 0:
        msg = out.stderr.strip().splitlines()[-1] if out.stderr.strip() else "failed"
        return {"error": msg}
    d = json.loads(out.stdout)
    r = {k: d.get(k) for k in FIELDS if k not in ("parent", "source")}
    r["parent"] = (d.get("parent") or {}).get("full_name")
    r["root"] = (d.get("source") or {}).get("full_name")
    return r


def load_cache(path):
    p = pathlib.Path(path)
    return json.loads(p.read_text()) if p.exists() else {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("seed", nargs="?", default="seed/sources.toml")
    ap.add_argument("--jobs", type=int, default=8)
    ap.add_argument("--cache", default="data/source-ancestry.json")
    ap.add_argument("--json", action="store_true", help="dump the merged cache and stop")
    args = ap.parse_args()

    rows = list(parse(args.seed))
    by_source = defaultdict(list)
    for r in rows:
        src = r.get("source")
        if src and src is not True:
            by_source[src].append(r)

    cache = load_cache(args.cache)
    todo = [s for s in by_source if s not in cache]
    if todo:
        print(f"fetching {len(todo)} of {len(by_source)} repos "
              f"({len(by_source) - len(todo)} cached)", file=sys.stderr)
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            for slug, meta in zip(todo, pool.map(fetch, todo)):
                cache[slug] = meta
        p = pathlib.Path(args.cache)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(cache, indent=1, sort_keys=True))

    if args.json:
        print(json.dumps(cache, indent=1, sort_keys=True))
        return

    tally = Counter()
    buckets = defaultdict(list)
    for slug, meta in sorted(cache.items()):
        ids = [r["id"] for r in by_source[slug]]
        if meta.get("error"):
            tally["error"] += 1
            buckets["error"].append((slug, meta["error"], ids))
            continue
        moved = meta["full_name"] and meta["full_name"].lower() != slug.lower()
        if meta.get("fork"):
            tally["fork"] += 1
            buckets["fork"].append((slug, meta.get("parent"), ids))
        if moved:
            tally["moved"] += 1
            buckets["moved"].append((slug, meta["full_name"], ids))
        if not meta.get("fork") and not moved:
            tally["own"] += 1
        if meta.get("archived"):
            tally["archived"] += 1
            buckets["archived"].append((slug, meta.get("pushed_at"), ids))

    print(f"{len(cache)} distinct repos over {sum(len(v) for v in by_source.values())} rows")
    for k in ("own", "fork", "moved", "archived", "error"):
        print(f"  {k:9} {tally[k]}")

    for name, title, arrow in (
        ("fork", "FORKS -- the design's own repo, or an ancestor's? (ticket 22's failure)", "forked from"),
        ("moved", "MOVED -- the drafted slug redirects elsewhere", "now"),
        ("archived", "ARCHIVED -- an aliveness signal, not a source question", "last push"),
        ("error", "UNREADABLE", ""),
    ):
        if not buckets[name]:
            continue
        print(f"\n## {title}\n")
        for slug, extra, ids in sorted(buckets[name]):
            print(f"- `{slug}` {arrow} `{extra}`" if arrow else f"- `{slug}` {extra}")
            print(f"  rows: {', '.join(sorted(ids))}")


if __name__ == "__main__":
    main()
