#!/usr/bin/env python3
"""The hardware repo a QMK record names in its README, which nothing reads.

`draft_sources.py` drafts a source row only for a record whose **`url` field** names a
repo, and ticket 22 measured
that field failing four ways: absent, a vendor page, two records sharing one url, and an
ancestor's repo. What nobody asked is whether the record says it somewhere else.

It does. A QMK readme carries a `Hardware Availability:` line, and 451 of the tree's
3,987 readmes name a GitHub repo in it that is not a firmware fork. `draft_sources.py`
already **parses that exact block** -- `CONTROLLER_BLOCK`, to spot ticket 33's
`[controller]` declines -- and throws away the repo it names.

    naked60   url: absent
              readme: "Hardware Availability: [PCB & Case Data](https://github.com/Salicylic-acid3/PCB_Data)"

THE CONTROL IS THE 118 THAT AGREE. Where the record's `url` field also names a repo, the
readme names the **same** repo 118 times. So this is a second statement of a fact the
corpus already trusts from the first, not a new kind of claim.

WHAT IT REACHES, measured 2026-09-02 against `seed/sources.toml`:

    118   the row already has this repo          <- the control
     73   the row has a DIFFERENT repo           mostly case (`ai03-2725/Orbit` vs
                                                 `orbit`), some real (`bbrfkr/dynamis-keyboard`
                                                 against the row's `bbrfkr/keyboards`)
    260   NO SOURCE ROW AT ALL                   the drafter never saw these records
            69  the repo is already in the corpus under another record's row
           191  the repo is NEW -- 154 distinct repos

And 154 repos is not 154 designs. Sampled 40 of them through `gh`:

    22/40   hold a `.kicad_pcb`                  ~85 repos, extrapolated
     9/40   hardware in another format only      `.step`, `.dxf`, `.svg` -- the same DXF
                                                 wall ticket 21 hit on splitkb's plates
     6/40   404                                  `source_gone` on arrival
     3/40   no hardware files

So the ceiling is around **85 repos over ~105 records**, before the publication gate
takes its share -- larger than the review queue's 34 (ticket 44)
and larger than the 71 boards no record describes (ticket 55).

THIS SCRIPT DECIDES NOTHING. It writes candidates, because a source row is a human's
ruling (ticket 26) and 154 of them is not the `dev-seed` signal's "rare on purpose".

Usage: scan_readme_sources.py [--qmk DIR] [--probe] [--write PATH] [--new]
"""
import argparse
import collections
import json
import pathlib
import re
import subprocess
import sys
import tomllib

REPO = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_QMK = pathlib.Path.home()/"code/qmk_firmware/keyboards"

# The same block `draft_sources.py` reads for ticket 33's `[controller]` decline,
# spelled the same way so the two cannot drift on where the block ends.
BLOCK = re.compile(r"Hardware Availability:(.*?)(?:\n\s*\n|\n\*\s*Keyboard|\Z)",
                   re.S | re.I)
GH = re.compile(r"https?://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)")
# A firmware tree is not a hardware repo -- ticket 33's whole point, and the reason
# `zodiark`'s own url (`Aleblazer/qmk_firmware/tree/master/keyboards/zodiark`) pointed
# away from the hardware its maintainer publishes two repos over.
FIRMWARE = {"qmk_firmware", "vial-qmk", "zmk", "tmk_keyboard", "kmk_firmware"}


def readme_repos(qmk):
    """`record id -> {owner/repo}` for every readme naming a non-firmware GitHub repo."""
    out = {}
    for path in sorted(qmk.rglob("readme.md")):
        block = BLOCK.search(path.read_text(errors="replace"))
        if not block:
            continue
        found = set()
        for owner, repo in GH.findall(block.group(1)):
            # A markdown link often ends in `.` or `.git`; both make a wrong slug.
            repo = repo.rstrip(".").removesuffix(".git")
            if repo.lower() in FIRMWARE:
                continue
            found.add(f"{owner}/{repo}")
        if found:
            out[str(path.parent.relative_to(qmk))] = found
    return out


