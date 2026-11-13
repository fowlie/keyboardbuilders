#!/usr/bin/env bash
# Copy the VPS's newest database backup to this machine. Weekly, from the user timer
# beside this file (Mondays 12:00).
#
# WHY OFF THE BOX, since 2026-11-10 (backlog #116). The database used to be rebuildable
# from git, so a copy on the VPS itself was enough. It now holds what only people can
# recreate: which designs their author unpublished, and the hearts. Lose the VPS and those
# go with it, and the next deploy would put an author's withdrawn page back up.
#
# Each copy is checked before it is kept: a backup nobody opened is the one that turns out
# to be empty.
set -euo pipefail

: "${KB_VPS:=keyboardbuilders.net}"            # an ~/.ssh/config alias for the VPS
: "${KB_REMOTE_DIR:=/var/backups/keyboardbuilders}"
: "${KB_LOCAL_DIR:=$HOME/backups/keyboardbuilders}"
: "${KB_LOCAL_KEEP:=12}"                         # weeks

fail() {
    echo "pull-backup: $*" >&2
    command -v notify-send >/dev/null && notify-send -u critical "keyboardbuilders backup" "$*" || true
    exit 1
}

mkdir -p "$KB_LOCAL_DIR"

newest=$(ssh -o BatchMode=yes -o ConnectTimeout=20 "$KB_VPS" \
    "ls -1t $KB_REMOTE_DIR/app-*.db.gz 2>/dev/null | head -1") || fail "could not reach $KB_VPS"
[ -n "$newest" ] || fail "no backups in $KB_VPS:$KB_REMOTE_DIR"

name=$(basename "$newest")
if [ -f "$KB_LOCAL_DIR/$name" ]; then
    echo "pull-backup: already have $name"
    exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
scp -q -o BatchMode=yes "$KB_VPS:$newest" "$tmp/$name" || fail "copy of $name failed"

gunzip -c "$tmp/$name" > "$tmp/app.db" || fail "$name is not a readable gzip"
[ "$(sqlite3 "$tmp/app.db" 'PRAGMA integrity_check;')" = "ok" ] || fail "$name failed integrity_check"
# The tables that cannot be rebuilt must be there. `unpublished` arrived with migration 8,
# so it is required only once the backup's own schema history says that migration ran.
required="heart"
v8=$(sqlite3 "$tmp/app.db" "SELECT count(*) FROM flyway_schema_history WHERE version = '8' AND success = 1;" 2>/dev/null || echo 0)
[ "$v8" = "1" ] && required="$required unpublished"
for table in $required; do
    sqlite3 "$tmp/app.db" "SELECT count(*) FROM $table;" >/dev/null 2>&1 \
        || fail "$name has no $table table"
done
summary="$(sqlite3 "$tmp/app.db" "SELECT count(*) FROM design;") designs"
for table in $required; do
    summary="$summary, $(sqlite3 "$tmp/app.db" "SELECT count(*) FROM $table;") $table"
done

mv "$tmp/$name" "$KB_LOCAL_DIR/$name"
ls -1t "$KB_LOCAL_DIR"/app-*.db.gz | tail -n "+$((KB_LOCAL_KEEP + 1))" | xargs -r rm --
echo "pull-backup: kept $name ($summary)"
