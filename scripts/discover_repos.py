#!/usr/bin/env python3
"""Find keyboard designs on GitHub that the corpus does not have yet -- backlog #105, step 1.

The nightly run (#104) only ever learns of a design when its firmware record lands in
upstream QMK or ZMK, so a design that lives only in its author's repo -- most of what
gets posted to Reddit each week -- never arrives. This searches GitHub for repos created
in the last WINDOW days, keeps those holding a KiCad board, and writes them to
`data/discovered.json` for `intake_ask.py` to put to the owner as one
yes/no question each. It rules on nothing and publishes nothing.

A BOARD THAT PLACES KEYS IS THE FILTER. Measured 2026-11-09 over the 1,535 repos the
queries below returned for the 30 days before:

    1,535  search hits: keymaps, configurators, typing trainers, on-screen keyboards
      276  hold a `.kicad_pcb` -- still mostly Hack Club macropads and MIDI keypads,
           retro computers, KiCad tools
       62  have a board placing >= MIN_SWITCHES switches (scan_boards.classify, the
           corpus's own test for "a board") and no NOISE word in name or description

MIN_SWITCHES is 12, not higher, because a split's halves are separate files and a small
one reads 12 a half (`rafaelromao/prisma`); a bare count of 18 lost it and ergoSHIFT. The
NOISE words then take out the 12-key student macropads a count of 12 lets in. Both are
judgement calls, measured on one month; a design that ships only gerbers or an ergogen
config is missed. This proves presence, never absence.

SEARCH IS CHEAP AND THE TREE CALL IS NOT, so a repo is read once and remembered under
`checked` with the push date it was read at. It is read again only when it has been
pushed since, which is how a repo that adds its PCB in week two is still found. Entries
created before the window are forgotten: search will not return them again.

A CANDIDATE IS NEVER DROPPED BY SEARCH, only by the corpus gaining its repo. The owner's
answer, not a sliding window, is what retires it.

Usage: discover_repos.py [--window 30] [--out data/discovered.json]
`gh` supplies the credential (GH_TOKEN on the VPS), as in fetch_activity.py.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import time
import tomllib
from datetime import date, timedelta

REPO = pathlib.Path(__file__).resolve().parent.parent
OUT = REPO / "data/discovered.json"

# Topics designers actually tag with, plus three plain-text searches for the many repos
# that carry no topic at all. `topic:keyboard` alone is ~700 a month, mostly software;
# the PCB filter is what makes it affordable to keep.
QUERIES = [
    "topic:mechanical-keyboard", "topic:keyboard", "topic:split-keyboard",
    "topic:ergonomic-keyboard", "topic:custom-keyboard", "topic:ergogen",
    "topic:zmk", "topic:zmk-module", "topic:zmk-keyboard", "topic:qmk", "topic:rmk",
    "topic:pcb topic:keyboard", "keyboard kicad in:name,description,readme",
    "keyboard pcb in:name,description", "split keyboard in:name,description",
    "macropad in:name,description",
]

# Each PCB file is classified; the largest switch count stands for the repo. A split's
# halves are separate files, so this is a HALF's keys -- see the docstring.
MIN_SWITCHES = 12
MAX_BOARDS_READ = 4
NOISE = re.compile(r"midi|synth|music|melod|piano|sampler|half.?life|hack ?club|hackpad"
                   r"|stardance|ysws|macro ?pad|macro ?deck|calc|gamepad|joystick|clock",
                   re.I)

GITHUB = re.compile(r"github\.com/([^/\s\"]+/[^/\s\"#?]+)")


def hardware_files(paths):
    return [p for p in paths if p.endswith(".kicad_pcb")]


def firmware_hints(paths):
    """Which firmware the repo seems to carry. Signs only; an empty list is Unknown."""
    found = set()
    for p in paths:
        if p.endswith("zephyr/module.yml") or "boards/shields/" in p:
            found.add("zmk")
        if p.endswith(("/keyboard.json", "/info.json")) or p in ("keyboard.json", "info.json"):
            found.add("qmk")
        if p.endswith("keyboard.toml"):
            found.add("rmk")
    return sorted(found)


def update(previous, hits, fetch_tree, count_switches, known, since):
    """Fold one search's hits into the stored state. Pure but for the two readers."""
    candidates = {k: v for k, v in previous.get("candidates", {}).items()
                  if k.lower() not in known}
    checked = {k: v for k, v in previous.get("checked", {}).items()
               if v["created"][:10] >= since}
    for h in hits:
        name = h["full_name"]
        if name.lower() in known:
            continue
        old = checked.get(name)
        if old and old["pushed"] == h["pushed_at"]:
            continue
        paths = fetch_tree(name, h["default_branch"])
        checked[name] = {"pushed": h["pushed_at"], "created": h["created_at"]}
        pcb = hardware_files(paths)
        if not pcb or NOISE.search(f"{name} {h.get('description') or ''}"):
            continue
        switches = count_switches(name, pcb)
        if switches < MIN_SWITCHES:
            continue
        candidates[name] = {
            "description": h.get("description") or "",
            "stars": h.get("stargazers_count"),
            "licence": (h.get("license") or {}).get("spdx_id"),
            "created": h["created_at"][:10],
            "pcb": pcb,
            "switches": switches,
            "firmware": firmware_hints(paths),
        }
    return {"candidates": dict(sorted(candidates.items())),
            "checked": dict(sorted(checked.items()))}


