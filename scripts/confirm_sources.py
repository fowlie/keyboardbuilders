#!/usr/bin/env python3
"""Apply verdicts to seed/sources.toml, and report what is left to rule.

Ticket 43 is 508 rows needing the owner's word. Hand-editing 508 aligned TOML
blocks is where a confirmation pass dies, and the file has a documented footgun:
`draft_sources.py --toml > seed/sources.toml` truncates before the merge can
read it and silently discards every ruling. So verdicts get applied by a tool.

It edits **in place, field by field**, touching only the rows named and only the
`verdict`, `source`, `path` and `note` lines -- never re-rendering the file. A
row's ruling is therefore unaffected by anything this tool does not know about,
and `draft_sources.py --write`'s `preserve_rulings` carries it across a
regeneration as before.

This does not decide anything. `confirmed` is the human's word (ticket 15); the
tool is the pen, not the ruling.

    # where am I?
    confirm_sources.py --report

    # rule rows, by firmware id or by repo (a repo rules its whole cluster)
    confirm_sources.py --id crkbd/rev1 crkbd/r2g --confirm
    confirm_sources.py --repo foostan/crkbd --confirm
    confirm_sources.py --repo-file /tmp/looked-at-these.txt --confirm

    # rule with a correction -- both survive a regeneration
    confirm_sources.py --id bluebell/swoop --source davidphilipbarr/Sweep --confirm
    confirm_sources.py --id kb58 --path "kb58_pico" --confirm

    # every remaining row whose source = false, i.e. "I looked, none is known"
    confirm_sources.py --select no-source --confirm

Nothing is written without --confirm (or --verdict); bare selection prints the
rows it would touch, which is also how you check a --select before trusting it.
"""
import argparse
import pathlib
import re
import sys
from collections import Counter, defaultdict

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from check_source_reachability import parse
import trees as tree_registry

TREES = tree_registry.load()

VERDICTS = ("drafted", "confirmed", "source_gone")
WIDTH = 9  # `verdict  = ` -- draft_sources.py's toml_lines pads keys to this


def blocks(text):
    """Split the file into (preamble, [block]) where each block is one entry.

    Kept deliberately dumb: the seed is flat TOML written by one renderer, so a
    block is the text between `[[entry]]` markers and a field is a line.
    """
    parts = re.split(r"(?m)^\[\[entry\]\]$", text)
    return parts[0], parts[1:]


def field(block, key):
    m = re.search(rf"(?m)^{key}\s*=\s*(.+)$", block)
    if not m:
        return None
    raw = m.group(1).strip()
    if raw in ("true", "false"):
        return raw == "true"
    return raw.strip('"')


def set_field(block, key, value):
    """Rewrite one field line, keeping the renderer's alignment. Adds nothing."""
    if value is False:
        rendered = "false"
    else:
        rendered = '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'
    line = f"{key.ljust(WIDTH)}= {rendered}"
    new, n = re.subn(rf"(?m)^{key}\s*=\s*.+$", lambda _: line, block, count=1)
    if n != 1:
        raise SystemExit(f"no `{key}` field to set on row {field(block, 'id')!r}")
    return new


def _lines(path):
    """Non-blank, non-comment lines of a list file. Empty when no path given."""
    if not path:
        return []
    return [l.strip() for l in open(path, encoding="utf-8")
            if l.strip() and not l.startswith("#")]


def selectors(args, rows_by_key):
    """The set of (tree, id) keys this invocation targets, from every selector.

    `id` is unique per tree, not overall: four designs -- `a_dux`, `clog`,
    `cradio`, `eternal_keypad` -- carry both a ZMK shield and a QMK record, so
    a bare `--id` names both rows. `--tree` narrows it when that matters.
    """
    want = set()
    unknown = []
    trees = {args.tree} if args.tree else None
    keys = [k for k in rows_by_key if not trees or k[0] in trees]
    for i in args.id or []:
        hit = [k for k in keys if k[1] == i]
        (want.update(hit) if hit else unknown.append(i))
    for line in _lines(args.id_file):
        tr, _, i = line.partition(":")
        if not i:
            unknown.append(line)
            continue
        hit = [k for k in keys if k == (tr, i)]
        (want.update(hit) if hit else unknown.append(line))
    repos = list(args.repo or [])
    repos += _lines(args.repo_file)
    by_repo = defaultdict(list)
    for k in keys:
        r = rows_by_key[k]
        if r.get("source"):
            by_repo[r["source"].lower()].append(k)
    for slug in repos:
        hit = by_repo.get(slug.lower())
        (want.update(hit) if hit else unknown.append(slug))
    if args.select:
        for k in keys:
            r = rows_by_key[k]
            if r.get("verdict") != "drafted":
                continue
            src, sig = r.get("source"), r.get("signal")
            if args.select == "no-source" and not src:
                want.add(k)
            elif args.select == "dev-seed" and sig == "dev-seed":
                want.add(k)
            elif (args.select or "").startswith("tree-") \
                    and r.get("tree") == args.select[len("tree-"):]:
                want.add(k)
    if unknown:
        raise SystemExit("not in the seed: " + ", ".join(unknown))
    return want


