# Deploying keyboardbuilders.net

Forgejo #26. One
jar, one systemd unit, a reverse proxy in front, and a backup timer that runs before there
is anything worth losing.

**This is installed and running.** The units are on the box, the timer is enabled, and
`https://keyboardbuilders.net` serves the corpus. The paragraph that used to stand here said
the VPS steps had never been run; it was written before the install and is kept only as a
line in the history.

Everything below is therefore a procedure that has been executed, not a plan — which is
why the two stale expected values in **Verify** were worth correcting rather than trusting.

## The decision this encodes: one artefact, not two

Ticket 26 weighed shipping a prebuilt `app.db` beside the jar against rebuilding from the
seed on boot. **The jar rebuilds**, because the seed is already inside it: there is then no
way to ship a jar and a database that disagree about the corpus, and a deploy is a single
file copy. Measured on the real seed, the import costs **192 ms** and the whole boot **2.4 s**,
so the argument for shipping a database was never about speed.

The day user accounts exist this flips: `keyboardbuilders.import-on-startup=false`, and the
deploy grows a second artefact. Until then there are no rows a rebuild could destroy.

## What the three traps were

All three come from ticket 26 and all three are handled in `application-prod.properties`:

1. **A relative database path.** `jdbc:sqlite:./app.db` resolves against the working
   directory, which under systemd is `/`. That does not fail — it creates an empty database,
   migrates it, and serves **zero designs with a 200**. The path is now absolute and comes
   from `KB_DATABASE`, so the answer cannot depend on how the process was launched.
2. **The dev profile.** It turns on jte development mode, which needs the Kotlin compiler
   that the packaged jar does not carry. The failure is at the **first render**, not at
   boot, so a smoke test that only checks the process is alive would pass. If a deploy 500s
   on every page, check this first.
3. **Nothing faces the internet directly.** `server.address=${KB_ADDRESS:127.0.0.1}`
   — and in production `KB_ADDRESS=172.17.0.1`, the docker bridge, because Caddy is a
   container and cannot reach host loopback. So the port is not on loopback on this box,
   and `ufw` is what closes it: `8080/tcp ALLOW 172.16.0.0/12  # caddy->keyboardbuilders`.
   **Every verify command below therefore uses the bridge address, not `127.0.0.1`** —
   curling loopback on the server gets `Connection refused` on a perfectly healthy
   deploy, which is a false alarm worth not raising twice.

## The proxy config lives here now

`deploy/Caddyfile` is a copy of `/home/fowlie/caddy/Caddyfile` on the VPS, and it is in
this repository for the same reason the systemd unit is: **a config that exists only in
production is a config nobody can review, and it goes wrong silently.**

That is not hypothetical. Twice in one day: `KB_ADDRESS` was hand-edited to the docker
bridge gateway in production while this repo shipped `127.0.0.1`, and installing the repo's
copy took the site down. Then a security-header block aimed at `keyboardbuilders.net` landed
on `forgejo.keyboardbuilders.net`, because **`keyboardbuilders.net {` is a suffix of every
subdomain block in that file** and a first-match replace found forgejo first. Both were
invisible until something was tested.

Edit it here, copy it up, `caddy validate`, then `caddy reload` — and check every vhost
afterwards, not just the one you changed.

The host is spelled out and the `command` prefix is not decoration. `VPS:` used to stand
here as though it were an ssh alias and cost a round trip on 2026-09-08 (`Could not resolve
hostname vps`), and a bare `ssh host` with no command opens an interactive shell, so the
remaining lines of a pasted block get typed into it.

The `command` prefix is now belt-and-braces rather than load-bearing. It was required
because the fish-to-bash bridge in `~/.config/fish/conf.d/zz_omarchy.fish` interpolated its
arguments unquoted, so every quote in a remote command died before bash saw it and the
redirect ran on the laptop. **That was fixed on 2026-09-08** — the bridge single-quotes each
argument — but the prefix is kept here because it costs nothing and this block is the one
place a quoted remote command is pasted from.

