#!/usr/bin/env python3
"""Generate the board files an Ergogen repo describes but does not commit.

jyap808/pando58 holds `ergogen/config.yaml` and its footprints and no `.kicad_pcb`;
the PCBs live only as Gerbers in Releases. Every board instrument here reads a
`.kicad_pcb` by repo and path, so the design had no board and could not publish.
This runs the author's own config through Ergogen and leaves the output where
`generated_boards.py` serves it to those instruments -- named by the path Ergogen
would have written inside the repo, so nothing downstream can tell the difference
except by asking the manifest.

WHICH REPOS. A confirmed `seed/sources.toml` repo whose file listing
(`source-trees.json`) holds NO `.kicad_pcb` and at least one `config.yaml` or
`config.yml`. A repo that commits its boards is read as committed -- generating
beside them would put a second copy of every board up for attribution. The YAML
must look like Ergogen's: top-level `points:` and `pcbs:`, or it is skipped.

WHICH ENGINE. The config's own `meta.engine`, exactly, as an image tag
(`deploy/ergogen/Dockerfile`, built on first use). A config that names no exact
version is recorded as an error and not generated: picking one for it is the
default-fill this project refuses, and Ergogen's output moves between versions.

THE AUTHOR'S CODE RUNS HERE. Ergogen footprints are JavaScript from the repo, so
the container gets `--network none`, a read-only checkout, a read-only root, and
one writable output folder. The engine is installed at image build, never fetched
at run time.

CACHED BY COMMIT. `manifest.json` records the HEAD sha and engine per repo, and a
repo whose `git ls-remote` HEAD and engine are unchanged is not regenerated. So
the nightly run pays one `ls-remote` per Ergogen repo and nothing else.

Usage: generate_ergogen.py [seed/sources.toml] [--trees PATH] [--container podman]
    CONTAINER overrides --container (podman here and on the VPS's rootless user).
"""
import argparse
import datetime
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import generated_boards  # noqa: E402
from check_source_reachability import parse  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
IMAGE = "localhost/keyboardbuilders-ergogen"
DOCKERFILE = REPO/"deploy/ergogen"
CONFIG = re.compile(r"(^|/)config\.ya?ml$")
EXACT = re.compile(r"^\d+\.\d+\.\d+$")
# Submodules are often cited over ssh (`git@github.com:ceoloide/ergogen-footprints`),
# which needs a key this machine should not have. `-c` reaches the submodule clones.
HTTPS = ["-c", "url.https://github.com/.insteadOf=git@github.com:"]


def git(*args, cwd=None):
    return subprocess.run(["git", *HTTPS, *args], cwd=cwd, check=True,
                          capture_output=True, text=True).stdout.strip()


def candidates(seed, listings):
    """{repo: [config path, ...]} for confirmed repos that commit no board."""
    repos = {r["source"] for r in parse(seed)
             if r.get("verdict") == "confirmed" and isinstance(r.get("source"), str)}
    out = {}
    for repo in sorted(repos):
        paths = (listings.get(repo) or {}).get("paths") or []
        if any(p.lower().endswith(".kicad_pcb") for p in paths):
            continue
        configs = [p for p in paths if CONFIG.search(p) and not p.startswith(".github/")]
        if configs:
            out[repo] = configs
    return out


def checkout(repo, work):
    dest = work/repo.lower()
    if dest.exists():
        git("fetch", "-q", "--depth", "1", "origin", "HEAD", cwd=dest)
        git("reset", "-q", "--hard", "FETCH_HEAD", cwd=dest)
    else:
        dest.parent.mkdir(parents=True, exist_ok=True)
        git("clone", "-q", "--depth", "1", f"https://github.com/{repo}", str(dest))
    git("submodule", "update", "-q", "--init", "--recursive", "--depth", "1", cwd=dest)
    return dest, git("rev-parse", "HEAD", cwd=dest)


def image(container, engine):
    tag = f"{IMAGE}:{engine}"
    if subprocess.run([container, "image", "exists", tag]).returncode != 0:
        subprocess.run([container, "build", "-q", "--build-arg", f"ERGOGEN_VERSION={engine}",
                        "-t", tag, str(DOCKERFILE)], check=True, capture_output=True)
    return tag


