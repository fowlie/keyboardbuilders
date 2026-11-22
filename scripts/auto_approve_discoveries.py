#!/usr/bin/env python3
"""Rule `design` on the discovery candidates that are safe, so they need no question.

A discovery question is a yes/no on "is this repo a keyboard design" (#105). Most are yes
(24 of the first 29 answers), and the owner's time is the bottleneck, so a candidate that
shows every sign below is ruled `design` here, recorded as `moderator = "auto"` in
seed/discoveries.toml like any other answer. That ruling publishes nothing: it only
queues the repo for importing. To undo one, change its verdict to `not_design`.

SAFE MEANS ALL OF: a recognised open licence, firmware found in the repo, a board
placing at least MIN_SWITCHES switches, and a description. Tuned on the 29 answers so
far: of those, the rule would have passed 3, and all 3 were designs. It is deliberately
narrow -- a candidate that fails it is asked about as before. `NOASSERTION` is not a
licence: it means GitHub found a licence file it could not name.

Usage: auto_approve_discoveries.py [--dry-run]
"""
import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from intake_answer import append_discovery  # noqa: E402
from intake_ask import DISCOVERED, DISCOVERIES, ruled_discoveries  # noqa: E402

MIN_SWITCHES = 30
BOT = "auto"


def is_safe(c):
    return (c.get("licence") not in (None, "", "NOASSERTION")
            and bool(c.get("firmware"))
            and (c.get("switches") or 0) >= MIN_SWITCHES
            and bool((c.get("description") or "").strip()))


def safe_unruled(found, ruled):
    return [repo for repo, c in sorted(found.get("candidates", {}).items())
            if repo.lower() not in ruled and is_safe(c)]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    found = json.loads(DISCOVERED.read_text()) if DISCOVERED.exists() else {}
    repos = safe_unruled(found, ruled_discoveries())
    for repo in repos:
        print(f"auto: {repo}" + (" (dry run)" if args.dry_run else ""))
        if not args.dry_run:
            append_discovery(DISCOVERIES, repo, "design", BOT)
    print(f"auto-ruled {len(repos)} discovery candidate(s) as design")
    return 0


if __name__ == "__main__":
    sys.exit(main())
