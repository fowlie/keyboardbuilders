#!/usr/bin/env python3
"""Count the boards in each confirmed source repo, so one row per board can be drafted.

Ticket 44. Ticket 19 decision 3 fixed the rule and ticket 44 amended it twice:

  * a **board** is a `.kicad_pcb` that *places switch footprints*
  * a **plate** is one whose footprints are all plate cutouts, excluded **by
    footprint library** and never by path -- the path filter is measured wrong,
    it drops `potato-65-solderable-platemount.kicad_pcb`, a real board
  * where a repo has **no board file at all**, count the design's own CAD
    sources instead (the Scotto amendment, 47 `.shapr` files)

The family tables come from `extract_switch_footprints.py` rather than being
restated, so the two instruments cannot drift apart on what a switch is.

**It reads over the network, not from a clone.** 942 board files across 249
repos is 1.5 GB of checkout; the first 1 MB of each file is enough to tell a
plate from a board, because a plate's footprints are homogeneous and appear
immediately. A file that is truncated *and* shows no switch is re-read in full,
so the cap can never turn a board into a plate. Results cache to --cache.

Usage: scan_boards.py [seed/sources.toml] [--jobs N] [--json]
"""
import argparse
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from itertools import combinations

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from check_source_reachability import parse
import board_refs
import generated_boards
import trees as tree_registry
from extract_switch_footprints import (ABSOLUTE_NOT_A_SWITCH, FAMILY_TOKENS,
                                       classify as classify_footprint,
                                       placed_names)
from measure_controller_socket import SELF_NAMED_CHIP
from switch_pins import footprints as pin_footprints, pin_families

# A footprint's VALUE, in both KiCad spellings: `(fp_text value ATmega32U4-AU ...)` before
# KiCad 6 and `(property "Value" "STM32F072CBT6" ...)` after. Both live only inside a
# footprint, so board text and silkscreen art never match.
VALUE = re.compile(r'\(fp_text\s+value\s+(?:"([^"]*)"|(\S+))|\(property\s+"Value"\s+"([^"]*)"')


def chips_in_values(text):
    """The chips a board names in its footprints' VALUES, as `chip_of` spells them.

    Backlog #107, Ferris: every 0.1 board places `Package_QFP:TQFP-44`, a package and no
    part, and writes `ATmega32U4-AU` in that footprint's value. The name-only read left the
    chip to QMK, where `ferris/0_1` and `ferris/0_2/*` disagree, so three pages read
    Unknown. A value is the author's own statement of the part, like a BOM row.
    """
    return sorted({hit.group(0).lower()
                   for m in VALUE.finditer(text)
                   if (hit := SELF_NAMED_CHIP.search(next(g for g in m.groups() if g is not None)))})

CAP = 1_000_000

# The three libraries ticket 19 decision 3 names, plus the generic words that
# stand in for them. Matched against `library:name` like everything else.
PLATE = re.compile(r"plateslot|keyplate|backplate|plate_slot", re.I)


# A key position, whether or not its name gives up a family. This is a *wider*
# question than `extract_switch_footprints.classify`, and conflating the two was
# this scan's first wrong answer: `keyswitches:Kailh_socket_plate_mount` is
# placed 59x on `maxr1998/phoebe_keyboard` and names no family, so a
# family-requiring test called a 59-key board switchless. For counting boards,
# "this is where a switch goes" is the question; the family is ticket 24's.
# `switch` bare, not `switches:` or `sw_`: `footprints:hole-switch` (98
# placements), `footprints:hole-switch-acrylic` (63) and `footprints:Switch_Hole`
# (60) all say it plainly and a narrower pattern missed all three. Safe because
# the absolute list runs first, so `Button_Switch_THT:SW_PUSH_6mm` is already
# gone on `push`.
SWITCH_LIKE = re.compile(
    r"switch|sw[_-]|_sw\b|kailh|gateron|outemu|cherry|choc"
    r"|\bmx\b|mx[_-]|[_-]mx|alps|hotswap|hot_swap|pg13\d\d|millmax|holtite",
    re.I,
)


def families(fp):
    """Every switch family this `library:name` mentions. Empty for a non-switch."""
    return set(classify_footprint(fp))


def is_switch(fp):
    """Does this footprint mark a key position? Family optional."""
    if ABSOLUTE_NOT_A_SWITCH.search(fp):
        return False
    return bool(families(fp)) or bool(SWITCH_LIKE.search(fp))


