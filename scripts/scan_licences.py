#!/usr/bin/env python3
"""Find every licence file in each confirmed source repo, and name the licence.

`Licensing` is a gate field with three parts -- hardware, firmware, documentation
-- and it is the ONLY gate field with no source anywhere in this repo. Measured
2026-08-26 over the 437 confirmed rows: every other required field can be filled
for 64-100% of them, and `licensing` for 0%. So nothing publishes, and 184 rows
are blocked on this field alone.

**This scan RECORDS, it does not INTERPRET.** A repo usually carries one
`LICENSE` at the root, and turning that into three separate answers is an
inference nobody has authorised. Deciding it needs a number that does not exist
yet -- how many repos state more than one licence, and where those files sit --
so this pass produces exactly that and stops. The hardware/firmware/documentation
mapping is a separate ruling, made against this output.

Absence is a RESULT here, not a failure: architecture.md §1.9 settles that a repo
with no licence means all rights reserved, which is `LicenceState::Unstated` and a
value. A repo scanned and found bare is therefore a complete answer, not a gap.

It reads the git tree over the API rather than cloning -- one request per repo
lists every path, which is what tells a root `LICENSE` from a `hardware/LICENSE`,
and that distinction is the whole point of the pass. Needs `gh auth` for the
5,000/hour limit; unauthenticated is 60/hour and will not finish.

Names are matched on DISTINCTIVE BODY TEXT, never on the filename -- a file called
`LICENSE` states whatever it states. An unrecognised licence is reported as
`unclassified` with its first line, never guessed at: a wrong licence is worse
than an unknown one, because it tells a builder they may do something they may not.

Usage: scan_licences.py [seed/sources.toml] [--jobs N] [--cache FILE] [--json]
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from check_source_reachability import parse

# A licence file by name. Deliberately generous -- the name only decides what to
# READ; the body decides what it says.
LICENCE_FILE = re.compile(
    r"(^|/)(licen[cs]e|copying|copyright|unlicense)([._-][\w.-]+)?$", re.I)

# ...but not a PROGRAM that happens to be named after one. `frobiac/adnw` ships
# `tools/copyright.sh`, a script that renews copyright years across the tree.
NOT_A_DOCUMENT = re.compile(r"\.(sh|bash|py|rb|pl|js|ts|mjs|ps1|bat|cmd|mk|ya?ml)$", re.I)

# Paths that are somebody else's licence, not this design's. Recorded but flagged,
# never silently dropped -- a repo that vendors QMK still has its own licence, and
# deciding which is which is the ruling this pass feeds, not one it makes.
VENDORED = re.compile(
    r"(^|/)(node_modules|vendor|third[_-]?party|external|deps|\.venv|"
    r"site-packages|qmk_firmware|zmk|zephyr|lib|libs|librar\w*|lib[_-]\w+|"
    r"submodules)/"
    r"|\.pretty/|\.3dshapes/|\.kicad_sym/", re.I)

# Distinctive body text. EVERY pattern in a tuple must match, so alternatives are
# spelled as one alternation rather than as separate entries -- an earlier draft
# listed two identifying strings per licence meaning "either", ran them through
# `all()`, and reported crkbd's plain CC BY 4.0 as unclassified.
#
# Order matters where one licence's text contains another's name. CERN-OHL's three
# variants share a preamble and differ only in the reciprocity word, so each needs
# both its identifier AND that word; the strong and weak variants are checked
# before the permissive one for the same reason.
SIGNATURES = [
    # v1.2 first: its text says "CERN Open Hardware Licence" too, and the v2
    # variant patterns would otherwise claim it.
    ("CERN-OHL-1.2",   (r"cern\s+open\s+hardware\s+licence\s+v?\.?\s*1\.2"
                        r"|cern\s+ohl.{0,30}version\s+1\.2",)),
    ("CERN-OHL-S-2.0", (r"cern[- ]ohl[- ]s"
                        r"|cern\s+open\s+hardware\s+licen[cs]e.{0,300}strongly\s+reciprocal",)),
    ("CERN-OHL-W-2.0", (r"cern[- ]ohl[- ]w"
                        r"|cern\s+open\s+hardware\s+licen[cs]e.{0,300}weakly\s+reciprocal",)),
    ("CERN-OHL-P-2.0", (r"cern[- ]ohl[- ]p"
                        r"|cern\s+open\s+hardware\s+licen[cs]e.{0,300}permissive",)),
    ("CC-BY-SA-4.0",   (r"attribution[- ]sharealike\s+4\.0"
                        r"|creativecommons\.org/licenses/by-sa/4\.0",)),
    ("CC-BY-SA-3.0",   (r"attribution[- ]sharealike\s+3\.0"
                        r"|creativecommons\.org/licenses/by-sa/3\.0",)),
    ("CC-BY-NC-SA-4.0",(r"attribution[- ]noncommercial[- ]sharealike\s+4\.0"
                        r"|creativecommons\.org/licenses/by-nc-sa/4\.0",)),
    ("CC-BY-NC-SA-3.0",(r"attribution[- ]noncommercial[- ]sharealike\s+3\.0"
                        r"|creativecommons\.org/licenses/by-nc-sa/3\.0",)),
    ("CC-BY-NC-4.0",   (r"attribution[- ]noncommercial\s+4\.0"
                        r"|creativecommons\.org/licenses/by-nc/4\.0",)),
    ("CC-BY-4.0",      (r"attribution\s+4\.0\s+international"
                        r"|creativecommons\.org/licenses/by/4\.0",)),
    ("CC0-1.0",        (r"cc0\s+1\.0|creativecommons\.org/publicdomain/zero/1\.0",)),
    ("Apache-2.0",     (r"apache\s+license.{0,40}version\s+2\.0",)),
    ("AGPL-3.0",       (r"gnu\s+affero\s+general\s+public\s+license",)),
    ("LGPL-3.0",       (r"gnu\s+lesser\s+general\s+public\s+license.{0,60}version\s+3",)),
    ("LGPL-2.1",       (r"gnu\s+lesser\s+general\s+public\s+license.{0,60}version\s+2\.1",)),
    ("GPL-3.0",        (r"gnu\s+general\s+public\s+license.{0,60}version\s+3",)),
    ("GPL-2.0",        (r"gnu\s+general\s+public\s+license.{0,60}version\s+2",)),
    ("MPL-2.0",        (r"mozilla\s+public\s+license\s+version\s+2\.0",)),
    ("Solderpad-2.1",  (r"solderpad\s+hardware\s+licen[cs]e.{0,40}2\.1",)),
    ("Solderpad-0.51", (r"solderpad\s+hardware\s+licen[cs]e.{0,40}0\.51",)),
    ("TAPR-OHL-1.0",   (r"tapr\s+open\s+hardware\s+license",)),
    ("Unlicense",      (r"this\s+is\s+free\s+and\s+unencumbered\s+software",)),
    ("BSD-3-Clause",   (r"neither\s+the\s+name\s+of.{0,80}nor\s+the\s+names\s+of",)),
    ("BSD-2-Clause",   (r"redistributions\s+in\s+binary\s+form\s+must\s+reproduce",)),
    ("Zlib",           (r"zlib\s+license"
                        r"|this\s+software\s+is\s+provided\s+'as-is'",)),
    ("Beerware",       (r"the\s+beer-?ware\s+license",)),
    # Accepted by the owner 2026-11-16: FSF-free and as permissive as a licence gets.
    ("WTFPL",          (r"do\s+what\s+the\s+fuck\s+you\s+want\s+to\s+public\s+licen[cs]e",)),
    ("ODbL-1.0",       (r"open\s+database\s+license\s+\(odbl\)|odc\s+open\s+database",)),
    ("MIT",            (r"permission\s+is\s+hereby\s+granted,\s+free\s+of\s+charge",)),
]
SIGNATURES = [(n, tuple(re.compile(p, re.I | re.S) for p in ps)) for n, ps in SIGNATURES]

# An author who writes the tag has ANSWERED the question; reading their prose
# instead is second-guessing them. `tweetydabird/lotus58` states
# `SPDX-License-Identifier: CERN-OHL-S-2.0+` in a nine-line notice that carries no
# licence body at all, so text matching had nothing to find.
#
# A `WITH` clause is part of the answer. Solderpad's own appendix tells authors to write
# `Apache-2.0 WITH SHL-2.1` -- SHL-2.1 is an SPDX *exception* to Apache, and the pair is
# how SPDX spells Solderpad 2.1. Reading the first word alone published Ferris, Sweep and
# Swoop as Apache-2.0 (2026-10-01).
SPDX_TAG = re.compile(r"SPDX-License-Identifier:\s*([A-Za-z0-9.\-+]+)(?:\s+WITH\s+([A-Za-z0-9.\-+]+))?")
SPDX_EXCEPTION = {"SHL-2.1": "Solderpad-2.1", "SHL-0.51": "Solderpad-0.51"}

API = "https://api.github.com"
RAW = "https://raw.githubusercontent.com"
BODY_CAP = 60_000        # every licence text we care about fits well inside this;
                         # applies to licence bodies ONLY, never to a tree listing

# Signatures match against the HEAD of the file, not the whole of it, because a
# licence names OTHER licences in its body. GPL-3.0 section 13 -- "Use with the GNU
# Affero General Public License" -- sits at byte ~29,000 of the standard text, and
# a whole-body search reported all 50 GPL-3.0 repos in this seed as AGPL-3.0. Every
# licence here announces itself in its title, so the head is where the answer is.
# A file that concatenates two licences therefore reports the first; that is the
# honest reading of a header, and the alternative was a wrong answer 50 times.
HEAD_CHARS = 4_000


def token():
    try:
        out = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, timeout=15)
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        pass
    return None


TOKEN = None


def fetch(url, accept="application/vnd.github+json", attempt=0, cap=None):
    """`cap` bounds a licence BODY. A tree listing must be read whole -- capping it
    truncates the JSON mid-object and the repo reports as unparseable, which is
    what this scan did on its first run against crkbd."""
    headers = {"User-Agent": "keyboardbuilders-licence-scan", "Accept": accept}
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"
    try:
        with urllib.request.urlopen(
                urllib.request.Request(url, headers=headers), timeout=60) as r:
            return r.read(cap) if cap else r.read()
    except urllib.error.HTTPError as e:
        # 403/429 here is the rate limiter, not a permission problem.
        if e.code in (403, 429) and attempt < 4:
            time.sleep(10 * (attempt + 1))
            return fetch(url, accept, attempt + 1, cap)
        return e.code
    except (urllib.error.URLError, OSError, TimeoutError):
        return None


def classify(body):
    """The licence this text states, or `unclassified` plus its opening line."""
    text = body.decode("utf-8", errors="ignore")
    first_line = next((ln.strip() for ln in text.splitlines() if ln.strip()), "")[:90]
    head = text[:HEAD_CHARS]
    tag = SPDX_TAG.search(head)
    if tag:
        if tag.group(2) in SPDX_EXCEPTION:
            return SPDX_EXCEPTION[tag.group(2)], first_line
        return tag.group(1).rstrip("+"), first_line
    for name, patterns in SIGNATURES:
        if all(p.search(head) for p in patterns):
            return name, first_line
    return "unclassified", first_line


def scan_repo(repo):
    """Every licence file in one repo: its path, what it says, whether it is vendored."""
    tree = fetch(f"{API}/repos/{repo}/git/trees/HEAD?recursive=1")
    if isinstance(tree, int):
        return {"repo": repo, "status": f"http-{tree}", "files": []}
    if tree is None:
        return {"repo": repo, "status": "unreachable", "files": []}
    try:
        data = json.loads(tree)
    except ValueError:
        return {"repo": repo, "status": "unparseable", "files": []}

    paths = [e["path"] for e in data.get("tree", []) if e.get("type") == "blob"]
    hits = [p for p in paths
            if LICENCE_FILE.search(p) and not NOT_A_DOCUMENT.search(p)]
    files = []
    for path in sorted(hits):
        body = fetch(f"{RAW}/{repo}/HEAD/{urllib.parse.quote(path)}",
                     accept="text/plain", cap=BODY_CAP)
        if isinstance(body, int) or body is None:
            files.append({"path": path, "licence": "unreadable", "first_line": "",
                          "vendored": bool(VENDORED.search(path)), "root": "/" not in path})
            continue
        name, head = classify(body)
        files.append({"path": path, "licence": name, "first_line": head,
                      "vendored": bool(VENDORED.search(path)), "root": "/" not in path})
    return {"repo": repo, "status": "ok", "truncated": data.get("truncated", False),
            "files": files}


def main():
    global TOKEN
    ap = argparse.ArgumentParser()
    ap.add_argument("seed", nargs="?", default="seed/sources.toml")
    ap.add_argument("--jobs", type=int, default=8)
    ap.add_argument("--cache", default="data/licence-scan.json")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    TOKEN = token()
    if not TOKEN:
        print("no `gh auth token` -- 60 requests/hour, this will not finish", file=sys.stderr)

    rows = [r for r in parse(args.seed) if r.get("verdict") == "confirmed"]
    repos = sorted({r["source"] for r in rows if r.get("source")})

    cache_path = pathlib.Path(args.cache)
    cache = json.loads(cache_path.read_text()) if cache_path.exists() else {}
    todo = [r for r in repos if r not in cache]
    print(f"{len(rows)} confirmed rows, {len(repos)} distinct repos, "
          f"{len(todo)} to fetch ({len(cache)} cached)", file=sys.stderr)

    if todo:
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            for i, result in enumerate(pool.map(scan_repo, todo), 1):
                cache[result["repo"]] = result
                if i % 25 == 0:
                    print(f"  {i}/{len(todo)}", file=sys.stderr)
                    cache_path.parent.mkdir(parents=True, exist_ok=True)
                    cache_path.write_text(json.dumps(cache, indent=1, sort_keys=True))
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        cache_path.write_text(json.dumps(cache, indent=1, sort_keys=True))

    results = [cache[r] for r in repos]
    if args.json:
        print(json.dumps(results, indent=1, sort_keys=True))
        return

    status = Counter(r["status"] for r in results)
    own = {r["repo"]: [f for f in r["files"] if not f["vendored"]] for r in results}
    count_own = Counter(len(v) for v in own.values())
    licences = Counter(f["licence"] for v in own.values() for f in v)
    nested = sum(1 for v in own.values() if any(not f["root"] for f in v))

    print(f"repos scanned: {len(results)}  {dict(status)}")
    print(f"\nlicence files per repo (vendored paths excluded):")
    for n in sorted(count_own):
        print(f"  {n} file(s): {count_own[n]:>4} repos")
    print(f"\nrepos with a licence somewhere OTHER than the root: {nested}")
    print(f"repos with NO licence at all (-> Unstated, a value): {count_own[0]}")
    print(f"\nlicences named:")
    for name, n in licences.most_common():
        print(f"  {name:<18} {n:>4}")
    unclassified = [(r["repo"], f["path"], f["first_line"])
                    for r in results for f in r["files"]
                    if f["licence"] == "unclassified" and not f["vendored"]]
    if unclassified:
        print(f"\nunclassified, needing a human ({len(unclassified)}):")
        for repo, path, head in unclassified[:15]:
            print(f"  {repo}/{path}: {head}")


if __name__ == "__main__":
    main()
