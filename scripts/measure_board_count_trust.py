#!/usr/bin/env python3
"""When may a board file's own switch count BE the design's key count?

Ticket 55 asks it of
the 71 boards no firmware record may describe: the owner ruled them in *conditional on
the count being provable per board*, and nothing in the corpus adjudicates a count
except a firmware record. So this asks whether the board can adjudicate itself, and
prices the answer against a control rather than a hope.

THE CONTROL IS THE PUBLISHED CORPUS. Every published design's count has already been
adjudicated by a record, so for each one we can ask what the board alone would have
said and whether it would have been right. Single-board designs only -- summing 22
revision files of `lotus58` is not a reading of anything.

THE INSTRUMENTS, and the finding is the gap between them:

    footprints   `scan_boards.py`'s count of switch-like footprints placed
    positions    `scan_switch_positions.py`'s count of DISTINCT (x, y) placements

They disagree for two opposite reasons. `chouchou` carries an MX footprint *and* a
Choc footprint on every key, so 20 keys read as 40 -- the footprint count is high.
`largelad` reads 83 footprints against 147 positions, because a footprint name the
switch matcher does not recognise still lands at a position -- the footprint count is
low. Either way, **disagreement means at least one instrument is wrong about this
board**, and that is the signal.

THE RESULT, measured 2026-09-02 over 244 single-board published designs:

    both instruments agree      n=149   the reading is exactly right   85%
    they disagree, footprints   n= 94                                  14%
    they disagree, positions    n= 94                                  38%

So agreement is a real discriminator -- 85% against 14% -- and it is the only one this
corpus offers. It is also NOT 100%, and the 22 failures are the alternates problem
ticket 55 predicted from `bakeneko-65`: `arisu` agrees at 64 and publishes 67,
`adalyn` at 34 publishes 36, `friedrich` at 50 publishes 52. **Twenty of the 22 are off
by 1-3**, the same margin 58 correctly-published designs already show, and the one
outlier is `kapl` -- 34 against 70, which is a hand and not an alternate.

WHAT IT DOES NOT SETTLE, and this is the load-bearing half. Of the 71 boards no record
may describe, 46 have both instruments agreeing -- and they are dominated by ONE HAND
of a split: 15 in `davidphilipbarr/36keys`, whose six published designs are all splits
at 34 keys, and 14 in `idank/keyboards`, whose published `sweeq` is a split at 34.
`lephantom` reads 15, `haigha` 17, `besom ortho` 18. Publishing those numbers publishes
half a keyboard, which is the `ScottoPad` fabrication with the sign flipped. So the
question the ruling half turns on is not the count -- it is the BODY, and doubling still
needs the witness `07d5ff0` demanded.

THE JACK IS THAT WITNESS, at 98% and 89%. A 3.5mm four-conductor jack on the board
means the design is a split **89 times out of 91**, and **89 of 100** published splits
place one. Only 11 do not, and they are nameable: `3w6` links its halves through two
separate middle PCBs, `wired-pierce` over an I/O expander, `cradio-ble` and `janus` are
wireless, and `karn`'s only connector is a two-pin battery lead.

And measured against the withheld pile it admits **nothing**: not one board-set exists
whose doubled reading lands on a record's count while that record declares no split, so
the record's own flag and `seed/bodies.toml` already cover every case where doubling
would fire.

BOTH ITS EXCEPTIONS ARE EXPLAINED, which is a better result than one being a corpus
error. `np12` is a 12-key macropad that plugs into a keyboard by TRRS -- a real product
shape and a real limit on the reading. `korris` places 44 Choc switches and TWO jacks
12mm apart, which reads as one per hand; its switches nonetheless span 246mm with no gap
wider than **18.5mm, one Choc key pitch**, so it is one keyboard's width and not two
hands'. That is the asymmetry worth keeping from the ruled-out gap witness below: a wide
central gap cannot prove a split, and the ABSENCE of any gap can refute one.

TWO WITNESSES MEASURED AND RULED OUT, so nobody builds them twice:

  * **The board's own `title_block` rev.** KiCad omits the block entirely when it is
    empty, and 32 of 60 sampled board files have no block at all; 15 of 60 state a
    non-empty rev. The values share no numbering scheme with the firmware's revision
    ids -- `1.0`, `Rev. 2`, `v1.5.0`, `0.95`, `a`, `2w` -- and `idank/keyboards`
    corne-cherry says `3.0.1` where QMK's Corne records are `rev1` and `rev4_1`. Real
    field, unjoinable.
  * **A gap in the middle of the key positions, as a WITNESS.** A both-hands-on-one-file
    split has two clusters, so a wide central x-gap looks like the split witness. It is
    not: `yanghu/unicorne`'s own board has a 37.2mm gap at x=135 and its readme is titled
    "A single-piece 40% ortho keyboard". An Alice or a Reviung has the same gap for the
    same reason. It survives as the FALSIFIER above -- no gap at all refutes two hands,
    which is what settled `korris`.

Usage: measure_board_count_trust.py [--designs FILE] [--defer FILE]
"""
import argparse
import collections
import json
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SCRATCH = REPO / "data"

