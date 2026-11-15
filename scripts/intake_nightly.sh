#!/usr/bin/env bash
# The intake bot's nightly run -- backlog #104, piece 6. One line to the owner's phone at
# the end, whatever happened.
#
#   1. pull           the checkout is the bot's whole state; the phone's answers are in it
#   2. trees          QMK/ZMK and the registered forks, every clone at depth 1
#   3. refresh        scripts/refresh_corpus.sh: repo scans, then build/measure to a fixpoint
#   3b. renders       PCB renders for new or changed boards, uploaded (render_boards.py
#                     --stale-only), then renders.json. A failed board notifies, never stops
#   4. photos         re-read READMEs for photo candidates, since designs may be new
#   4b. activity      GitHub stars, last push and archived flag (fetch_activity.py). Never
#                     scheduled until 2026-09-29, so every design after 2026-09-10 had none
#   4c. discover      GitHub repos created lately that hold a KiCad board and are not in
#                     the corpus (discover_repos.py, backlog #105): tomorrow's questions
#   5. commit         the derived files, and ONLY derived files, then push
#   6. ask            queue the day's questions on /moderation (intake_ask.py, capped)
#
# THE COMMIT IS DERIVED OUTPUT ONLY. Rulings arrive through intake_answer.py; this run
# regenerates what follows from them. Anything else showing up in `git status` means a
# script wrote somewhere unexpected, and the run stops before committing it.
#
# THE TESTS GATE THE DEPLOY, NOT THIS SCRIPT. The push runs .github/workflows/deploy.yml,
# which tests before it ships; a red build leaves the site on the previous version.
#
# Environment (systemd's EnvironmentFile=/etc/intake/env): GH_TOKEN (the bot's), NTFY_URL,
# KB_S3_* (photo uploads), QMK_DIR, ZMK_DIR.
set -uo pipefail

cd "$(dirname "$0")/.."

# READ WHOLE BEFORE IT RUNS. This script fast-forwards its own checkout, and bash reads
# a script as it goes -- so a merge that rewrote this file mid-run would have bash
# resume at the old byte offset of the new text. Inside a function, the whole body is
# parsed before the first command, and the final `exit` stops bash reading on.
main() {
  log=$(mktemp)
  trap 'rm -f "$log"' EXIT

  notify() {  # notify PRIORITY TITLE MESSAGE
    [ -n "${NTFY_URL:-}" ] || return 0
    curl -fsS -H "Title: $2" -H "Priority: $1" -H "Tags: keyboard" -d "$3" "$NTFY_URL" >/dev/null \
      || echo "ntfy failed" >&2
  }

  step() {  # step NAME COMMAND...: run it, and on failure tell the phone which step died
    local name=$1; shift
    echo "== $name" >&2
    if ! "$@" 2>&1 | tee -a "$log" >&2; then
      notify high "Intake nightly FAILED at $name" "$(grep -v "^[[:space:]]*$" "$log" | tail -n 4 | cut -c1-160)"
      exit 1
    fi
  }

  # Fetch and fast-forward rather than `git pull`, which a `pull.rebase = true` config turns
  # into a rebase that refuses to run beside any uncommitted file.
  step fetch  git fetch --quiet origin
  step pull   git merge --ff-only --quiet '@{u}'
  step trees  python3 scripts/refresh_trees.py --shallow
  step refresh scripts/refresh_corpus.sh
  # PCB RENDERS, backlog #117 (owner, 2026-11-15): until now only run by hand, so a design
  # the bot unblocked went live with no 3D render. Only new or changed boards
  # (--stale-only), uploaded to object storage; renders.json follows from the manifest.
  # NOT A step: render_boards exits 1 if any one board fails, and one bad board file must
  # not cost the night its commit. The ones that did render still ship.
  echo "== renders" >&2
  if ! python3 scripts/render_boards.py --all --stale-only --upload 2>&1 | tee -a "$log" >&2; then
    notify default "Intake nightly: some PCB renders failed" \
      "$(grep -E 'partial|FAILED|Failed' "$log" | tail -n 3 | cut -c1-160)"
  fi
  step render-seed python3 scripts/build_render_seed.py
  step photos python3 scripts/draft_photo_candidates.py
  step activity python3 scripts/fetch_activity.py
  step discover python3 scripts/discover_repos.py

  derived='^(data/[^/]+\.json|src/main/resources/seed/[^/]+\.json)$'
  changed=$(git status --porcelain | cut -c4-)
  stray=$(printf '%s\n' "$changed" | grep -vE "$derived" | grep -v '^$' || true)
  if [ -n "$stray" ]; then
    notify high "Intake nightly stopped before committing" "Unexpected files changed: $stray"
    exit 1
  fi

  designs_before=$(git show HEAD:src/main/resources/seed/designs.json | python3 -c 'import json,sys;print(len(json.load(sys.stdin)))')
  designs_after=$(python3 -c 'import json;print(len(json.load(open("src/main/resources/seed/designs.json"))))')
  # NEVER LOSE A DESIGN UNATTENDED. A rename keeps the count; a drop means something
  # upstream of the build broke -- the first VPS run fell 333 -> 330 on a repo spelled
  # two ways, and only a person watching the log stopped it. That is a dev-session
  # question, so the run stops here and the checkout is left as it is for a look.
  if [ "$designs_after" -lt "$designs_before" ]; then
    lost=$(python3 scripts/intake_lost_designs.py)
    notify high "Intake nightly stopped: designs would drop" \
      "$designs_before -> $designs_after, not committed. Gone: $lost"
    exit 1
  fi
  if [ -n "$changed" ]; then
    git add -- $changed
    git commit --quiet -m "Refresh the corpus's derived files, nightly" \
      -m "Written by scripts/intake_nightly.sh (backlog #104): refresh_corpus.sh, draft_photo_candidates.py and fetch_activity.py re-run over the current rulings. $designs_before -> $designs_after designs." \
      -- $changed
    step push git push --quiet origin HEAD:main
    what="committed $(git rev-parse --short HEAD), $(printf '%s\n' "$changed" | wc -l) files, designs $designs_before → $designs_after"
  else
    what="nothing changed, $designs_after designs"
  fi

  # Backlog #117: the questions go to the site's moderator queue, not GitHub issues.
  asked=$(python3 scripts/intake_ask.py --site-queue /var/lib/keyboardbuilders/moderation/questions.json 2>>"$log" | tail -1) || {
    notify high "Intake nightly FAILED at ask" "$(grep -v "^[[:space:]]*$" "$log" | tail -n 4 | cut -c1-160)"; exit 1; }

  notify default "Intake nightly" "$what. $asked. https://keyboardbuilders.net/moderation"
}
main "$@"; exit