def generate(container, tag, checkout_dir, config_dir):
    """Run Ergogen on one config folder; return {name: text} of its .kicad_pcb files."""
    with tempfile.TemporaryDirectory() as out:
        # AS US, so what it writes is ours to read and delete. Rootless podman maps
        # container ids onto subuids unless told to keep ours; Docker needs only --user.
        user = ["--user", f"{os.getuid()}:{os.getgid()}"]
        if pathlib.Path(container).name == "podman":
            user = ["--userns", "keep-id", *user]
        run = subprocess.run(
            [container, "run", "--rm", "--network", "none", "--read-only", *user,
             "--tmpfs", "/tmp", "-v", f"{checkout_dir}:/src:ro,Z", "-v", f"{out}:/out:Z",
             tag, f"/src/{config_dir}" if config_dir else "/src", "-o", "/out"],
            capture_output=True, text=True, timeout=600)
        if run.returncode != 0:
            raise RuntimeError((run.stderr or run.stdout).strip().splitlines()[-1][:200])
        pcbs = pathlib.Path(out)/"pcbs"
        return {f.name: f.read_text(errors="replace")
                for f in sorted(pcbs.glob("*.kicad_pcb"))} if pcbs.is_dir() else {}


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("seed", nargs="?", default="seed/sources.toml")
    ap.add_argument("--trees", default="data/source-trees.json")
    ap.add_argument("--work", default=str(pathlib.Path.home()/".cache/keyboardbuilders/ergogen"))
    ap.add_argument("--container", default=os.environ.get("CONTAINER", "podman"))
    args = ap.parse_args()

    listings = json.loads(pathlib.Path(args.trees).read_text())
    todo = candidates(args.seed, listings)
    root = generated_boards.ROOT
    manifest = generated_boards.manifest()
    work = pathlib.Path(args.work)
    print(f"{len(todo)} repos with an Ergogen-shaped config and no committed board")

    for repo, configs in todo.items():
        prev = manifest.get(repo) or {}
        head = subprocess.run(["git", "ls-remote", f"https://github.com/{repo}", "HEAD"],
                              capture_output=True, text=True).stdout.split("\t")[0]
        if head and prev.get("sha") == head and not prev.get("error"):
            print(f"  {repo}: cached at {head[:7]}")
            continue
        entry = {"sha": None, "engine": None, "configs": [], "boards": [], "error": None,
                 "generated": datetime.date.today().isoformat()}
        try:
            where, sha = checkout(repo, work)
            entry["sha"] = sha
            shutil.rmtree(root/repo.lower(), ignore_errors=True)
            for cfg in configs:
                text = (where/cfg).read_text(errors="replace")
                if not (re.search(r"^points:", text, re.M) and re.search(r"^pcbs:", text, re.M)):
                    continue
                engine = str(((yaml.safe_load(text) or {}).get("meta") or {}).get("engine") or "")
                if not EXACT.match(engine):
                    raise RuntimeError(f"{cfg}: meta.engine {engine or 'absent'!r} is not an exact version")
                entry["engine"] = engine
                folder = str(pathlib.PurePosixPath(cfg).parent).lstrip(".")
                for name, body in generate(args.container, image(args.container, engine),
                                           where, folder).items():
                    path = f"{folder}/output/pcbs/{name}" if folder else f"output/pcbs/{name}"
                    f = root/repo.lower()/path
                    f.parent.mkdir(parents=True, exist_ok=True)
                    f.write_text(body)
                    entry["boards"].append(path)
                entry["configs"].append(cfg)
        except Exception as e:                    # noqa: BLE001 -- record, don't stop the run
            entry["error"] = f"{type(e).__name__}: {e}"[:240]
        manifest[repo] = entry
        print(f"  {repo}: {entry['error'] or f'{len(entry['boards'])} boards at {(entry['sha'] or '')[:7]}, ergogen {entry['engine']}'}")

    root.mkdir(parents=True, exist_ok=True)
    (root/"manifest.json").write_text(json.dumps(manifest, indent=1, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
