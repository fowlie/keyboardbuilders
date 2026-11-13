#!/usr/bin/env python3
"""Which of the corpus's own board files would render as more than a bare PCB.

Backlog #60. The ticket's own five-board spike found the model-resolution rate is not
a rate at all -- it is four distinct causes, discoverable from a `(model ...)` line's
own STRING, with no clone needed to classify it:

  kiprjmod    `${KIPRJMOD}/...` -- ships with the repo
  stock-live  a KiCad path variable naming a directory the CURRENT stock library has
  stock-dead  the same, naming a directory KiCad has since RENAMED (5->6 broke this)
  absolute    a hardcoded path into the AUTHOR'S OWN MACHINE, e.g. `/Users/foostan/...`

**A fifth, more important finding from the same spike: a footprint can have NO `(model
...)` LINE AT ALL**, and this is not the same failure as the four above. `weteor/3w6`'s
36 switch footprints are a project-local library the author never attached a 3D part to
-- 94% of its OTHER model references resolve, and the render is still a bare board,
because the switches are the part a reader recognises a keyboard by. So this counts
switch footprints and non-switch footprints SEPARATELY: the corpus-wide "would this look
like a keyboard" answer is the switch number, not the aggregate one.

Reads over the network like `scan_boards.py`, not from a clone -- a model reference is
readable from the board file's own text; the model FILE it names is not needed to
classify it. `fetch(..., cap=0)` (borrowed rather than restated) reads the WHOLE file:
the cap that is safe for "does this file place a switch" is not safe here, because a
model line near the end of a multi-megabyte file is exactly as informative as one at
the start.

WHAT THIS DOES NOT VERIFY: whether a `${KIPRJMOD}`-relative path's file actually exists
in the repo. That would need the clone this scan exists to avoid.

WHAT "OTHER" COVERAGE DOES NOT MEAN: a board where every non-switch footprint resolves
can still be a bare copper FABRICATION PANEL rather than a keyboard -- `dilemma`'s
4x6_4 board is the measured example, two hands plus break-off rails and no soldermask at
all, and nothing in a board file's TEXT says "this is a panel" the way
`build_silhouettes.py`'s own geometry check does for silhouettes. That check is tied to
key-POSITION clustering, not to model references, and is not reused here. Treat "other"
coverage as "the non-switch parts would render", never as "this looks like a keyboard".

Usage: measure_3d_resolution.py [--json]
"""
import argparse
import json
import pathlib
import re
import sys
from collections import Counter

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from extract_switch_footprints import FOOTPRINT  # noqa: E402
from scan_boards import fetch, is_switch  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
SCRATCH = REPO/"data"

# `(model "quoted/path"` is the KiCad 6+ shape; `(model unquoted/path.wrl` with no
# quotes at all is what a pre-6 file (`3w6`'s sibling `sofle-choc`, "Legacy zone fill
# strategy" in its own load warning) writes instead. Missing the second form undercounts
# by 48 of 49 refs on that one file alone -- caught by checking this script's own numbers
# against the five boards the ticket's spike already rendered, not by inspection.
MODEL = re.compile(r'\(model\s+(?:"([^"]+)"|(\S+))')

# From `kicad/kicad:9.0-full`'s own `/usr/share/kicad/3dmodels`, read 2026-09-09 --
# 105 directories. Kept as a file rather than hardcoded here because it is a fact about
# an image tag, not about this repo, and the two should be able to drift independently.
STOCK_DIRS = frozenset(
    (SCRATCH/"kicad-9.0-full-3dmodels.txt").read_text().split()
)

KICAD_VAR = re.compile(r"\$\{(KI\w*3DMODEL\w*|KISYS3DMOD)\}")


def classify_ref(ref):
    """One of "kiprjmod", "stock-live", "stock-dead", "absolute" for a `(model ...)` path."""
    if ref.startswith("${KIPRJMOD}"):
        return "kiprjmod"
    m = KICAD_VAR.search(ref)
    if m:
        rest = ref[m.end():].lstrip("/")
        libdir = rest.split("/", 1)[0] if rest else ""
        return "stock-live" if libdir in STOCK_DIRS else "stock-dead"
    return "absolute"


