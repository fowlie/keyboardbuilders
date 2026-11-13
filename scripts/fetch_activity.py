#!/usr/bin/env python3
"""Fetch each source repo's stars, last push and archived flag from GitHub.

Fills the three columns `design_search` has held open since 2026-08-16 and nothing has
ever written: `stars`, `last_commit`, `archived`. They already order the default list
through `design_search_rank`, so this is load-bearing whether or not a page ever renders
a star count -- that display question is ticket 54's and is deliberately untouched here.

WHY ITS OWN FILE AND NOT A FIELD ON designs.json. Aliveness refreshes on a different
clock from the corpus. Facet backfill (ticket 13)
decision 8 makes the aliveness pass a REBUILD on a cron, while `designs.json` only moves
when a human confirms a seed row. Folding one into the other would mean regenerating the
corpus to learn that a repo gained a star, and would make every activity refresh look
like a corpus change in git.

WHY GRAPHQL. 126 distinct repos is 126 REST calls, and unauthenticated GitHub allows 60
an hour. One GraphQL query carries 50 repositories, so the whole corpus is three calls --
and the `gh` CLI supplies the credential, so no token is handled here or stored anywhere.

NULL IS NOT ZERO, AND THAT IS THE WHOLE CARE THIS SCRIPT TAKES. A repo that 404s, is
renamed, or is rate-limited is written as ABSENT, never as `0` stars or an epoch date.
`design_search.stars` carries a comment saying so, SQLite sorts NULLs last under DESC,
and that band -- "no star count" ranking below every known one rather than as if it had
none -- is the behaviour a default-fill would silently destroy.

Usage: fetch_activity.py [--out src/main/resources/seed/activity.json] [--designs FILE]
"""
import argparse
import collections
import json
import pathlib
import re
import subprocess
import sys
from datetime import datetime, timezone

REPO = pathlib.Path(__file__).resolve().parent.parent
GITHUB = re.compile(r"https://github\.com/([^/]+)/([^/#?]+)")

# GitHub caps an aliased query well above this; 50 keeps one failure from costing much.
BATCH = 50


def repos_in(designs):
    """Every distinct owner/name a design cites, in a stable order."""
    found = {}
    for design in designs:
        for source in design.get("sources") or []:
            hit = GITHUB.match(source)
            if hit:
                owner, name = hit.group(1), hit.group(2).removesuffix(".git")
                found[f"{owner}/{name}"] = (owner, name)
    return dict(sorted(found.items()))


def query_for(batch):
    """One aliased query. `gh` supplies the credential, so none appears here."""
    fields = "stargazerCount pushedAt isArchived nameWithOwner"
    parts = [
        f'  r{i}: repository(owner: "{owner}", name: "{name}") {{ {fields} }}'
        for i, (owner, name) in enumerate(batch)
    ]
    return "query {\n" + "\n".join(parts) + "\n}"


