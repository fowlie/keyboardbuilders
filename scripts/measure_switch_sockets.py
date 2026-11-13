#!/usr/bin/env python3
"""Which designs' boards take hotswap sockets.

The builder's first question about switches -- "can I change them later without a
soldering iron" -- which this catalogue currently cannot answer at all.
`SwitchFootprint` records the SHAPE of a switch (MX, Choc v1, Choc v2, Alps) and says
nothing about how it is held.

PRESENCE ONLY, AND THE ABSENCE IS NOT "SOLDERED". This is a regex over footprint
NAMES, so it proves a board takes sockets and can never prove one does not. Measured
2026-09-16 over the 328-design seed: 127 designs place a socket-named footprint, and of
the 201 that do not, **30 say hotswap in their own README** -- `Bastardkb/Dilemma`
("version with hotswap"), `ianklug/grooveboard` ("Uses Kailh MX hot-swap sockets").
Their boards name the part something this scan cannot recognise. (`pierrechevalier83/ferris`
was cited here as a third, wrongly: its readme lists "Hot-Swap support" among its
NON-goals, and its boards' pads are solder-only. Corrected 2026-10-01; the 30 was not
re-counted.) So a design missing from the output is `Unknown`,
never a measured "soldered" -- the same rule `measure_extras.py` and `Design.kt` state,
learnt the expensive way on backlog #66.

Proving "soldered" needs pad GEOMETRY, not part names, and is a separate ticket.

READS THE DESIGN'S OWN BOARDS via `attributed_boards.load()`, never an rglob over the
repo -- the correction `measure_extras.py` records, which was wrong on 15 of 228 designs
and looked right on every one of them.

THE EXCLUSIONS ARE IMPORTED, NOT RESTATED. `extract_switch_footprints`' list already
carries every trap someone paid for, and this corpus contains a real `Logos:Hotswap
Logo` -- a silkscreen graphic that a bare `hotswap` regex reads as a socket.

Usage: measure_switch_sockets.py
"""
import collections
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from attributed_boards import REPO, SCRATCH, load  # noqa: E402
from extract_switch_footprints import ABSOLUTE_NOT_A_SWITCH  # noqa: E402
from measure_key_pitch import switch_families  # noqa: E402

# Kailh CPG151101S11 (MX) and CPG1350 (Choc) are the two sockets this hobby uses, and
# both are named outright about as often as they are named by part number. `socket_mx`
# / `socket_choc` catch the `Kailh_socket_*` family when the brand is dropped.
SOCKET = re.compile(
    r"hot[\s_-]?swap|kailh[\s_-]?socket|socket[\s_-]?(?:mx|choc)|cpg151101|cpg1350",
    re.I,
)

# A DEV-BOARD SOCKET IS NOT A SWITCH SOCKET, and both are called "socket". These are
# headers the controller drops into -- `controller_footprint`'s question, already asked
# and answered elsewhere. Without this, every board with a socketed Pro Micro would
# claim hotswap switches.
CONTROLLER_SOCKET = re.compile(r"pin[\s_-]?socket|conn|header|jst|\busb\b", re.I)


# MILL-MAX RECEPTACLES, backlog #107: sleeves pressed into a switch's widened, plated pin
# holes -- Sweep 2's `choc-v1-with-millmax` drills its pins 1.52 mm for them. Mill-Max also
# makes the pins people socket a DEV BOARD with, so the name alone is not enough: it counts
# only on a footprint `switch_families` reads as a switch. Measured 2026-10-01, every
# Mill-Max name on a published design's boards is a switch footprint (Sweep 2, Jiran
# Classic, Wave); the guard is for the dev-board footprint that has not turned up yet.
MILLMAX = re.compile(r"mill[\s_-]?max", re.I)


def kinds_in(raw_names):
    """Which socket kinds a board's `library:name` strings show: `hotswap`, `millmax`,
    both, or neither. Same asymmetry as `sockets_in`: exclusions read the whole name, the
    pattern reads only the bare name."""
    kinds = set()
    if sockets_in(raw_names):
        kinds.add("hotswap")
    for full in raw_names:
        if ABSOLUTE_NOT_A_SWITCH.search(full) or CONTROLLER_SOCKET.search(full):
            continue
        if MILLMAX.search(full.split(":")[-1]) and switch_families(full):
            kinds.add("millmax")
    return kinds


def sockets_in(raw_names):
    """The socket-named footprints among a board's `library:name` strings.

    Asymmetric, exactly as `extract_switch_footprints.classify` is and for the same
    reason: exclusions read the WHOLE `library:name`, the socket pattern reads only the
    bare name. A library is named for everything it holds --
    `PCM_Switch_Keyboard_Hotswap_Kailh` holds that board's diodes too, and matching the
    library would hand every one of them a socket.
    """
    hits = set()
    for full in raw_names:
        if ABSOLUTE_NOT_A_SWITCH.search(full) or CONTROLLER_SOCKET.search(full):
            continue
        if SOCKET.search(full.split(":")[-1]):
            hits.add(full)
    return hits


def main():
    rows, names_for = load()

    per, examples = {}, collections.Counter()
    unread = 0
    for design in rows:
        raw = names_for(design["slug"])
        # An unread board is NOT a board without sockets. Skipping keeps the
        # denominator honest and keeps a silent absence out of the cache.
        if not raw:
            unread += 1
            continue
        hits = sockets_in(raw)
        per[design["slug"]] = sorted(kinds_in(raw))
        for hit in hits:
            examples[hit[:52]] += 1

    found = sorted(slug for slug, v in per.items() if v)
    print(f"  {len(rows)} designs: {len(per)} boards read, {unread} not read\n")
    print(f"  socket evidence      {len(found):>4}   -> facet row")
    print(f"  read, no socket name {len(per) - len(found):>4}   -> NO ROW (Unknown, not "
          f"'soldered')")
    print(f"  boards not read      {unread:>4}   -> NO ROW\n")
    print("  most-placed socket footprints:")
    for name, n in examples.most_common(8):
        print(f"    {n:>4}  {name}")

    out = SCRATCH/"switch-socket-scan.json"
    out.write_text(json.dumps({s: per[s] for s in sorted(per)}, indent=1))
    print(f"\n  {len(found)} designs with evidence -> {out.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
