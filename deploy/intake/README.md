# The intake bot on the VPS

Backlogs #104, #117. Every night the bot refreshes the corpus and queues up to 20 questions
on the site's /moderation page (`/var/lib/keyboardbuilders/moderation/questions.json`). A
moderator answers each with a button; every ten minutes the bot reads the answers from the
site's database and commits them to main, which deploys. No LLM is involved at any step.
Each run ends with a notification on ntfy. The bot needs no GitHub account of its own
(since 2026-11-15): it pushes with a deploy key and reads GitHub with the owner's read-only
token.

| unit | what | notifies |
|---|---|---|
| `intake-nightly.timer` → `scripts/intake_nightly.sh` | pull, `refresh_trees.py --shallow`, `refresh_corpus.sh`, `draft_photo_candidates.py`, commit derived files, push, `intake_ask.py` | always, high priority on failure |
| `intake-answer.timer` → `scripts/intake_poll.sh` | `intake_answer.py --push`: answers from /moderation | only when it commits, or fails |

The two runs share `/var/lib/intake/lock`, so only one of them writes to the checkout at a
time.

## Install, once

Run as root on the VPS.

```sh
apt install git python3 curl imagemagick gh util-linux

useradd --system --create-home --home-dir /srv/intake --shell /usr/sbin/nologin intake
install -d -o intake -g intake -m 700 /var/lib/intake
install -d -m 750 -g intake /etc/intake

# The checkout sits where seed/trees.toml's `~/code/...` paths resolve for this user,
# so no QMK_DIR/ZMK_DIR override is needed. It pushes over SSH with a deploy key with
# write access, /srv/intake/.ssh/deploy_ed25519 (added to the repo 2026-11-15).
sudo -u intake git clone git@github.com:fowlie/keyboardbuilders.git \
    /srv/intake/code/keyboardbuilders
sudo -u intake git -C /srv/intake/code/keyboardbuilders config user.name  intake-bot
sudo -u intake git -C /srv/intake/code/keyboardbuilders config user.email intake-bot@noreply.keyboardbuilders.net
sudo -u intake git -C /srv/intake/code/keyboardbuilders config core.sshCommand \
    "ssh -i /srv/intake/.ssh/deploy_ed25519 -o IdentitiesOnly=yes"
```

**Ergogen, rootless.** `scripts/generate_ergogen.py` runs a repo's Ergogen config in a
container, and that container runs the repo author's footprint JavaScript. So it is
rootless podman as `intake`, never `intake` in the `docker` group, which is root on this
host. A `--system` user gets no subordinate ids, so give it some; the range must not
overlap fowlie's (100000-165535). Lingering gives `intake` a user runtime directory, which
rootless podman wants and a `User=` service does not otherwise have.

```sh
apt install podman uidmap
usermod --add-subuids 200000-265535 --add-subgids 200000-265535 intake
loginctl enable-linger intake
```

Done on the VPS 2026-09-29 (Debian 13, podman 5.4.2), and checked in the service's own
context -- `systemd-run --uid=intake --property=EnvironmentFile=/etc/intake/env` -- not a
login shell: `generate_ergogen.py` built the 4.1.0 image and produced jyap808/pando58's
left board byte-identical (bar its date line) to the dev machine's. A failure still lands
in `generated-boards/manifest.json` as that repo's `error` and the run carries on.

`/etc/intake/env`, mode 640, owner `root:intake`. **Never commit it.** It holds the owner's
token, the object-storage keys, and the ntfy topic, which anyone who knows the name can read.

```sh
NTFY_URL=https://ntfy.sh/<the owner's topic>
GH_TOKEN=...           # the owner's fine-grained token, read-only: GitHub API reads for
                       # fetch_activity.py and discover_repos.py, nothing that writes
KB_S3_ACCESS_KEY=...   # photo uploads, as in ~/.config/keyboardbuilders/objectstorage.env
KB_S3_SECRET_KEY=...
```

Then:

```sh
cp /srv/intake/code/keyboardbuilders/deploy/intake/intake-*.{service,timer} /etc/systemd/system/
systemctl daemon-reload
systemctl start intake-nightly.service     # the first run by hand; watch it
journalctl -u intake-nightly -f
systemctl enable --now intake-nightly.timer intake-answer.timer
```

The first nightly run clones QMK and ZMK at depth 1 (a few hundred MB, not QMK's 530 MB of
history) and re-downloads every attributed board, so expect it to take longer than later
runs.