# A 3.5mm four-conductor jack, and deliberately nothing wider. This is the split
# cable. It is NOT every connector -- JST and PicoBlade carry inter-half wiring too,
# but they also carry OLEDs, encoders and BATTERIES, and `karn`'s only JST part is a
# two-pin `S2B-PH-K` battery lead, so they say nothing on their own.
#
# READ OFF THE CORPUS'S OWN 3,362 FOOTPRINT NAMES, not guessed at, and the first
# version of this pattern was wrong in the expensive direction. It spelled the part
# `mj4pp9`, and 33 of the corpus's boards spell it **`MJ-4PP-9`** -- so Sofle v2, whose
# board places `SofleKeyboard-footprint:MJ-4PP-9`, read as a split with no wired link,
# and the measured recall came out at 52% instead of 89%. Sixty-five names match the
# corrected pattern and every one is a jack.
#
# THE TRAPS, both of them near misses on `jack` and `3.5mm`: the `lumberjack:` footprint
# LIBRARY (every part in it ends in `jack`) and `MountingHole_3.5mm`, `M2_hole_3.5mm`,
# `LED_SK6812MINI_PLCC4_3.5x3.5mm` and `ProMicro_v3.5`. The anchors are what excludes
# them -- `jack[-_ ]?3\.5` and `3\.5mm[-_ ]?pj` rather than a bare `jack` or `3.5` --
# so no explicit deny-list is needed, and one was tried and rejected nothing.
JACK = re.compile(r"mj[-_ ]?4pp|trrs|trss|\bpj[-_ ]?3\d{2}|pj[-_ ]?39[89]|sj[-_]?435"
                  r"|jack[-_ ]?3\.5|3\.5mm[-_ ]?pj|audio[-_ ]?jack|3\.5mm.*jack", re.I)


def load(name):
    return json.loads((SCRATCH / name).read_text())


def readings(scan, positions, repo, paths):
    """The two counts, footprints and distinct positions, over one design's boards."""
    fp = sum((scan.get(f"{repo}\t{p}") or {}).get("switches") or 0 for p in paths)
    pos = sum(positions.get(f"{repo}\t{p}", 0) for p in paths)
    return fp, pos


def control(designs, design_boards, scan, positions):
    """What the board alone would have said, where a record has already ruled."""
    rows = []
    for design in designs:
        info = design_boards.get(design["slug"])
        if not info or len(info["paths"] or []) != 1:
            continue
        fp, pos = readings(scan, positions, info["repo"], info["paths"])
        counts = {layout["keys"] for layout in design["layouts"]}
        split = design["body"]["kind"] == "split"

        def right(reading):
            return reading in counts or (split and reading * 2 in counts)

        rows.append({"slug": design["slug"], "fp": fp, "pos": pos,
                     "counts": sorted(counts), "split": split,
                     "right_fp": right(fp) if fp else None,
                     "right_pos": right(pos) if pos else None})
    return rows