```sh
scp deploy/Caddyfile <vps>:/home/fowlie/caddy/Caddyfile
command ssh <vps> 'sudo docker exec caddy caddy validate --config /etc/caddy/Caddyfile'
command ssh <vps> 'sudo docker exec caddy caddy reload  --config /etc/caddy/Caddyfile'
for h in keyboardbuilders.net forgejo.keyboardbuilders.net statlite.keyboardbuilders.net; do
  curl -s -o /dev/null -w "$h %{http_code}\n" "https://$h/"
done
```

`statlite` answers **401** — its basic auth — and `dev` answers **502** unless a local
instance is up on 8081. Neither is a failed reload, and both look like one at a glance.

Copy up a whole reviewed file rather than editing in place, and diff before you do: on
2026-09-08 the live file was confirmed byte-identical to the repo copy first, which is the
check that would have caught the `KB_ADDRESS` drift below. `caddy validate` warns that the
input is not formatted; that warning is about whitespace and is not a reason to stop.

## One OS dependency, and it is not obvious

The share cards (`/designs/:slug/og.png`) are drawn with Java2D, which needs font libraries
that `openjdk-*-jre-headless` does not pull in. Without them every card is a **500**, and
the page still renders fine — so nothing looks broken until a crawler fetches the image:

```sh
sudo apt-get install -y libharfbuzz0b libfreetype6 libfontconfig1 fonts-dejavu-core
```

The failure names `libharfbuzz.so.0` and is the whole diagnosis.

## Install

```sh
# 1. A user that owns nothing else.
sudo useradd --system --home /var/lib/keyboardbuilders --shell /usr/sbin/nologin keyboardbuilders
sudo mkdir -p /opt/keyboardbuilders /var/lib/keyboardbuilders /var/backups/keyboardbuilders
sudo chown keyboardbuilders:keyboardbuilders /var/lib/keyboardbuilders /var/backups/keyboardbuilders

# 2. The artefacts.
./gradlew bootJar
scp build/libs/keyboardbuilders-*-SNAPSHOT.jar VPS:/tmp/kb.jar
sudo install -m 0644 /tmp/kb.jar /opt/keyboardbuilders/keyboardbuilders.jar
sudo install -m 0755 deploy/backup.sh /opt/keyboardbuilders/backup.sh

# 3. The units.
sudo install -m 0644 deploy/keyboardbuilders.service /etc/systemd/system/
sudo install -m 0644 deploy/keyboardbuilders-backup.service /etc/systemd/system/
sudo install -m 0644 deploy/keyboardbuilders-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now keyboardbuilders.service keyboardbuilders-backup.timer
```

## Verify, and verify the render rather than the process

A service that is `active (running)` proves nothing — trap 2 fails at first render.

```sh
systemctl status keyboardbuilders
sudo journalctl -u keyboardbuilders -n 30 --no-pager | grep Imported
curl -sS -o /dev/null -w '%{http_code}\n' http://172.17.0.1:8080/designs
curl -sS http://172.17.0.1:8080/designs | grep -c 'silhouette'
```

The last one is the real check: it proves a template rendered, not merely that a port
answered. **Expect one per row on the page, so `Page.DEFAULT_SIZE` of them — 50 today.**
An earlier version of this file said 100, which **was** the page size: Forgejo #12 measured
it at 57 ms on a throttled swap and #14 shipped it, then the owner moved it to 50 on
2026-08-30 because a hundred rows is a lot to scroll. `Page`'s own class comment carries
that history. A stale expected number turns a healthy deploy into a bug hunt, so read the
constant rather than this file.

The `Imported` line should name the same count as `designs.json`, whatever that is on the
day — 275 as of `c65eb25`. Don't hard-code it here; `python3 -c 'import json;
print(len(json.load(open("src/main/resources/seed/designs.json"))))'` is the number, and
the point of the check is that the two agree.