def footprint_blocks(text):
    """(name, block_text) for every footprint, block_text running to the next one.

    Approximate -- it is the next `(footprint`/`(module` START, not a balanced-paren
    close -- but a model line belongs to exactly one footprint and KiCad never
    interleaves two footprints' bodies, so the approximation cannot misattribute one.
    """
    starts = list(FOOTPRINT.finditer(text))
    names = [(q or bare or "") for q, bare in ((m.group(1), m.group(2)) for m in starts)]
    bounds = [m.start() for m in starts] + [len(text)]
    return list(zip(names, (text[bounds[i]:bounds[i + 1]] for i in range(len(starts)))))


def resolution(text):
    """{"switch": Counter, "other": Counter} of the footprints (not the raw references)
    in each bucket, plus a (footprints, modeled, showing) triple per bucket.

    COUNTED PER FOOTPRINT, NOT PER REFERENCE, on both denominator and numerator. A
    single footprint can carry more than one `(model ...)` line -- a through-hole part
    with an alternate body, say -- and the first version of this counted references,
    so a footprint with two resolving refs outweighed one with a single resolving ref
    and a design's own rate could exceed 1.0. What a reader sees is binary per footprint:
    it shows something, or it does not.

    The denominator is every footprint in the bucket, not just the ones that happen to
    carry a reference at all. `potato65` is why: 212 switch footprints, only 66 ever
    modeled, and a rate measured against 66 instead of 212 reads a perfect 100% for a
    board with 146 empty holes.
    """
    out = {"switch": Counter(), "other": Counter()}
    fp_count = {"switch": 0, "other": 0}
    modeled_count = {"switch": 0, "other": 0}
    showing_count = {"switch": 0, "other": 0}
    for name, block in footprint_blocks(text):
        refs = [q or bare for q, bare in MODEL.findall(block)]
        bucket = "switch" if is_switch(name) else "other"
        fp_count[bucket] += 1
        modeled_count[bucket] += bool(refs)
        causes = {classify_ref(r) for r in refs}
        showing_count[bucket] += bool(causes & {"kiprjmod", "stock-live"})
        # Reported cause distribution stays per-reference (kept for the corpus-wide
        # cause breakdown), which double-counting cannot distort the way a rate can.
        for ref in refs:
            out[bucket][classify_ref(ref)] += 1
    return out, fp_count, modeled_count, showing_count


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--json", action="store_true", help="write the per-design scan too")
    args = ap.parse_args()

    boards = json.loads((SCRATCH/"design-boards.json").read_text())

    per_design = {}
    corpus_switch, corpus_other = Counter(), Counter()
    totals = {"switch": [0, 0], "other": [0, 0]}  # [footprints, modeled]
    for slug in sorted(boards):
        info = boards[slug]
        by_bucket = {"switch": Counter(), "other": Counter()}
        fp_count = {"switch": 0, "other": 0}
        modeled_count = {"switch": 0, "other": 0}
        showing_count = {"switch": 0, "other": 0}
        for path in info["paths"]:
            text, err, _ = fetch(info["repo"], path, cap=0)
            if text is None:
                continue
            out, fpc, modc, showc = resolution(text)
            for k in by_bucket:
                by_bucket[k] += out[k]
                fp_count[k] += fpc[k]
                modeled_count[k] += modc[k]
                showing_count[k] += showc[k]

        # THE DENOMINATOR IS EVERY FOOTPRINT IN THE BUCKET, AND THE NUMERATOR IS
        # FOOTPRINTS THAT SHOW SOMETHING, NEITHER ONE A SUM OF RAW REFERENCES --
        # for "other" exactly as much as for "switch". `potato65` places 212 switch
        # footprints and only 66 ever had a model attached; a rate measured against 66
        # instead of 212 reads 100% for a board with 146 empty holes. Caught by
        # rendering a design this script had not been tuned against.
        entry = {"repo": info["repo"]}
        for bucket in ("switch", "other"):
            entry[f"{bucket}_footprints"] = fp_count[bucket]
            entry[f"{bucket}_footprints_with_any_model"] = modeled_count[bucket]
            entry[f"{bucket}_rate"] = (
                round(showing_count[bucket] / fp_count[bucket], 3) if fp_count[bucket] else 0.0
            )
        entry["by_cause"] = {"switch": dict(by_bucket["switch"]), "other": dict(by_bucket["other"])}
        per_design[slug] = entry
        corpus_switch += by_bucket["switch"]
        corpus_other += by_bucket["other"]
        for bucket in ("switch", "other"):
            totals[bucket][0] += fp_count[bucket]
            totals[bucket][1] += modeled_count[bucket]

    def bucket_for(rate, footprints, modeled, noun):
        if footprints == 0:
            return f"no {noun} footprints read"
        if modeled == 0:
            return f"{noun}s have NO model line at all"
        if rate >= 0.8:
            return f"{noun}s modeled AND >=80% resolvable"
        if rate >= 0.3:
            return f"{noun}s modeled, 30-80% resolvable"
        return f"{noun}s modeled, <30% resolvable"

    switch_buckets, other_buckets = Counter(), Counter()
    for d in per_design.values():
        switch_buckets[bucket_for(d["switch_rate"], d["switch_footprints"],
                                   d["switch_footprints_with_any_model"], "switch")] += 1
        other_buckets[bucket_for(d["other_rate"], d["other_footprints"],
                                  d["other_footprints_with_any_model"], "other")] += 1

    print(f"  {len(per_design)} designs measured\n")
    for bucket in ("switch", "other"):
        fp, modeled = totals[bucket]
        print(f"  {bucket} footprints read:        {fp}")
        print(f"  ..with at least one model line: {modeled}  "
              f"({modeled * 100 // fp if fp else 0}%)")
    print()
    print("  of the model refs that DO exist, by cause:")
    print("  switch footprints:")
    for cause, count in corpus_switch.most_common():
        print(f"    {cause:<12}{count:>6}  {count * 100 // sum(corpus_switch.values()):>3}%")
    print("  every other footprint:")
    for cause, count in corpus_other.most_common():
        print(f"    {cause:<12}{count:>6}  {count * 100 // sum(corpus_other.values()):>3}%")
    print("\n  SWITCHES (would a reader see key positions):")
    for label, count in switch_buckets.most_common():
        print(f"    {count:>4}  {label}")
    print("\n  OTHER FOOTPRINTS (would the rest of the board look populated):")
    for label, count in other_buckets.most_common():
        print(f"    {count:>4}  {label}")

    worth_it = sorted(s for s, d in per_design.items()
                       if d["switch_footprints_with_any_model"] > 0 and d["switch_rate"] >= 0.8)
    print(f"\n  worth rendering, switches included ({len(worth_it)} of {len(per_design)}): "
          f"{', '.join(worth_it[:15])}" + (" ..." if len(worth_it) > 15 else ""))

    # A GOOD-LOOKING BOARD WITHOUT SWITCHES -- the user's actual question. >=80% of
    # OTHER footprints resolve, but switches do NOT (otherwise it is already in the
    # list above). NOT screened for fabrication panels; see the module docstring.
    pcb_only = sorted(s for s, d in per_design.items()
                       if d["other_footprints_with_any_model"] > 0 and d["other_rate"] >= 0.8
                       and s not in worth_it)
    print(f"\n  good-looking PCB, switches NOT included ({len(pcb_only)} of {len(per_design)}): "
          f"{', '.join(pcb_only[:15])}" + (" ..." if len(pcb_only) > 15 else ""))
    print("  (not screened for fabrication panels -- a board can score well here and still")
    print("   be a bare copper panel; see the docstring)")

    if args.json:
        out = SCRATCH/"model-resolution-scan.json"
        out.write_text(json.dumps(per_design, indent=1, sort_keys=True))
        print(f"\n  {len(per_design)} designs -> {out.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
