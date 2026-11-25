#!/usr/bin/env python3
"""Is key pitch extractable from a board file? A measurement, not a facet.

`architecture.md`:623 states the fact and the reason it matters: *"Pitch is a property
of the PCB, not of the footprint -- many PCBs carry both MX and Choc footprints at
19.05 mm spacing"*, and keycap fit is COMPUTED from `(mounts, pitch)` rather than
stored. `KeyPitch` is designed with four variants and `pitch` is in ticket 03's v0.5
facet inventory. None of it is built: `Design.kt` has no pitch, and `board-scan.json`
keeps footprint NAMES while throwing the coordinates away.

Raised by the owner on `diepala/cantor`: *"The Cantor Classic uses choc switches with
choc spacing. Therefore, only MBK keycaps can be used. The Cantor MX uses either choc
V1, choc V2, or MX switches with the slightly larger MX spacing."* Two designs whose
real difference is invisible to the model as built -- both would record as a set of
switch families, which is true and is not the fact a buyer needs.

WHAT THIS ANSWERS, AND ONLY THIS. Can the number be read off the file at all, and for
how much of the corpus? It writes no facet, changes no schema and decides nothing.

HOW. A `.kicad_pcb` places every footprint with `(at x y [rotation])` in millimetres.
Take the switch footprints, and for each one measure the distance to its nearest
neighbour. On a keyboard that distance is the column or row pitch, whichever is
smaller. The MODE of those distances is the board's pitch: a stray outlier -- a rotated
thumb cluster, a lone key on a bracket -- moves a mean and leaves a mode alone.

WHY NEAREST-NEIGHBOUR AND NOT A GRID FIT. Split boards, columnar stagger and rotated
thumb clusters mean the switches do not sit on one grid, so fitting one would fail on
exactly the ergonomic boards this corpus is about. Nearest-neighbour needs no grid.

WRITING THE CACHE. `--write` runs over every attributed board -- confirmed boards.toml
rows AND the board-first boards `design-boards.json` names -- and stores the result as
`data/pitch-scan.json`, keyed `repo\tpath` exactly like `board-scan.json`
so `build_seed.py` can join on it. Committed, like the other scans, because the corpus
must be reproducible from source on every build without re-fetching 200 files.

`--write --only SLUG` measures one published design's boards and merges them in.

Usage: measure_key_pitch.py [--sample N] [--repo SLUG] [--write PATH [--only SLUG]]
"""
import argparse
import collections
import json
import math
import pathlib
import re
import sys
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from extract_switch_footprints import classify as switch_families  # noqa: E402
import board_refs  # noqa: E402
import generated_boards  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent

# A LIBRARY NAME MAY CONTAIN SPACES: `Keyboard Switches:SW_MX_HotSwap_Reversible`. The
# second version of this stopped at the space, captured `Keyboard`, and silently found
# zero switches on 18 of 80 boards including all of lotus58. `extract_switch_footprints`
# carries a comment about being bitten by exactly this in 2026-08 -- so the name runs to
# the closing quote, or to the next `(` when unquoted.
#
# `(footprint "Lib:Name" ...)` in KiCad 6+, `(module Lib:Name (layer F.Cu) ...)` in
# KiCad 5 — UNQUOTED, which is the whole reason this needed a second attempt. The first
# version demanded quotes and found zero placements in `tapioki/cephalopoda` while the
# board scan happily read 34 switch names out of the same file. 5.1-era files are a
# large share of this corpus, so quoted-only silently discarded most of it.
#
# The body runs to the next placement at the same indentation, or the end of the file.
# Indentation rather than paren-matching because these files are megabytes and a regex
# that counts brackets over them is slower than fetching them. `[ \t]` and not `\s`:
# `\s` matches newlines, so the captured indent swallowed the blank lines between
# placements and the backreference then matched nothing -- KiCad 6 files silently
# returned zero placements while KiCad 5 files kept working.
PLACEMENT = re.compile(
    r'^([ \t]*)\((?:footprint|module)\s+(?:"([^"]+)"|([^(\n]+?))\s*(?=\()'
    r'(.*?)(?=^\1\((?:footprint|module)\s|\Z)',
    re.S | re.M)

AT = re.compile(r"\(at\s+(-?[\d.]+)\s+(-?[\d.]+)")

# THE SCAN'S OWN CLASSIFIER, not a second one. The first version of this file invented
# a regex and it was wrong in both directions: it missed most real switches, and it
# matched `RotaryEncoder_Alps_EC11E-Switch` and a library called
# `Keyboard Switches & Encoders`, which put `tweetydabird/lotus58` at a confident
# 5.10 mm "pitch" that is the gap between an encoder's pins. `classify()` already
# excludes encoders, sliders and stabilisers, reads families off the bare name only,
# and has been measured against 24 boards. One instrument, not two.

# The two pitches the design enumerates, plus the tolerance a real board needs: KiCad
# files carry rounded millimetres and authors round differently.
KNOWN = {"mx_19.05": (19.05, 19.05), "choc_18x17": (18.0, 17.0)}
TOLERANCE = 0.35


