#!/usr/bin/env python3
"""Where our facets contradict the author's own words.

Backlog #35 found `?body=unibody` returning split keyboards, and it found it BY ACCIDENT:
someone read the 71 author-written summaries beside our own facets and noticed `torn` was
filed unibody while its author called it "The split keyboard kit". That ticket's margin
says the obvious thing -- *"the summaries are now a free cross-check on every facet they
mention, and this is the first thing they caught. Worth running deliberately rather than
by accident."* This is that, run deliberately.

THE SUMMARIES ARE A PRIMARY SOURCE AND NOT OUR PROSE. `seed/summaries.toml` holds
sentences copied verbatim from each author's README, human-confirmed, one per design. When
the person who designed a keyboard writes "split" and our record says unibody, one of the
two is wrong and it is not usually them.

THE ASYMMETRY IS THE WHOLE DESIGN, and it is the lesson this corpus keeps re-teaching --
most recently on 2026-09-07, when a footprint scan's silence had been read as "this board
has room for nothing" and 11 pages said so untruthfully. A regex over prose is an
instrument like any other: it can prove a claim was MADE and can never prove one was not.
A one-sentence summary omits almost everything true about a keyboard. So:

  * prose asserts X, we assert not-X   -> CONTRADICTION. One of us is wrong. Reported.
  * prose asserts X, we record nothing -> GAP. Our extractor missed something the author
                                          stated in plain English. Reported separately,
                                          because the fix is different: widen an
                                          instrument rather than correct a value.
  * prose is silent                    -> NOTHING. Not evidence, not reported, and the
                                          reason this script does not simply diff.

`MX` IS THE TRAP IN HERE and it is worth naming before it bites someone. `41-1350`'s
summary reads "choc-v2 & v1 switches, MX-spaced": the MX is the SPACING, not the switch,
and a naive match files a Choc board as taking MX switches -- inventing the exact class of
wrong value this script exists to find. Spacing phrasings are excluded explicitly below.

Usage: measure_summary_agreement.py [--json PATH]
"""
import argparse
import collections
import json
import pathlib
import re
import sys
import tomllib

REPO = pathlib.Path(__file__).resolve().parent.parent

# "MX" as a SWITCH, never as a pitch. `MX-spaced`, `MX spacing`, `MX-compatible spacing`
# and `MX-pitch` all describe the grid a Choc board can sit on. See the docstring.
MX_AS_SPACING = re.compile(r"mx[\s-]*(spac|pitch|compatible\s+spac)", re.I)

# "SPLIT" IS TWO WORDS IN THIS HOBBY and the first draft of this script conflated them,
# which is #35's own error in mirror image -- reading a LAYOUT word as a CONSTRUCTION
# fact. `andean-condor` is "48 key monoblock split keyboard": one piece of PCB with a
# split layout, and our `unibody` is right. `lumberjack` is "a split 5x12 ortholinear
# keyboard PCB" -- one board, 60 keys. `la_nc` delivers "a lightly split, ortho typing
# experience". All three were reported as contradictions and none was one.
SPLIT_IS_A_LAYOUT = re.compile(
    r"monoblock|monosplit|unibody|one[\s-]piece|light(ly)?\s+split|"
    r"split\s+(ergonomic\s+)?layout|split\s+\d+\s*[x×]\s*\d+", re.I)

# A NUMBER BESIDE "KEY" IS NOT ALWAYS THE KEY COUNT. `sofle-pico` is "6×4+5 keys" -- the 5
# is the thumb row, not the keyboard; `for-science` has "a 3 key thumb cluster"; and
# `tenkey-plusplus` has "the layout of a standard 10-key", where 10-key is the NAME of a
# numpad. Each was reported as a contradiction against a correct value.
COUNT_IS_A_PART = re.compile(
    r"(?:[+×x]\s*)$|(?:standard\s+|a\s+)$", re.I)          # what precedes the number
COUNT_IS_A_CLUSTER = re.compile(
    r"^\s*(?:thumb|cluster|thumbs|inner|outer|extra|additional|numpad|layout)", re.I)

# PROSE CAN DESCRIBE A DIFFERENT KEYBOARD. `pteron56v0` reads "This project is the
# evolution of the Pteron-Keyboard project, an incredible ergonomic keyboard that was
# handwired only" -- the handwiring is the ANCESTOR's, and our `pcb` is right. A summary
# is one sentence and the sentence is not always about this design.
ABOUT_ANOTHER_DESIGN = re.compile(
    r"evolution of|based on|inspired by|predecessor|the original|fork of|"
    r"successor to|derived from", re.I)


