#!/usr/bin/env python3
"""Check every `source` in seed/sources.toml resolves to a live repository.

The confirmation pass on ticket 26 is a human reading rows. A row whose repo is
gone cannot survive the build whatever the human writes, so it should be culled
before it costs anyone attention. Reads the seed, HEADs each distinct source,
and reports alive / moved / dead.

AND EVERY NON-UPSTREAM TREE `seed/trees.toml` REGISTERS, backlog #64. A fork tree
is pinned to a BRANCH, not a commit -- deliberately, because a branch tracks the
author's current truth and a catalogue of buildable designs wants that, while a
SHA is stable and immediately stale. The cost of that choice is that the branch
can move or vanish, and this is where it gets paid: the branch URL is probed like
any other source, so a fork tree is higher-decay than upstream rather than a
different kind of thing. It is the treatment `source` already gets, which has
caught a repo that left GitHub entirely.

Usage: check_source_reachability.py [seed/sources.toml] [--jobs N]
"""
import pathlib
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

ENTRY = re.compile(r"^\[\[entry\]\]", re.M)
FIELD = re.compile(r'^(\w+)\s*=\s*(.+)$', re.M)


def parse(path):
    """Yield one dict per [[entry]] block. TOML is flat here, so a line scan is enough."""
    text = open(path, encoding="utf-8").read()
    blocks = ENTRY.split(text)[1:]
    for block in blocks:
        row = {}
        for line in block.splitlines():
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("["):
                continue
            m = FIELD.match(line)
            if not m:
                continue
            key, raw = m.group(1), m.group(2).strip()
            if raw in ("true", "false"):
                row[key] = raw == "true"
            else:
                row[key] = raw.strip('"')
        if row:
            yield row


def probe(repo, attempt=0):
    """HEAD github.com/<repo>. Returns (status, final_url)."""
    return probe_url(f"https://github.com/{repo}", attempt)


def probe_url(url, attempt=0):
    """HEAD any URL. Returns (status, final_url) -- the same four states [probe] does."""
    req = Request(url, method="HEAD", headers={"User-Agent": "keyboardbuilders-seed-check"})
    try:
        with urlopen(req, timeout=20) as resp:
            final = resp.geturl()
            moved = final.rstrip("/").lower() != url.rstrip("/").lower()
            return ("moved" if moved else "alive"), final
    except HTTPError as e:
        if e.code == 404:
            return "dead", url
        if e.code == 429 and attempt < 3:
            time.sleep(5 * (attempt + 1))
            return probe_url(url, attempt + 1)
        return f"http-{e.code}", url
    except (URLError, OSError) as e:
        if attempt < 2:
            time.sleep(2)
            return probe_url(url, attempt + 1)
        return f"error-{type(e).__name__}", url


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    path = args[0] if args else "seed/sources.toml"
    jobs = 8
    for a in sys.argv[1:]:
        if a.startswith("--jobs"):
            jobs = int(a.split("=", 1)[1]) if "=" in a else 8

    rows = list(parse(path))
    by_repo = {}
    for row in rows:
        src = row.get("source")
        if src and src is not True:
            by_repo.setdefault(src, []).append(row)

    print(f"{len(rows)} rows, {len(by_repo)} distinct sources", file=sys.stderr)

    repos = sorted(by_repo)
    with ThreadPoolExecutor(max_workers=jobs) as pool:
        results = list(pool.map(probe, repos))

    for repo, (status, final) in zip(repos, results):
        ids = ",".join(f"{r['tree']}:{r['id']}" for r in by_repo[repo])
        tail = f"\t{final}" if status == "moved" else ""
        print(f"{status}\t{repo}\t{len(by_repo[repo])}\t{ids}{tail}")

    check_trees()

    if "--apply" in sys.argv:
        dead = {r for repo, (st, _) in zip(repos, results) if st == "dead" for r in [repo]}
        apply_source_gone(path, dead)


def check_trees():
    """Probe the branch URL of every non-upstream tree in `seed/trees.toml`.

    Backlog #64. Reported, never applied: `apply_source_gone` verdicts a ROW, and a
    tree is not a row -- a vanished branch invalidates every design that names the
    tree, which is a person's decision and not a field to flip.
    """
    try:
        sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
        import trees as tree_registry
        reg = tree_registry.load()
    except (OSError, ValueError, KeyError) as e:
        print(f"# no tree registry read ({type(e).__name__})", file=sys.stderr)
        return
    forks = [t for t in reg.ids()
             if not reg.upstream(t) and reg.entry(t).get("url")]
    if not forks:
        return
    print(f"{len(forks)} non-upstream trees", file=sys.stderr)
    for tid in forks:
        e = reg.entry(tid)
        url = f"{e['url'].rstrip('/')}/tree/{e.get('ref') or 'HEAD'}"
        status, final = probe_url(url)
        tail = f"\t{final}" if status == "moved" else ""
        print(f"{status}\ttree:{tid}\t{url}{tail}")


def apply_source_gone(path, dead_repos):
    """Set `verdict = "source_gone"` on every row whose repo 404s.

    Ticket 26: a dead repo is a RULING, not a pending row. Left `drafted` it would
    mean "nobody has looked", when the truth is "looked, it is gone" -- the same
    Unknown-vs-NotApplicable distinction ticket 25 settled for switches. Verdicting
    them takes them off the confirmation pile permanently and stops a re-draft
    proposing them as fresh work.

    Only ever moves a row OFF `drafted`. A row a human has already ruled is left
    alone -- their word outranks this measurement, which decays.
    """
    text = pathlib.Path(path).read_text(encoding="utf-8")
    blocks = text.split("[[entry]]")
    out, changed = [blocks[0]], 0
    for b in blocks[1:]:
        m = re.search(r'^source\s*=\s*"([^"]+)"', b, re.M)
        v = re.search(r'^verdict\s*=\s*"drafted"$', b, re.M)
        if m and v and m.group(1) in dead_repos:
            b = b[:v.start()] + 'verdict  = "source_gone"' + b[v.end():]
            changed += 1
        out.append(b)
    pathlib.Path(path).write_text("[[entry]]".join(out), encoding="utf-8")
    print(f"# applied source_gone to {changed} rows", file=sys.stderr)


if __name__ == "__main__":
    main()
