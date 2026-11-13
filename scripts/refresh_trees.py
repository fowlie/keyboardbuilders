#!/usr/bin/env python3
"""Clone or update every firmware tree `seed/trees.toml` registers, and say what a
non-upstream one actually holds.

Backlog #64. The registry names a checkout per tree and every reader resolves a record
id under it; this is what puts the checkout there. Without it the Cheapino builds only
on the machine where somebody happened to run `git clone` by hand, which is a
reproducibility hole in the intake #64 opened.

    refresh_trees.py                 clone what is missing, fetch what is present
    refresh_trees.py --check         say what is missing or stale, change nothing
    refresh_trees.py --records       what each fork holds that upstream never did
    refresh_trees.py --shallow       clone upstream at depth 1 too (the VPS; no --records)

WHAT `--records` IS FOR, and it is a correction to the ticket that filed it. #64 asked
for "a drafter for rows nobody upstreamed", on the reading that a registered fork is a
small tree of the author's own work. **It is not.** A QMK fork is a whole second copy of
upstream at whatever vintage it branched, so the author's own record is one needle in
three and a half thousand. Measured on `cheapino-vial`, 2026-09-07:

    3578  records in the fork
     176  the fork has and upstream's WORKING TREE does not
      98    -- of those, upstream has the keyboard under a moved path (`bastardkb/*`)
      78    -- under a top-level name upstream's working tree lacks
      57       -- but present in upstream's GIT HISTORY: removed or renamed since
      21  have never existed upstream at all, under any name, ever
       1  is the design the tree was registered for

So a drafter over the raw difference would queue **176 rows to find one keyboard**, and
even the tightest honest filter queues 21 -- twenty of which are *vial-qmk's* additions
rather than the registered author's. That is the same cost inversion backlog #21
diagnosed, arriving from the other side. A registry entry exists because a person ruled
that a NAMED design's record lives in that tree; the fork is not a discovery surface and
enumerating it is not intake. This prints the number so the next person has it rather
than the guess, and deliberately writes no rows.

The three-way split is the whole point of the report: a bare `fork - upstream` is 88%
noise, and the two cheap filters that remove it -- the moved path and the deleted one --
are only visible if you ask upstream's history and not just its working tree.
"""
import argparse
import pathlib
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import trees as tree_registry  # noqa: E402


def run(args, cwd=None):
    """Run a git command, returning (ok, output)."""
    out = subprocess.run(args, cwd=cwd, capture_output=True, text=True, timeout=1800)
    return out.returncode == 0, (out.stdout + out.stderr).strip()


def records(root):
    """Every keyboard record under a QMK-shaped root, by its id."""
    found = set()
    for name in ("info.json", "keyboard.json"):
        for path in root.rglob(name):
            found.add(str(path.parent.relative_to(root)))
    return found


def is_shallow(local):
    return run(["git", "-C", str(local), "rev-parse", "--is-shallow-repository"])[1] == "true"


def refresh(entry, local, check, shallow=False):
    """Clone [entry]'s tree at its pinned ref, or fetch it if it is already there."""
    ref = entry.get("ref") or "HEAD"
    if not local.exists():
        if check:
            return "MISSING", f"no checkout at {local}"
        # SHALLOW AND SINGLE-BRANCH. The registry pins one ref and the readers want a
        # working tree, not a history -- except `--records`, which asks UPSTREAM's
        # history and never a fork's, so upstream is the one tree cloned in full --
        # unless --shallow says this machine never asks (backlog #104: the VPS, where
        # QMK's 530 MB of history is disk nothing reads).
        depth = [] if entry.get("upstream") and not shallow else ["--depth", "1"]
        ok, said = run(["git", "clone", *depth, "--branch", ref,
                        entry["url"], str(local)])
        return ("cloned" if ok else "FAILED"), said.splitlines()[-1] if said else ""
    if check:
        ok, head = run(["git", "-C", str(local), "rev-parse", "--short", "HEAD"])
        return ("present" if ok else "NOT A REPO"), f"{ref} at {head}"
    # A FETCH KEEPS THE CLONE'S DEPTH. `fetch --depth 1` on a full clone cuts its
    # history to one commit, which is exactly what `--records` reads -- so only a clone
    # that is already shallow fetches shallow.
    depth = ["--depth", "1"] if is_shallow(local) else []
    ok, said = run(["git", "-C", str(local), "fetch", *depth, "origin", ref])
    if not ok:
        return "FAILED", said.splitlines()[-1] if said else ""
    ok, said = run(["git", "-C", str(local), "checkout", "--force", "FETCH_HEAD"])
    return ("updated" if ok else "FAILED"), said.splitlines()[-1] if said else ""