# A footprint naming a stabilizer is sometimes a self-contained wide-key mount
# (a real switch, with integrated stabilizer legs in its own name) and
# sometimes a bare accessory placed beside a SEPARATE switch footprint for the
# same key -- which `stabil`'s exclusion above exists for. Found 2026-09-03,
# owner-reported: `gtips/reviung`'s `reviung34-split` reads 33 keys and its
# own README states 34 -- the missing one is
# `_reviung-kbd:MXOnly-2U-Hotswap-ReversedStabilizers`.
#
# A NAME-BASED RULE CANNOT ARBITRATE THIS. "Hotswap" looked like the tell
# (the sockets ARE the switch) until `gtips/reviung61` needed its own
# SOLDERED `MX-6.25U-ReversedStabilizers-NoLED` -- no "hotswap" anywhere --
# to reach its stated 61 keys. And a LIBRARY-based carve-out fails the other
# way: `ai03-2725/contra` ships a `MX-2U-ReversedStabilizers-NoLED` from
# `MX_Alps_Hybrid` that is a documented SECOND, OPTIONAL footprint over two of
# its 48 already-counted 1U positions, while `reviung61`'s spacebar is from
# the same library and is real. Same library, same singleton shape, opposite
# answer -- no name-shaped rule survives both.
#
# THE BOARD'S OWN DIODE COUNT ARBITRATES INSTEAD, because a keyboard needs
# exactly one diode per key (`measure_build_difficulty.py`'s own reasoning),
# and a bare stabilizer accessory needs no diode of its own. Try every subset
# of the board's candidate names, smallest first, and trust the smallest one
# that makes (already-counted switches) + (that subset) equal the diode count
# exactly. Smallest first so a board that already balances with NONE of them
# (`ai03-2725/contra`, 48 switches = 48 diodes) is never talked into adding
# one anyway -- which is also what protects a genuine bare-accessory board:
# its real switches already balance against their own diodes without the
# accessory's help, so the accessory is excluded at the first, emptiest
# subset tried. A TIE -- more than one subset of the winning size balances --
# trusts nothing: `gtips/reviung`'s own `reviung46` has three single-name
# subsets that each independently balance, and picking one over the other two
# would be a coin flip wearing a diode count's confidence.
#
# Verified against every board carrying a `stabil` name in the corpus: exact,
# untied balances for `gtips/reviung`'s whole family (`reviung33`, `34split`'s
# `-R`, `39`, `41`, `61`) at one extra name, `reviung53` at ZERO extra names
# (its four candidates are alternates, not four keys), `ai03-2725/jp60`,
# `makenova/omega`, `sotoba/contender`, `skeletonkbd/skeletonnumpad`,
# `takishim/mikeneko65` each at one. Ties or no-diode-info stay untouched:
# `reviung46` (tie), `dcpedit/masonry`, `dcpedit/redherring`, `iktas/pill60`,
# `jpskenn/jones` (each already over its diode count before any candidate is
# added -- a different, pre-existing problem this cannot and should not
# paper over), `ai03-2725/contra` (balances at zero, correctly excluded).
STABIL = re.compile(r"stabil", re.I)
# `measure_build_difficulty.py`'s pattern, widened for the bare reference-
# designator spelling `_reviung-kbd`'s own diode footprints use --
# `D3_SMD_1side`, `D3_TH_SMD` -- which name no library word for "diode" at
# all. Anchored to the BARE name only: a library prefix containing "d_"
# incidentally (there are none observed, but the anchor costs nothing) must
# not count.
DIODE = re.compile(r"\bd_|diode|sod-\d|do-3[45]", re.I)
DIODE_DESIGNATOR = re.compile(r"^d\d*_", re.I)


def stabilizer_switch_names(names):
    """Which of THIS BOARD's stabilizer-named footprints are trustworthy switches.

    `names` is the board's own `{footprint_name: count}` -- not one name in
    isolation, and not just which names are present, because the arbitration
    is arithmetic over the whole board. See the block comment above for the
    reasoning and what it was checked against.
    """
    candidates = [n for n in names if STABIL.search(n)]
    if not candidates:
        return frozenset()
    diodes = sum(c for n, c in names.items()
                 if DIODE.search(n) or DIODE_DESIGNATOR.match(n.split(":", 1)[-1]))
    if not diodes:
        return frozenset()
    base = sum(c for n, c in names.items() if is_switch(n))
    for r in range(len(candidates) + 1):
        matches = [combo for combo in combinations(candidates, r)
                   if base + sum(names[n] for n in combo) == diodes]
        if len(matches) == 1:
            return frozenset(matches[0])
        if matches:                        # a tie at this size: no combo wins
            return frozenset()
    return frozenset()