def known_repos():
    """Every repo the corpus already names, lowercased: sources, boards, trees, designs."""
    found = set()
    for name in ("sources.toml", "boards.toml"):
        for row in tomllib.loads((REPO / "seed" / name).read_text())["entry"]:
            if isinstance(row.get("source"), str):
                found.add(row["source"].lower())
    text = (REPO / "seed/trees.toml").read_text()
    designs = (REPO / "src/main/resources/seed/designs.json").read_text()
    for m in GITHUB.finditer(text + designs):
        found.add(m[1].removesuffix(".git").lower())
    return found


def gh(*args):
    out = subprocess.run(["gh", "api", *args], capture_output=True, text=True)
    if out.returncode:
        raise RuntimeError(out.stderr.strip()[:200])
    return json.loads(out.stdout)


def search(since):
    hits = {}
    for q in QUERIES:
        for page in range(1, 11):  # search stops at 1,000 results
            items = gh("-X", "GET", "search/repositories", "-f",
                       f"q={q} created:>={since} fork:false archived:false",
                       "-f", "per_page=100", "-f", f"page={page}")["items"]
            time.sleep(2.1)  # search allows 30 calls a minute, and the queries need ~30
            for it in items:
                hits[it["full_name"]] = it
            if len(items) < 100:
                break
    return list(hits.values())


def fetch_tree(name, branch):
    try:
        tree = gh(f"repos/{name}/git/trees/{branch}?recursive=1")
    except RuntimeError as e:  # an empty repo has no tree; a vanished one 404s
        print(f"  {name}: {e}", file=sys.stderr)
        return []
    return [t["path"] for t in tree.get("tree", [])]


def count_switches(name, pcb):
    from scan_boards import classify
    return max(classify(name, p).get("switches", 0) for p in pcb[:MAX_BOARDS_READ])


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--window", type=int, default=30)
    ap.add_argument("--out", type=pathlib.Path, default=OUT)
    args = ap.parse_args()

    since = (date.today() - timedelta(days=args.window)).isoformat()
    previous = json.loads(args.out.read_text()) if args.out.exists() else {}
    hits = search(since)
    state = update(previous, hits, fetch_tree, count_switches, known_repos(), since)
    args.out.write_text(json.dumps(state, indent=1) + "\n")
    new = len(state["candidates"]) - len(previous.get("candidates", {}))
    print(f"{len(hits)} search hits since {since}; {len(state['candidates'])} candidates "
          f"({new:+d})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
