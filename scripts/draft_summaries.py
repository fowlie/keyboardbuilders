#!/usr/bin/env python3
"""Draft each design's summary from its repo's README, for a human to rule on.

234 design pages carry no prose at all. `Design.extras.summary` is `Recorded<String>`,
`design.summary` is a NOT NULL column and `design_search.hay_b` is weighted for it by
bm25 -- three slots held open since the schema was written, and the seed fills none of
them. Every design page says "No summary recorded."

WHAT THIS FIELD IS, AND WHY IT IS NOT LIKE THE OTHERS. Every other seeded fact is READ
from a file the author committed -- a footprint, a key count, a licence. A summary is
PROSE WRITTEN BY THE AUTHOR ABOUT THEIR OWN KEYBOARD, quoted. That makes it cheap to
check (the citation is one click away and the quote is verbatim) and expensive to get
wrong (it is a sentence this site puts under someone else's keyboard's name). So the
extraction is deliberately lossy: five gates reject, and only one signal auto-confirms.

  ABOUT         the paragraph must mention a keyboard at all. `kyrremann/bartlesplit`
                opens "Bartleby is a baby Rat Creature adopted by Fone Bone and Smiley",
                which is where the NAME came from and describes a comic character.
  NAV           `Showcase | Highlight Features | How this Project started` is a table of
                contents, and it is long, prose-shaped and does not start with a pipe.
  CAPTION       `*Axon version 1.0 built with Durock T1 and GMK Bento*` describes the
                photograph above it.
  REPO_LEVEL    "Here you'll find the design files for @tzarc's custom keyboard designs"
                introduces a COLLECTION. A README that introduces a collection cannot
                describe a member of it -- this is the multi-design repo showing through
                the prose rather than through the file tree.
  DISCLAIMER    "If you order PCB's from these, YOU have the sole responsibility for how
                these turn out!!!" is `tweetydabird/lotus58`'s opening line.

All five live in `measure_summaries.py`, which is the instrument that found them and
prints the distribution; this file is the drafter. Measured over 234 designs:

    111  a paragraph survives all five gates
     71  of those are DEFINITIONAL -- auto-confirmed, see below
     40  are prose that is not a definition -- drafted, a human rules
     70  the repo holds several designs, so one paragraph cannot be four summaries
     52  the README has nothing in it that survives
      1  no README we can read

DEFINITIONAL IS THE ONLY AUTO-CONFIRM, and it is the same bar `draft_boards.py` uses for
`sole`: a signal strong enough that a human confirming it would have nothing to decide.
"The 3w6 is a low profile, split ortholinear keyboard with 36 keys" says what the thing
IS. "It all started with a handwired Lily58fied version of the Atreis keyboard" is real
prose by the author and may well be the best sentence available, but choosing it is a
judgement, so it waits.

A SHARED REPO GETS NO ROW AT ALL. `build_seed.py`'s attribution rule already refuses to
state one repo's switch family for the four designs inside it; a summary is the same
error in prose, and a more embarrassing one, because the sentence would name the wrong
keyboard rather than merely be imprecise about it.

KEYED BY SLUG, unlike `sources.toml` and `boards.toml`, because the summary is a property
of the DESIGN and the design is the thing with a slug. The cost is that renaming a design
orphans its row -- so this script REPORTS orphans rather than dropping them silently, and
ticket 07 decision 8 keeps old slugs resolving in the meantime.

Usage: draft_summaries.py [--write seed/summaries.toml] [--report]
"""
import argparse
import collections
import json
import pathlib
import re
import sys
import tomllib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from measure_summaries import (  # noqa: E402
    DEFINITIONAL, GITHUB, first_paragraph, trim,
)

REPO = pathlib.Path(__file__).resolve().parent.parent

# A bare URL survives every gate and reads as noise in a sentence: `andreasch87/kapl`
# ends "Please read project wiki for details: https://…", and the trim then cuts the URL
# in half. Removed with whatever punctuation was introducing it.
URL = re.compile(r"\s*[:,]?\s*<?https?://\S+>?")

# A sentence that was going to list something. `hub20`'s README opens "Hub20 is a numpad
# with a bunch of features you never knew you needed. They include:" and the list is a
# bullet block this extractor already refuses to read.
LEAD_IN = re.compile(r"\s*[^.!?]*\b(include|includes|are|as follows|the following)\s*:\s*$", re.I)


def clean(text):
    """The quote as it will be printed: no URLs, no sentence left hanging on a colon."""
    if not text:
        return None
    text = URL.sub("", text)
    text = LEAD_IN.sub("", text).strip()
    text = re.sub(r"\s+", " ", text).strip(" -–—")
    return text or None


# Signals that mean SOMEONE RULED, as opposed to the drafter proposing. Both are copied
# through every re-draft untouched, for the reason `boards.toml` gives: this file is
# written from scratch on every run, so without the protection a re-draft eats the review
# queue's output. The drafter's own `confirmed` is NOT protected -- overturning it is the
# point of improving the extractor.
#
# `agent` IS A SEPARATE SIGNAL FROM `human` ON PURPOSE. It carries the same protection and
# makes a weaker claim, because `signal` records WHO VOUCHED and that is the whole value of
# the column. An agent read each sentence against the design and ruled; a person has not.
# Writing `human` for work a human did not do would corrupt the field irreversibly --
# nothing afterwards distinguishes a falsified `human` from a real one. Upgrading an
# `agent` row to `human` is one edit; un-falsifying the column is not possible.
RULED = ("human", "agent")