## Push-to-deploy (backlog #71) — the container path

A push to `main` runs `.github/workflows/deploy.yml` on GitHub's runners: build the jar,
run the tests, build the image, push it to `ghcr.io/fowlie/keyboardbuilders:<sha>`, then
tell the VPS to run that tag. Pull requests run `.github/workflows/test.yml`, which tests
and holds no secrets.

### Why the build is not on this box

The VPS has 3.8 GB and already runs Forgejo, Postgres and Caddy. A Gradle plus Kotlin build
wants ~1.5 GB, so building here risks OOM-killing the site in order to deploy the site. The
box only ever pulls an image and restarts a container.

Until the move to GitHub (2026-11) the build ran on a Forgejo runner on the owner's
machine. That cannot carry over: a self-hosted runner on a public repo runs whatever a
stranger's pull request contains, on that machine.

### What it fixes, in one line

The jar is built **from the commit being deployed**. On 2026-09-07 a stale jar shipped
twice in one afternoon, because building and shipping were separate manual acts with
nothing tying them together. Staleness stops being unlikely and becomes unrepresentable.

### Setup, once

**1. The image.** Nothing to set up: the workflow logs in to ghcr.io with its own
`GITHUB_TOKEN`. The image carries an `org.opencontainers.image.source` label naming the
repo, so the package takes the repo's visibility, public, and the VPS pulls without a
login. After the first deploy, check the package page says *Public*; if not, change it
under the package's settings.

**2. Repository secrets** (Settings → Secrets and variables → Actions). That is not
Settings → *Deploy keys*, which holds PUBLIC keys allowed to clone or push and correctly
refuses a private one. None of these is called `DEPLOY_KEY` so the name cannot send you
there.

| secret | what it is |
|---|---|
| `VPS_SSH_KEY` | private half of `~/.ssh/kb-deploy`, whole, BEGIN/END lines included |
| `VPS_HOST` | `user@<the VPS's address>` |
| `VPS_SSH_PORT` | the VPS's sshd port, which is not 22 |

**3. On the VPS:**

```sh
mkdir -p ~/keyboardbuilders && cp compose.yml ~/keyboardbuilders/
sudo install -m 0755 ship.sh /usr/local/bin/kb-ship

# The key can run kb-ship and NOTHING else. The tag it asks for arrives in
# SSH_ORIGINAL_COMMAND as data, and kb-ship validates it before acting.
cat >> ~/.ssh/authorized_keys <<'KEY'
command="/usr/local/bin/kb-ship",no-agent-forwarding,no-port-forwarding,no-pty,no-X11-forwarding ssh-ed25519 AAAA... deploy@ci
KEY
```

That restriction is what makes CI holding an ssh key acceptable: the key cannot open a
shell, cannot read the corpus, and cannot install an arbitrary file. It can say "run this
image tag", and only for an image in our own registry with a hex tag.

**4. The cutover — just push.** `kb-ship` does it, and it is deliberately not a manual
sequence any more.

The unit and the container both want `172.17.0.1:8080`, so a window is unavoidable — one
address cannot hold two servers. What is avoidable is a LONG window and a manual one. The
first version of this told the operator to stop the unit and then push; on 2026-09-07 the
deploy that followed failed on an ssh port, and the site served 502 for five minutes with
Googlebot crawling through it. So the order is now fixed in code:

  1. `docker compose pull` — the slow, failure-prone step, run **while the old server is
     still serving**. A bad tag or a registry hiccup costs nothing.
  2. stop the unit
  3. start the container
  4. wait for healthy — and **if it never is, `docker compose down` and start the unit
     again**, because on the cutover itself the old server IS the rollback

The window is therefore a container start, and a failure costs seconds plus a red build
rather than an outage that waits to be noticed. `sudo` inside `kb-ship` works without a
tty, which was checked rather than assumed.

### What supervises what, after the cutover