def fetch(slug, path):
    """No read cap, and that is not a detail -- `koca58` is 20.1 MB and the switch
    footprints sit past the 14 MB of copper-zone and graphics data that precedes them.
    A 6 MB cap silently truncated every read of it to nothing but preamble, so
    `switch_positions()` found zero switches and the board dropped out of the cache with
    no error -- indistinguishable from "this board has no measurable pitch," which is
    false. Found 2026-09-09 while independently re-verifying this script's own numbers.
    Board files top out in the tens of MB in this corpus; reading one in full costs
    nothing a one-off scan need worry about."""
    if (text := generated_boards.read(slug, path)) is not None:
        return text, None
    url = board_refs.raw_url(slug, path)
    try:
        with urllib.request.urlopen(url, timeout=90) as r:
            return r.read().decode("utf-8", "replace"), None
    except Exception as e:                        # noqa: BLE001
        return None, f"{type(e).__name__}"


def switch_positions(text):
    """One (x, y) per KEY, in millimetres.

    ONE FOOTPRINT FAMILY, not all of them, and that is the whole correctness of this
    function. A key is commonly placed as SEVERAL footprints at almost the same spot --
    `tweetydabird/lotus58` puts `Kailh_socket_MX_reversible_RGB` and
    `MX_SK6812MINI-E_REVERSIBLE` **5.08 mm** apart on every one of its 29 keys, and a
    dual-footprint board stacks an MX and a Choc footprint on the same key. Measuring
    across all of them reads the gap between a socket and its own switch as the pitch,
    which is how this file first reported lotus58 at 5.10 mm with 100% confidence.

    Taking the single most-placed switch footprint gives one point per key. It costs
    nothing when a board places only one kind, and on a board that mixes them the pitch
    is a mode anyway, so a family covering most of the keys still answers correctly.
    """
    byname = collections.defaultdict(list)
    for _, quoted, unquoted, body in PLACEMENT.findall(text):
        name = (quoted or unquoted).strip()
        if not switch_families(name):
            continue
        hit = AT.search(body)
        if hit:
            byname[name].append((float(hit.group(1)), float(hit.group(2))))
    if not byname:
        return []
    # Ties broken by name so two runs over one file never disagree.
    best = max(byname, key=lambda n: (len(byname[n]), n))
    # CO-LOCATED PLACEMENTS ARE ONE KEY. A REVERSIBLE PCB carries the same footprint on
    # both sides at identical coordinates, so `tapioki/cephalopoda` places 34 for 17
    # keys and every point's nearest neighbour is its own twin at 0.00 mm. Dropping
    # those left nothing to measure and the board read as unjudgeable. Reversible boards
    # are common enough here that the domain models them (`Body.Split.reversible`).
    return sorted({(round(x, 2), round(y, 2)) for x, y in byname[best]})


def nearest_neighbour_gaps(points):
    """For each switch, the distance to its closest other switch, rounded to 0.05 mm."""
    gaps = []
    for i, (x1, y1) in enumerate(points):
        best = None
        for j, (x2, y2) in enumerate(points):
            if i == j:
                continue
            d = math.hypot(x1 - x2, y1 - y2)
            if best is None or d < best:
                best = d
        if best is not None and 5.0 < best < 40.0:   # a key gap, not a duplicate or a stray
            gaps.append(round(best * 20) / 20)
    return gaps


def pitch_of(gaps):
    """The board's pitch, by the mode of its nearest-neighbour gaps."""
    if len(gaps) < 8:
        return None, 0.0, None
    counted = collections.Counter(gaps)
    mode, hits = counted.most_common(1)[0]
    confidence = hits / len(gaps)
    for label, (x, y) in KNOWN.items():
        if abs(mode - x) <= TOLERANCE or abs(mode - y) <= TOLERANCE:
            return label, confidence, mode
    return "other", confidence, mode


DESIGN_BOARDS = REPO/"data/design-boards.json"


def attributed_boards():
    """Every `(repo, path)` some published design is built from, confirmed rows first.

    TWO SOURCES, because a design reaches `designs.json` by two routes. A firmware-first design
    has a CONFIRMED `seed/boards.toml` row naming its board. A board-first design is built
    from the board file itself, with no boards.toml row at all, and `design-boards.json`
    (written by `build_seed.py`) is the only place its board is named. This used to read
    boards.toml alone, so no board-first design could ever get a pitch: `stront-choc` read
    "Key spacing -- not recorded" while its own board places every switch 17 mm apart.
    """
    import tomllib
    rows = tomllib.loads((REPO/"seed/boards.toml").read_text())["entry"]
    targets = [(r["source"], p) for r in rows if r["verdict"] == "confirmed"
               for p in r["boards"]]
    if DESIGN_BOARDS.exists():
        mapping = json.loads(DESIGN_BOARDS.read_text())
        targets += [(m["repo"], p) for _, m in sorted(mapping.items()) for p in m["paths"]]
    seen, unique = set(), []
    for t in targets:
        if t not in seen:
            seen.add(t); unique.append(t)
    return unique