def trrs_witnesses(designs, design_boards, scan):
    """Does a 3.5mm jack on the board predict that the design is a split?

    Ticket 55: *"doubling a hand's count needs a witness, and `placed` alone cannot
    tell one hand from a whole board."* `seed/bodies.toml` is that witness today, and
    it holds four rulings. This asks whether the board can witness it instead.
    """
    table = collections.Counter()
    wrong = []
    for design in designs:
        info = design_boards.get(design["slug"])
        if not info:
            continue
        names = set()
        for path in info["paths"] or []:
            names |= set((scan.get(f"{info['repo']}\t{path}") or {}).get("names") or {})
        jack = any(JACK.search(n) for n in names)
        split = design["body"]["kind"] == "split"
        table[(jack, split)] += 1
        if jack and not split:
            wrong.append(design["slug"])
    return table, wrong


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--designs", default=str(REPO / "src/main/resources/seed/designs.json"))
    ap.add_argument("--defer", default=None,
                    help="build_seed.py --defer-json output, to price the withheld pile")
    args = ap.parse_args()

    designs = json.loads(pathlib.Path(args.designs).read_text())
    design_boards = load("design-boards.json")
    scan = load("board-scan.json")
    positions = load("switch-positions.json")

    rows = control(designs, design_boards, scan, positions)
    agree = [r for r in rows if r["fp"] and r["pos"] and r["fp"] == r["pos"]]
    apart = [r for r in rows if r["fp"] and r["pos"] and r["fp"] != r["pos"]]

    def rate(group, key, label):
        group = [r for r in group if r[key] is not None]
        if not group:
            print(f"  {label:38s} n=0")
            return
        ok = sum(1 for r in group if r[key])
        print(f"  {label:38s} n={len(group):3d}  right={ok:3d}   {100 * ok / len(group):3.0f}%")

    print(f"CONTROL -- {len(rows)} single-board published designs, count already adjudicated")
    rate(agree, "right_fp", "instruments agree, either reading")
    rate(apart, "right_fp", "they disagree, footprints")
    rate(apart, "right_pos", "they disagree, positions")

    print("\n  agreeing and STILL wrong -- the layout alternates:")
    for r in sorted((r for r in agree if r["right_fp"] is False),
                    key=lambda r: abs(r["fp"] - min(r["counts"], key=lambda c: abs(c - r["fp"])))):
        near = min(r["counts"], key=lambda c: abs(c - r["fp"]))
        print(f"    {r['slug']:26s} read {r['fp']:4d}   publishes {r['counts']}"
              f"   off by {abs(r['fp'] - near)}")

    table, wrong = trrs_witnesses(designs, design_boards, scan)
    tp, fp_ = table[(True, True)], table[(True, False)]
    fn = table[(False, True)]
    print(f"\nA 3.5mm JACK AS THE SPLIT WITNESS, over {sum(table.values())} designs")
    print(f"  precision, a jack means split   {tp}/{tp + fp_} = {100 * tp / max(tp + fp_, 1):.0f}%")
    print(f"  recall, a split places a jack   {tp}/{tp + fn} = {100 * tp / max(tp + fn, 1):.0f}%")
    print(f"  jack but published unibody      {wrong}")

    if not args.defer:
        return
    pile = [e for e in json.loads(pathlib.Path(args.defer).read_text())
            if e["reason"] == "no firmware record fits this board's key count"]

    def unreachable(entry):
        declared = entry.get("declares") or {}
        return not declared or all(v["names_another_board"] for v in declared.values())

    ruling = [e for e in pile if unreachable(e)]
    print(f"\nTHE RULING HALF -- {len(ruling)} of {len(pile)} withheld boards no record may describe")
    agree_n = [e for e in ruling if e.get("placed") and e.get("seen")
               and e["placed"] == e["seen"]]
    print(f"  instruments agree               {len(agree_n):3d}   "
          f"(85% exact in the control above)")
    print(f"  they disagree                   {len(ruling) - len(agree_n):3d}")
    by_repo = collections.Counter(e["repo"] for e in agree_n)
    print("  and where the agreeing ones live:")
    for repo, n in by_repo.most_common():
        pub = [d for d in designs if (design_boards.get(d["slug"]) or {}).get("repo") == repo]
        shape = collections.Counter(d["body"]["kind"] for d in pub)
        counts = sorted({layout["keys"] for d in pub for layout in d["layouts"]})
        print(f"    {n:3d}  {repo:32s} already publishes {dict(shape)} at {counts[:6]}")


if __name__ == "__main__":
    sys.exit(main())