**The app has no systemd unit any more — docker does that job.** But systemd has not gone
away, and the division is worth being able to state:

| | |
|---|---|
| the app | the `keyboardbuilders` container, `restart: unless-stopped` |
| the container across reboots | `docker.service` / `docker.socket`, both `enabled` — that is what makes `unless-stopped` survive a boot |
| the actuator for StatLite | published to `127.0.0.1:9091` by the compose file, and still answering 200 |
| the nightly backup | `keyboardbuilders-backup.timer`, untouched — it reads `/var/lib/keyboardbuilders/app.db`, which is the same bind-mounted file |

**`keyboardbuilders.service` is disabled, not deleted, and that is deliberate.** The unit
file and `/opt/keyboardbuilders/keyboardbuilders.jar` both remain, so the pre-container
deployment is intact as a last-resort fallback:

```sh
cd ~/keyboardbuilders && docker compose down
sudo systemctl enable --now keyboardbuilders
```

That serves whatever jar is on disk, which is by now an old corpus — so it is a way to get
a site up, not a way to get the right site up. Reach for the tag rollback below first.

**It must never be `enabled` while the container runs.** Both bind `172.17.0.1:8080`, and
the loser of that race is whichever starts second. `kb-ship` refuses to deploy while the
unit is active, so the failure is loud rather than a silent 502.

### Rollback

`ship.sh` records the previous tag to `.env.previous` **before** replacing it, waits for
the container to report healthy, and rolls back by itself if it does not. By hand:

```sh
cd ~/keyboardbuilders && cp .env.previous .env && docker compose up -d
```

This is the part that is strictly better than what it replaces. Today's rollback is a copy
of the old jar in `/tmp` — which exists only if someone remembered to take it, and clears
on reboot.

### What deliberately did not change

The published address, the database path, the management port and every environment value
are exactly what the systemd unit set, and **Caddy's config is untouched**. This swaps the
process supervisor, not the deployment: a change that also rewrites the proxy is two
changes, and only one of them can be blamed when it breaks.

**Verified by running it**, 2026-09-07, before any cutover: the image serves `/designs`
with 50 silhouettes, `og.png` returns 200 (so the font libraries in the image are right —
the trap most likely to bite), `/designs/mx` 301s to its new name, and the import logs
`324 designs and 2815 facets`. The bind mount and uid 987 work under rootless podman.

One harmless thing that showed up while doing it: the JVM writes a `.java/fonts/` cache
into the container user's home, which is the bind-mounted `/var/lib/keyboardbuilders`. It
costs a few KB and `backup.sh` only ever reads `app.db`, so nothing needs doing about it —
but it will appear in that directory on the server and is not a corruption.

Three things the container had to reproduce, each of which is a trap this file already
documents:

- **The font libraries.** `libharfbuzz0b libfreetype6 libfontconfig1 fonts-dejavu-core` are
  installed in the image, for the same reason they are installed on the host: without them
  every `og.png` is a 500 while every page renders fine.
- **The bind addresses.** In a container `0.0.0.0` is the container's own namespace, so the
  boundary moves from the JVM to the `ports:` block — `172.17.0.1:8080` for the app because
  Caddy is a container, `127.0.0.1:9091` for the actuator because StatLite is a host
  process and `show-details=always` makes that port a leak. `management.server.address` had
  to become environment-driven for this; it was hard-coded, which made the app
  uncontainerisable without losing monitoring.
- **The database is a bind mount, not a named volume**, and the container runs as uid 987 —
  the same service account that owns the directory today — so `backup.sh` keeps working
  unchanged and knows nothing about docker.

## Updating

```sh
./gradlew bootJar && scp build/libs/keyboardbuilders-*-SNAPSHOT.jar VPS:/tmp/kb.jar
sudo install -m 0644 /tmp/kb.jar /opt/keyboardbuilders/keyboardbuilders.jar
sudo systemctl restart keyboardbuilders
```