def write_cache(out, only=None):
    """Every attributed board's pitch, keyed like `board-scan.json` so a build can join.

    Stores the MODE and the confidence beside the verdict, not just the verdict: a board
    the extractor is 40% sure about is a different thing from one it is 100% sure about,
    and the consumer -- not this script -- should decide what to do with a weak reading.

    `only` (a published slug) measures THAT design's boards and merges them into the
    existing cache, leaving every other row as it is. A full run re-fetches every board
    at upstream HEAD, so it can move rows nobody asked about; this cannot.
    """
    if only:
        mapping = json.loads(DESIGN_BOARDS.read_text())
        if only not in mapping:
            raise SystemExit(f"{only!r} is not in {DESIGN_BOARDS.name}")
        targets = [(mapping[only]["repo"], p) for p in mapping[only]["paths"]]
    else:
        targets = attributed_boards()

    out_path = pathlib.Path(out)
    previous = json.loads(out_path.read_text()) if out_path.exists() else {}
    cache = dict(previous) if only else {}
    verdicts = collections.Counter()
    for i, (slug, path) in enumerate(targets, 1):
        text, err = fetch(slug, path)
        if text is None:
            # AN UNREAD BOARD KEEPS ITS LAST READING. A full run starts empty, so without
            # this one timeout -- or a generated board on a machine that cannot generate
            # it (jyap808/pando58 on the VPS, 2026-09-29) -- silently erased a measured
            # pitch. Unread is not unmeasurable; a board that is really gone stops being
            # a target, and check_source_paths.py reports it.
            verdicts[f"fetch: {err}"] += 1
            if (key := f"{slug}\t{path}") in previous:
                cache[key] = previous[key]
            continue
        points = switch_positions(text)
        label, confidence, mode = pitch_of(nearest_neighbour_gaps(points))
        verdicts[label or "too few keys to measure"] += 1
        if label:
            cache[f"{slug}\t{path}"] = {
                "pitch": label, "mm": mode, "confidence": round(confidence, 2),
                "keys": len(points),
            }
        else:
            # A merge must not keep a reading the board no longer supports.
            cache.pop(f"{slug}\t{path}", None)
        if i % 25 == 0:
            print(f"  {i}/{len(targets)}")

    pathlib.Path(out).write_text(json.dumps(cache, indent=1, sort_keys=True))
    print(f"\n{len(targets)} boards read, {len(cache)} rows in the cache -> {out}")
    for label, n in verdicts.most_common():
        print(f"  {n:>4}  {label}")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sample", type=int, default=40)
    ap.add_argument("--repo", help="measure one repo's boards and stop")
    ap.add_argument("--write", nargs="?", const="data/pitch-scan.json",
                    help="scan every attributed board and cache the result")
    ap.add_argument("--only", metavar="SLUG",
                    help="with --write: measure one design's boards, merge into the cache")
    args = ap.parse_args()
    if args.write:
        return write_cache(args.write, only=args.only)

    import tomllib
    if args.repo:
        # Every board the scan found in that repo, ATTRIBUTED OR NOT. A repo worth
        # inspecting by hand is usually one whose attribution is still open --
        # `diepala/cantor` is the case that prompted this script and has none.
        scan = json.loads((REPO/"data/board-scan.json").read_text())
        targets = [(k.split("\t")[0], k.split("\t", 1)[1]) for k, v in scan.items()
                   if k.startswith(args.repo + "\t") and v.get("kind") == "board"]
    else:
        rows = tomllib.loads((REPO/"seed/boards.toml").read_text())["entry"]
        targets = [(r["source"], p) for r in rows if r["verdict"] == "confirmed"
                   for p in r["boards"]]
    seen, unique = set(), []
    for t in targets:
        if t not in seen:
            seen.add(t); unique.append(t)
    unique = unique[:args.sample] if not args.repo else unique

    print(f"measuring {len(unique)} attributed board files\n")
    verdicts, rowsout = collections.Counter(), []
    for slug, path in unique:
        text, err = fetch(slug, path)
        if text is None:
            verdicts["fetch failed"] += 1
            continue
        points = switch_positions(text)
        label, confidence, mode = pitch_of(nearest_neighbour_gaps(points))
        if label is None:
            verdicts["too few switch placements to judge"] += 1
            continue
        verdicts[label] += 1
        rowsout.append((slug, path.split("/")[-1], len(points), mode, label, confidence))

    print(f"{'repo':<34} {'board':<28} {'sw':>4} {'mode':>6}  pitch        conf")
    for slug, board, n, mode, label, confidence in rowsout:
        print(f"  {slug:<32} {board[:26]:<28} {n:>4} {mode:>6.2f}  {label:<12} {confidence:.0%}")

    print("\nverdicts:")
    for label, n in verdicts.most_common():
        print(f"  {n:>4}  {label}")
    solid = sum(1 for *_, c in rowsout if c >= 0.5)
    if rowsout:
        print(f"\n{solid} of {len(rowsout)} judged boards agree with themselves at >=50%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