def probe(slug):
    """Does this repo exist, and what hardware does it carry? Needs `gh` authenticated."""
    proc = subprocess.run(
        ["gh", "api", f"repos/{slug}/git/trees/HEAD?recursive=1",
         "--jq", '[.tree[]|select(.type=="blob")|.path]'],
        capture_output=True, text=True)
    if proc.returncode:
        return {"status": "unreachable", "error": proc.stderr.strip()[:80]}
    try:
        files = json.loads(proc.stdout)
    except ValueError:
        return {"status": "unreachable", "error": "unreadable tree"}
    boards = [f for f in files if f.endswith(".kicad_pcb")]
    other = [f for f in files
             if f.lower().endswith((".brd", ".sch", ".kicad_sch", ".step", ".stl",
                                    ".dxf", ".f3d", ".svg"))]
    licences = [f for f in files
                if f.split("/")[-1].upper().startswith(("LICEN", "COPYING"))]
    return {"status": "ok", "files": len(files), "boards": boards[:20],
            "n_boards": len(boards), "other_hardware": len(other),
            "licence": bool(licences)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--qmk", default=str(DEFAULT_QMK))
    # WHICH TREE'S README URLS THESE ARE. The ids come from walking one checkout, so the
    # seed rows they join to are that checkout's tree and not the word "qmk" -- point
    # `--qmk` at a fork and every id would have missed its own row. Backlog #64.
    ap.add_argument("--tree", default="qmk",
                    help="the seed tree the scanned checkout is (see seed/trees.toml)")
    ap.add_argument("--probe", action="store_true",
                    help="ask GitHub what each NEW repo holds (needs gh)")
    ap.add_argument("--write", metavar="PATH", help="write the candidates as JSON")
    ap.add_argument("--new", action="store_true", help="list only the new-repo records")
    args = ap.parse_args()

    qmk = pathlib.Path(args.qmk)
    if not qmk.is_dir():
        print(f"no QMK tree at {qmk}", file=sys.stderr)
        return 1
    hits = readme_repos(qmk)
    rows = {(e["tree"], e["id"]): e
            for e in tomllib.loads((REPO/"seed/sources.toml").read_text())["entry"]}
    known = {(r.get("source") or "").lower() for r in rows.values() if r.get("source")}

    verdicts, candidates = collections.Counter(), []
    for rid, repos in sorted(hits.items()):
        row = rows.get((args.tree, rid))
        if row is None:
            fresh = [r for r in sorted(repos) if r.lower() not in known]
            kind = "no row, new repo" if fresh else "no row, repo already in corpus"
        elif any((row.get("source") or "").lower() == r.lower() for r in repos):
            kind = "row already has this repo"
        elif row.get("source") is False:
            kind = "row says source = false"
        else:
            kind = "row has a different repo"
        verdicts[kind] += 1
        if kind != "row already has this repo":
            candidates.append({"record": rid, "readme_repos": sorted(repos),
                               "row_source": (row or {}).get("source"), "verdict": kind})

    print(f"{len(hits)} QMK records name a non-firmware GitHub repo "
          f"under Hardware Availability")
    for kind, n in verdicts.most_common():
        print(f"  {n:4d}  {kind}")

    fresh_repos = sorted({r for c in candidates if c["verdict"] == "no row, new repo"
                          for r in c["readme_repos"]}, key=str.lower)
    print(f"\n{len(fresh_repos)} distinct repos the corpus has never seen")

    if args.probe:
        tab = collections.Counter()
        seen = {}
        for slug in fresh_repos:
            seen[slug] = probe(slug)
            info = seen[slug]
            if info["status"] != "ok":
                tab["404 / unreachable"] += 1
            elif info["n_boards"]:
                tab["holds a .kicad_pcb"] += 1
            elif info["other_hardware"]:
                tab["hardware, but no .kicad_pcb"] += 1
            else:
                tab["no hardware files"] += 1
        print()
        for kind, n in tab.most_common():
            print(f"  {n:4d}/{len(fresh_repos)}  {kind}")
        for c in candidates:
            c["probe"] = {r: seen[r] for r in c["readme_repos"] if r in seen}

    if args.new:
        for c in candidates:
            if c["verdict"] == "no row, new repo":
                print(f"  {c['record']:40s} -> {c['readme_repos']}")

    if args.write:
        pathlib.Path(args.write).write_text(json.dumps(candidates, indent=1))
        print(f"\nwrote {len(candidates)} candidates -> {args.write}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
