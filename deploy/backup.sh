#!/usr/bin/env bash
# Back up the SQLite database. Forgejo #26.
#
# `sqlite3 .backup`, NOT `cp`. The database is one file, which makes copying it look
# safe and is exactly the trap: a plain copy taken while the app is mid-write yields a
# torn file that may restore, may not, and gives no sign which until the day it matters.
# `.backup` uses the online backup API and is consistent against a live writer.
set -euo pipefail

: "${KB_DATABASE:=/var/lib/keyboardbuilders/app.db}"
: "${KB_BACKUP_DIR:=/var/backups/keyboardbuilders}"
: "${KB_BACKUP_KEEP:=14}"
# GOATCOUNTER IS GONE FROM HERE, 2026-08-30, and its absence is the point. It used to be
# the ONE database on this box holding data that could not be rebuilt: the app's is
# regenerated from the seed on every boot, so losing it costs a restart, while losing the
# counter's cost the traffic history permanently. Moving to hosted GoatCounter removed the
# only irreplaceable file on the machine -- so this script now backs up something that
# does not strictly need backing up, and the VPS is fully disposable.
#
# NO LONGER TRUE, 2026-11-10 (backlog #116). The database now holds what people wrote and
# git cannot rebuild: the designs their author unpublished, and the hearts. These copies
# stay on this box, so the dev machine pulls the newest one off it weekly
# (deploy/dev-machine/pull-backup.sh) and checks those tables are in it.

mkdir -p "$KB_BACKUP_DIR"
stamp=$(date -u +%Y%m%dT%H%M%SZ)

# `name db sentinel-sql minimum` -- the sentinel is what makes the check mean something:
# a file can pass integrity_check and still be an empty schema, which is exactly the
# backup you do not want rotating a good one out.
backup_one() {
    local name="$1" db="$2" sentinel="$3" minimum="$4"
    [ -f "$db" ] || { echo "  $name: no database at $db, skipping"; return 0; }
    local out="$KB_BACKUP_DIR/$name-$stamp.db"
    sqlite3 "$db" ".backup '$out'"

    if ! sqlite3 "$out" "PRAGMA integrity_check;" | grep -qx "ok"; then
        echo "  $name: failed integrity_check, refusing to rotate: $out" >&2
        return 1
    fi
    local count
    count=$(sqlite3 "$out" "$sentinel")
    # An EMPTY count is not zero, it is a failed query -- and `[ "" -lt 1 ]` does not
    # evaluate false, it errors. That is how the first version of this reported success
    # while writing 44-byte files: the backup never happened and nothing said so.
    if [ -z "$count" ] || [ "$count" -lt "$minimum" ]; then
        echo "  $name: sentinel returned $count, refusing to rotate: $out" >&2
        return 1
    fi

    gzip -f "$out"
    echo "  $name: $count rows -> $out.gz"
    ls -1t "$KB_BACKUP_DIR/$name"-*.db.gz 2>/dev/null | tail -n "+$((KB_BACKUP_KEEP + 1))" \
        | xargs -r rm --
}

status=0
backup_one app "$KB_DATABASE" "SELECT count(*) FROM design;" 1 || status=1
exit "$status"
