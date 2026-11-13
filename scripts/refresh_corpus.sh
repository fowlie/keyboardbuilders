#!/usr/bin/env bash
# Re-measure the whole corpus and rebuild what the site serves, in the one order that works.
#
# Backlog #104, piece 1. Until now this order was only ever in someone's head, and it is
# not obvious, because the build and the measurements feed each other:
#
#   build_seed.py  writes designs.json, design-boards.json and design-trees.json
#   measure_*.py   read those three, and write the *-scan.json files
#   build_seed.py  reads the *-scan.json files back in
#
# So one build is never enough after a seed change: a newly confirmed row gets its board
# attributed by the first build, measured by the scans, and only gains its measured
# fields on the second. A second build can itself publish a design the first withheld
# (a scan filled the field it was deferred on), and that design has not been measured
# yet -- so the measure-and-build pair repeats until designs.json stops changing.
#
# Three stages:
#
#   1. repo scans      what each confirmed source repo holds. Every one caches, so a
#                      re-run only asks about repos it has not seen. --no-repo-scans
#                      skips this stage and trusts the caches as they are.
#   2. build/measure   build, measure, build, until designs.json is stable. NOT offline:
#                      the measure scripts fetch any attributed board they have not
#                      cached, and measure_key_pitch.py --write re-reads every board at
#                      upstream HEAD (its own docstring says so), so a repo that moved
#                      can move a pitch. Review the scan diffs before committing them.
#   3. drawings        silhouettes.json and layout_silhouettes.json, from the final build.
#
# Stage 1 starts by filling source-trees.json (fetch_source_trees.py), which is gitignored,
# so a fresh checkout -- the VPS -- has none, and which nothing else fills for confirmed
# rows.
#
# Not run here, because build_seed.py reads none of their output: measure_usb.py,
# measure_mcu_package.py, measure_3d_resolution.py (renders need podman and KiCad), and
# the draft_*.py queues (they propose rulings; they are not measurements).
#
# Usage: scripts/refresh_corpus.sh [--no-repo-scans]
#   QMK_DIR and ZMK_DIR override the firmware checkouts (default ~/code/qmk_firmware and
#   ~/code/zmk), so the VPS can keep them somewhere else.
set -euo pipefail

cd "$(dirname "$0")/.."

repo_scans=true
case "${1:-}" in
  --no-repo-scans) repo_scans=false ;;
  "") ;;
  *) echo "usage: $0 [--no-repo-scans]" >&2; exit 2 ;;
esac

qmk="${QMK_DIR:-$HOME/code/qmk_firmware}"
zmk="${ZMK_DIR:-$HOME/code/zmk}"
designs=src/main/resources/seed/designs.json
max_rounds=4

step() { printf '\n== %s\n' "$*" >&2; }

if $repo_scans; then
  step "1. repo scans"
  # The file listings every scan below reads. Nothing else fills this gitignored cache
  # for confirmed rows, and a repo missing from it scans as a repo with no boards.
  python3 scripts/fetch_source_trees.py
  # A board file that changed upstream is forgotten by every cache that read it, so the
  # scans below read it again (Forgejo #142: Sango drew from a board it no longer has).
  python3 scripts/check_board_freshness.py
  # A path that 404s is a finding for a person, not a reason to stop measuring the
  # rest, so that exit status is reported and not fatal -- but a crash is a crash.
  if ! paths_out=$(python3 scripts/check_source_paths.py --quiet 2>&1); then
    printf '%s\n' "$paths_out" >&2
    if grep -q Traceback <<<"$paths_out"; then
      echo "check_source_paths crashed" >&2; exit 1
    fi
    echo "check_source_paths: some source paths are gone (listed above)" >&2
  fi
  # Boards an Ergogen repo describes but does not commit, generated before the board
  # scan so it reads them like committed ones. Per-repo failures land in the manifest.
  python3 scripts/generate_ergogen.py
  python3 scripts/scan_boards.py
  python3 scripts/scan_switch_positions.py
  python3 scripts/scan_licences.py
  python3 scripts/scan_boms.py
  python3 scripts/check_source_ancestry.py
fi

build() { python3 scripts/build_seed.py --qmk "$qmk" --zmk "$zmk"; }

measure() {
  python3 scripts/measure_key_positions.py
  python3 scripts/measure_keycaps.py
  python3 scripts/measure_key_pitch.py --write
  python3 scripts/measure_extras.py --qmk "$qmk" --zmk "$zmk"
  python3 scripts/measure_build_difficulty.py
  python3 scripts/measure_switch_sockets.py
  python3 scripts/measure_layout_names.py --zmk "$zmk"
  # These two read designs.json itself, not only design-boards.json, so they must
  # follow a build.
  python3 scripts/measure_splay.py
  python3 scripts/measure_stagger.py
  # These three read only what their caches lack, and were never scheduled: a design the
  # bot added had no switch mounts, exposed-components or power-switch answer (2026-10-04).
  python3 scripts/measure_switch_pads.py
  python3 scripts/measure_exposed_components.py
  python3 scripts/measure_power_switch.py
}

# REFILL WHAT THE FRESHNESS CHECK FORGOT BEFORE THE FIRST BUILD. The build reads the
# keycap and position readings to accept a board's key count (yosino58 places 118
# footprints for 58 keys), so building with them missing drops designs, and the families
# check then stops the run. These only read what their caches lack, against the last
# run's design-boards.json, so on a quiet night they cost nothing.
step "2. refill readings of changed boards"
python3 scripts/measure_key_positions.py
python3 scripts/measure_keycaps.py
python3 scripts/measure_switch_pads.py
python3 scripts/measure_exposed_components.py

step "2. build, measure, build"
build
round=1
while :; do
  before=$(sha256sum "$designs")
  step "2. measure and rebuild, round $round"
  measure
  build
  [ "$(sha256sum "$designs")" = "$before" ] && break
  if [ "$round" -ge "$max_rounds" ]; then
    echo "designs.json still changing after $max_rounds rounds -- a scan and the build disagree" >&2
    exit 1
  fi
  round=$((round + 1))
done

step "3. drawings"
python3 scripts/build_silhouettes.py --qmk "$qmk" --zmk "$zmk"

step "done: designs.json stable after $round round(s)"
