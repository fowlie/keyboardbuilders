#!/usr/bin/env bash
# Push the nightly run's commit, even when main moved while the run was working (#39).
#
# 2026-11-25: a commit to .github/FUNDING.yml landed during a 40-minute run, the run's
# push was rejected, and its unpushed commit then made every later run fail at the
# fast-forward it starts with. So: fetch, rebase onto main, push; and if main moves
# again in the seconds between, go round again.
#
# The night commits only derived files, so a rebase onto someone's commit almost never
# conflicts. When it does (someone committed derived files by hand), the night's commit
# is dropped and the checkout reset to main: tomorrow's run rebuilds the same files from
# the newer rulings, and a stuck checkout would cost every night until then.
#
# Exit 0 pushed, 1 could not push (left as it is, retried by the next run's start),
# 2 conflict (night dropped, checkout on main).
#
# Usage, from the checkout: scripts/intake_publish.sh [remote] [branch]
set -uo pipefail

remote=${1:-origin}
branch=${2:-main}

for attempt in 1 2 3; do
  git fetch --quiet "$remote" || { echo "fetch from $remote failed" >&2; exit 1; }
  if ! git rebase --quiet "$remote/$branch" >&2; then
    git rebase --abort >/dev/null 2>&1
    git reset --quiet --hard "$remote/$branch"
    echo "conflict rebasing onto $remote/$branch: the night's commit was dropped, the checkout is on $branch"
    exit 2
  fi
  if git push --quiet "$remote" "HEAD:$branch"; then
    exit 0
  fi
  echo "push rejected, attempt $attempt of 3" >&2
done
exit 1