The restart re-imports, so a corpus change ships with the jar and nothing else moves.

## Backups

Daily, `Persistent=true` so a missed run happens at next boot rather than being skipped,
14 kept. `backup.sh` uses `sqlite3 .backup` and **not** `cp`: the database being one file
makes copying it look safe, and a plain copy taken mid-write yields a torn file that may or
may not restore, with no sign which until it matters.

It backs up **two** databases: the app's and GoatCounter's. GoatCounter's is the one that
matters — the app's is rebuilt from the seed on every boot, so losing it costs a restart,
while losing the traffic history is permanent.

It verifies before it rotates — `PRAGMA integrity_check` plus a sentinel count — because
pruning on the strength of an unverified new file is how a good copy gets deleted to make
room for a bad one. The sentinel is not decoration: a database can pass `integrity_check`
and still be an empty schema. An **empty** count is treated as failure rather than zero,
because `[ "" -lt 1 ]` errors rather than evaluating false — which is how an earlier
version of this script reported success while writing 44-byte files.

Restore is `gunzip` and put the file back:

```sh
sudo systemctl stop keyboardbuilders
sudo -u keyboardbuilders sh -c 'gunzip -c /var/backups/keyboardbuilders/app-STAMP.db.gz > /var/lib/keyboardbuilders/app.db'
sudo systemctl start keyboardbuilders
```

Though while `import-on-startup=true`, a restore is rarely the repair: the corpus rebuilds
itself from the jar. The backups exist for the day that stops being true, which is the day
someone else's data is in there.

