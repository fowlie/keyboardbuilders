"""Which optional hardware a design's board makes room for.

Encoders, displays, pointing devices, speakers, haptics. The question a builder asks
as "can I put a knob on it", which this catalogue currently cannot answer.

THE VERB IS "SUPPORTS", NOT "HAS", and the distinction is the whole reason to be
careful here. A footprint is a place a part CAN go: an OLED footprint with no display
soldered is one of the most common builds there is, and encoders are routinely left
off. So this measures what a board makes room for, which is the more useful fact
anyway -- but it must be worded that way wherever it reaches a reader.

READS THE DESIGN'S OWN BOARDS, VIA `attributed_boards.py`, and that is a correction
rather than a refactor. This used to take a clone root and `rglob` every `.kicad_pcb`
in the repo: `sweeq` therefore "supported a display" on the strength of seventeen other
boards in `idank/keyboards`, and `kobold` -- a 16-key macropad -- claimed a display, an
encoder, an SD card and a speaker, every one of them Djinn's or Ghoul's. See
`attributed_boards.py` for the full measurement; 15 of 228 designs were wrong this way,
and none of them looked wrong.

Usage: measure_extras.py
"""
import argparse
import collections
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from attributed_boards import REPO, SCRATCH, load  # noqa: E402
from measure_controller_socket import bare  # noqa: E402
import qmk_metadata  # noqa: E402
import trees as tree_registry  # noqa: E402

# Deliberately named after the part, never after a brand's board. `pmw3360` and
# `cirque` are sensor families that many designs use; `charybdis` would be one design.
EXTRAS = {
    # `evqwgd` IS A PART NUMBER THAT SAYS NOTHING, and that is why it is listed. The
    # Panasonic EVQWGD001 is the low-profile scroll encoder half this hobby uses, and a
    # footprint named bare `EVQWGD001` matches none of the words below. `chocofly` places
    # one, and it cost TWO wrong facts from one gap: its key count published 61 where its
    # author says "60 keys total plus one rotary encoder" -- because
    # `build_seed.without_encoder_positions` reads THIS pattern to know what to subtract
    # and found no encoder to subtract -- and its `extensions` recorded nothing at all.
    #
    # Found 2026-09-07 by `measure_summary_agreement.py`, comparing our facets against
    # the authors' own summaries. Measured across the corpus: chocofly is the only design
    # affected, so this widening is small in reach as well as in size.
    "encoder":   re.compile(r"ec11|rotary|encoder|evqwgd", re.I),
    "display":   re.compile(r"\boled|ssd1306|sh1106|display|\blcd\b|epaper|e-?ink", re.I),
    "trackball": re.compile(r"pmw3\d{3}|adns[-_]?\d|trackball", re.I),
    "trackpad":  re.compile(r"cirque|trackpad|touchpad", re.I),
    "speaker":   re.compile(r"speaker|buzzer|piezo", re.I),
    "haptic":    re.compile(r"haptic|drv2605|lra\b", re.I),
    "sd_card":   re.compile(r"sd[-_]?card|microsd", re.I),
}

# BACKLOG #21's FOLLOW-ON, WIDENING 1 OF 2: the same library-name fallback `socket_of`
# already has (`measure_controller_socket.py`), scoped to trackball/trackpad only. The
# footprint-name loop below reads only the BARE half of a name, so a footprint whose
# library names the part and whose own name does not -- `molecule`'s
# `ADNS-9800 - v2.1:2X04` -- matches nothing. Measured safe the same way `socket_of`'s
# fallback was: recovers exactly one design (`molecule`), zero measured false positives.
# Not applied to every kind, because that widening was never measured -- precision over
# coverage.
LIBRARY_FALLBACK = {"trackball": EXTRAS["trackball"], "trackpad": EXTRAS["trackpad"]}

