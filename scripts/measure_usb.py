#!/usr/bin/env python3
"""Which USB receptacle does each soldered design's own board carry? Backlog #97.

A soldered design's cable is a fact about its board: the receptacle footprint is on the
`.kicad_pcb`. A socketed design's cable is a fact about the dev board plugged into it,
which this file does not measure -- that is `seed/controllers.toml`'s half.

READ FROM THE FOOTPRINT'S OWN NAME, NEVER ITS LIBRARY'S. `board-scan.json` keys each
footprint as `library:name`, and a library called `Type-C` also holds
`Type-C:logo2-small` (a silkscreen logo) and `Type-C:USBLC6-2SC6` (an ESD chip), so the
part before the colon proves nothing. The same trap as #60's regex matching
`kbd-template` because "template" contains "plate", and as `Micro` matching Pro Micro 317
times.

ONLY A NAME THAT STATES ITS TYPE COUNTS. A bare part number (`Molex-0548190589`,
`U-M-M5DD-W-1`) may well be a receptacle, but reading it means a datasheet, not a
pattern; it is listed as `unnamed` for a person to check, not guessed at.

AN EMPTY BOARD IS UNKNOWN, NOT "NO USB". A board may take its USB from a daughterboard
over a JST or a header (`USB_breakout`, `Pins_USB`, `USB_5POS_header`), or from a
footprint this pattern does not know. Silence here is ignorance -- see
[[project-extractors-prove-presence]].

Usage: measure_usb.py [--out FILE]
"""
import argparse
import collections
import json
import pathlib
import re

REPO = pathlib.Path(__file__).resolve().parent.parent

# Order matters only in that each name is tested against every rule and must match
# exactly one type; a name matching two is reported, not resolved.
TYPES = {
    "usb_c": re.compile(
        r"USB[_-]?C\b|USB[_-]C[_-]|TYPE[_-]?C|USB4085|USB45\d\d|U262-16|"
        r"12401548E4|UTC16-G", re.I),
    # `USB` REQUIRED: a bare `Micro[_-]B` matches `ProMicro_B`, `ProMicro_BLE` and
    # `ArduinoProMicro-BackSide`, which are dev-board sockets, not receptacles.
    "micro_b": re.compile(r"USB[_-]?Micro", re.I),
    "mini_b": re.compile(r"USB[_-]?Mini|Mini[_-]?B\b|MINIB|MINI_MAB", re.I),
    "usb_a": re.compile(r"USB[_-]A[_-]", re.I),
    "usb_b": re.compile(r"USB[_-]B[_-]", re.I),
}
# Footprints whose name contains a type word but which are not receptacles.
NOT_A_RECEPTACLE = re.compile(
    r"logo|USBLC6|ESD|spacer|outline|cutout|Hybrid|breakout|header|Pins_USB", re.I)
# Named as USB but not stating a type -- a daughterboard link, or a hybrid.
NAMED_USB_UNTYPED = re.compile(r"USB", re.I)


def classify(footprint):
    """`(type, None)`, `(None, "untyped")`, or `(None, None)` for a non-USB footprint."""
    name = footprint.split(":", 1)[-1]
    if NOT_A_RECEPTACLE.search(name):
        return None, ("untyped" if NAMED_USB_UNTYPED.search(name) else None)
    hits = [t for t, rx in TYPES.items() if rx.search(name)]
    if len(hits) == 1:
        return hits[0], None
    if len(hits) > 1:
        return None, "ambiguous"
    return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scan", default="data/board-scan.json")
    ap.add_argument("--boards", default="data/design-boards.json")
    ap.add_argument("--designs", default="src/main/resources/seed/designs.json")
    ap.add_argument("--out", default="data/usb-scan.json")
    args = ap.parse_args()

    scan = json.loads((REPO/args.scan).read_text())
    boards = json.loads((REPO/args.boards).read_text())
    designs = json.loads((REPO/args.designs).read_text())

    out, tally = {}, collections.Counter()
    for design in designs:
        slug = design["slug"]
        controller = design.get("controller") or {}
        attributed = boards.get(slug) or {}
        files = {}
        for path in attributed.get("paths") or []:
            entry = scan.get(f"{attributed['repo']}\t{path}")
            if not entry or entry.get("kind") != "board":
                continue
            found, loose = collections.Counter(), collections.Counter()
            for footprint, n in entry["names"].items():
                kind, why = classify(footprint)
                if kind:
                    found[kind] += n
                elif why:
                    loose[footprint] += n
            files[path] = {"receptacles": dict(found), "unclassified": dict(loose)}
        kinds = sorted({k for f in files.values() for k in f["receptacles"]})
        out[slug] = {
            "controller": controller.get("kind"),
            "usb": kinds,
            "files": files,
        }
        tally[(controller.get("kind"), "+".join(kinds) or ("no board" if not files
                                                            else "none found"))] += 1

    (REPO/args.out).write_text(json.dumps(out, indent=1, sort_keys=True) + "\n")
    print(f"{len(out)} designs")
    for (kind, usb), n in sorted(tally.items(), key=lambda t: (str(t[0][0]), -t[1])):
        print(f"  {str(kind):<9} {usb:<22} {n}")


if __name__ == "__main__":
    main()
