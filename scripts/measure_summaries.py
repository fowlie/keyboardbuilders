#!/usr/bin/env python3
"""Can a design's README supply the summary the corpus has never had?

234 design pages carry no prose at all: `Design.extras.summary` is `Recorded<String>`,
`design.summary` is a NOT NULL column, `design_search.hay_b` is weighted for it by bm25,
and the seed fills none of them. This measures whether the obvious source works before
anything is built on it.

WHAT "WORKS" HAS TO MEAN HERE. A summary is the one field that would be PROSE WRITTEN BY
SOMEONE ELSE, so the bar is not "did we get a string". Three ways it fails, and they need
telling apart:

  * nothing usable        the README is a title, a badge wall, or a build guide that
                          opens mid-instruction
  * usable but not ABOUT  the first paragraph describes the repo, the licence or the
                          firmware rather than the keyboard
  * usable and shared     the repo holds several designs, so one paragraph would be
                          stated as a fact about each of them -- exactly the failure
                          `build_seed.py`'s attribution rule already refuses for switch
                          families, and the reason 24 repos are held back here

`gh` supplies the credential, as in `fetch_activity.py`. This script writes NOTHING into
the seed; it prints a distribution and a sample to read.

Usage: measure_summaries.py [--designs FILE] [--out FILE] [--sample N]
"""
import argparse
import collections
import html
import json
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
GITHUB = re.compile(r"https://github\.com/([^/]+)/([^/#?]+)")

# Four objects per repo already; 25 keeps one failed batch cheap.
BATCH = 25

CANDIDATES = {
    "md": "README.md",
    "lower": "readme.md",
    "bare": "README",
    "rst": "README.rst",
    "txt": "README.txt",
}

FENCE = re.compile(r"^\s*(```|~~~)")
COMMENT = re.compile(r"<!--.*?-->", re.S)
TAG = re.compile(r"<[^>]+>")
IMAGE = re.compile(r"!\[[^\]]*\]\([^)]*\)")
LINKED_IMAGE = re.compile(r"\[\s*!\[[^\]]*\]\([^)]*\)\s*\]\([^)]*\)")
LINK = re.compile(r"\[([^\]]*)\]\([^)]*\)")
SETEXT = re.compile(r"^\s*(=+|-{3,})\s*$")
BULLET = re.compile(r"^\s*([-*+]\s|\d+[.)]\s)")
EMPHASIS = re.compile(r"\*\*|__|(?<!\w)[*_](?!\s)")

# A NAV ROW, not a sentence: `Showcase | Highlight Features | How this Project started`
# is `rarepotato8de/3x3macropad`'s table of contents and it passed every structural test
# -- it is long, it is prose-shaped, and it does not start with a pipe.
NAV = re.compile(r"\S\s*\|\s*\S.*\S\s*\|\s*\S")

# AN IMAGE CAPTION. `*Axon version 1.0 built with Durock T1 and GMK Bento*` is the line
# under the hero photo, italicised whole, and it reads as a description until you notice
# it is describing a photograph.
#
# SINGLE DELIMITER ONLY -- markdown italics (`*x*`, `_x_`), not bold (`**x**`, `__x__`).
# The naive `[*_].+[*_]` matches both, so `coseyfannitutti/mysterium`'s bolded definitional
# line ("**TKL keyboard that can be entirely assembled using only through hole
# components...**") was being read as a caption and thrown away. A reader bolds a sentence
# to emphasise it; italicising a whole line is what you do to a caption under a photo --
# different convention, and only one of them means "this describes a picture, not itself".
CAPTION = re.compile(r"^\s*(\*(?!\*)[^*]+(?<!\*)\*(?!\*)|_(?!_)[^_]+(?<!_)_(?!_))\s*$")

# THE RELEVANCE GATE, and it is the one that earns its place. Everything above rejects a
# shape; this rejects a paragraph that is well-formed prose ABOUT SOMETHING ELSE --
# `kyrremann/bartlesplit` opens "Bartleby is a baby Rat Creature adopted by Fone Bone and
# Smiley", which is where the keyboard's NAME comes from and says nothing about the
# keyboard. Structural tests cannot catch that; a subject test can.
#
# `qmk`/`zmk` are deliberately NOT here: `knaruo/barracuda` opens "QMK support is
# available in my forked qmk_firmware", which is a fact about firmware support and not a
# description of the board.
ABOUT = re.compile(
    r"\b(keyboard|keypad|macropad|macro[ -]?pad|numpad|num[ -]?pad|keeb|pcb|switch(es)?|"
    r"keycaps?|keys|split|ortholinear|ergonomic|column[- ]staggered|row[- ]staggered|"
    r"thumb cluster|handwired|hand[- ]wired|hotswap|hot[- ]swap|encoder|choc|cherry mx)\b",
    re.I)