def fetch(slug, path, cap=CAP):
    # A board the repo describes but does not commit (generate_ergogen.py) is read
    # whole from the local cache; `cap` exists to spare the network, not the disk.
    if (text := generated_boards.read(slug, path)) is not None:
        return text, None, len(text.encode())
    url = board_refs.raw_url(slug, path)
    try:
        with urllib.request.urlopen(url, timeout=90) as r:
            data = r.read(cap) if cap else r.read()
        # THE BYTE COUNT IS RETURNED, NOT INFERRED FROM THE STRING. `read(cap)` caps
        # BYTES and `decode` yields CHARACTERS, so a file full of multi-byte sequences
        # comes back as 999,974 characters from a 1,000,000-byte read -- and a caller
        # testing `len(text) >= cap` concludes it got the whole file. Seven board files
        # were still undercounted after the cap bug was first fixed, for exactly this
        # reason, all of them at 999,9xx characters.
        return data.decode("utf-8", "replace"), None, len(data)
    except Exception as e:                        # noqa: BLE001 -- report, don't raise
        return None, f"{type(e).__name__}: {e}"[:80], 0


# A shape drawn ON Edge.Cuts, in KiCad 5's bare and KiCad 6's quoted spelling. The layer
# TABLE in every file's header names Edge.Cuts too, as `(44 Edge.Cuts user)`, which this
# does not match: a table entry is not a drawing.
EDGE_CUTS = re.compile(r'\(layer\s+"?Edge\.Cuts"?\s*\)')
# A routed track, and NOT the setup block's `(segment_width 0.2)`, a design rule every
# KiCad 5 file carries: counting it read Orbit's unrouted HEAD as one track, and missed it.
SEGMENT = re.compile(r"\(segment\s")


def outline_and_tracks(text):
    """`(has an outline, routed segments)` for one WHOLE board file -- issue #41.

    `ai03-2725/orbit`'s HEAD was an unplaced netlist import, no outline and no tracks, and
    the site drew it as a rectangle and published facts read off its grid. Either one alone
    is a real board: Ergogen draws an outline and routes nothing."""
    return bool(EDGE_CUTS.search(text)), len(SEGMENT.findall(text))


def switch_families(text, switch_names):
    """The families a board's switch footprints take, and which of them the PINS gave.

    The name is read first and, where it names a family, is the whole answer. Only a name
    that names none is read by its pins (`switch_pins.py`), once, from its first placement:
    `Biacco42/Ergo42` places 28 of `library:key_switch` and the name says nothing, while
    the pins sit where MX and Choc v1 pins go. Returns `(families, {name: pin families})`,
    the second only for names the pins actually filled, so the cache says which families
    came from where."""
    fam, by_pins = set(), {}
    unnamed = set()
    for f in switch_names:
        named = families(f)
        fam |= named
        if not named:
            unnamed.add(f)
    if unnamed:
        for name, block in pin_footprints(text):
            if name in unnamed:
                unnamed.discard(name)
                if pins := pin_families(block):
                    by_pins[name] = sorted(pins)
                    fam |= pins
    return fam, by_pins


def classify(slug, path):
    """board / plate / no-switch, with the families and the counts behind it."""
    text, err, nbytes = fetch(slug, path)
    if text is None:
        return {"path": path, "kind": "unreadable", "error": err}
    fps = placed_names(text)
    trusted = stabilizer_switch_names(Counter(fps))
    sw = [f for f in fps if is_switch(f) or f in trusted]
    whole = nbytes < CAP
    if nbytes >= CAP:
        # ANY FILE AT THE CAP IS RE-FETCHED WHOLE, not only one that looks switchless.
        #
        # This guard used to read `if not sw and len(text) >= CAP` -- it paid for the
        # whole file only when the cap had hidden EVERY switch, on the theory that the
        # thing to protect was the board/plate verdict. But the COUNT is load-bearing
        # too: the board-first pass joins a firmware record to a board by `placed in counts or
        # placed * 2 in counts`, so a truncated count silently fails the join and the
        # design is deferred with a plausible-looking reason.
        #
        # Measured against local clones of 185 repos: 31 of 674 cached board files were
        # undercounted this way. `peej/lumberjack-keyboard` recorded 2 switches against
        # a true 62; `tzarc/keyboards` Djinn 61 against 129; `idank/keyboards`
        # corne-light 26 against 42. Every one is a file over the 1 MB cap, and none of
        # them tripped the old guard, because each had SOME switches in the first
        # megabyte. A wrong count is indistinguishable from a right one.
        # A FAILED RE-READ KEEPS THE FIRST MEGABYTE rather than crashing on None; `whole`
        # stays False, so nothing is concluded from what the cap left out.
        full, err, size = fetch(slug, path, cap=0)
        if full is not None:
            text, nbytes, whole = full, size, True
            fps = placed_names(text)
            trusted = stabilizer_switch_names(Counter(fps))
            sw = [f for f in fps if is_switch(f) or f in trusted]
    plates = [f for f in fps if PLATE.search(f)]
    fam, by_pins = switch_families(text, set(sw))
    kind = "board" if sw else ("plate" if plates else "no-switch")
    # Every distinct footprint name, with its count. Stored because the first
    # run of this scan asked the wrong question -- it required a footprint to
    # name a *family*, so `keyswitches:Kailh_socket_plate_mount` x59 read as
    # zero switches and Phoebe read as a plate. Keeping the names means the
    # next wrong question is answerable without re-fetching 1.5 GB.
    # ONLY FROM A WHOLE FILE. An outline or a track past the cap would be missed, and a
    # missed one reads as "unfinished": an extractor proves presence, never absence.
    drawn = dict(zip(("outline", "tracks"), outline_and_tracks(text))) if whole else {}
    return {"path": path, "kind": kind, "bytes": len(text), "switches": len(sw), **drawn,
            "footprints": len(fps), "plate_footprints": len(plates),
            "families": sorted(fam),
            "pin_families": by_pins,
            "chips": chips_in_values(text),
            "names": dict(Counter(fps).most_common(400))}




