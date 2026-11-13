#!/usr/bin/env python3
"""Gather, per flagged row, the evidence a human needs to rule on it.

Ticket 43 asks one question per row -- *does this repo hold the hardware for
this firmware entry?* -- and for most rows the record's own `url` plus a matching
owner answers it. This script serves the rows where it does not:

  * **forks**, from `check_source_ancestry.py` -- ticket 22's failure, a record
    citing the repo it was forked from
  * **moves**, where the drafted slug redirects to a different owner or name
  * **owner mismatches**, the rows `draft_sources.py` already flagged in `note`

For each, it fetches the repo's file tree and answers the two things a human
would otherwise click through to find: **is there hardware in here at all**, and
**does any of it name this design**. A fork carrying a `.kicad_pcb` under the
design's own name is the author's own hardware and the row is right; a fork whose
only hardware names the ancestor is ticket 22's failure.

It does not rule. It puts the evidence on one screen.

Needs an authenticated `gh`. Writes markdown to stdout.

Usage: source_dossier.py [seed/sources.toml] > dossier.md
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from check_source_reachability import parse

# Hardware, in the formats this corpus actually ships -- and it ships more than
# KiCad. Measured while widening this: `4pplet` uses **Altium** (`.schdoc`,
# `.pcbdoc`) across 13 rows and `zigotica` ships **bare gerbers** (`.gko`,
# `.gbl`, `.drl`), so a KiCad-and-mesh list called four real hardware repos
# empty. Deliberately not a guess at "design files": ticket 45 made filenames
# non-evidence six times over, so this only says *a CAD or PCB file is present*.
HARDWARE = re.compile(
    r"\.("
    r"kicad_pcb|kicad_sch|kicad_pro|kicad_mod|sch|brd|"      # KiCad, Eagle
    r"schdoc|pcbdoc|prjpcb|schlib|pcblib|outjob|"            # Altium
    r"step|stp|stl|3mf|dxf|f3d|shapr|scad|obj|iges|igs|"     # CAD and mesh
    r"gbr|gbl|gtl|gbs|gts|gbo|gto|gbp|gtp|gml|gko|gm1|drl|"  # bare gerbers
    r"zip"                                                   # gerber bundles
    r")$", re.I)
NOISE = re.compile(r"(^|/)(\.github|node_modules|keymaps|firmware|docs?)/", re.I)


def gh(path):
    out = subprocess.run(["gh", "api", path], capture_output=True, text=True,
                         timeout=120)
    if out.returncode != 0:
        return None
    return json.loads(out.stdout)


def tree(slug):
    """Every path in the repo's default branch. One call, may be truncated."""
    d = gh(f"repos/{slug}/git/trees/HEAD?recursive=1")
    if not d:
        return None, False
    return [e["path"] for e in d.get("tree", []) if e.get("type") == "blob"], \
        bool(d.get("truncated"))


# Words that name a variant, a part or a vendor rather than a design. Matching
# on these is a false *accept*: every Charybdis file says `elitec` somewhere.
GENERIC = {
    "keyboard", "keyboards", "handwired", "hand", "wired", "wireless", "split",
    "rev", "revision", "version", "ansi", "iso", "jis", "left", "right", "mini",
    "nano", "micro", "macro", "standard", "compact", "base", "high", "bling",
    "promicro", "elitec", "blackpill", "bluepill", "rp2040", "stm32", "avr",
    "choc", "chocolate", "cherry", "hotswap", "solderable", "soldered",
    "gamepad", "macropad", "numpad", "keypad", "pcb", "case", "plate", "usb",
    "edition", "pico", "feather", "dualencoder", "oled", "encoder", "lite",
}