def ask_github(batch):
    """Run one batch through `gh api graphql`. Returns alias -> repo object or None.

    A batch that names a dead repo comes back with BOTH `data` and `errors`: GitHub fills
    the aliases it could resolve and reports the rest. So a non-zero exit is not a reason
    to discard the batch -- the partial data is the useful part, and the missing entries
    are exactly the repos that should end up absent.
    """
    done = subprocess.run(
        ["gh", "api", "graphql", "-f", f"query={query_for(batch)}"],
        capture_output=True, text=True,
    )
    if not done.stdout.strip():
        print(f"  gh failed with no output: {done.stderr.strip()[:200]}", file=sys.stderr)
        return {}, False
    payload = json.loads(done.stdout)
    # NO `data` AT ALL is a failed request, not a batch of deleted repos: a bad token
    # answers {"message": "Bad credentials"} with no GraphQL `errors` to inspect.
    trusted = payload.get("data") is not None
    if not trusted:
        print(f"  gh answered without data: {str(payload.get('message', ''))[:120]}",
              file=sys.stderr)
    for problem in payload.get("errors") or []:
        # NOT_FOUND is ordinary here: repos are renamed and deleted, and the corpus is a
        # snapshot. Anything else is worth seeing -- and makes the batch's silences
        # untrustworthy, because a rate limit is not a deleted repo.
        if problem.get("type") != "NOT_FOUND":
            trusted = False
            print(f"  {problem.get('type')}: {problem.get('message', '')[:120]}",
                  file=sys.stderr)
    # Keyed by the alias index, so the caller can map an answer back to what it ASKED
    # for. Keying by GitHub's `nameWithOwner` instead loses repos silently: GitHub
    # answers in the repository's CANONICAL casing, and the seed cites whatever the
    # firmware record wrote -- `josefadamcik/soflekeyboard` comes back as
    # `josefadamcik/SofleKeyboard`. 57 of 126 differ by case alone.
    return {int(k[1:]): v for k, v in (payload.get("data") or {}).items() if v}, trusted


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--designs", default="src/main/resources/seed/designs.json")
    ap.add_argument("--out", default="src/main/resources/seed/activity.json")
    args = ap.parse_args()

    designs = json.loads((REPO/args.designs).read_text())
    wanted = repos_in(designs)
    print(f"{len(designs)} designs cite {len(wanted)} distinct GitHub repos")

    # A FAILED BATCH KEEPS WHAT WE KNEW. Absent means GitHub said NOT_FOUND, never that
    # the question went unanswered: before 2026-09-29 one `gh` failure wrote a whole batch
    # of 50 as absent, and once this ran nightly and unattended that would have committed
    # the stars off the site.
    out_path = REPO/args.out
    previous = (json.loads(out_path.read_text()).get("repos") or {}) if out_path.exists() else {}
    items = list(wanted.values())
    activity, why = {}, collections.Counter()
    for start in range(0, len(items), BATCH):
        batch = items[start:start + BATCH]
        answers, trusted = ask_github(batch)
        if not trusted:
            for owner, name in batch:
                if (asked := f"{owner}/{name}") in previous:
                    activity[asked] = previous[asked]
                    why["kept from the last run -- the batch failed"] += 1
        for index, repo in answers.items():
            asked = f"{batch[index][0]}/{batch[index][1]}"
            activity[asked] = {
                "stars": repo["stargazerCount"],
                # ISO-8601 straight from GitHub. `last_commit` is a slight misnomer
                # inherited from the schema: pushedAt is the last PUSH, which is the
                # aliveness signal wanted, and is not the authoring date of a commit.
                "lastPush": repo["pushedAt"],
                "archived": repo["isArchived"],
                # What GitHub calls it TODAY. Recorded only when it differs from what we
                # asked for, which makes a rename visible rather than invisible: 12 repos
                # in the corpus answer to a name that is not the one the seed cites, and
                # they resolve only because GitHub redirects. That is a fact about the
                # seed worth surfacing, not something to normalise away.
                **({"renamedTo": repo["nameWithOwner"]}
                   if repo["nameWithOwner"].lower() != asked.lower() else {}),
            }
        print(f"  {start + len(batch)}/{len(items)}")

    missing = sorted(set(wanted) - set(activity))
    why["answered"] = len(activity)
    why["no answer -- absent, NOT zero"] = len(missing)

    out = {
        # Stamped so staleness is a fact the data carries rather than something a reader
        # has to infer. Ticket 54's third question is exactly how honest a displayed
        # number is allowed to be about its own age; this is the number it needs.
        "fetchedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "repos": dict(sorted(activity.items())),
    }
    path = REPO/args.out
    path.write_text(json.dumps(out, indent=1, sort_keys=False))

    print(f"\n{out['fetchedAt']} -> {args.out}")
    for reason, n in why.most_common():
        print(f"  {n:>4}  {reason}")
    if missing:
        print(f"        e.g. {', '.join(missing[:5])}")
    renamed = {k: v["renamedTo"] for k, v in activity.items() if "renamedTo" in v}
    if renamed:
        print(f"\n{len(renamed)} repos answer to a different name now (GitHub redirects):")
        for old, new in list(renamed.items())[:5]:
            print(f"  {old}  ->  {new}")

    if activity:
        ranked = sorted(activity.items(), key=lambda kv: -kv[1]["stars"])
        print("\nmost-starred in the corpus:")
        for name, facts in ranked[:5]:
            print(f"  {facts['stars']:>6}  {name}")
        archived = [n for n, f in activity.items() if f["archived"]]
        print(f"\n{len(archived)} archived repos" + (f": {', '.join(archived[:4])}" if archived else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