def claims(text):
    """What a summary ASSERTS. Absent keys mean the sentence said nothing, never 'no'."""
    t = text.lower()
    out = {}

    # -- body ----------------------------------------------------------------------
    if SPLIT_IS_A_LAYOUT.search(t):
        out["body"] = "unibody"
    elif re.search(r"\bsplit\b", t) and not re.search(r"\bnon[\s-]?split\b", t):
        out["body"] = "split"

    # -- key count, with the parts filtered out. See COUNT_IS_A_PART.
    keys = []
    for m in re.finditer(r"(\d{1,3})\s*[\s-]?\s*keys?\b", t):
        if COUNT_IS_A_PART.search(t[max(0, m.start() - 12):m.start()]):
            continue
        if COUNT_IS_A_CLUSTER.search(t[m.end():m.end() + 14]):
            continue
        keys.append(int(m.group(1)))
    # "60 keys total" is the author settling it explicitly, so it wins outright.
    total = re.search(r"\((\d{1,3})\s*keys?\s*total\)|(\d{1,3})\s*keys?\s*total", t)
    if total:
        out["keys"] = [int(total.group(1) or total.group(2))]
    elif keys:
        out["keys"] = sorted(set(keys))

    # -- switches ------------------------------------------------------------------
    switches = set()
    if re.search(r"\bchoc\b", t):
        switches.add("choc")
    if re.search(r"\bmx\b", t) and not MX_AS_SPACING.search(t):
        switches.add("mx")
    if switches:
        out["switches"] = sorted(switches)

    # -- build method --------------------------------------------------------------
    hw = re.search(r"hand[\s-]?wired|handwire", t)
    if hw:
        # POSITION DECIDES WHOSE ATTRIBUTE IT IS. `pteron56v0` is "the evolution of the
        # Pteron-Keyboard project, ... that was handwired only" -- the attribution comes
        # FIRST and the handwiring belongs to the ancestor. `adalyn` is a "handwired
        # keyboard based on the great idea by somepin" -- the handwiring comes first and
        # is its own; only the idea is borrowed. A flat "does the sentence mention another
        # project" test silently dropped adalyn, which is a real finding, so the rule is
        # positional rather than presence-based.
        ref = ABOUT_ANOTHER_DESIGN.search(t)
        if not (ref and ref.start() < hw.start()):
            out["build"] = "handwire"
    elif re.search(r"\bpcb\b", t):
        out["build"] = "pcb"

    # -- extensions, the facet shipped on 2026-09-07 -------------------------------
    ext = set()
    for kind, pat in (
        ("encoder",   r"\bencoder|rotary\b"),
        ("display",   r"\boled\b|\bdisplay\b|\blcd\b|e[\s-]?paper|e[\s-]?ink"),
        ("trackball", r"trackball"),
        ("trackpad",  r"trackpad|touchpad|cirque"),
        ("speaker",   r"speaker|buzzer|piezo"),
        ("haptic",    r"haptic"),
    ):
        if re.search(pat, t):
            ext.add(kind)
    if ext:
        out["ext"] = sorted(ext)
    return out


def ours(d):
    """The same questions, answered from the record. `None` means we say nothing."""
    body = d["body"]["kind"]
    sw = set()
    for s in d["switches"]:
        k = s["kind"]
        sw.add("choc" if k.startswith("choc") else "mx" if k == "mx" else k)
    return {
        "body": "split" if body in ("split",) else "unibody" if body == "unibody" else body,
        "keys": sorted({l["keys"] for l in d["layouts"]}),
        "switches": sorted(sw),
        "build": d["buildMethod"]["kind"],
        # ABSENT, not empty: `extensions` is written only where evidence was found, and
        # #66 is explicit that no evidence is not evidence of none.
        "ext": sorted(d["extensions"]) if "extensions" in d else None,
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--json", metavar="PATH", help="write the findings for follow-up")
    args = ap.parse_args()

    designs = {d["slug"]: d for d in
               json.loads((REPO/"src/main/resources/seed/designs.json").read_text())}
    rows = tomllib.loads((REPO/"seed/summaries.toml").read_text())["entry"]
    confirmed = [e for e in rows if e.get("verdict") == "confirmed" and e.get("summary")]

    contradictions, gaps = [], []
    checked = collections.Counter()
    for row in confirmed:
        d = designs.get(row["slug"])
        if d is None:
            continue                      # a summary for a design that no longer publishes
        said, have = claims(row["summary"]), ours(d)
        for field, asserted in said.items():
            checked[field] += 1
            mine = have.get(field)
            if mine is None:
                gaps.append((row["slug"], field, asserted, "we record nothing", row["summary"]))
                continue
            if field in ("keys", "switches", "ext"):
                missing = sorted(set(asserted) - set(mine))
                if not missing:
                    continue
                # A one-sentence summary naming a part we did not find is a GAP in our
                # reading. It is only a CONTRADICTION where our value is a closed
                # statement about the same thing -- switches and key count are; the
                # extensions set is explicitly a lower bound and can only ever be short.
                (gaps if field == "ext" else contradictions).append(
                    (row["slug"], field, asserted, mine, row["summary"]))
            elif asserted != mine:
                contradictions.append((row["slug"], field, asserted, mine, row["summary"]))

    print(f"  {len(confirmed)} confirmed summaries, {sum(checked.values())} facet claims "
          f"read from them\n")
    print("  claims by facet: " + ", ".join(f"{k} {v}" for k, v in checked.most_common()))

    for title, rowset, note in (
        ("CONTRADICTIONS -- one of us is wrong, and it is not usually the author",
         contradictions, "our value is a closed statement about the same thing"),
        ("GAPS -- the author states it and our instruments did not find it",
         gaps, "fix by widening an extractor, not by correcting a value"),
    ):
        print(f"\n  {title}")
        print(f"    ({note})")
        if not rowset:
            print("    none"); continue
        by_field = collections.defaultdict(list)
        for r in rowset:
            by_field[r[1]].append(r)
        for field, items in sorted(by_field.items(), key=lambda kv: -len(kv[1])):
            print(f"\n    {field}  ({len(items)})")
            for slug, _, said, mine, summary in items[:12]:
                print(f"      {slug:<26} author: {said}   ours: {mine}")
                print(f"      {'':<26} \"{summary[:88]}\"")
            if len(items) > 12:
                print(f"      ... {len(items) - 12} more")

    if args.json:
        pathlib.Path(args.json).write_text(json.dumps(
            {"contradictions": [dict(zip(("slug", "field", "author", "ours", "summary"), r))
                                for r in contradictions],
             "gaps": [dict(zip(("slug", "field", "author", "ours", "summary"), r))
                      for r in gaps]}, indent=1))
        print(f"\n  -> {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
