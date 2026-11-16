#!/bin/sh
# The ONLY thing the deploy key can do. Backlog #71.
#
# Installed at /usr/local/bin/kb-ship on the VPS and named in authorized_keys as a forced
# command, so a connection with that key runs THIS and nothing else -- the argument the
# client sends arrives in SSH_ORIGINAL_COMMAND and is treated as data, never as a command.
#
# That is what makes CI holding an ssh key acceptable: the key cannot run a shell, cannot
# read the corpus, and cannot install an arbitrary file. It can say "run this image tag",
# and the tag is validated below before anything acts on it.
set -eu

IMAGE="${SSH_ORIGINAL_COMMAND:-}"
COMPOSE_DIR=/home/fowlie/keyboardbuilders

# VALIDATED, NOT TRUSTED. Without this the "argument" is a string from the internet that
# reaches a shell -- the forced command would stop `ssh host rm -rf /` and would not stop
# an image name containing a semicolon. The pattern admits exactly our own registry path
# and a 7-to-40 character hex tag, which is what the workflow sends. Two registries: GitHub's,
# where the GitHub Actions workflow pushes, and Forgejo's, kept for rolling back to a tag
# built before the move.
case "$IMAGE" in
  ghcr.io/fowlie/keyboardbuilders:[0-9a-f]*|forgejo.keyboardbuilders.net/fowlie/keyboardbuilders:[0-9a-f]*)
    tag=${IMAGE##*:}
    case "$tag" in
      *[!0-9a-f]*) echo "refused: tag is not hex: $tag" >&2; exit 2 ;;
    esac
    [ ${#tag} -ge 7 ] && [ ${#tag} -le 40 ] || { echo "refused: tag length" >&2; exit 2; }
    ;;
  *) echo "refused: not an image this host deploys: $IMAGE" >&2; exit 2 ;;
esac

# THE CUTOVER HAPPENS HERE, NOT IN A HUMAN'S HANDS, and that is a correction rather than
# a convenience. The first version of this file only REFUSED while the systemd unit held
# 172.17.0.1:8080, which left the operator to stop the unit themselves and then hope the
# deploy worked. On 2026-09-07 it did not -- the deploy failed on an ssh port -- and
# keyboardbuilders.net served 502 for five minutes with Googlebot crawling through it.
#
# A port conflict cannot be avoided (one address, two servers), so the window cannot be
# zero. What it can be is SHORT and SELF-HEALING: stop the unit, start the container, and
# if the container never reports healthy, put the unit back. The failure mode is then a
# few seconds of 502 and a red build, rather than an outage that waits for someone to
# notice. `docker compose pull` runs BEFORE the unit is stopped for the same reason: the
# slow part of a deploy has no business happening inside the window.
UNIT_WAS_UP=no
if systemctl is-active --quiet keyboardbuilders 2>/dev/null; then
  UNIT_WAS_UP=yes
fi

restore_unit() {
  if [ "$UNIT_WAS_UP" = yes ]; then
    echo "restoring the systemd unit" >&2
    sudo systemctl start keyboardbuilders || true
  fi
}

# KEEP TWO IMAGES OF OUR OWN: the one now running and the one in .env.previous, which is
# the fast rollback. Anything older is a git revert and a fresh build away, and at ~550 MB
# a tag and a dozen deploys a day, the weekly cron prune let the disk reach 90% on
# 2026-11-16. Runs only after a HEALTHY deploy -- a failed one keeps everything for
# diagnosis -- and never fails the deploy itself.
prune_old_images() {
  keep_prev=$(sed -n 's/^KB_IMAGE=//p' .env.previous 2>/dev/null || true)
  docker images --format '{{.Repository}}:{{.Tag}}' \
    | grep -E '^(ghcr\.io|forgejo\.keyboardbuilders\.net)/fowlie/keyboardbuilders:' \
    | grep -vxF -e "$IMAGE" -e "${keep_prev:-$IMAGE}" \
    | xargs -r docker rmi >/dev/null 2>&1 || true
  docker image prune -f >/dev/null 2>&1 || true
  echo "kept images: $IMAGE ${keep_prev:-(no previous)}"
}

cd "$COMPOSE_DIR"

# PULL FIRST, WHILE THE OLD SERVER IS STILL SERVING. This is the slow step and the one
# most likely to fail (a registry hiccup, a bad tag), so it must happen outside the
# window. A pull failure here costs nothing: the unit is still up and the site is still
# answering.
#
# AND BEFORE .env IS TOUCHED. The tag is handed to compose in the environment, which
# outranks .env. Until 2026-11-16 .env was rewritten first, so a failed pull (a containerd
# "failed commit on ref" that a retry cleared) left .env naming an image that never ran
# while the script said "nothing was changed". The next deploy then recorded that tag as
# the rollback, and prune_old_images deleted the real one.
if ! KB_IMAGE="$IMAGE" docker compose pull; then
  echo "FAILED to pull $IMAGE; nothing was changed" >&2
  exit 1
fi

# THE PREVIOUS TAG IS RECORDED BEFORE IT IS REPLACED, and this is the rollback that today
# does not really have one: the current scheme copies the old jar to /tmp, which clears on
# reboot, and only if someone remembered to do it. Here the last-known-good tag is on disk
# and `docker compose up -d` with it in .env is the whole undo.
if [ -f .env ]; then
  grep '^KB_IMAGE=' .env > .env.previous 2>/dev/null || true
fi

printf 'KB_IMAGE=%s\n' "$IMAGE" > .env

if [ "$UNIT_WAS_UP" = yes ]; then
  echo "stopping the systemd unit to free 172.17.0.1:8080 (one-time cutover)" >&2
  sudo systemctl disable --now keyboardbuilders

  # AND WAIT FOR THE PORT, because `--now` returns when systemd has SENT the signal, not
  # when the JVM has let go. The unit allows 30s to drain in-flight requests
  # (TimeoutStopSec=30s, server.shutdown=graceful), so the address stays bound after this
  # command succeeds. The first cutover proved it: the container could not bind, exited,
  # and `restart: unless-stopped` retried 37 times before the port came free. It ended up
  # running, so nothing looked wrong -- which is exactly why it is worth fixing.
  i=0
  while [ $i -lt 40 ]; do
    ss -ltn 2>/dev/null | grep -q '172\.17\.0\.1:8080' || break
    i=$((i + 1)); sleep 1
  done
  if [ $i -ge 40 ]; then
    echo "FAILED: 172.17.0.1:8080 still bound 40s after stopping the unit" >&2
    restore_unit
    exit 1
  fi
  echo "  port released after ${i}s" >&2
fi

if ! docker compose up -d --remove-orphans; then
  echo "FAILED to start the container" >&2
  restore_unit
  exit 1
fi

# WAIT FOR HEALTHY, and fail the deploy if it never gets there. compose.yml's healthcheck
# fetches a PAGE and greps it, so this waits on a render rather than on a port -- trap 2
# in deploy/README.md is a failure at first render that a liveness probe sails past.
i=0
while [ $i -lt 60 ]; do
  state=$(docker inspect -f '{{.State.Health.Status}}' keyboardbuilders 2>/dev/null || echo starting)
  case "$state" in
    healthy) echo "deployed $IMAGE"; prune_old_images; exit 0 ;;
    unhealthy) break ;;
  esac
  i=$((i + 1)); sleep 5
done

echo "FAILED to become healthy; rolling back" >&2
docker logs keyboardbuilders --tail 40 >&2 || true
if [ -s .env.previous ]; then
  cp .env.previous .env
  docker compose up -d
  echo "rolled back to $(cat .env.previous)" >&2
else
  # NOTHING TO ROLL BACK TO means this was the cutover itself, so the old server is the
  # rollback. Stop the container first: it and the unit want the same address, and a unit
  # that cannot bind is not a restoration.
  docker compose down || true
  restore_unit
fi
exit 1