# BACKLOG #21's FOLLOW-ON, WIDENING 2 OF 2: QMK's OWN `POINTING_DEVICE_DRIVER` as a
# SECOND presence instrument, for the boards neither the bare name nor the library name
# can see -- `bastardkb/dilemma` places a generic `PinHeader_1x04` for its Cirque sensor,
# which no footprint pattern can match (Design.kt already names this gap).
#
# ONLY THE DRIVER VALUES THIS CORPUS HAS ACTUALLY CONFIRMED, deliberately incomplete.
# `custom` (molecule's own driver) is NOT here: it names no specific sensor, so guessing
# a kind for it would trade a missing value for a maybe-wrong one -- and molecule is
# already recovered above, by its footprint. A driver seen but not mapped is reported,
# not guessed; extending this list is a deliberate, checkable decision, not a default.
DRIVER_KIND = {
    "cirque_pinnacle_spi": "trackpad",
    "cirque_pinnacle_i2c": "trackpad",
    "paw3204": "trackball",
}


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--qmk", default=str(pathlib.Path.home()/"code/qmk_firmware"))
    ap.add_argument("--zmk", default=str(pathlib.Path.home()/"code/zmk"))
    args = ap.parse_args()

    rows, names_for = load()
    reg = tree_registry.load(qmk=args.qmk, zmk=args.zmk)
    design_trees = json.loads((SCRATCH/"design-trees.json").read_text())

    have = collections.Counter()
    examples = collections.defaultdict(collections.Counter)
    driver_seen = collections.Counter()
    per, measured = {}, 0
    for design in rows:
        raw = names_for(design["slug"])
        names = {bare(n) for n in raw}
        # An unread board is NOT a design with no extras. Skipping keeps the
        # denominator honest and keeps a silent absence out of the cache.
        if not names:
            continue
        measured += 1
        found = []
        for kind, pattern in EXTRAS.items():
            hits = [n for n in names if pattern.search(n)]
            if hits:
                have[kind] += 1
                found.append(kind)
                for hit in hits:
                    examples[kind][hit[:40]] += 1

        # WIDENING 1: the library half of a footprint name, trackball/trackpad only.
        for kind, pattern in LIBRARY_FALLBACK.items():
            if kind in found:
                continue
            libs = {n.split(":", 1)[0].strip().lower() for n in raw if ":" in n}
            hits = [lib for lib in libs if pattern.search(lib)]
            if hits:
                have[kind] += 1
                found.append(kind)
                for hit in hits:
                    examples[kind][f"library:{hit[:32]}"] += 1

        # WIDENING 2: QMK's own POINTING_DEVICE_DRIVER, for a board whose footprint is
        # too generic for either name to say what it is. tree looked up positionally --
        # design-trees.json records one tree per firmware entry, same order.
        trees = design_trees.get(design["slug"]) or []
        for fw, tree in zip(design.get("firmware") or [], trees):
            if fw.get("family") != "qmk" or tree not in reg or reg.reader(tree) != "qmk":
                continue
            root = reg.root(tree)
            if not root:
                continue
            driver = qmk_metadata.pointing_device_driver(fw["path"], root)
            if not driver:
                continue
            driver_seen[driver] += 1
            kind = DRIVER_KIND.get(driver)
            if kind and kind not in found:
                have[kind] += 1
                found.append(kind)
                examples[kind][f"driver:{driver}"] += 1

        per[design["slug"]] = found

    print(f"  supported by, of {measured} of {len(rows)} designs whose boards were read\n")
    for kind, n in have.most_common():
        eg = ", ".join(x for x, _ in examples[kind].most_common(2))
        print(f"  {kind:<11}{n:>4}  {n * 100 // measured:>3}%   e.g. {eg}")
    print(f"\n  at least one: {sum(1 for v in per.values() if v)}   two or more: "
          f"{sum(1 for v in per.values() if len(v) > 1)}")

    unmapped = {d: n for d, n in driver_seen.items() if d not in DRIVER_KIND}
    if unmapped:
        print(f"\n  POINTING_DEVICE_DRIVER seen but not mapped to a kind (reported, not "
              f"guessed): {dict(unmapped)}")

    out = SCRATCH/"extras-scan.json"
    out.write_text(json.dumps(dict(sorted(per.items())), indent=1))
    print(f"\n  {len(per)} designs -> {out.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