**That day was 2026-11-10** (backlog #116): the `unpublished` table records which designs
their author took down, and the import cannot rebuild it. Lose it and the next deploy puts
those pages back up. With the hearts, it is why a copy now leaves the box.

### The off-box copy, on the dev machine

Weekly, Mondays 12:00, as a systemd **user** timer (Arch has no cron daemon), and
`Persistent=true` so a Monday the machine was off runs at the next boot. It copies the VPS's
newest backup to `~/backups/keyboardbuilders`, keeps 12, and refuses a copy that fails
`integrity_check` or lacks the `heart` table, or `unpublished` once the backup's own schema
history says migration 8 ran. A failure raises a desktop notification.

```sh
ln -sf ~/code/keyboardbuilders/deploy/dev-machine/keyboardbuilders-pull-backup.{service,timer} ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now keyboardbuilders-pull-backup.timer
systemctl --user list-timers keyboardbuilders-pull-backup.timer   # next run
journalctl --user -u keyboardbuilders-pull-backup.service         # what it kept
```

## Analytics (Forgejo #27, reversed 2026-08-30)

**Hosted GoatCounter at `keyboardbuilders.goatcounter.com`.** Nothing to install, nothing
to back up. Free under 100k views a month for non-commercial use, which this is nowhere
near.

It was self-hosted first, as a systemd unit on `goatcounter.keyboardbuilders.net`. #27
chose that partly to keep the script **first-party**, and that argument was already half
undercut in its own notes: blocker lists match hostnames containing `goatcounter` and
`/count` paths, so both forms are blocked about equally. What self-hosting really bought
was that visitor data stayed on the box, and what it cost was the **only file on this VPS
that could not be rebuilt** — everything else regenerates from the jar. The owner's rule
settled it: nothing is self-hosted unless it has to be.

**The VPS is now fully disposable.** If it burns down, a fresh box plus the jar plus the
unit is the whole recovery. `backup.sh` still runs, but nothing it saves is irreplaceable.

### Configuration

Two settings, because the hits and the script come from **different hosts**:

```
KEYBOARDBUILDERS_ANALYTICS=https://keyboardbuilders.goatcounter.com
# KEYBOARDBUILDERS_ANALYTICS_SCRIPT=...   default https://gc.zgo.at/count.js
```

Hosted GoatCounter serves `/count.js` from a shared host and **404s it on the per-site
subdomain**; only the `/count` endpoint lives there. Deriving both from one origin worked
while self-hosted and produced a script tag pointing at nothing the moment it moved.

**Unset loads no script at all** — so tests, local development, and anyone running this
from source get a site with zero JavaScript, and only the deployed instance counts.

### What was torn down

```sh
sudo systemctl disable --now goatcounter        # done
# Caddyfile: the goatcounter.keyboardbuilders.net vhost removed, caddy reload
# backup.sh: the GC_DATABASE entry removed
# deploy/goatcounter.service: deleted
```

Still to do by hand, neither urgent: **delete the `goatcounter` DNS record** at the
registrar, and remove `/var/lib/goatcounter` plus the old `goatcounter-*.db.gz` files in
`/var/backups/keyboardbuilders` once you are sure you want the history gone. The ufw rule
for port 8082 can go too.

## Monitoring (Forgejo #28)

Spring Boot Actuator on a **separate loopback port** (`9091`), polled by **StatLite**, a
single Go binary storing history in SQLite. Neither is reachable from the internet.

`show-details=always` is only safe because of where the endpoints live:

- `management.server.port=9091`, `management.server.address=127.0.0.1` — the proxy never
  publishes that port, and unlike the app it is not even on the docker bridge.
- Caddy refuses `/actuator*` on the public site, so a later properties change cannot
  quietly expose it.

**StatLite has no dashboard authentication of its own** — its docs say so. Caddy provides
it instead, at `statlite.keyboardbuilders.net`, with basic auth over a 32-character random
password hashed at bcrypt cost 14. Generate and hash the password with:

```sh
openssl rand -base64 32                     # keep this in a password manager
docker exec -it caddy caddy hash-password   # paste it here, put the HASH in the Caddyfile
```

The dashboard is readable, not writable — it exposes health, metrics and host
CPU/memory/disk — so one strong credential is proportionate for a single-operator server.
If something writable ever goes behind it, move to mTLS (`client_auth` in Caddy) or a
VPN instead; a password with no rate limiting is not the right lock for that.

StatLite binds `172.17.0.1` rather than loopback, because Caddy is a container and cannot
reach host loopback. It needs
`ufw allow proto tcp from 172.16.0.0/12 to any port 9090`.

An SSH tunnel still works and needs nothing published, if you prefer it — but the sshd on
this box hardens `AllowTcpForwarding no`, so it wants narrowing rather than disabling:

```sh
# in /etc/ssh/sshd_config.d/ssh-hardening.conf
AllowTcpForwarding local
PermitOpen 172.17.0.1:9090
```

Install:

```sh
# binary
curl -fsSLO https://github.com/PVRLabs/statlite/releases/download/v0.3.0/statlite_0.3.0_linux_amd64.tar.gz
tar xzf statlite_0.3.0_linux_amd64.tar.gz
sudo useradd --system --home /var/lib/statlite --shell /usr/sbin/nologin statlite
sudo mkdir -p /opt/statlite /etc/statlite /var/lib/statlite
sudo chown statlite:statlite /var/lib/statlite
sudo install -m 0755 statlite /opt/statlite/statlite

# config and unit
sudo install -m 0644 deploy/statlite.yaml /etc/statlite/statlite.yaml
sudo install -m 0644 deploy/statlite.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now statlite
```

Verify — the second command is the one that matters, because it proves the actuator is
*not* reachable from outside:

```sh
curl -s http://127.0.0.1:9091/actuator/health | head -c 200   # on the box; 9091 IS loopback
curl -s -o /dev/null -w '%{http_code}\n' https://keyboardbuilders.net/actuator/health   # expect 404
```

## Not here yet

TLS and the reverse proxy vhost — the box already terminates TLS for
`forgejo.keyboardbuilders.net`, so the proxy config belongs with whatever does that rather
than being invented here. `keyboardbuilders.net` currently serves an unrelated GitHub Pages
site; pointing the domain at this is a DNS change and a deliberate one.
