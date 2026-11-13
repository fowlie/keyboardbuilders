#!/usr/bin/env python3
"""Every footprint name on the boards a design actually owns.

The one place the three `measure_*` scripts that ask a question of a board's footprints
get their input, so they cannot disagree about which boards belong to which design.

WHY THIS EXISTS, and it is a bug rather than a tidy-up. `measure_extras.py`,
`measure_build_difficulty.py` and `measure_mcu_package.py` each took a clone root,
`rglob`'d **every** `.kicad_pcb` in a design's repo, and re-derived the slug-to-board
attribution themselves by scoring slug tokens against paths. That is the exact failure
`build_seed.py`'s attribution rule exists to prevent -- *"a single-row repo can hold 22
board files, and reading all of them unions the switch families of twelve superseded
versions into one design's record"* -- and it was live in all three. Measured:

    sweeq     "supports a display", from SEVENTEEN other boards in `idank/keyboards`
              -- Corne, Lily58, Keyball, TPS42. Its own board has nine footprints and
              no OLED.
    kobold    a 16-key macropad claiming display + encoder + sd_card + speaker, every
              one of them from Djinn and Ghoul in `tzarc/keyboards`.
    sofle v1  a trackpad, from `Sofle_Pico` -- a different Sofle.
    paws60    an encoder and a display, from `sakura50` and `lazulipad`.

Of 228 designs both readings cover, 213 agree and **15 differ, every one of them a
fact bleeding between designs in one repo.**

TWO SOURCES, BOTH ALREADY IN THE REPO AND BOTH ALREADY CURRENT:

    design-boards.json   slug -> {repo, paths}, written by `build_seed.py` from the
                         CONFIRMED attribution. Authoritative, and not a guess.
    board-scan.json      repo\\tpath -> every footprint name with its count, which the
                         controller and MCU readers already trust for exactly this.

So the clone root is gone. It was 1.5 GB of checkout for data the corpus keeps in two
committed files, and the scans had rotted because of it: `extras-scan.json` covered 234
designs against a corpus of 272 and still held rows for slugs renamed on 2026-08-29.
This reads all 272 in milliseconds, with no network at all.
"""
import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SCRATCH = REPO/"data"


def load(designs="src/main/resources/seed/designs.json"):
    """`(rows, names_for)` -- the design list, and a slug's own footprint names.

    `names_for` returns the names AS THE BOARD SPELLS THEM, `library:name`, and each
    caller applies `bare` itself. Not pre-normalised, because `socket_of` is asked of
    the full string in `measure_build_difficulty.py` -- a dev-board socket is headers
    rather than the part it names -- and a helper that decided that for its callers
    would be quietly changing what they measure.

    An empty set means the scan has not read this design's boards, which is NOT the
    same as a design with no extras. Every caller must skip it rather than record an
    absence -- the rule `Recorded.Unknown` exists for on the domain side.
    """
    rows = json.loads((REPO/designs).read_text())
    boards = json.loads((SCRATCH/"design-boards.json").read_text())
    scan = json.loads((SCRATCH/"board-scan.json").read_text())

    def names_for(slug):
        info = boards.get(slug)
        if not info:
            return set()
        found = set()
        for path in info["paths"] or []:
            entry = scan.get(f"{info['repo']}\t{path}") or {}
            found |= set(entry.get("names") or {})
        return found

    return rows, names_for