def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("seed", nargs="?", default="seed/sources.toml")
    ap.add_argument("--trees", default="data/source-trees.json")
    ap.add_argument("--cache", default="data/board-scan.json")
    ap.add_argument("--jobs", type=int, default=8)
    ap.add_argument("--json", action="store_true")
    # NAMED FOR ITS FIRST JOB AND IT DOES MORE THAN THAT. It re-derives `switches`,
    # `plate_footprints`, `families` AND `kind` from the cached names -- so it is what to
    # run after ANY change to the family or exclusion tables in
    # `extract_switch_footprints.py`, not only after a change to the board/plate
    # predicate. The help said "kind" alone and cost someone a duplicate implementation
    # of this on 2026-09-07, having not found it.
    ap.add_argument("--reclassify", action="store_true",
                    help="re-derive switches, families and kind from cached footprint "
                         "names, no network -- run after any lexicon change")
    # The footprint-VALUE reader (`chips`) landed 2026-10-01, after most of the cache was
    # written, and a cached file is never re-read. Measured for #106 on 2026-10-02: 73
    # published soldered designs read "board names no chip" only because of this.
    ap.add_argument("--refresh-chips", action="store_true",
                    help="re-fetch every cached board file that has no `chips`, and record them")
    # The pin reader (`switch_pins.py`) landed 2026-11-16, after the whole cache was
    # written. This re-fetches only the boards with a switch-like name that names no
    # family -- the only footprints the pins are ever asked about.
    ap.add_argument("--read-pins", action="store_true",
                    help="re-fetch cached boards with an unnamed switch footprint, read its pins")
    # The outline and track reader landed 2026-11-26 (issue #41), after the whole cache was
    # written. A board without the two fields is never held back, so this fills them.
    ap.add_argument("--read-outline", action="store_true",
                    help="re-fetch cached boards with no `outline`, record outline and tracks")
    args = ap.parse_args()

    rows = [r for r in parse(args.seed) if r.get("verdict") == "confirmed"]
    trees = json.loads(pathlib.Path(args.trees).read_text())
    by_repo = defaultdict(list)
    for r in rows:
        if r.get("source"):
            by_repo[r["source"]].append(r)

    generated = {k.lower(): v for k, v in generated_boards.listing().items()}
    jobs = []
    for slug in sorted(by_repo):
        for p in ((trees.get(slug) or {}).get("paths") or []) + generated.get(slug.lower(), []):
            if p.lower().endswith(".kicad_pcb"):
                jobs.append((slug, p))

    cache = json.loads(pathlib.Path(args.cache).read_text()) \
        if pathlib.Path(args.cache).exists() else {}
    if args.reclassify:
        # Re-decide board/plate/no-switch from the stored footprint names. The
        # predicate has been wrong twice; re-fetching 1.5 GB to fix a regex is
        # not a thing anyone should have to do.
        for key, res in cache.items():
            names = res.get("names")
            if names is None:
                continue
            trusted = stabilizer_switch_names(names)
            sw = {f: n for f, n in names.items() if is_switch(f) or f in trusted}
            plates = {f: n for f, n in names.items() if PLATE.search(f)}
            fam = set()
            for f in sw:
                # A name the lexicon now reads drops its pin reading: the name wins.
                fam |= families(f) or set((res.get("pin_families") or {}).get(f, ()))
            res["switches"] = sum(sw.values())
            res["plate_footprints"] = sum(plates.values())
            res["families"] = sorted(fam)
            res["kind"] = "board" if sw else ("plate" if plates else "no-switch")
        pathlib.Path(args.cache).write_text(json.dumps(cache, indent=1, sort_keys=True))
        print(f"reclassified {len(cache)} files from cache", file=sys.stderr)
    if args.read_pins:
        stale = [k for k, v in cache.items() if "pin_families" not in v
                 and any(is_switch(n) and not families(n) for n in (v.get("names") or {}))]
        print(f"reading pins on {len(stale)} cached board files", file=sys.stderr)

        def pins(key):
            text, err, _ = fetch(*key.split("\t", 1), cap=0)
            if text is None:
                return None
            sw = {n for n in cache[key]["names"] if is_switch(n)}
            return switch_families(text, sw)
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            for key, got in zip(stale, pool.map(pins, stale)):
                if got is not None:
                    cache[key]["families"] = sorted(set(cache[key]["families"]) | got[0])
                    cache[key]["pin_families"] = got[1]
        pathlib.Path(args.cache).write_text(json.dumps(cache, indent=1, sort_keys=True))
    if args.read_outline:
        stale = [k for k, v in cache.items() if v.get("kind") == "board" and "outline" not in v]
        print(f"reading outlines of {len(stale)} cached board files", file=sys.stderr)

        def outline(key):
            text, err, _ = fetch(*key.split("\t", 1), cap=0)
            return None if text is None else outline_and_tracks(text)
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            for key, got in zip(stale, pool.map(outline, stale)):
                if got is not None:
                    cache[key]["outline"], cache[key]["tracks"] = got
        pathlib.Path(args.cache).write_text(json.dumps(cache, indent=1, sort_keys=True))
    if args.refresh_chips:
        stale = [k for k, v in cache.items() if v.get("kind") == "board" and "chips" not in v]
        print(f"reading values of {len(stale)} cached board files", file=sys.stderr)

        def values(key):
            text, err, _ = fetch(*key.split("\t", 1), cap=0)
            return None if text is None else chips_in_values(text)
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            for key, chips in zip(stale, pool.map(values, stale)):
                if chips is not None:
                    cache[key]["chips"] = chips
        pathlib.Path(args.cache).write_text(json.dumps(cache, indent=1, sort_keys=True))
    todo = [(s, p) for s, p in jobs if f"{s}\t{p}" not in cache]
    if todo:
        print(f"scanning {len(todo)} of {len(jobs)} board files", file=sys.stderr)
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            for (s, p), res in zip(todo, pool.map(lambda t: classify(*t), todo)):
                cache[f"{s}\t{p}"] = res
        cp = pathlib.Path(args.cache)
        cp.parent.mkdir(parents=True, exist_ok=True)
        cp.write_text(json.dumps(cache, indent=1, sort_keys=True))

    per = defaultdict(list)
    for key, res in cache.items():
        slug = key.split("\t")[0]
        if slug in by_repo:
            per[slug].append(res)

    if args.json:
        print(json.dumps({s: per[s] for s in sorted(per)}, indent=1, sort_keys=True))
        return

    kinds = Counter(r["kind"] for rs in per.values() for r in rs)
    print(f"{len(jobs)} .kicad_pcb files across {len(per)} repos")
    for k, n in kinds.most_common():
        print(f"  {n:5} {k}")

    print(f"\n{len(by_repo)} confirmed repos:")
    noboard = [s for s in by_repo if not any(r["kind"] == "board" for r in per.get(s, []))]
    print(f"  {len(by_repo) - len(noboard):5} have at least one board")
    print(f"  {len(noboard):5} have none -- the CAD-source branch, or no hardware")

    # A QMK RECORD IS ONE ITS TREE READS WITH QMK'S READER, not one whose `tree` is
    # spelled "qmk" -- a fork of QMK still runs QMK. Backlog #64.
    reg = tree_registry.load()
    print("\nboards vs QMK records, where they disagree:")
    for slug in sorted(by_repo):
        boards = [r for r in per.get(slug, []) if r["kind"] == "board"]
        recs = [r for r in by_repo[slug]
                if r["tree"] in reg and reg.reader(r["tree"]) == "qmk"]
        if not recs or len(boards) == len(recs):
            continue
        arrow = "records > boards -- a NAMING PICK" if len(recs) > len(boards) \
            else "boards > records -- a fan-out"
        print(f"  {len(recs):3} recs {len(boards):3} boards  {slug:44} {arrow}")


if __name__ == "__main__":
    main()