def report(rows):
    """Where the pass stands, in ticket 43's own buckets."""
    drafted = [r for r in rows if r.get("verdict") == "drafted"]
    print(f"{len(rows)} rows -- {len(rows) - len(drafted)} ruled, "
          f"{len(drafted)} drafted")
    print("  " + "  ".join(f"{v}={n}" for v, n in
                           sorted(Counter(r.get("verdict") for r in rows).items())))
    if not drafted:
        print("\nnothing left to rule.")
        return

    cluster = Counter(r["source"] for r in rows if r.get("source"))
    buckets = defaultdict(list)
    for r in drafted:
        src = r.get("source")
        if not src:
            buckets["no source stated -- rule `I looked, none is known`"].append(r)
        elif r.get("signal") == "dev-seed":
            buckets["dev-seed -- a human already supplied it"].append(r)
        elif cluster[src] > 1:
            buckets["shared repo -- one look rules the whole cluster"].append(r)
        else:
            buckets["one record, one repo -- a glance"].append(r)

    print("\nremaining, by bucket:")
    for name, rs in sorted(buckets.items(), key=lambda kv: -len(kv[1])):
        repos = len({r["source"] for r in rs if r.get("source")})
        looks = f", {repos} repos" if repos and repos != len(rs) else ""
        print(f"  {len(rs):4}  {name}{looks}")

    shared = buckets["shared repo -- one look rules the whole cluster"]
    if shared:
        print(f"\nthe {len({r['source'] for r in shared})} unruled clusters, "
              f"biggest first -- `--repo <slug>` rules each whole:")
        per = defaultdict(list)
        for r in shared:
            per[r["source"]].append(r["id"])
        for slug, ids in sorted(per.items(), key=lambda kv: (-len(kv[1]), kv[0])):
            print(f"  {len(ids)}  {slug}\n       {', '.join(sorted(ids))}")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed", default="seed/sources.toml")
    ap.add_argument("--report", action="store_true", help="print progress and stop")
    ap.add_argument("--id", nargs="+", metavar="ID", help="firmware ids to rule")
    # THE CHOICES COME FROM THE REGISTRY, backlog #64: `tree` is a key into
    # `seed/trees.toml`, so a literal pair here would refuse to name a fork-hosted
    # row the moment one is added -- which is the whole failure mode #64 removes.
    ap.add_argument("--tree", choices=tuple(TREES.ids()),
                    help="narrow every selector to one tree")
    ap.add_argument("--repo", nargs="+", metavar="SLUG",
                    help="rule every row whose source is this repo")
    ap.add_argument("--id-file", metavar="PATH",
                    help="a file of `tree:id` lines, as `source_dossier.py --ids` "
                         "emits; # comments allowed")
    ap.add_argument("--repo-file", metavar="PATH",
                    help="a file of repo slugs, one per line, # comments allowed")
    ap.add_argument("--select",
                    choices=("no-source", "dev-seed")
                            + tuple(f"tree-{t}" for t in TREES.ids()),
                    help="rule a whole class of still-drafted rows")
    ap.add_argument("--confirm", action="store_true",
                    help="set verdict = confirmed (same as --verdict confirmed)")
    ap.add_argument("--verdict", choices=VERDICTS)
    ap.add_argument("--source", help="correct the repo slug (or `false` for none known)")
    ap.add_argument("--path", help="correct the subdirectory (or `false`)")
    ap.add_argument("--note", help="replace the note")
    args = ap.parse_args()

    path = pathlib.Path(args.seed)
    text = path.read_text(encoding="utf-8")
    rows = list(parse(args.seed))
    rows_by_key = {(r["tree"], r["id"]): r for r in rows}
    if len(rows_by_key) != len(rows):
        dupes = [k for k, n in Counter((r["tree"], r["id"]) for r in rows).items()
                 if n > 1]
        raise SystemExit("the seed repeats a (tree, id): "
                         + ", ".join(f"{t}:{i}" for t, i in dupes))

    if args.report or not (args.id or args.id_file or args.repo
                           or args.repo_file or args.select):
        report(rows)
        return

    verdict = args.verdict or ("confirmed" if args.confirm else None)
    want = selectors(args, rows_by_key)
    if not want:
        print("nothing selected.")
        return

    edits = {"verdict": verdict, "source": args.source,
             "path": args.path, "note": args.note}
    edits = {k: (False if v == "false" else v) for k, v in edits.items() if v is not None}
    if len(want) > 1 and ("source" in edits or "path" in edits):
        raise SystemExit("--source/--path correct one row; select a single --id")

    if not edits:
        print(f"{len(want)} rows selected, no verdict given -- would touch:")
        for k in sorted(want):
            r = rows_by_key[k]
            print(f"  {k[0]:4} {k[1]:44} "
                  f"{r.get('source') or 'false':40} {r.get('verdict')}")
        print("\nadd --confirm to rule them.")
        return

    pre, bs = blocks(text)
    out, touched, already = [pre], [], []
    for b in bs:
        key = (field(b, "tree"), field(b, "id"))
        if key in want:
            before = field(b, "verdict")
            for k, v in edits.items():
                b = set_field(b, k, v)
            (already if before == edits.get("verdict") else touched).append(key)
        out.append(b)
    text = out[0] + "".join("[[entry]]" + b for b in out[1:])
    path.write_text(text, encoding="utf-8")

    shown = ", ".join(f"{k} = {v!r}" for k, v in edits.items())
    print(f"{len(touched)} rows set to {shown}"
          + (f" ({len(already)} already were)" if already else ""))
    after = [r for r in parse(args.seed) if r.get("verdict") == "drafted"]
    print(f"{len(after)} rows still drafted.")


if __name__ == "__main__":
    main()