def tokens(row):
    """The words that would prove this repo holds *this* design's hardware.

    Words, not whole strings: the record `name` "Ferris sweep" must match
    `ferris.kicad_pcb`, and "CO60 rev 1" must match `co60.kicad_pcb`. Generic
    and variant words are dropped, because `elitec` appears in every Charybdis
    path and matching it would corroborate nothing.
    """
    out = set()
    parts = [row.get("name") or ""] + (row.get("id") or "").split("/")
    for s in parts:
        for w in re.split(r"[^A-Za-z0-9]+", s):
            w = w.lower()
            if len(w) >= 3 and w not in GENERIC and not w.isdigit():
                out.add(w)
        whole = re.sub(r"[^a-z0-9]+", "", s.lower())
        if len(whole) >= 5 and whole not in GENERIC:
            out.add(whole)
    return out


def hits(paths, toks):
    """Hardware paths, and which of them name the design."""
    hw = [p for p in paths if HARDWARE.search(p) and not NOISE.search(p)]
    named = [p for p in hw
             if any(t in re.sub(r"[^a-z0-9]+", "", p.lower()) for t in toks)]
    return hw, named


def repo_names_design(slug, toks):
    """Does the repo's own name carry the design's name?

    Measured need: where the repo *is* the design, authors name the files
    generically -- `beekeeb/piantor` ships `pcb/left/keyboard_pcb.kicad_pcb`,
    `gregandcin/teaqueen` ships `keyboard.kicad_pcb`, `dcpedit/masonry` ships
    `pcb/pcb.kicad_pcb`. The filename corroborates nothing there and the repo
    name corroborates everything, so both count.
    """
    flat = re.sub(r"[^a-z0-9]+", "", (slug or "").lower())
    return any(t in flat for t in toks)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("seed", nargs="?", default="seed/sources.toml")
    ap.add_argument("--ancestry", default="data/source-ancestry.json")
    ap.add_argument("--cache", default="data/source-trees.json")
    ap.add_argument("--jobs", type=int, default=8)
    ap.add_argument("--ids", choices=("corroborated", "look"),
                    help="print `tree:id` per line instead of the dossier")
    args = ap.parse_args()

    rows = [r for r in parse(args.seed) if r.get("verdict") == "drafted"]
    anc = json.loads(pathlib.Path(args.ancestry).read_text())

    # Why every row and not just the flagged ones: the point of the pass is to
    # spend a human's attention where it changes an answer. "No red flag" is an
    # absence and corroborates nothing; "a CAD file in here carries this
    # design's own name" is a positive signal, and it is only available for rows
    # whose tree has been read. So read them all.
    want = sorted({r["source"] for r in rows if r.get("source")
                   and not (anc.get(r["source"]) or {}).get("error")})
    cache = json.loads(pathlib.Path(args.cache).read_text()) \
        if pathlib.Path(args.cache).exists() else {}
    todo = [s for s in want if s not in cache]
    if todo:
        print(f"fetching {len(todo)} of {len(want)} trees", file=sys.stderr)
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            for slug, (paths, trunc) in zip(todo, pool.map(tree, todo)):
                cache[slug] = {"paths": paths, "truncated": trunc}
        cp = pathlib.Path(args.cache)
        cp.parent.mkdir(parents=True, exist_ok=True)
        cp.write_text(json.dumps(cache, indent=1, sort_keys=True))

    # One classification per row. A row is *corroborated* when a hardware file
    # in the repo carries this design's own name: three independent things then
    # agree -- QMK's url, the repo's own ancestry, the file on disk -- and
    # nobody looked. That is what makes a row cheap enough to settle in bulk.
    flagged = defaultdict(list)
    classed = {}
    for r in rows:
        src = r.get("source")
        m = (anc.get(src) or {}) if src else {}
        paths = ((cache.get(src) or {}).get("paths")) or []
        hw, named = hits(paths, tokens(r)) if paths else ([], [])
        if not src:
            why = "no repo stated -- `I looked, none is known` is the ruling"
        elif m.get("error"):
            why = "repo unreadable"
        elif not paths:
            why = "tree unreadable"
        elif not hw:
            why = "no hardware in the repo at all"
        elif not named and not repo_names_design(src, tokens(r)):
            why = "hardware present, but neither it nor the repo name names this design"
        else:
            why = None
        classed[(r["tree"], r["id"])] = why
        if why:
            flagged["look"].append(r)
        if src and not m.get("error"):
            if m.get("fork"):
                flagged["fork"].append(r)
            elif m["full_name"].lower() != src.lower():
                flagged["moved"].append(r)
            elif "matches neither maintainer" in (r.get("note") or ""):
                flagged["owner"].append(r)

    if args.ids:
        for key, why in sorted(classed.items()):
            hit = (why is None) if args.ids == "corroborated" else (why is not None)
            if hit:
                print(f"{key[0]}:{key[1]}")
        return

    titles = {
        "look": ("Rows a human must settle",
                 "Nothing corroborates these: no hardware file in the repo "
                 "carries this design's own name. This is the queue."),
        "fork": ("Forks", "The repo is a fork. Ticket 22's failure is a record "
                 "citing the repo it was forked *from* -- so the question is "
                 "whether the author laid their own hardware into it."),
        "moved": ("Moves", "The drafted slug redirects. A rename by the same "
                  "account needs no ruling; a transfer to a different person "
                  "means the drafted owner is not the author."),
        "owner": ("Owner mismatches", "`draft_sources.py` flagged these: the "
                  "url's owner matches neither the record's maintainer nor its "
                  "manufacturer, and the check cannot tell an ancestor's repo "
                  "from a co-author's."),
    }

    print("# Source dossier -- the rows where a look is load-bearing\n")
    print("Generated by `scripts/source_dossier.py` for "
          "[ticket 43](issues/43-confirm-the-source-rows.md). **Evidence, not "
          "rulings.** *hardware* counts CAD and PCB files outside `docs/`, "
          "`keymaps/` and `firmware/`; *names it* means a hardware path "
          "contains the design's own name.\n")
    for key in ("look", "fork", "moved", "owner"):
        rs = flagged[key]
        if not rs:
            continue
        title, blurb = titles[key]
        print(f"\n## {title} — {len(rs)} rows\n\n{blurb}\n")
        for r in sorted(rs, key=lambda r: (str(r.get("source") or ""), r["id"])):
            src = r.get("source")
            m, t = (anc.get(src) or {}) if src else {}, (cache.get(src) or {})
            paths = t.get("paths") or []
            hw, named = hits(paths, tokens(r))
            print(f"### `{r['tree']}:{r['id']}` — {r['name']}\n")
            why = classed.get((r["tree"], r["id"]))
            if why:
                print(f"- **{why}**")
            if not src:
                print(f"- no repo stated; QMK/ZMK said: {r.get('stated')}")
                if r.get("note"):
                    print(f"- flagged: {r['note']}")
                print()
                continue
            print(f"- drafted source: `{src}`")
            if m.get("full_name") and m["full_name"].lower() != src.lower():
                print(f"- **now** `{m['full_name']}`")
            if m.get("fork"):
                print(f"- **fork of** `{m.get('parent')}`"
                      + (f" (root `{m['root']}`)"
                         if m.get("root") and m["root"] != m.get("parent") else ""))
            print(f"- stated: {r.get('stated')}")
            print(f"- {m.get('stargazers_count')} stars, last push "
                  f"{(m.get('pushed_at') or '?')[:10]}"
                  + (", **archived**" if m.get("archived") else ""))
            if m.get("description"):
                print(f"- description: *{m['description']}*")
            if not paths:
                print("- tree: **unreadable**")
            else:
                print(f"- {len(hw)} hardware files of {len(paths)} total"
                      + (" (tree truncated)" if t.get("truncated") else ""))
                if named:
                    print(f"- **names this design** ({len(named)}): "
                          + ", ".join(f"`{p}`" for p in named[:6]))
                elif hw:
                    print("- **no hardware path names this design.** Present: "
                          + ", ".join(f"`{p}`" for p in hw[:6]))
            if r.get("note"):
                print(f"- flagged: {r['note']}")
            print()


if __name__ == "__main__":
    main()