def report_records(reg):
    """What each non-upstream tree holds that upstream never did. See the module docstring."""
    upstream = {}
    for tid in reg.ids():
        if reg.upstream(tid) and reg.root(tid) and reg.root(tid).is_dir():
            upstream[reg.reader(tid)] = tid

    for tid in reg.ids():
        root = reg.root(tid)
        if reg.upstream(tid) or not root or not root.is_dir():
            continue
        parent = upstream.get(reg.reader(tid))
        if not parent:
            print(f"{tid}: no upstream {reg.reader(tid)} checkout to compare against")
            continue
        up_root = reg.root(parent)
        if is_shallow(up_root.parent):
            # A shallow upstream has no history, so every `git log` below would come back
            # empty and call every record "never existed upstream" -- a wrong answer
            # indistinguishable from a right one. Refuse instead.
            print(f"{tid}: upstream checkout {up_root.parent} is shallow (--shallow); "
                  f"--records needs its history")
            continue
        up, mine = records(up_root), records(root)
        added = sorted(mine - up)
        tops = {r.split("/")[0] for r in up}
        # UPSTREAM'S WORKING TREE IS NOT UPSTREAM'S ANSWER. A record whose path moved
        # (`bastardkb/charybdis/3x5/v1/elitec`) and one upstream deleted are both
        # still upstream's keyboards, and only the history says so.
        outside = [a for a in added if a.split("/")[0] not in tops]
        never = [a for a in outside
                 if not run(["git", "-C", str(up_root.parent), "log", "--oneline", "-1",
                             "--", f"keyboards/{a}"])[1]]
        print(f"\n{tid}  ({reg.entry(tid)['url']} @ {reg.entry(tid).get('ref')})")
        print(f"  {len(mine):5}  records in this tree")
        print(f"  {len(added):5}  upstream's working tree does not have")
        print(f"  {len(added) - len(outside):5}    -- upstream has the keyboard under a moved path")
        print(f"  {len(outside) - len(never):5}    -- upstream had it and removed or renamed it")
        print(f"  {len(never):5}  have never existed upstream at all:")
        for n in never:
            print(f"           {n}")
        print("\n  NOT A DRAFTING QUEUE -- see this script's docstring. A registry entry "
              "exists\n  because a person named ONE design's record; the rest of the fork "
              "is upstream\n  at an older vintage.")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true",
                    help="report what is missing or present, change nothing")
    ap.add_argument("--records", action="store_true",
                    help="what each fork holds that upstream never did")
    ap.add_argument("--tree", help="just this one registered tree")
    ap.add_argument("--shallow", action="store_true",
                    help="clone upstream trees at depth 1 as well; --records then refuses")
    args = ap.parse_args()

    reg = tree_registry.load()
    if args.records:
        return report_records(reg)

    failed = 0
    for tid in reg.ids():
        if args.tree and tid != args.tree:
            continue
        entry, local = reg.entry(tid), reg.local(tid)
        if local is None:
            # `hardware` is a registry entry with no tree behind it -- a row that borrows
            # another row's record. Nothing to check out, and saying so beats skipping it
            # silently, because "no checkout" and "checkout missing" are different facts.
            print(f"{tid:16} --        no tree to check out (reader={reg.reader(tid)})")
            continue
        state, said = refresh(entry, local, args.check, args.shallow)
        failed += state in ("FAILED", "MISSING", "NOT A REPO")
        print(f"{tid:16} {state:9} {local}  {said}")
    return 1 if (failed and not args.check) else 0


if __name__ == "__main__":
    sys.exit(main() or 0)
