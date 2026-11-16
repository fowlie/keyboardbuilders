#!/usr/bin/env bash
# The intake bot's 10-minute answer check -- backlog #104, piece 6.
#
# Runs intake_answer.py --push. Tells the phone only when it committed something or
# failed: an empty poll every ten minutes would be 144 notifications a day about nothing.
#
# Environment: as scripts/intake_nightly.sh.
set -uo pipefail

cd "$(dirname "$0")/.."

# READ WHOLE BEFORE IT RUNS. This script fast-forwards its own checkout, and bash reads
# a script as it goes -- so a merge that rewrote this file mid-run would have bash
# resume at the old byte offset of the new text. Inside a function, the whole body is
# parsed before the first command, and the final `exit` stops bash reading on.
main() {

  notify() {  # notify PRIORITY TITLE MESSAGE
    [ -n "${NTFY_URL:-}" ] || return 0
    curl -fsS -H "Title: $2" -H "Priority: $1" -H "Tags: keyboard" -d "$3" "$NTFY_URL" >/dev/null \
      || echo "ntfy failed" >&2
  }

  # ONE NOTIFICATION PER FAILURE, not one per poll. A broken config fails every ten
  # minutes until someone fixes it -- the S3 credentials did, 15 identical alerts in a
  # morning. The last failure is kept here; the same one again stays quiet, a new one
  # notifies, and the first success after a failure says so once.
  state=${INTAKE_STATE_DIR:-/var/lib/intake}/poll-failure

  # FAST-FORWARD BEFORE PYTHON STARTS, not only inside it. intake_answer.py --push merges
  # too, but by then Python has loaded the old code, so the first poll after a parser
  # change answered with the old parser (#82, 2026-09-28). A failure here is left for
  # intake_answer.py's own merge to report.
  git fetch --quiet origin && git merge --ff-only --quiet '@{u}' 2>/dev/null

  # Answers from the site's moderator queue (#117).
  if ! out=$(python3 scripts/intake_answer.py --push 2>&1); then
    echo "$out" >&2
    why=$(printf '%s\n' "$out" | grep -v '^[[:space:]]*$' | tail -n 4 | cut -c1-160)
    if [ "$(cat "$state" 2>/dev/null)" != "$why" ]; then
      notify high "Intake answers FAILED" "$why (repeats of this stay quiet)"
      printf '%s' "$why" > "$state" 2>/dev/null || true
    fi
    exit 1
  fi
  if [ -e "$state" ]; then
    rm -f "$state"
    notify default "Intake answers working again" "The last failure is fixed."
  fi
  echo "$out"
  case "$out" in
    *committed*) notify low "Intake answers" "$(printf '%s' "$out" | tail -1)" ;;
  esac
}
main "$@"; exit