# ABOUT THE REPOSITORY, NOT THE KEYBOARD. `tzarc/keyboards` opens "Here you'll find the
# design files for @tzarc's custom keyboard designs" and `joe-scotto/scottokeebs` opens
# "ScottoKeebs at its core is an extensive collection" -- both name a keyboard and neither
# describes one, so ABOUT passes them. This is the multi-design repo showing through the
# prose: a README that introduces a COLLECTION cannot describe a member of it.
REPO_LEVEL = re.compile(
    r"^\s*(this (repo|repository)|here (you|you'll|is|are)|in this (repo|repository)|"
    r"welcome|hi there|hello|my keyboard designs|the following)\b"
    r"|\b(design files for|collection of|repository (of|for|detailing)|warehouse of|"
    r"contains (the )?(pcb|design|kicad|3d))\b", re.I)

# A CAVEAT OR A DISCLAIMER, which opens a surprising number of READMEs and reads as a
# description until you notice it is a warning. `weteor/elephant42` opens "currently, not
# compatible with some switches"; `tweetydabird/lotus58` opens "If you order PCB's from
# these, YOU have the sole responsibility for how these turn out!!!".
DISCLAIMER = re.compile(
    r"^\s*(currently|note|warning|caution|disclaimer|important|if you order|please note)\b"
    r"|\bsole responsibility\b|\bat your own risk\b|\bnot (yet )?(compatible|tested|working)\b",
    re.I)

# THE STRONG SIGNAL, and the only one that auto-confirms. A DEFINITIONAL sentence says
# what the thing IS -- "The 3w6 is a low profile, split ortholinear keyboard with 36 keys",
# "A 12 key mechanical keypad that can be used as a arrow-pad". Everything else that
# survives the gates above is real prose by the author and may well be right, but it is a
# judgement rather than a reading, so it waits for a human the way every other seed row
# does.
NOUN = r"(keyboard|keypad|macropad|macro pad|numpad|num pad|keeb|pcb)"
DEFINITIONAL = re.compile(
    rf"^\s*(the|a|an|this)?\s*[\w'\-. ]{{0,40}}\b(is|are)\b[^.]{{0,120}}\b{NOUN}\b"
    rf"|^\s*(a|an|the)\b[^.]{{0,100}}\b{NOUN}\b", re.I)

# Trailing link bait, left behind once the link markup is stripped: `marvfpv/adalyn` ends
# "check out his repo here", which was a hyperlink and is now a dangling instruction.
DANGLER = re.compile(
    r"[,;.]?\s*(check (it |him |her |them |his |out)?[\w ]*here|see here|more (info|details)"
    r"[\w ]*here|read more|available here|link(ed)? here|here on github|on github here)"
    r"\s*[.!]?\s*$", re.I)


def repos_in(designs):
    found = {}
    for design in designs:
        for source in design.get("sources") or []:
            hit = GITHUB.match(source)
            if hit:
                owner, name = hit.group(1), hit.group(2).removesuffix(".git")
                found.setdefault(f"{owner}/{name}", (owner, name))
    return dict(sorted(found.items()))


def query_for(batch):
    parts = []
    for i, (owner, name) in enumerate(batch):
        objects = " ".join(
            f'{key}: object(expression: "HEAD:{path}") {{ ... on Blob {{ text }} }}'
            for key, path in CANDIDATES.items()
        )
        parts.append(f'  r{i}: repository(owner: "{owner}", name: "{name}") {{ {objects} }}')
    return "query {\n" + "\n".join(parts) + "\n}"


def fetch(repos):
    """slug -> README text, or None where the repo has none we can read."""
    out = {}
    slugs = list(repos)
    for start in range(0, len(slugs), BATCH):
        chunk = slugs[start:start + BATCH]
        query = query_for([repos[s] for s in chunk])
        run = subprocess.run(["gh", "api", "graphql", "-f", f"query={query}"],
                             capture_output=True, text=True)
        if run.returncode != 0:
            # A batch that fails is ABSENT, never empty text -- the same rule
            # `fetch_activity.py` takes for a 404 repo.
            print(f"  batch {start}: {run.stderr.strip()[:200]}", file=sys.stderr)
            for slug in chunk:
                out[slug] = None
            continue
        data = json.loads(run.stdout).get("data") or {}
        for i, slug in enumerate(chunk):
            repo = data.get(f"r{i}") or {}
            text = None
            for key in CANDIDATES:
                blob = repo.get(key)
                if blob and blob.get("text"):
                    text = blob["text"]
                    break
            out[slug] = text
        print(f"  {min(start + BATCH, len(slugs))}/{len(slugs)}", file=sys.stderr)
    return out