def ruled(path):
    """Rows someone ruled -- `signal` in RULED -- copied through every re-draft."""
    p = pathlib.Path(path)
    if not p.exists():
        return {}
    return {e["slug"]: e for e in tomllib.loads(p.read_text())["entry"]
            if e.get("signal") in RULED}


def draft(designs_path, readmes_path, keep=None):
    designs = json.loads((REPO / designs_path).read_text())
    readmes = json.loads((REPO / readmes_path).read_text())
    keep = keep or {}
    used = set()

    # A repo serving more than one design cannot supply a per-design sentence.
    per_repo = collections.Counter(s for d in designs for s in (d.get("sources") or []))

    rows, why = [], collections.Counter()
    for design in designs:
        slug = design["slug"]
        if slug in keep:
            rows.append(keep[slug])
            used.add(slug)
            why["kept, someone ruled"] += 1
            continue

        urls = design.get("sources") or []
        repo = next((f"{m.group(1)}/{m.group(2).removesuffix('.git')}"
                     for u in urls for m in [GITHUB.match(u)] if m), None)
        if repo is None:
            why["no github source"] += 1
            continue
        if any(per_repo[u] > 1 for u in urls):
            why["shared repo -- one README, several designs"] += 1
            continue
        readme = readmes.get(repo)
        if readme is None:
            why["no README we can read"] += 1
            continue
        summary = clean(trim(first_paragraph(readme)))
        if not summary:
            why["nothing in the README survives the gates"] += 1
            continue

        definitional = bool(DEFINITIONAL.match(summary))
        why["definitional -- confirmed" if definitional else "prose -- drafted"] += 1
        rows.append({
            "slug": slug,
            "source": repo,
            "summary": summary,
            "verdict": "confirmed" if definitional else "drafted",
            "signal": "definitional" if definitional else "prose",
            "note": "" if definitional else
                    "real prose by the author, but choosing it is a judgement -- read it "
                    "against the keyboard and confirm, reword or clear it",
        })

    # A ruled row whose slug never matched a current design -- the design was renamed or
    # dropped, and the loop above never had a chance to carry it forward or count it as an
    # orphan, because it only ever sees CURRENT slugs. Checking `rows` against `designs`
    # (the old code) was vacuous: every row in `rows` was appended from that same loop, so
    # its slug was always in `designs` by construction and the check could never fire.
    orphans = sorted(set(keep) - used)
    return rows, why, orphans


def toml_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def render(rows):
    lines = [
        "# seed/summaries.toml -- one sentence about each design, quoted from its own",
        "# README. Drafted by scripts/draft_summaries.py; keyed by SLUG, because a summary",
        "# is a property of the design and the design is the thing with a slug.",
        "#",
        "#   drafted    a machine proposed it, nobody has ruled -- EXCLUDED from the build",
        "#   confirmed  settled, use it",
        "#   declined   someone read it and rejected it: the README opens with something",
        "#              that is not a description of THIS keyboard. EXCLUDED, and the row",
        "#              persists -- delete it and the next re-draft proposes it again",
        "#",
        "# THE TEXT IS A QUOTATION, NOT OUR SENTENCE. It is copied verbatim from the",
        "# author's README and the page says so and links to it. That is what makes the row",
        "# cheap to check -- open `source`, read the first paragraph, compare -- and it is",
        "# why rewording one is a human's job and never the drafter's.",
        "#",
        "# `signal` says how it got here:",
        "#   definitional  it says what the keyboard IS (\"The 3w6 is a low profile, split",
        "#                 ortholinear keyboard with 36 keys\"), so a reviewer has nothing",
        "#                 to decide -- auto-confirmed, and overturnable like any proposal",
        "#   prose         real prose by the author that is not a definition. Drafted:",
        "#                 choosing it is a judgement, and the standing preference is that",
        "#                 a wrong sentence costs more than an absent one",
        "#   agent         an AGENT read the sentence against the design and ruled. Same",
        "#                 protection as `human`, deliberately weaker claim -- `signal`",
        "#                 records WHO vouched, which is the whole value of the column",
        "#   human         a PERSON ruled, and this row is copied through every re-draft",
        "#                 untouched -- the file is regenerated from scratch",
        "#",
        "# A DESIGN WITH NO ROW HAS NO SUMMARY, and the page says \"No summary recorded\"",
        "# rather than inventing one. 123 of the 234 have no row: 70 because their repo holds",
        "# several designs and one README cannot describe four keyboards, 52 because nothing",
        "# in their README survives the gates, and 1 because there is no README to read.",
        "",
    ]
    for row in sorted(rows, key=lambda r: r["slug"]):
        lines += [
            "[[entry]]",
            f'slug     = {toml_str(row["slug"])}',
            f'source   = {toml_str(row["source"])}',
            f'summary  = {toml_str(row["summary"])}',
            f'verdict  = {toml_str(row["verdict"])}',
            f'signal   = {toml_str(row["signal"])}',
            f'note     = {toml_str(row.get("note", ""))}',
            "",
        ]
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--designs", default="src/main/resources/seed/designs.json")
    ap.add_argument("--readmes", default="data/readme-scan.json")
    ap.add_argument("--write", nargs="?", const="seed/summaries.toml")
    args = ap.parse_args()

    rows, why, orphans = draft(args.designs, args.readmes,
                               keep=ruled(args.write or "seed/summaries.toml"))

    print(f"{len(rows)} rows\n")
    for reason, n in why.most_common():
        print(f"  {n:4}  {reason}")
    if orphans:
        print(f"\n  {len(orphans)} ORPHANED rows -- the design was renamed or dropped:")
        for slug in orphans:
            print(f"       {slug}")

    if args.write:
        (REPO / args.write).write_text(render(rows), encoding="utf-8")
        print(f"\nwrote {args.write}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