def strip_inline(line):
    """A line with its decoration removed, so what is left is what a reader would read."""
    line = LINKED_IMAGE.sub("", line)
    line = IMAGE.sub("", line)
    line = LINK.sub(r"\1", line)
    line = TAG.sub("", line)
    line = EMPHASIS.sub("", line)
    return line.strip()


def is_prose(line):
    """Prose, or one of the many things a README opens with that is not a sentence."""
    if not line or SETEXT.match(line):
        return False
    stripped = line.lstrip()
    if stripped.startswith("#") or stripped.startswith("|") or BULLET.match(stripped):
        return False
    if NAV.search(stripped) or CAPTION.match(stripped):
        return False
    text = strip_inline(stripped.lstrip("> "))
    if len(text) < 20:
        return False
    # A badge wall reduces to nothing once its images and links are stripped; a heading
    # that survived the `#` test as raw HTML reduces to a couple of words.
    return len(text.split()) >= 5


def first_paragraph(readme):
    """The README's first block of running prose, decoration removed."""
    if not readme:
        return None
    body = COMMENT.sub("", readme)
    lines, fenced = [], False
    for raw in body.splitlines():
        if FENCE.match(raw):
            fenced = not fenced
            continue
        if not fenced:
            lines.append(raw)

    para, started = [], False
    for raw in lines:
        if is_prose(raw):
            para.append(strip_inline(raw.lstrip("> ")))
            started = True
        elif started:
            break
    if not para:
        return None
    text = re.sub(r"\s+", " ", " ".join(para)).strip()
    # TO A FIXED POINT, because danglers nest. `marvfpv/adalyn` ends "...by somepin here
    # on GitHub, check out his repo here": one pass removes the trailing clause and leaves
    # "here on GitHub" as the new tail, which is the same defect one link further in.
    for _ in range(4):
        shorter = DANGLER.sub("", text).strip().rstrip(",;")
        if shorter == text:
            break
        text = shorter
    text = html.unescape(text)
    if not text or not ABOUT.search(text):
        # Well-formed prose about something else -- see ABOUT. Absent beats wrong.
        return None
    if REPO_LEVEL.search(text) or DISCLAIMER.search(text):
        return None
    return text


def trim(text, limit=300):
    """Whole sentences up to the limit, and never a fragment."""
    if not text:
        return None
    if len(text) <= limit:
        return text
    cut = text[:limit]
    end = max(cut.rfind(". "), cut.rfind("! "), cut.rfind("? "))
    if end > 60:
        return cut[:end + 1]
    return cut.rsplit(" ", 1)[0] + "…"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--designs", default="src/main/resources/seed/designs.json")
    ap.add_argument("--out", default="data/readme-scan.json")
    ap.add_argument("--sample", type=int, default=25)
    args = ap.parse_args()

    designs = json.loads((REPO / args.designs).read_text())
    repos = repos_in(designs)

    cache = REPO / args.out
    if cache.exists():
        readmes = json.loads(cache.read_text())
        print(f"{len(readmes)} READMEs from {args.out}", file=sys.stderr)
    else:
        print(f"fetching {len(repos)} READMEs", file=sys.stderr)
        readmes = fetch(repos)
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(json.dumps(readmes, indent=1))

    # How many designs share a repo -- the summary cannot be stated per design where it
    # is read per repo.
    per_repo = collections.Counter(
        s for d in designs for s in (d.get("sources") or []))
    sole = {}
    for design in designs:
        urls = design.get("sources") or []
        sole[design["slug"]] = all(per_repo[u] == 1 for u in urls)

    verdicts = collections.Counter()
    rows = []
    for design in designs:
        slug = design["slug"]
        repo = next((f"{m.group(1)}/{m.group(2).removesuffix('.git')}"
                     for u in (design.get("sources") or [])
                     for m in [GITHUB.match(u)] if m), None)
        readme = readmes.get(repo) if repo else None
        para = trim(first_paragraph(readme))
        if repo is None:
            verdict = "no github source"
        elif readme is None:
            verdict = "no README we can read"
        elif para is None:
            verdict = "no opening prose about the keyboard"
        elif not sole[slug]:
            verdict = "shared repo -- one paragraph, several designs"
        else:
            verdict = "usable"
        verdicts[verdict] += 1
        rows.append((verdict, slug, repo, para))

    print(f"\n{len(designs)} designs\n")
    for verdict, n in verdicts.most_common():
        print(f"  {n:4}  {verdict}")

    print(f"\n-- a sample of {args.sample} usable summaries, to read rather than count\n")
    usable = [r for r in rows if r[0] == "usable"]
    for _, slug, repo, para in usable[:args.sample]:
        print(f"{slug}  ({repo})\n    {para}\n")

    print("-- and the ones with no opening prose, which is where the work would be\n")
    for _, slug, repo, _ in [r for r in rows if r[0] == "no opening prose about the keyboard"][:15]:
        print(f"  {slug:34} {repo}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
