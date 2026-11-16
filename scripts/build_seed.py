#!/usr/bin/env python3
"""Assemble confirmed seed rows into complete design records the site can serve.

Every required field or nothing: `DesignCore` has no optional members, so a record
that cannot fill all ten is not a partial record, it is not a record. This script
therefore reports two numbers -- what it built, and what each unbuilt row was
missing -- and the second is the interesting one.

Four sources, none of them guessed at:

  seed/sources.toml          which repo holds a row's hardware, human-confirmed
  seed/trees.toml            WHERE each row's firmware record lives -- backlog #64
  board-scan.json            switch families and footprint names, per board file
  licence-scan.json          every licence file in each repo
  the registered checkouts   key counts and split flags, from each row's own tree

THE ATTRIBUTION RULE, and it is the reason this yields less than the row count:
**a row with no attribution may only import a repo that holds exactly ONE design.**
The board scan is keyed per board file with nothing tying a file to a row, so
`joe-scotto/scottokeebs` holding 25 designs would have one row's switch families
stated for all 25 -- 25 facts checked once. `seed/boards.toml` ties them; until it
does, the row waits. Two ways to need it, and they defer separately: several rows
sharing a repo (43% of confirmed rows), and one row over a repo holding several
designs. Neither is a loss -- both are the review queue.

THE NAME RULE, ticket 44's: **a design is named by its board's own directory**, not
by the firmware id that happens to point at it. `davidphilipbarr/36keys` and
`davidphilipbarr/sweep` both answer to a QMK/ZMK record called `cradio` and are two
keyboards; the boards say Cradio and Sweep. Drafted in `seed/boards.toml`, normalised
by the human confirming the row, and the slug follows the name.

THE LICENCE RULE, the owner's, 2026-08-26: one licence file fills all three slots,
and hardware is the one that matters. Repos with SEVERAL licence files are deferred
instead -- `foostan/crkbd` carries CC-BY-4.0 and MIT side by side, which is a repo
saying its hardware and its firmware differ, and picking one would be inventing the
answer to the question the three slots exist to ask. `seed/licences.toml` is where that
answer goes once a person has made it, and it is the only thing that can fill the three
slots with three DIFFERENT values -- every one of the 234 records built without it has
the same licence in all three, because one file is all the repo gave us.

THE TREE RULE, backlog #64's: **a row's `tree` is a key into `seed/trees.toml`**, not
one of two literal strings. The registry says which reader understands the tree and
which checkout to read it from, so a record that lives in an author's own unmerged fork
-- `tompi/vial-qmk@cheapinov2` holds the Cheapino's -- is read by QMK's reader from a
different root. What a design PUBLISHES is the reader, never the tree id: a QMK fork
still runs QMK, and `family` is what Door B matches controllers on. See [firmware_ref].

Usage: build_seed.py [--out src/main/resources/seed/designs.json] [--qmk DIR] [--zmk DIR]
                     [--firmware-url] [--verbose]
"""
import argparse
import collections
import json
import os
import pathlib
import re
import subprocess
import tomllib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import board_fills
import firmware_records
import qmk_metadata
import zmk_wiring
import trees as tree_registry
from check_source_reachability import parse
from draft_boards import VERSION, id_tokens, load_boards, toks
from measure_controller_socket import (socket_of, chip_of, chip_in_bom, FOOTPRINT,
                                       MCU_PACKAGE, MCU_NAMED)
# The encoder pattern is measure_extras' own, imported rather than restated so the two
# instruments cannot drift apart on what an encoder is -- the rule scan_boards.py
# already follows for switch families.
from measure_extras import EXTRAS
from measure_keycaps import most_keys
from measure_usb import classify as usb_receptacle

ENCODER = EXTRAS["encoder"]

REPO = pathlib.Path(__file__).resolve().parent.parent

# The board scan's family names, mapped to the domain's switch footprints. There is no
# catch-all: every commercial switch gets its own kind (owner, 2026-10-02).
SWITCH = {"MX": {"kind": "mx"}, "ChocV1": {"kind": "choc_v1"},
          "ChocV2": {"kind": "choc_v2"}, "Alps": {"kind": "alps"},
          "KailhX": {"kind": "kailh_x"},
          "ChocMini": {"kind": "choc_mini"}}



def sockets_on_board(found):
    """The form factors ONE board file offers -- backlog #99.

    A PCB can place two controller footprints and let the builder choose: `prkl30` takes
    a Pro Micro or a Feather. So the answer is a set, but only ever from ONE board file.
    A design's other board files are usually its revisions, and joining their sockets
    would invent a PCB that offers both (the domain's `Controller.Socket` says why).

    The first board file that names any socket decides, the precedence this had when it
    returned one value; the change is that it now keeps every socket that file names.
    """
    for entry in found:
        hits = {socket_of(name) for name in (entry.get("names") or {})} - {None}
        if hits:
            return hits
    return set()


def socket_controller(footprints):
    """The seed's socket record, or None when no footprint is one the domain models."""
    known = sorted(FOOTPRINT[f] for f in footprints if f in FOOTPRINT)
    return {"kind": "socket", "footprints": known} if known else None

# The connector a split declares on its own board -- map ticket 52. A 3.5mm
# four-conductor jack is the split cable, and JACK is deliberately narrower than
# "every connector a repo places": JST and PicoBlade carry inter-half wiring too, but
# also batteries, OLEDs and encoders, and `karn`'s only JST part is a two-pin battery
# lead -- so widening the lexicon to them would stop it saying anything about the link.
# Read off the corpus's own footprint names, not guessed at, and priced 2026-09-02 at
# 98% precision / 89% recall over all 270 published designs
# (scripts/measure_board_count_trust.py). THE LEXICON IS THE CAUTION: the first version
# spelled the part `mj4pp9`, and the corpus spells it `MJ-4PP-9` -- missing that dash
# read Sofle v2, Lily58, Helix, Yosino58 and Rhymestone as having no wired link, at 52%
# recall instead of 89%.
JACK = re.compile(r"mj[-_ ]?4pp|trrs|trss|\bpj[-_ ]?3\d{2}|pj[-_ ]?39[89]|sj[-_]?435"
                  r"|jack[-_ ]?3\.5|3\.5mm[-_ ]?pj|audio[-_ ]?jack|3\.5mm.*jack", re.I)

# An RJ45 / 8P8C jack, read off the footprint's own name -- the six in the corpus spell it
# `RJ45_Amphenol_54602-x08_Horizontal`, `RJ45_Ninigi_GE`, `RJ45_SMD_MJ88_HLSV6`,
# `RJ45-DS1128-05-S8B8P` and `8P8C_SMT_Double_Sided`. `Cutout_8P8C_SMT` is the same jack's
# case cutout and matches too, which is harmless: it sits beside the jack it cuts for.
RJ45 = re.compile(r"rj[-_ ]?45|8p8c", re.I)

# A 4-pin Mini-DIN jack, the S-Video connector -- Forgejo #132, Wave's
# `custom:AdamTech_MDJ-004-FS_reversible`. NEVER BARE "din": the corpus places DIN as a
# STANDARD in dozens of names that are no connector at all -- `R_Axial_DIN0207...`
# resistors and `MountingHole_..._DIN965` screws, measured 2026-09-29 across every
# scanned board. So the part family (`MDJ-00n`) or the words "mini-din" / "s-video".
# `(?<![a-z0-9])`, not `\b`: the part follows an underscore (`AdamTech_MDJ-004`), and an
# underscore is a word character, so `\b` never fires there.
MINI_DIN = re.compile(r"(?<![a-z0-9])mdj[-_ ]?00\d|mini[-_ ]?din|s[-_ ]?video", re.I)

# NO OVERRIDES, and the one there was is the reason this note exists. `weteor/3w6` was
# hand-ruled `bridge` -- "joined by a rigid PCB bridge" on the page -- on the reading
# that `3W6_middle_single_L/R.kicad_pcb` were the two halves of a bridge board that the
# automatic reading could not see, both being `no-switch` files outside the design's own
# board set. **They are the MIDDLE PLATES.** The repo's README introduces them under
# revision 2 as "new middle plate (1.6mm, better 2.0mm) instead of diodes. Uses Aluminium
# PCBs as a cheaper alternative to lasered aluminium" -- case stack, not interconnect.
#
# What the halves actually use is a USB-C cable, owner-confirmed 2026-09-06 and legible
# in the main board's own footprints: `3W6.kicad_pcb` places all 36 switches, ONE
# AtMega32U4, one TSSOP-24 (the TCA9555 the README names), eight `Breakaway_Tabs` -- so
# one panel snapped into two halves -- and **three** `USB-C-12-Pin-MidMount`: one to the
# host and one on each half for the link.
#
# It read as no link until 2026-09-29, because the link port and the host port are the
# same footprint. They are told apart now by COUNTING, not by name: one chip drives both
# halves (the reason it needed a bodies.toml ruling at all), so the host needs one socket
# and the other two can only be the link -- see `one_chip` on `split_link`. Still no
# hand-written exception: a design's page saying nothing beats one saying something
# untrue, and an exception is how the untrue thing got here. `SplitLink.Bridge` now has
# no members in the corpus -- backlog #63.


def split_link(slug, repo, paths, boards, controller, one_chip=False):
    """Every connector a split's own board places to join its halves -- ticket 52,
    widened by #97 to more connectors and to a LIST, because a board may offer a choice
    (Stront MX-38: "USB-C/TRRS") and a reader filtering for USB-C must find it.

    An empty list is a value, not a gap: a board with no link footprint might be
    wireless, or linked over an I/O expander (`wired-pierce`, Forgejo #35's neighbour), or
    genuinely unbuilt -- the design states what its board HAS, and which of those it is
    becomes the build's business.

    USB-C ONLY ON A SOCKETED SPLIT. There the host cable plugs into the dev board, so a
    receptacle on the keyboard's own PCB is the link: Stront's README says "Interconnect:
    USB-C", and Lotus 58's "USB-C" folder places one where its "TRRS" folder places the
    jack. On a soldered split the same footprint is the host port -- 16 soldered TRRS
    splits carry one -- and `3w6`, which uses USB-C for both, cannot be told apart by
    name (see the note above `JACK`).

    ...UNLESS ONE CHIP DRIVES BOTH HALVES. `one_chip` is set only where the firmware
    reads as one matrix and a person ruled the design two halves (seed/bodies.toml): the
    far half is an I/O expander, so there is one host port, and a USB-C socket beyond
    one is the link. Counted as PLACEMENTS across the whole board-set: Pando places two
    on its left board and one on its right, the 3w6 three on its one panel. Measured
    2026-09-29 over the 20 soldered splits: it reads those two and moves no other. It is
    never asked of a split whose firmware drives two chips -- the Dilemmas and Corne v4s
    place two USB-C footprints per board and link over TRRS, and a count there would
    lie.
    """
    names = set()
    usb_c = 0
    for entry in boards.get(repo) or []:
        if entry.get("path") in (paths or ()):
            placed = entry.get("names") or {}
            names |= set(placed)
            usb_c += sum(n for name, n in placed.items() if usb_receptacle(name)[0] == "usb_c")
    links = []
    if any(JACK.search(n) for n in names):
        links.append("trrs")
    if (controller or {}).get("kind") == "socket" \
            and any(usb_receptacle(n)[0] == "usb_c" for n in names):
        links.append("usb_c")
    elif one_chip and (controller or {}).get("kind") == "soldered" and usb_c > 1:
        links.append("usb_c")
    if any(RJ45.search(n.split(":", 1)[-1]) for n in names):
        links.append("ethernet")
    if any(MINI_DIN.search(n.split(":", 1)[-1]) for n in names):
        links.append("mini_din")
    return links


# A 4-pin JST-SH, the cable to a separate USB daughterboard (the Unified Daughterboard's
# connector). Only read on a soldered board with no receptacle of its own -- its chip
# needs USB from somewhere, and this is the only place left for it to come from. The
# same part carries I2C on a board that DOES have a receptacle, which is why it is never
# read there.
DAUGHTERBOARD = re.compile(r"SM04B-SRSS", re.I)


def host_port(repo, paths, boards, controller, body):
    """The USB receptacle on a design's OWN board that the computer cable plugs into --
    `usb_c`, `micro_b`, `mini_b` or `daughterboard` -- or None. Backlog #97.

    A SOLDERED board's receptacle is the host port: every soldered split carrying USB-C
    also places a separate link, or is `3w6`. Two different kinds on one board is None,
    not a pick.

    A SOCKETED board's cable plugs into the dev board, which `seed/controllers.toml`
    answers, so this is None -- except on a ONE-PIECE socketed board that places a
    receptacle of its own (`env-kb60-mxhs`, `bevi`, `tripel` and two more). With no
    halves to link, that receptacle is a pass-through to the host and the cable goes
    there. On a socketed split the same receptacle is the link (`split_link`).
    """
    names = set()
    for entry in boards.get(repo) or []:
        if entry.get("path") in (paths or ()):
            names |= set(entry.get("names") or {})
    kinds = {usb_receptacle(n)[0] for n in names} & {"usb_c", "micro_b", "mini_b"}
    kind = (controller or {}).get("kind")
    if kind == "soldered":
        if len(kinds) == 1:
            return kinds.pop()
        if not kinds and any(DAUGHTERBOARD.search(n) for n in names):
            return "daughterboard"
        return None
    if kind == "socket" and body.get("kind") == "unibody" and len(kinds) == 1:
        return kinds.pop()
    return None


def slugify(text):
    s = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return re.sub(r"-{2,}", "-", s)


def version_stripped(record_id):
    """The same id with a bare-VERSION leaf dropped, or the id unchanged.

    `8pack/rev11` -> `8pack`; `crkbd/rev4_1/standard` unchanged, because `standard`
    names a variant rather than a revision. `VERSION` is `draft_boards`' own, imported
    rather than restated so the two files cannot drift on what a version looks like.
    """
    segs = record_id.split("/")
    while len(segs) > 1 and VERSION.match(segs[-1]):
        segs = segs[:-1]
    return segs[-1]


def read_attributions(entries):
    """`(tree, id) -> [(board paths, name), ...]` from seed/boards.toml's confirmed rows.

    A LIST, BECAUSE ONE FIRMWARE RECORD CAN RUN ON SEVERAL BOARDS, one row each. The
    `osprette` shield runs on Osprette v1 and on the Osprangle; the README says so
    ("They all share a matrix, so they all run the same firmware"). This was a dict of one
    board-set per record until 2026-11-16, so a second row silently replaced the first.
    Each row becomes its own design, named by its own row.

    One record claiming the same board in two rows is two designs from one PCB, so it
    stops the build. Two RECORDS on one board is normal -- qmk:clog and zmk:clog are one
    keyboard, and the merge joins them by slug.
    """
    out = collections.defaultdict(list)
    for e in entries:
        if e["verdict"] != "confirmed" or not e["boards"]:
            continue
        key = (e["tree"], e["id"])
        for have, _ in out[key]:
            if have & set(e["boards"]):
                raise SystemExit(
                    f"seed/boards.toml: {e['tree']}:{e['id']} claims "
                    f"{sorted(have & set(e['boards']))} in two rows -- one board, two designs")
        out[key].append((set(e["boards"]), e.get("name") or ""))
    return dict(out)


def names_another_board(record_id, bset, all_sets):
    """Does this record's name point at a DIFFERENT board in the same repo?

    The LAST SEGMENT of the id, not the whole thing: a QMK id often carries its
    maintainer -- `ianklug/grooveboard` -- and that name appears in no board path, so
    comparing every token made a real match look partial and deleted all six grooveboards.
    The leaf is the keyboard's own name.

    Three outcomes, and the middle one is the rule:

      leaf tokens all present     a family member. `cradio` fits `Cradio BLE`, `Cradio 10`
                                  and the rest -- one firmware over several designs, which
                                  is the owner's ruling and what the corpus looks like.
      some leaf tokens missing,   a CONFLICT. `handwired/macro3` misses the `3` on
      and present elsewhere       `macro2`, which happens to place 8 switches -- the count
                                  that record declares. Coincidence, not evidence.
      no leaf tokens anywhere     no discrimination possible, so the record is free.
                                  `crkbd/rev4_1/standard` reaches neither Corne board:
                                  QMK slices by revision, the repo by switch type.

    AND WHERE THE LEAF REACHES NOTHING, ASK AGAIN WITHOUT IT. A leaf that is a bare
    revision names no board anywhere, so the third outcome fires and the record floats
    free onto any board in the repo that happens to place the right number of switches.
    `kyleterry/spacetime-keyboard` is the cost: `ergl` places 25 positions, doubled to
    50 by the split rule, and 50 is what `spacetime/rev1` and `rev2` declare -- so a
    27-footprint board published Spacetime's key count under its own name, while the
    Spacetime board published beside it. Six of `cgarcia2097/8-pack`'s seven pages are
    the same shape on `8pack/rev11`.

    A FALLBACK AND NOT A REPLACEMENT, which is the half that had to be measured. Asking
    `version_stripped` FIRST breaks the case the leaf exists for: `ferris/0_1` and
    `ferris/0_2/*` discriminate BY the version, so stepping past it made all four 0.2
    designs cite `ferris/0_1` and took one of them from `Unknown` to `atmega32u4` -- the
    wrong direction, since the 0.1 boards place a different package. So the leaf is
    tried first and the stripped id only where the leaf reached nothing. Measured over
    the built corpus: 277 -> 270 designs, and **not one existing record changed a
    field.**
    """
    def reaches(wanted, paths):
        return any(all(t in set(toks(p)) for t in wanted) for p in paths)

    others = [p for other in all_sets if other is not bset for p in other]
    for leaf in dict.fromkeys((record_id.split("/")[-1],
                               version_stripped(record_id))):
        wanted = id_tokens(leaf)
        if not wanted:
            continue
        if reaches(wanted, bset):
            return False
        if reaches(wanted, others):
            return True
    return False


def without_encoder_positions(counts, found, split):
    """The declared layouts, minus the ones that are counting an encoder as a key.

    A QMK `LAYOUT` is a MATRIX MAP, not a physical description, and a rotary encoder's
    push is a matrix position like any other. So a board with an encoder declares one
    more "key" than it has keys, and we published that number.

    Forgejo #23, owner-reported: KLOR reads **44 keys** and no built KLOR has 44. Its
    board places 21 switches and 1 encoder per hand -- (21 + 1) x 2 = 44 -- while its own
    README says "36-42 key" and the board says 42. The firmware was the only one of the
    three sources disagreeing, and it was the one we printed.

    THE TEST IS EXACT ARITHMETIC, NOT A TOLERANCE, and that is what makes it safe. The
    count is corrected only when `(switches + encoders) x halves` lands exactly on a
    declared layout AND `switches x halves` does not. An off-by-one from a reset button
    -- which is most of the board/firmware disagreements in this corpus, measured -- does
    not satisfy it and is left alone. The board scan is the noisier instrument here and
    this deliberately does NOT let it overrule the firmware in general.

    Measured over the corpus: 13 designs match, and five corroborate on their own NAME,
    which is the check that this is reading the world rather than a coincidence --
    `np12` 13->12, `maxr1998/pulse4k` 6->4, `tunks/ergo33` 34->33, `anavi/arrows` 5->4,
    and `sofle_pico` 60->58, a Sofle being 58 keys.
    """
    halves = 2 if split else 1
    switches = sum(e.get("switches") or 0 for e in found)
    encoders = sum(n for e in found
                   for name, n in (e.get("names") or {}).items()
                   if ENCODER.search(name))
    if not switches or not encoders:
        return counts
    keys_only = switches * halves
    with_encoders = (switches + encoders) * halves
    if with_encoders in counts and keys_only not in counts:
        return [keys_only if c == with_encoders else c for c in counts]
    return counts


LICENCE_SLOTS = ("hardware", "firmware", "documentation")


def _halves_of(body):
    """How many pieces a built `body` says the keyboard is in."""
    return 1 if body["kind"] == "unibody" else body.get("halves") or 2


def soldered_chip(found, built, repo=None, boms=None):
    """Which chip is on this board, or None -- `Controller.Soldered.mcu`.

    Only ever asked once the board file has established that a chip IS on the board, so
    this answers "which one" and never "is there one". Three sources, in order of how
    directly each one states the answer:

    0. **The board's own BOM**, where the repo ships one. A BOM STATES A PART where a
       footprint only describes a hole: `foostan/crkbd` writes
       `RP2040,"U1,rU1",QFN-56-1EP_7x7mm_P0.4mm,C2040`, which is what the author sent to
       a fab. Scanned by `scripts/scan_boms.py`, joined per BOARD rather than per repo.
       **It is worth 2 designs and both are Corne** -- stated plainly because the
       forecast was ten times that. Every other chip a BOM names is outside the domain's
       three. It goes first anyway: it is the strongest statement available, so any
       future widening of `Mcu` should reach these designs without reordering anything.
    1. **The board file**, where a footprint names its own part. Nine of 75 soldered
       repos do (`Keeb_components:ATMEGA32U4-MU`, `rp2040:RP2040`). It is the design's
       own drawing.
    2. **QMK's `processor`** -- `Controller.Soldered`'s "honest second source", and this
       is the separate pass it asks for. `built` is `built_for()` of each record the
       design runs; a ZMK shield names no chip, so only QMK's ever speaks here.

    `Unknown` wherever the sources disagree or the chip is outside the domain's three.
    Measured over the 108 soldered designs in the 275: 15 from the board, 41 from QMK,
    **51 named a chip the enum had no member for** -- `atmega32a` 12, `atmega328p` 12,
    `stm32f072` 10, `atmega32u2` 8 -- and 1 names nothing at all. The list has since been
    widened to what the corpus names (`measure_controller_socket.CHIPS`, 2026-09-07 and
    2026-09-29); a nearest-member fit is still the default-fill this project refuses.

    The disagreement case is real and not theoretical: `ianklug/grooveboard` ships nine
    boards under ONE QMK record declaring `atmega32u4`, and three of them place an
    `rp2040:RP2040`. The board file is right about those three, which is the whole
    argument for the precedence -- and it is what makes nine identically-specified pages
    into six and three.
    """
    stated = {c for e in found
              for r in ((boms or {}).get(f"{repo}\t{e.get('path')}")
                        or {}).get("parts") or []
              if (c := chip_in_bom(r["part"]))}
    if len(stated) == 1:
        return stated.pop()
    if stated:
        return None
    # A footprint's name, or the part its value states (`chips`, read by scan_boards.py;
    # absent on a file cached before 2026-10-01, which is not-read and not "none").
    named = {c for e in found for n in (e.get("names") or {}) if (c := chip_of(n))}
    named |= {c for e in found for c in e.get("chips") or []}
    if len(named) == 1:
        return named.pop()
    if named:
        return None
    declared = {b.chip for b in built}
    declared.discard(None)
    return declared.pop() if len(declared) == 1 else None


def split_reading_holds(found, layouts):
    """Can a split shield's reading hold this design's own keys?

    `zmk_wiring.split_wiring` answers with ONE half's scan, so two halves' worth --
    `2 x rows x cols` for a matrix, `2 x pins` for direct pins -- must reach the largest
    layout the design publishes. A reading that cannot is a shield attributed to some
    other board, and its wiring is not this design's to print."""
    places = 2 * (found[1] if found[0] == "direct" else found[1] * found[2])
    return max((l["keys"] for l in layouts), default=0) <= places


def shared_record_target(controller, board_chip, record_chip):
    """What a QMK record SEVERAL designs cite is built for, said of one of them -- or None.

    A shared record is withheld by default (the `targetMcu` pass says why: `ianklug/
    grooveboard` is nine designs on one record spanning atmega and RP2040 hardware). The
    one case where that caution has nothing left to guard is a SOLDERED design whose own
    board names the very chip the record is built for: the chip is on the board, the
    record targets it, and no sibling's hardware can change either fact.

    `board_chip` MUST BE THE BOARD'S OWN WITNESS -- `soldered_chip(found, [])`, its BOM or
    its drawing -- never the published `mcu`. `soldered_chip` falls back to QMK's
    `processor`, so a board that names no chip can carry this same record's chip as its
    `mcu`, and "the design's MCU equals the record's" would then be the record agreeing
    with itself. The published `mcu` must agree as well, so the page never prints a
    target that contradicts its own MCU line.

    A socketed design gets None however its siblings are built: the builder picks the
    module, and which one this shared record was written for is not this design's fact."""
    if (controller or {}).get("kind") != "soldered" or not record_chip:
        return None
    if board_chip != record_chip or controller.get("mcu") != record_chip:
        return None
    return record_chip


def contradicts_board(built, chip):
    """Does this record describe a controller the board visibly does not have?

    Asked only of a board that SOLDERS its chip and offers no socket, so the board's own
    drawing is the witness. Two contradictions, both read from the record itself:

      * a QMK `development_board` (`promicro`) -- the record is for a board that takes a
        plug-in controller. `crkbd/rev1` is Corne v1-v3's Pro Micro record; the repo's
        main branch holds only v4, which places its own RP2040.
      * a QMK `processor` that is not the chip the board states -- `crkbd/r2g` is
        Mechboards' ATmega32U4 PCB, not foostan's RP2040 one.

    And a ZMK SHIELD, which by ZMK's own definition sits on a separate controller board;
    a keyboard with its chip on board is a ZMK `board`, never a shield.

    `built` is the record's `built_for()`; the first and third are its
    `needs_separate_controller`.
    """
    if built.needs_separate_controller:
        return True
    return bool(chip and built.chip and built.chip != chip)


def socket_contradicts(built, footprints):
    """Is this record built for a Pro Micro, on a board with no Pro Micro socket?

    The socketed counterpart of `contradicts_board`, and the board is again the witness:
    the socket footprints it places. 2026-11-16: Clog V2 and V4 take a Seeed Xiao, and
    carried `clog` from QMK (`elite_c`) and ZMK (`requires: [pro_micro]`) because their
    key count fits. The README says V2 has its own config and V4 runs `geta`.

    ONLY THE PRO MICRO FAMILY, where the footprint is unambiguous. Measured the same day
    over every socketed design: this drops 6 refs on 4 designs (clog-v2, clog-v4, pica40's
    rev1, lily58-lite-rev3's shield). A board that names no socket keeps its records --
    silence is Unknown, never a disagreement.

    `built` is the record's `built_for()`: an Elite-C record and a `requires: [pro_micro]`
    shield both read `socket == "pro_micro"`, from one list in `DEV_BOARD`.
    """
    if not footprints or "pro_micro" in footprints:
        return False
    return built.socket == "pro_micro"


def agreeing_with_board(fits, footprints, soldered, found, records, repo, boms):
    """The records that fit a board's key count, narrowed by its controller WHEN THAT
    DISCRIMINATES -- the same shape as `names_another_board`'s token rule.

    Key count alone put all seven `foostan/crkbd` records on both Corne v4 boards: the
    Pro Micro ZMK shield, Corne v1-v3's `rev1`, Mechboards' `r2g`, and the four `rev4_*`
    records that actually run it. That also cost Corne its wiring, since `rev1`'s
    diode matrix and `rev4`'s direct pins disagree.

    ONLY NARROWS, NEVER EMPTIES. Where no fitting record agrees with the board, they are
    all kept, as before. Measured 2026-09-28 over the published corpus: 17 soldered
    designs carry a contradicting record, and 6 have nothing else (`grooveboard` x3,
    `paws60`, `cypher`, `dropin-v2`). Withholding those would be a different ruling
    from this one. The build diff was 10 designs, all in Half B: Corne x2, and the
    eight Ferris boards, which were wearing `ferris/sweep` (the Pro Micro Sweep). That
    record was also the only one to set their split flag and the direct-pin wiring, so
    seed/bodies.toml now rules them split. They keep no wiring, which is right: 0.1 and
    0.2 read the far half through an MCP23017.
    """
    if footprints or not soldered:
        return fits
    chip = soldered_chip(found, [], repo, boms)
    agreeing = [f for f in fits
                if not contradicts_board(records.of(f[0]["tree"], f[0]["id"]).built_for(), chip)]
    return agreeing or fits


def firmware_ref(reg, row, with_url):
    """The `firmware` entry a design publishes for one confirmed row.

    `family` IS THE READER, never the tree id, and that is the load-bearing line of
    backlog #64. A fork of QMK still runs QMK: `FirmwareRef.familyKey()` maps a ref to
    `"qmk"` for Door B's compatibility check, so a Pro Micro that runs QMK runs the
    Cheapino. If a fork-hosted record published `family = "cheapino-vial"` the
    CONTROLLER section would silently stop matching and the design would show an empty
    compatibility list with no error. Same for the summary chip `family()` feeds -- a
    chip reading `vial-qmk` puts an intake detail on the scanning surface. **The fork
    changes where the record lives, never what runs it.**

    So `url` is the ONLY place the difference shows, and it is null for every record in
    the upstream tree -- which is every design in the corpus before this. `with_url`
    gates emitting it at all: `SeedDesignCatalog` parses this file, and a field the
    domain has no member for breaks the build. See [main]'s `--firmware-url`.

    `_tree` is build-time joinery, not a fact about a design -- the wiring pass needs to
    know which tree to read a record's `matrix_pins` from. Popped before the seed is
    written, like `board_key` and `board_paths`.
    """
    reader = reg.reader(row["tree"])
    ref = {"family": reader,
           "path": row["id"] if reader == "qmk" else None,
           "shield": row["id"] if reader == "zmk" else None,
           "_tree": row["tree"]}
    if with_url:
        ref["url"] = reg.record_url(row["tree"], row["id"])
    return ref


def wiring_is_its_own(ref):
    """May this firmware ref's matrix be published as the design's own wiring?

    NOT WHEN IT IS BORROWED. A `hardware` row runs another design's record (Forgejo
    #22), and that record's `matrix_pins` or `kscan` describe the DONOR's board.
    Chocofi runs Corne's ZMK shield, which scans 4 x 6; Chocofi's own PCB wires 5
    columns, and published as 4 x 6 until 2026-11-15. The firmware still runs, so the
    ref stays; only the wiring reading stops. `_borrowed` is joinery like `_tree`.
    """
    return not ref.get("_borrowed")


def stated_keys(row, found):
    """A person's key count for this design, and the board's verdict on it.

    Returns `(claimed, reason)`: `claimed` is None where the row states nothing, and
    `reason` is a defer string where it states something the board contradicts.

    THE COUNT IS ASSERTED AND CHECKED, and the check is the whole of it. `keys` is a
    human's statement and the board is the falsifier: it must equal what the boards
    place, or twice it for a split board file holding one hand. The same
    either-interpretation rule Half B uses -- and it cannot be derived alone, because a
    split design whose file holds BOTH hands already places the full count and doubling
    it would publish twice the keyboard.

    LIFTED OUT OF THE `hardware` BRANCH, 2026-09-07, on the owner's ruling that the
    Cheapino *"is not 37 keys, it is 36 + 1 encoder"* -- backlog #65. A `hardware` row
    has always had to state its count because it borrows a record and inherits no
    layout; the same statement is now available to ANY row, where it OVERRULES the
    record instead of standing in for it. That is this corpus's standing precedence and
    not a new one: `seed/bodies.toml` overrules QMK's split flag, `seed/boards.toml`
    overrules the drafted name, and `build_seed` already says THE HUMAN OUTRANKS THE
    RECORD where the two disagree about a witness.

    WHY A RULING AND NOT ARITHMETIC. `without_encoder_positions` is the machine that
    should have caught this and its test is `(switches + encoders) x halves`, which
    assumes an encoder PER HAND. The Cheapino's board is reversible -- one file, built
    twice -- placing 18 switches and one encoder, and only one half's encoder is in the
    matrix map, so the true relation is `18 x 2 + 1`. Widening the rule to that form was
    measured over all 573 confirmed rows and corrects exactly two designs, and the
    second is `keyzog/kapl`, whose own README says *"a split ergonomic keyboard with 70
    keys (7x5)"* -- twice. 70 and 37 are the same arithmetic shape and only their authors
    separate them. So the number cannot be derived here, and a person states it.

    IT REPLACES THE COUNTS, it does not subtract from them. One number in, one number
    out: a row whose record declares several layouts would lose the alternates, which is
    fine for the case this exists for and is stated here so nobody discovers it.
    """
    raw = row.get("keys")
    if raw is None or raw is False:
        return None, None
    # `parse` is this file's own reader and yields every value as a STRING, so
    # `keys = 36` arrives as "36" and a bare `in (placed, placed * 2)` compares a
    # string to two ints and is always false. Coerced here rather than in the reader,
    # which other callers depend on returning text.
    try:
        claimed = int(str(raw).strip())
    except (TypeError, ValueError):
        return None, "stated keys is not a number"
    placed = sum(e.get("switches") or 0 for e in found)
    if not placed:
        return None, "no switch count on the attributed board"
    # OR ANY ONE BOARD'S KEYCAP READING, backlog #81. A footprint count is not a key
    # count on a board offering alternates -- `redherring` places 98 switch footprints
    # for 89 keys -- and `measure_keycaps.most_keys` reads what the footprint count
    # cannot: the most caps the board holds at once. Accepting either reading can only
    # admit a ruling the board corroborates, never invent one.
    held = {n for n in keycap_counts(row.get("source"), found) if n}
    # OR ANY ONE BOARD FILE'S KEYS. The sum is right for a split shipped as `-L` and
    # `-R`, and wrong for revisions of one board: `chocofly`'s three files each place 60
    # key sockets, and the sum is 180. Its footprints name no widths, so `held` cannot
    # read it either.
    #
    # A FILE'S OWN COUNT, AND THAT COUNT LESS ITS ENCODERS, because only some encoder
    # footprints are counted as switches (a combined MX-and-encoder footprint is; a
    # `EVQWGD001` roller is not). This read "less its encoders" alone, which was right for
    # chocofly only by accident: the scan counted its two power switches as keys, 62, and
    # its two rollers took them off again. With the power switches excluded (2026-10-04)
    # the subtraction left 58 and chocofly dropped out of the catalogue.
    each = {n for n in keys_per_file(row.get("source"), found) if n}
    whole = {e["switches"] for e in found if e.get("switches")}
    if not any(claimed in (n, n * 2) for n in held | each | whole | {placed}):
        return None, "stated keys does not match the board"
    return claimed, None


_KEYCAPS = None
_ENCODERS = None


def keycap_counts(repo, found):
    """`measure_keycaps.most_keys` for each attributed board file, from
    `keycap-scan.json` -- `None` for a board it could not read."""
    global _KEYCAPS, _ENCODERS
    if _KEYCAPS is None:
        path = REPO/"data/keycap-scan.json"
        scan = json.loads(path.read_text()) if path.exists() else {}
        _KEYCAPS = {(v["repo"], p): b.get("caps")
                    for v in scan.values() for p, b in v["boards"].items()}
        _ENCODERS = {(v["repo"], p): b.get("encoders")
                     for v in scan.values() for p, b in v["boards"].items()}
    return [most_keys(_KEYCAPS.get((repo, e.get("path"))) or [])
            for e in found]


def keys_per_file(repo, found):
    """Each attributed board file's switch count less the encoders `keycap-scan.json`
    found on it -- `None` for a file with no switch count."""
    keycap_counts(repo, [])  # loads the scan
    return [e["switches"] - (_ENCODERS.get((repo, e.get("path"))) or 0)
            if e.get("switches") else None for e in found]


def two_pieces(ruled, record_says):
    """Is this design in two or more physical pieces?

    The witness Half B's doubling needs. A person's ruling from `seed/bodies.toml` wins
    where there is one -- in BOTH directions, so a `unibody` ruling refuses a doubling
    the record's flag would have allowed -- and the record answers where there is not.

    `ruled` is a count of pieces, 0 or None meaning nobody has ruled.
    """
    return ruled >= 2 if ruled else record_says


def body_rulings(path):
    """Designs a person has ruled one-piece-or-two, keyed by slug. CONFIRMED ROWS ONLY.

    A `drafted` row carries `halves = 0` -- the absence of a ruling, not a keyboard in
    zero pieces -- so an unruled design behaves exactly as it did before the file
    existed. Written by `scripts/draft_bodies.py`, which holds the evidence and the
    reason the machine may not decide this alone.
    """
    p = pathlib.Path(path)
    if not p.exists():
        return {}
    return {e["slug"]: e["halves"] for e in tomllib.loads(p.read_text()).get("entry", [])
            if e.get("verdict") == "confirmed" and e.get("halves")}


def reversible_rulings(path):
    """`slug -> bool` for the CONFIRMED rows of seed/bodies.toml that state `reversible`.

    Optional on a row, and absent is the default: `reversible` publishes Unknown for
    most splits because a board file seldom shows it. A person can read it where the
    halves are visibly different boards -- Ferris places its chip and USB on the left
    half and an MCP23017 IO expander on the right, so no one PCB serves both hands.
    """
    p = pathlib.Path(path)
    if not p.exists():
        return {}
    return {e["slug"]: e["reversible"] for e in tomllib.loads(p.read_text()).get("entry", [])
            if e.get("verdict") == "confirmed" and isinstance(e.get("reversible"), bool)}


def wiring_rulings(path):
    """Key wiring for designs a person read `matrix_scan_custom()` for, keyed by slug.

    CONFIRMED ROWS ONLY, same discipline as `body_rulings`. A `drafted` row carries
    `rows = 0` -- the absence of a reading, not a matrix of no size -- so a design
    still queued for review publishes exactly as it did before this file existed
    (`wiring` left unset by `qmk_metadata.wiring()`, which already correctly declines
    to guess at a CUSTOM_MATRIX board).
    """
    p = pathlib.Path(path)
    if not p.exists():
        return {}
    return {e["slug"]: e for e in tomllib.loads(p.read_text()).get("entry", [])
            if e.get("verdict") == "confirmed" and e.get("rows")}


def based_on_rulings(path):
    """Credited ancestors a person confirmed, keyed by slug. CONFIRMED ROWS ONLY.

    Backlog #24. Unlike `body_rulings`/`wiring_rulings`, a row here does not correct a
    machine signal -- it supplies what no machine may: `based_on` is recorded only from
    an explicit statement, and reading one is exactly the judgement this file exists
    for. Each entry is `{kind: "held", slug: ...}` or `{kind: "external", name: ...,
    url: ...}`, the same two shapes `SeedAncestor` reads on the Kotlin side.
    """
    p = pathlib.Path(path)
    if not p.exists():
        return {}
    out = {}
    for e in tomllib.loads(p.read_text()).get("entry", []):
        if e.get("verdict") != "confirmed":
            continue
        out[e["slug"]] = e["ancestors"]
    return out


SIZE_FIELDS = (("role", "role"), ("drawn", "drawn"), ("width_mm", "widthMm"),
               ("height_mm", "heightMm"), ("layers", "copperLayers"))


def board_sizes(path):
    """`(pcb, plates)` from seed/board-sizes.toml, which `measure_board_size.py` writes.

    `pcb` is `slug -> {drawn, widthMm, heightMm, halves, copperLayers}`: `drawn` says
    what one board file holds (`board`, `half`, `both halves`), the size is EACH board's
    -- one half, for a split -- and `halves` replaces it where a split's halves differ,
    left first. `plates` is `slug -> [{path, role, drawn, widthMm, heightMm,
    copperLayers}]`, `role` being "switch plate" or absent. Each carries ONLY what was
    measured: an absent key is Unknown, and a design with nothing measured gets no
    `pcb` at all. Machine rows, so a row for a slug that no longer publishes is stale
    rather than wrong, and the caller skips it instead of stopping the build.
    """
    p = pathlib.Path(path)
    if not p.exists():
        return {}, {}
    data = tomllib.loads(p.read_text())

    def known(row):
        out = {name: row[key] for key, name in SIZE_FIELDS if key in row}
        if "half_sizes_mm" in row:
            out["halves"] = [{"widthMm": w, "heightMm": h} for w, h in row["half_sizes_mm"]]
        return out

    pcb = {e["slug"]: known(e) for e in data.get("entry", []) if known(e)}
    plates = collections.defaultdict(list)
    for row in sorted(data.get("plate", []), key=lambda r: r["path"]):
        plates[row["slug"]].append({"path": row["path"], **known(row)})
    return pcb, dict(plates)


def family_rulings(path, published):
    """`slug -> family name`, from the CONFIRMED rows of seed/families.toml. Backlog #107.

    Strict, the way `seed/renames.toml` is: a member that does not publish, or a design
    two families both claim, stops the build. Either means a ruling no longer matches the
    corpus, and a person needs telling -- a family that silently loses a board is the
    exact confusion this file exists to end.
    """
    p = pathlib.Path(path)
    if not p.exists():
        return {}
    out = {}
    for f in tomllib.loads(p.read_text()).get("family", []):
        if f.get("verdict") != "confirmed":
            continue
        for slug in f["members"]:
            if slug not in published:
                raise SystemExit(f"seed/families.toml: {f['name']} names {slug!r}, which "
                                 "does not publish -- renamed or withdrawn?")
            if slug in out:
                raise SystemExit(f"seed/families.toml: {slug!r} is in both {out[slug]!r} "
                                 f"and {f['name']!r}")
            out[slug] = f["name"]
    return out


def family_sources(path):
    """`[{name, source}]` for every CONFIRMED family, sorted by name. Backlog #107.

    What the site's family page needs beyond the members, which each design already
    carries as `family`: where the grouping comes from. Membership is deliberately NOT
    repeated here -- a second list of members beside the designs' own `family` field is a
    second place for the two to disagree, and the design page and the family page must
    name the same boards.
    """
    p = pathlib.Path(path)
    if not p.exists():
        return []
    return sorted(({"name": f["name"], "source": f["source"]}
                   for f in tomllib.loads(p.read_text()).get("family", [])
                   if f.get("verdict") == "confirmed"),
                  key=lambda f: f["name"].lower())


def fork_based_on(ancestry, repo_to_slugs):
    """`Held` credits from GitHub's own fork-parent data, keyed by child slug.

    RESTRICTED TO UNAMBIGUOUS REPOS ON BOTH SIDES, and that guard is not optional.
    `horrortroll/keebproj` forked `marksard/keyboards` -- which holds BOTH `rhymestone`
    and `treadstone32`. A naive repo-to-slug lookup picks one of the two arbitrarily,
    and "the fork's parent" would silently credit whichever happened to win, which is
    exactly the failure ticket 22 named: "url is not an identity key." So a repo on
    either side of the edge that maps to more than one published design contributes no
    automatic credit at all -- it is left for `seed/based_on.toml` to settle by hand.

    Verified against this corpus 2026-09-10, three edges survive the guard:
    adelheid->arisu, juliet->romeo, cantor-remix->cantor -- all six repos involved hold
    exactly one design each.
    """
    out = {}
    for repo, info in ancestry.items():
        if not info.get("fork"):
            continue
        parent = (info.get("parent") or "").lower()
        children = repo_to_slugs.get(repo.lower(), [])
        parents = repo_to_slugs.get(parent, [])
        if len(children) == 1 and len(parents) == 1 and children[0] != parents[0]:
            out[children[0]] = [{"kind": "held", "slug": parents[0]}]
    return out


def licence_rulings(path):
    """The repos a person has ruled the licence split for, keyed by repo.

    CONFIRMED ROWS ONLY. A `drafted` row is the drafter's proposal and carries empty
    slots, so reading one would publish `Unstated` -- "all rights reserved" -- over a
    repo that plainly states a licence. Drafted rows are excluded and the repo defers
    exactly as it did before this file existed, which is what makes adding the file a
    no-op until somebody rules a row.
    """
    p = pathlib.Path(path)
    if not p.exists():
        return {}
    return {e["repo"]: e for e in tomllib.loads(p.read_text()).get("entry", [])
            if e.get("verdict") == "confirmed"}


def board_declines(path):
    """Board-sets a person has ruled are not a design, keyed by any one of their paths.

    `seed/declines.toml` rules out a firmware RECORD before it gets a sources.toml row;
    Half B mints from board-SETS, which have no record to hang that ruling on. This is
    the same idea at the grain Half B actually works in -- see seed/board-declines.toml.

    CONFIRMED ROWS ONLY, the same rule every other seed file takes.
    """
    p = pathlib.Path(path)
    if not p.exists():
        return set()
    return {(e["repo"], e["path"]) for e in tomllib.loads(p.read_text()).get("entry", [])
            if e.get("verdict") == "confirmed"}


def licence_slots(repo, scanned, rulings):
    """The three licence slots for one repo. `(licensing, None)` or `(None, reason)`.

    Both halves of the build ask this same question, and until this function existed
    they answered it with two copies of the same twenty lines. The copies had already
    drifted: Half A deferred with "several licences at the repo root, needs a ruling"
    and Half B with the same words minus the half that says what to do about it.

    A HUMAN RULING WINS OUTRIGHT and is never merged with the scan. The scan can see
    that `foostan/crkbd` holds CC-BY-4.0 and MIT; only a person can say which one covers
    the hardware, and a ruling the scan then "corrected" would not be a ruling. This is
    also the only path that can return three different values -- everything below fills
    all three slots from one file, because one file is all those repos state.

    Below the ruling nothing has changed, and the ORDER still carries the argument: the
    same-licence reduction runs before the root-file rule, because `joric/jorne`'s four
    `LICENSE` files are one licence in four folders and must never reach the root
    question at all.
    """
    ruled = rulings.get(repo)
    if ruled:
        return {slot: list(ruled[slot]) for slot in LICENCE_SLOTS}, None
    if not scanned or scanned["status"] != "ok":
        return None, "repo not scanned for licences"
    own = [f for f in scanned["files"] if not f["vendored"]]
    # SEVERAL LICENCE FILES IS USUALLY NOT SEVERAL LICENCES. Two reductions before
    # anything defers, and the first is a defect rather than a rule: `joric/jorne`
    # ships four `LICENSE` files, one per design folder, and all four are MIT.
    # Counting files instead of licences made that a conflict.
    if len({f["licence"] for f in own}) == 1:
        own = own[:1]
    elif len(own) > 1:
        # THE ROOT LICENCE WINS -- the owner's ruling, 2026-08-28. A LICENSE at the
        # repo root is the repo's statement about itself; one under
        # `automation/tools/PcbDraw/` is a vendored tool's, and `pierrechevalier83/ferris`
        # ships five of those against one Apache-2.0 at its root.
        #
        # Ruled with its counterexample in hand, so it is a decision and not an
        # oversight: `marvfpv/friedrich` is GPL-3.0 at the root and CC-BY-NC-SA-4.0
        # under `PCB/`, and it is the PCB one that describes the hardware. That repo
        # now publishes as GPL-3.0.
        root = [f for f in own if "/" not in f["path"]]
        if len(root) != 1:
            return None, "several licences at the repo root, needs a ruling"
        own = root
    if own and own[0]["licence"] in ("unclassified", "unreadable"):
        return None, "licence not recognised"
    stated = [own[0]["licence"]] if own else []
    return {slot: list(stated) for slot in LICENCE_SLOTS}, None



def main():
    ap = argparse.ArgumentParser()
    # Written into the app's resources, not into seed/: `seed/` holds what humans
    # curate, and this file is a build output that the site loads at startup.
    ap.add_argument("--out", default="src/main/resources/seed/designs.json")
    ap.add_argument("--qmk", default=os.path.expanduser("~/code/qmk_firmware"))
    ap.add_argument("--zmk", default=os.path.expanduser("~/code/zmk"))
    ap.add_argument("--verbose", action="store_true")
    # OFF UNTIL THE DOMAIN HAS THE FIELD. Backlog #64 splits in two: the intake half is
    # this script's, the `FirmwareRef.url` half is the owner's, and `SeedDesignCatalog`
    # parses `designs.json` strictly -- an unknown field breaks HIS build, not this
    # script's. So the address is computed and withheld: everything below can be
    # finished, tested and committed without ever writing it, and flipping this on is
    # the last step and a joint one.
    ap.add_argument("--firmware-url", action="store_true",
                    help="emit FirmwareRef.url -- ONLY once the domain has the field")
    # EVERY DEFERRAL, NAMED. The summary counts them and the counts are what get
    # quoted, but no measurement can start from a count: `157 half B: no firmware
    # record fits` says nothing about WHICH 157 boards, and the answer was being
    # re-derived by hand from board-scan.json every time anyone asked. Written only
    # when asked for, so the ordinary build is unchanged.
    ap.add_argument("--defer-json", metavar="PATH",
                    help="write every unbuilt row and board-set, with its reason")
    args = ap.parse_args()

    # THE TREE REGISTRY, backlog #64. `tree` is a key into `seed/trees.toml`, not one
    # of two literal strings, so a record hosted in someone's unmerged fork is read by
    # the same reader from a different root. `--qmk` and `--zmk` still override the two
    # upstream entries' `local`, so every existing command line means what it did.
    reg = tree_registry.load(qmk=args.qmk, zmk=args.zmk)
    rows = [r for r in parse(str(REPO/"seed/sources.toml"))
            if r.get("verdict") == "confirmed"]
    # A ROW NAMING NO REGISTERED TREE IS A CURATION ERROR, and it is caught here rather
    # than at each of the thirty-odd places that ask a row which reader it wants. It
    # defers like any other unusable row -- the defer report is where a curator looks --
    # instead of taking the whole build down over one typo.
    unregistered = [r for r in rows if r.get("tree") not in reg]
    rows = [r for r in rows if r.get("tree") in reg]
    # A `hardware` row names the row whose firmware it runs, as `tree:id`. Resolved
    # here so the reference is checked once rather than at every use.
    by_key = {(r["tree"], r["id"]): r for r in rows}

    scan = json.loads((REPO/"data/board-scan.json").read_text())
    boards = collections.defaultdict(list)
    for key, value in scan.items():
        boards[key.split("\t")[0]].append(value)

    licences = json.loads((REPO/"data/licence-scan.json").read_text())
    # The human's answer where the scan cannot give one. Curated, so it lives in `seed/`
    # beside the other four ruling files rather than in `data/` with the scans.
    rulings = licence_rulings(REPO/"seed/licences.toml")
    # Board-sets ruled not a design of their own -- Half B's counterpart to
    # seed/declines.toml, at the grain Half B actually mints from.
    declined_boards = board_declines(REPO/"seed/board-declines.toml")
    # Key pitch, measured off each board's own switch placements by
    # scripts/measure_key_pitch.py. Absent for a board with too few keys to average --
    # a two-key macropad has no spacing to speak of -- and absent stays absent.
    # THE PARTS EACH BOARD'S OWN BOM NAMES, where the repo ships one -- 67 boards over
    # 53 repos, scanned by `scripts/scan_boms.py`. Optional like the pitch scan: a
    # missing file means no design gains a chip from a BOM, and nothing else changes.
    bom_file = REPO/"data/bom-scan.json"
    boms = json.loads(bom_file.read_text()) if bom_file.exists() else {}
    pitch_file = REPO/"data/pitch-scan.json"
    pitches = json.loads(pitch_file.read_text()) if pitch_file.exists() else {}
    # THE HARDEST COMPONENT ON EACH BOARD, from scripts/measure_build_difficulty.py.
    # Deliberately NOT joined into `buildMethod.soldering` above -- backlog #18 is
    # replacing that field's whole shape (a design-level 3-value enum) with this
    # component-level one, and states outright that `soldering` is populated for
    # "zero designs, so there is no migration cost, now or ever": writing the OLD
    # shape now would manufacture the exact migration #18 exists to avoid. This
    # writes the SCAN's own answer under its own name instead, a sibling fact next
    # to `buildMethod` rather than inside it, so nothing here prejudges the enum
    # #18 has not been given yet. Nothing reads it yet either -- landing the data
    # is `#18`'s own "Agent, lands first" half of the split; the domain field, the
    # enum, and the row line stay `who:owner`.
    difficulty_file = REPO/"data/build-difficulty-scan.json"
    difficulty = ({row["slug"]: row
                   for row in json.loads(difficulty_file.read_text()) if row.get("hardest")}
                  if difficulty_file.exists() else {})

    records = firmware_records.FirmwareRecords(reg)
    shield_of = records.shield

    def built_for(rows):
        """`built_for()` of each confirmed row, for `soldered_chip`."""
        return [records.of(r["tree"], r["id"]).built_for() for r in rows if r]

    def ref_record(ref):
        """The record a published firmware ref names."""
        return records.of(ref["_tree"], ref["path"] or ref["shield"])

    rows_per_repo = collections.Counter(r["source"] for r in rows if r.get("source"))
    # How many DESIGNS the repo holds, not how many board files: draft_boards groups a
    # design's several boards (`top-pcb` and `thumb-pcb`) and sets aside plates,
    # artefacts and retired folders. A repo holding one design needs no attribution.
    board_sets, board_names, _, _ = load_boards(str(REPO/"data/board-scan.json"))
    designs_per_repo = {r: len(sets) for r, sets in board_sets.items()}

    # A FORK STILL HOLDS ITS PARENT'S BOARD FILES, and Half B was minting designs from
    # them. Forgejo #30: six designs -- `corne-cherry`, `corne-chocolate`, `corne-classic`,
    # `corne-light`, `corne-cherry-v3`, `wl-corne-cherry` -- published from
    # `joric/jorne`, which GitHub reports as `fork: true, parent: foostan/crkbd` and
    # which describes itself as "an extended Corne keyboard". Not one of its nine board
    # files is named `jorne`; every one is a Corne.
    #
    # Two things were wrong with that and the second is the serious one:
    #
    #   * The CREDIT went to the fork. `sources` said joric/jorne for six keyboards
    #     foostan designed.
    #   * The LICENCE was the fork's. `foostan/crkbd` carries CC-BY-4.0 and MIT side by
    #     side, which is why the licence rule defers it -- picking one would invent the
    #     answer. The fork carries MIT alone, so the corpus asserted **MIT for foostan's
    #     hardware** on the strength of someone else's copy. The original is withheld for
    #     being ambiguous while a fork of it publishes six times, unambiguously, wrongly.
    #
    # THE TEST IS THE NAME, NOT THE PATH, and it has to be: joric restructured, so
    # `corne-cherry/pcb/corne-cherry.kicad_pcb` and upstream's
    # `pcbs/corne-cherry/hotswap/corne-cherry.kicad_pcb` share no path. Tokens do the
    # work `NOISE` already strips `pcb`/`board`, so `juliet-pcb` against its parent's
    # `romeo-pcb` yields `juliet` against `romeo` and does NOT collide -- which is the
    # case that proves this is not merely "forks cannot publish". `na-cly/juliet` is a
    # fork of `coseyfannitutti/romeo`, mints from its OWN `juliet-pcb.kicad_pcb`, and
    # keeps publishing.
    #
    # Measured over all 10 forks with a stated parent: this refuses the six Cornes and
    # nothing else.
    #
    # THIS CONTRADICTS A COMMENT IN THE MERGE STEP BELOW, deliberately and only in part.
    # That comment says `foostan/crkbd`, `joric/jorne` and `idank/keyboards` "each ship a
    # `corne-cherry`, and they are three designs". Its operational point -- merge on the
    # board, never on the name -- stands and is untouched. Its parenthetical is wrong
    # about the world: a fork's copy of a Corne is not a third Corne, it is the same
    # keyboard checked out twice.
    ancestry_file = REPO/"data/source-ancestry.json"
    ancestry = json.loads(ancestry_file.read_text()) if ancestry_file.exists() else {}
    confirmed_repos = {r["source"] for r in rows if r.get("source")}

    def switch_total(repo, bset):
        """Raw switch count of a board-set, summed over its own files.

        Not doubled here for a one-hand set -- the caller compares both a set's raw
        total and its double against the other side, so which side (if either) is a
        single hand does not have to be known in advance.
        """
        return sum((scan.get(f"{repo}\t{p}") or {}).get("switches", 0) for p in bset)

    def is_ancestors_board(repo, bset):
        """Is this board-set actually the parent's own design, not merely its cousin?

        Only asked of a fork whose PARENT IS ITSELF A CONFIRMED SOURCE. Where the parent
        is outside the corpus there is nothing to compare against and no better home to
        send the design to, so the fork keeps it -- `floookay/adelheid` and
        `tweetydabird/lotus58` both publish for that reason.

        NAME OVERLAP ALONE IS NOT ENOUGH, and `joric/jorne` is the measurement that
        found it. Its own README calls it "an extended Corne keyboard" -- a genuine
        derivative shares its base's vocabulary by construction, so `corne-light`
        (42 keys, a plain, unmodified Corne) and `corne-cherry`'s 2.1 revision (22
        switches on ONE HAND, 44 keys doubled -- Jorne's own declared count, one
        column more than Corne's 42) both carry the token `corne` against parent
        `foostan/crkbd`'s own `corne-cherry`/`corne-chocolate` sets, and the OLD rule
        refused both alike (Forgejo #30, 2026-08-29's `bb3edf7`). Only the first is
        actually the parent's board checked out twice.

        So a name match is now just the CANDIDATE, and the SIZE has to agree too --
        checked both raw and doubled on each side, since `board-scan.json` counts
        whatever a file holds and a one-hand file is exactly half a keyboard.
        Measured 2026-09-10: this keeps refusing `corne-chocolate` (21/hand=42 against
        the parent's 42) and `corne-light` (42 against 42), and stops refusing
        `corne-cherry` 2.1 and 3.0 and `corne-classic` (all 44, matching nothing the
        parent has) -- the six from #30 split three and three, not six and zero.
        """
        info = ancestry.get(repo) or {}
        parent = info.get("parent")
        if not info.get("fork") or not parent or parent not in confirmed_repos:
            return False
        mine = set(id_tokens(board_names[(repo, tuple(bset))]))
        if not mine:
            return False
        mine_n = switch_total(repo, bset)
        mine_sizes = {mine_n, mine_n * 2} - {0}
        if not mine_sizes:
            return False
        for other in board_sets.get(parent, []):
            if not (mine & set(id_tokens(board_names[(parent, tuple(other))]))):
                continue
            other_n = switch_total(parent, other)
            if mine_sizes & {other_n, other_n * 2}:
                return True
        return False

    # Which board file is this row's, and what the design it holds is called -- from
    # seed/boards.toml. Drafted by scripts/draft_boards.py and confirmed by a human; a
    # `drafted` attribution is deliberately NOT read, so an unconfirmed guess can never
    # reach the site.
    no_board = set()
    bfile = REPO/"seed/boards.toml"
    attributions = read_attributions(
        tomllib.loads(bfile.read_text())["entry"] if bfile.exists() else [])
    if bfile.exists():
        for e in tomllib.loads(bfile.read_text())["entry"]:
            if e["verdict"] == "no_board":
                no_board.add((e["tree"], e["id"]))

    # HALF B: a board no firmware record was attributed to still becomes a design.
    # Ticket 19 decision 1 -- "the unit is the board, not the firmware entry" -- and the
    # owner's ruling 2026-08-29 that one firmware may cover several designs: "like Cantor
    # and Cantor Remix ... some guy forked Cantor and created a 36 key version".
    #
    # THE FIRMWARE STILL HAS TO FIT THE BOARD. Attaching a repo's record to every board in
    # it is the wholesale import this file already refuses, wearing a different hat:
    # ai03-2725/orbit declares 68 keys while its three boards place 60, 49 and 48. So a
    # record joins a board only when the board's own switch count matches a layout the
    # record declares, and the controller kinds do not contradict. Measured over the
    # corpus: 162 of 483 board/record pairs agree, 317 do not -- so the check is doing
    # most of the work, not rubber-stamping.
    # Claimed by PATH, not by the exact tuple a row lists. A human may attribute two
    # files the grouper treats as two sets -- `Cantor_Classic`'s square and rounded
    # outlines are one design to a person and two board-sets to the grouping rule -- and
    # matching tuples let Half B re-emit them, so `Cantor` and `cantor-classic` both
    # published from the same PCB.
    # THE BOARD'S OWN SWITCH COUNT, READ A SECOND WAY. `extract_switch_footprints`
    # counts FOOTPRINTS, which double-counts a board that offers a choice at one
    # position -- `chouchou` carries MX and Choc on every key and reads as 40 switches on
    # a 20-key keyboard. Measured over the 232 published designs both instruments can
    # see, 83 disagree. Neither is right alone: the position reader returns nothing for
    # `koca58`, so this is a SECOND reading and not a replacement, and the join below
    # accepts a record agreeing with either. See `scripts/scan_switch_positions.py`.
    posfile = REPO/"data/switch-positions.json"
    positions = json.loads(posfile.read_text()) if posfile.exists() else {}

    # LOUD IF A CLAIMED PATH NAMES A DEAD ROW. Backlog #76: a `boards.toml` entry keyed
    # to a `(tree, id)` sources.toml never confirmed -- a hand-typed row, or an id that
    # moved -- used to remove the board from Half B with no deferral at all, because
    # this set was built from EVERY confirmed boards.toml entry with nothing checking
    # its id is a live record. `by_key` is exactly that check and already exists by this
    # point in the build. Zero rows fail it today, so this costs nothing and turns the
    # next one into a build failure instead of a design that silently stops existing.
    claimed_paths = set()
    for e in (tomllib.loads(bfile.read_text())["entry"] if bfile.exists() else []):
        if e["verdict"] != "confirmed":
            continue
        if (e["tree"], e["id"]) not in by_key:
            raise SystemExit(
                f"seed/boards.toml confirms {e['tree']}:{e['id']}, which names no "
                "confirmed row in seed/sources.toml -- backlog #76. Fix the id, or the "
                "board silently drops out of Half B with no trace."
            )
        for path in e["boards"]:
            claimed_paths.add((e["source"], path))
    # A record ALREADY ATTRIBUTED to a board is not free to describe another one.
    # `davidphilipbarr/macropads` is why: `handwired/macro3` declares 8 keys and was
    # matched to macro3's own board by name, while macro2 happens to PLACE 8 switches --
    # so a bare count match handed macro3's record to macro2 as well, and the two
    # published identical records. A count match is evidence; a name match that a human
    # confirmed is stronger, and it is a positive statement about which board this is.
    #
    # Corne is unaffected and is the reason this is not stricter: none of its seven
    # records is attributed to either board, so all four rev4 records stay available to
    # both -- which is the owner's ruling that one firmware may cover several designs.
    rows_for_repo = collections.defaultdict(list)
    for row in rows:
        if row.get("source"):
            rows_for_repo[row["source"]].append(row)

    # A PERSON'S RULING ON ONE-PIECE-OR-TWO, loaded before either half runs. It is
    # applied to `body` further down, after the halves and before the merge, and that
    # stays where it is -- but Half B's join needs it EARLIER, as the witness that lets
    # a hand's count be doubled. Same question, two uses.
    ruled_bodies = body_rulings(REPO/"seed/bodies.toml")
    ruled_reversible = reversible_rulings(REPO/"seed/bodies.toml")
    ruled_wiring = wiring_rulings(REPO/"seed/wiring.toml")

    # EVERY CONFIRMED ROW THAT SHARES ONE BOARD SET, so a per-record fact can be asked
    # of all of them at once. `mcu` is the fact that needs it: `fatenozomi/arisu-pcb` is
    # one PCB submitted to QMK twice, and the two submissions contradict each other --
    # `arisu` declares `processor: atmega32u4`, `nightly_boards/ph_arisu` declares
    # `development_board: promicro`. Asked one row at a time, Half A built two designs
    # differing only in `mcu`, and the merge below rightly refused to pick between them,
    # so the design vanished. Asked together, the board file breaks the tie: it places an
    # MCU package, so the chip is stated by one record and merely unstated by the other,
    # which is not a contradiction.
    rows_for_board = collections.defaultdict(list)
    for row in rows:
        for paths, _ in attributions.get((row["tree"], row["id"]), []):
            rows_for_board[(row.get("source"), tuple(sorted(paths)))].append(row)

    designs, deferred = [], collections.Counter()
    examples = collections.defaultdict(list)
    unbuilt = []

    def defer(reason, row, **facts):
        """A confirmed row Half A could not publish.

        `facts` carries the board paths where the deferral is ABOUT the board rather
        than about the row -- "nothing names a controller" is a statement about a file,
        and a reader who cannot see which file has to re-derive the attribution before
        they can check it.
        """
        deferred[reason] += 1
        unbuilt.append({"half": "A", "reason": reason, "id": row["id"],
                        "tree": row.get("tree"), "repo": row.get("source"), **facts})
        if len(examples[reason]) < 3:
            examples[reason].append(row["id"])

    def deferB(reason, repo, bset, **facts):
        """A board-set Half B could not publish, with what was measured off it.

        Half A defers a ROW and names it by its firmware id; Half B defers a BOARD,
        which has no id of its own -- so the repo and its file paths are the identity,
        and `facts` carries the numbers the reason turns on. Without them a reader has
        to re-run the instruments to learn why 48 was not 42.
        """
        deferred[f"half B: {reason}"] += 1
        unbuilt.append({"half": "B", "reason": reason, "repo": repo,
                        "boards": sorted(bset), **facts})
        if len(examples[f"half B: {reason}"]) < 3:
            examples[f"half B: {reason}"].append(f"{repo}:{sorted(bset)[0]}")

    for row in unregistered:
        defer(f"tree {row.get('tree')!r} is not in seed/trees.toml", row)

    # ONE UNIT PER (RECORD, BOARD ROW), not per record: a record seed/boards.toml puts on
    # two boards is two designs. A record with no row is one unit with no boards, as before.
    units = [(row, paths, name)
             for row in rows
             for paths, name in (attributions.get((row["tree"], row["id"])) or [(None, "")])]
    for row, mine, ruled_name in units:
        repo = row.get("source")
        if not repo:
            defer("no repo known", row); continue
        key = (row["tree"], row["id"])
        if key in no_board:
            defer("no board in the repo (measured)", row); continue
        # A board-decline holds whichever half would mint the board: `rpneko65`'s board
        # is attributed to its own QMK record, so Half B's check below never sees it.
        if mine and any((repo, path) in declined_boards for path in mine):
            defer("a person ruled this board-set is not a design of its own", row,
                  boards=sorted(mine)); continue
        # AN UNATTRIBUTED ROW MAY ONLY IMPORT A REPO THAT HOLDS ONE DESIGN. Two ways to
        # fail that, and the second was invisible until names came from boards:
        # several rows sharing a repo, and ONE row over a repo holding several designs.
        # `ai03-2725/orbit` ships `Orbit-Choc-PCB` and `Orbit-PCB` against a single QMK
        # record, and unioning them states that one keyboard takes both Choc and MX
        # switches. Measured over the built corpus: 30 records imported wholesale this
        # way and 18 of them union boards that disagree. Deferring costs coverage;
        # keeping them costs correctness, and this project pays the first.
        if not mine:
            if rows_per_repo[repo] > 1:
                defer("shared repo (attribution not yet done)", row); continue
            if designs_per_repo.get(repo, 0) > 1:
                defer("several designs in the repo (attribution not yet done)", row); continue

        # -- licensing -------------------------------------------------------
        licensing, why = licence_slots(repo, licences.get(repo), rulings)
        if why:
            defer(why, row); continue

        # -- hardware, from the board scan ------------------------------------
        found = boards.get(repo)
        # An attribution narrows the facts to THIS row's board rather than every board
        # in the repo. It matters beyond shared repos: a single-row repo can hold 22
        # board files (tweetydabird/lotus58), and reading all of them unions the switch
        # families of twelve superseded versions into one design's record.
        if mine:
            found = [e for e in (found or []) if e.get("path") in mine]
        if not found:
            defer("no readable board or plate", row); continue
        families, has_board, soldered = set(), False, False
        footprints = sockets_on_board(found)
        for entry in found:
            families |= set(entry.get("families") or [])
            if entry.get("kind") == "board":
                has_board = True
            for name in (entry.get("names") or {}):
                if MCU_PACKAGE.search(name) or MCU_NAMED.search(name):
                    soldered = True
        if not families:
            defer("no switch footprint on any board", row,
                  boards=sorted(mine or [])); continue

        # THE PITCH IS THE BOARD'S, so it is read from the attributed paths and not from
        # the repo. A repo holding a Choc-spaced design and an MX-spaced one -- which
        # `diepala/cantor` is exactly -- would otherwise hand both the same answer.
        #
        # Where a design owns several boards they are its revisions, so they should agree;
        # where they do not, the fact is genuinely in dispute and stays absent rather than
        # being decided by whichever path sorted first.
        seen_pitch = {(pitches[f"{repo}\t{p}"]["pitch"], pitches[f"{repo}\t{p}"]["mm"])
                      for p in (mine or []) if f"{repo}\t{p}" in pitches}
        # The MILLIMETRES travel with the label, not just the label. Without them an
        # `other` board -- 19.50mm, 17.50mm, both measured at high confidence -- arrives
        # as "unusual, size unknown", which is a worse record than the one we have.
        pitch = ({"kind": seen_pitch.copy().pop()[0], "mm": seen_pitch.copy().pop()[1]}
                 if len(seen_pitch) == 1 else None)

        # -- layouts, split, controller ---------------------------------------
        # The row whose firmware this design runs. For qmk and zmk that is the row
        # itself; a `hardware` row borrows another's -- Forgejo #22.
        fw = row
        reader = reg.reader(row["tree"])
        if reader == "hardware":
            ref = tuple((row.get("firmware") or ":").split(":", 1))
            fw = by_key.get(ref)
            if not fw or fw["tree"] not in reg:
                defer("hardware row: firmware reference names no confirmed row", row); continue

        if reader == "hardware":
            # MANDATORY HERE, optional everywhere else. A `hardware` row borrows a
            # record and inherits no layout of its own, so an unstated count is not a
            # design; on any other row the same statement OVERRULES the record it has.
            # See [stated_keys] for the assertion and its falsifier.
            claimed, why = stated_keys(row, found)
            if claimed is None:
                defer(f"hardware row: {why or 'no stated keys'}", row); continue
            counts = [claimed]
            # Splitness comes from the borrowed record: the firmware knows whether it
            # drives one half or two, and a hardware fork does not change that.
            kb, why = records.of(fw["tree"], fw["id"]).keyboard()
            if kb is None:
                defer(f"hardware row: {why}", row); continue
            split = kb.split
            halves = 2 if split else None
        else:
            kb, why = records.of(row["tree"], row["id"]).keyboard()
            if kb is None:
                defer(why, row); continue
            counts, split, halves = kb.key_counts, kb.split, kb.halves
            if reader == "qmk":
                counts = without_encoder_positions(counts, found, split)
            # A SHIELD'S `requires` OUTRANKS A SOLDERED-LOOKING BOARD; QMK's
            # `development_board` below does not. Measured 2026-11-16: giving ZMK QMK's
            # `not soldered` guard flips `romac` from its Pro Micro socket to a soldered
            # atmega32u4 and drops its shield, and the RoMac is a Pro Micro macropad.
            socket = records.of(row["tree"], row["id"]).built_for().socket
            if reader == "zmk" and socket and not footprints:
                footprints = {socket}
        # THE HUMAN OUTRANKS THE RECORD, backlog #65 and the owner's ruling on the
        # Cheapino. Applied AFTER the record has been read, so the board-falsifier in
        # [stated_keys] has the same `found` to check against that the record was
        # measured against, and a row that states nothing is untouched.
        if reader != "hardware":
            claimed, why = stated_keys(row, found)
            if why:
                defer(why, row); continue
            if claimed is not None:
                counts = [claimed]
        if not counts:
            defer("no key count", row); continue

        # QMK'S `development_board`, WHERE THE BOARD FILE NAMED NO SOCKET. Ticket 27
        # decision 3, the owner's ruling, and it closes an asymmetry rather than opening
        # a door: ZMK's `requires` is the same signal on the other tree and was already
        # being read twenty lines above. QMK's side was simply never wired in.
        #
        # LAST, AND IN BOTH DIRECTIONS. The board file is the design's own drawing and
        # wins where both speak -- ticket 27's precedence -- so this is consulted only
        # where the board named NEITHER a socket nor an MCU package. Not consulting it
        # against a positive soldered signal is the half that had to be measured, and
        # it flipped seven designs the wrong way before the `not soldered` guard:
        #
        #   corne cherry, corne chocolate   `crkbd/rev1` declares `promicro`, and it is
        #                                   true of rev1. The PCB in the repo TODAY places
        #                                   NO socket footprint at all: a `QFN-56` beside
        #                                   its own crystal, a SOIC-8 flash, a SOT-223
        #                                   regulator and USB-C -- an integrated board.
        #                                   Corroborated from the other side rather than
        #                                   read off the package: `crkbd/rev4_0` and
        #                                   `rev4_1` both declare `processor: rp2040`,
        #                                   and `r2g` declares `atmega32u4`. So the repo
        #                                   holds a generation `rev1` does not describe.
        #   five Ferris boards              `ferris/0_1` and `0_2` declare nothing; the
        #                                   `promicro` came from `ferris/sweep`, which
        #                                   Half B included because it fits the key count.
        #                                   The 0.1 boards place `TQFP-44` and the 0.2s a
        #                                   `Dual_MCU_QFP_QFN_48`. Soldered, and the Sweep
        #                                   is a different keyboard that takes a nice!nano.
        #
        # So ticket 27 finding 5 -- "QMK's metadata is wrong 5 times in 15, always in the
        # expensive direction" -- has a mirror image: it is also wrong in the CHEAP
        # direction, claiming a socket for a board that solders its MCU, and a repo whose
        # PCB has moved on is how. The board file is the only thing that can be current.
        #
        # A value outside the domain's six leaves `footprint` None and the row defers.
        # That is the standing preference, not an oversight: `arduino_uno` is a real
        # socketed board with no member to be, and forcing it to the nearest one would
        # be the default-fill this project refuses.
        if not footprints and not soldered and fw and reg.reader(fw["tree"]) == "qmk":
            footprints = {records.of(fw["tree"], fw["id"]).built_for().socket} - {None}

        if socket_controller(footprints):
            controller = socket_controller(footprints)
        elif soldered:
            # NOT inferred from the package -- ticket 27 measured that wrong at least 31
            # times -- but named where a source names it. See [soldered_chip].
            siblings = rows_for_board.get((repo, tuple(sorted(mine or [])))) or []
            controller = {"kind": "soldered",
                          "mcu": soldered_chip(found, built_for([fw] + siblings),
                                               repo, boms)}
        else:
            defer("nothing names a controller", row, boards=sorted(mine or []),
                  # EVERY footprint on the board, not a filtered guess: the point of
                  # the dump is to let a person see what the board DOES name when the
                  # lexicon found nothing it recognised.
                  names=sorted({n for e in found for n in (e.get("names") or {})})
                  ); continue

        # THE NAME COMES FROM THE BOARD, NOT THE FIRMWARE -- ticket 44's rule, and the
        # reason `qmk:cradio` and `zmk:cradio` stop colliding: one is a board in
        # `davidphilipbarr/36keys`, the other a board in `davidphilipbarr/sweep`, and
        # they were never one design. A row with no attribution keeps the firmware's
        # name, which is every single-board repo the drafter confirmed `sole`.
        title = ruled_name or row["name"]
        slug_value = slugify(title if ruled_name else row["id"])
        paths_value = sorted(mine) if mine else sorted(
            e["path"] for e in found if e.get("path"))
        designs.append({
            # WHICH BOARD FILES THIS DESIGN WAS READ FROM. Popped before the seed is
            # written, like `board_key` -- it is build-time joinery, not a fact about a
            # design. `measure_key_positions.py` needs it because designs.json records
            # no path, and re-deriving one would mean a second copy of the naming rule.
            "board_paths": paths_value,
            "board_repo": repo,
            "name": title,
            "slug": slug_value,
            "board_key": f"{repo}\t" + "\t".join(sorted(mine)) if mine else None,
            "sources": [f"https://github.com/{repo}"],
            # `fw`, not `row`: a hardware row publishes the firmware it borrows, so
            # `family` stays qmk/zmk and no consumer learns the word "hardware".
            "firmware": [dict(firmware_ref(reg, fw, args.firmware_url),
                              **({"_borrowed": True} if fw is not row else {}))],
            "layouts": [{"keys": n} for n in counts],
            "switches": [SWITCH[f] for f in sorted(families) if f in SWITCH],
            # `null` where nothing could be measured. It is the one fact here that is
            # neither stated by a file nor inherited from firmware -- it is derived from
            # where the switches physically sit -- so it is the likeliest to be missing.
            "pitch": pitch,
            "buildMethod": {"kind": "pcb" if has_board else "handwire"},
            # See the comment above `difficulty_file`: a sibling fact to `buildMethod` in
            # the wire format, not a value inside it -- `SeedDesignCatalog.toDomain` is
            # the one that nests it into `BuildMethod.Pcb` on the way into the domain.
            #
            # `parts` IS A LIST, AND KEEPING IT ONE IS NOT OPTIONAL: 98 of 323 measured
            # boards have MORE THAN ONE COMPONENT TIED FOR HARDEST (`3x5-3-dilemma`'s
            # LED, MCU, passives and USB-C receptacle are all `smd_fine` together).
            # Recording only the first would silently manufacture a false single cause
            # on 30% of rows -- exactly the wrong-value-that-looks-right failure this
            # field exists to avoid, on the very field meant to replace a value that
            # was already too coarse.
            # Filled after the names.toml rename below, by the PUBLISHED slug.
            "hardestComponent": None,
            "controller": controller,
            # `reversible` unknown: measured unreadable for 103 of 106 splits. `link` is
            # ticket 52's connector reading -- see `split_link`.
            "body": ({"kind": "split", "halves": halves or 2, "reversible": None,
                      "link": split_link(slug_value, repo, paths_value, boards, controller)}
                     if split else {"kind": "unibody"}),
            "licensing": licensing,
        })

    # -- Half B: boards nothing was attributed to ------------------------------------
    # A BOARD HALF A PUBLISHED IS CLAIMED, whether or not a boards.toml row names it. A
    # single-board repo's row reads its board with no attribution at all, so the board
    # was never in `claimed_paths` -- and `PyrooL/Alpha` published twice from one PCB,
    # as `alpha` and `alpha-pro-micro`, the night its source row was confirmed.
    for d in designs:
        for path in d.get("board_paths") or []:
            claimed_paths.add((d["board_repo"], path))
    for repo, sets in board_sets.items():
        repo_rows = rows_for_repo.get(repo) or []
        if not repo_rows:
            continue
        for bset in sets:
            if any((repo, path) in claimed_paths for path in bset):
                continue
            if is_ancestors_board(repo, bset):
                deferB("a fork's copy of its parent's design", repo, bset,
                       parent=(ancestry.get(repo) or {}).get("parent"))
                continue
            if any((repo, path) in declined_boards for path in bset):
                deferB("a person ruled this board-set is not a design of its own",
                       repo, bset)
                continue
            name = board_names[(repo, tuple(bset))]
            # The ruling that decides whether this board may be read as one hand. Looked
            # up by the slug the design will publish under, which Half B knows here.
            ruled = ruled_bodies.get(slugify(name))

            licensing, why = licence_slots(repo, licences.get(repo), rulings)
            if why:
                deferB(why, repo, bset, name=name); continue

            found = [e for e in (boards.get(repo) or []) if e.get("path") in bset]
            families, has_board, soldered = set(), False, False
            footprints = sockets_on_board(found)
            placed = 0
            for entry in found:
                families |= set(entry.get("families") or [])
                placed += entry.get("switches") or 0
                if entry.get("kind") == "board":
                    has_board = True
                for fname in (entry.get("names") or {}):
                    if MCU_PACKAGE.search(fname) or MCU_NAMED.search(fname):
                        soldered = True
            seen = sum(positions.get(f"{repo}\t{p}", 0) for p in bset)
            if not families:
                deferB("no switch footprint on the board", repo, bset, name=name,
                       placed=placed, seen=seen); continue
            if not placed:
                deferB("no switch count to check the firmware against", repo, bset,
                       name=name, seen=seen); continue

            # THE JOIN. A record describes this board only if the board's own switch count
            # is one of the layouts it declares -- or half of one, since a split board file
            # is one hand. Everything else about the design comes from the board itself.
            fits = []
            for row in repo_rows:
                # DOES THE RECORD'S NAME FIT THIS BOARD? Three outcomes, and the middle
                # one is the whole rule:
                #
                #   every token present  a family member. `cradio` scores 3.0 on
                #                        `Cradio BLE`, `Cradio 10` and the rest, which is
                #                        one firmware over several designs -- the owner's
                #                        ruling, and it is what the corpus looks like.
                #   some tokens missing  a CONFLICT. `handwired/macro3` scores 2.5 on
                #                        `macro2` because the `3` is absent, and macro2
                #                        happens to place 8 switches, which is the count
                #                        that record declares. Coincidence, not evidence.
                #   nothing at all       no discrimination possible, so the record is free
                #                        to describe any board. `crkbd/rev4_1/standard`
                #                        scores None on both Corne boards: QMK slices by
                #                        revision, the repo by switch type, and neither
                #                        name reaches the other.
                if names_another_board(row["id"], bset, board_sets[repo]):
                    continue
                kb, _ = records.of(row["tree"], row["id"]).keyboard()
                if kb is None:
                    continue
                counts, split = kb.key_counts, kb.split
                # EITHER READING, and the asymmetry is deliberate: this can only ever
                # admit a board, never withhold one, and an admission still has to match
                # a layout the record itself declares -- so nothing is invented, only
                # read a second way. Measured on the 50 withheld boards a record could
                # describe: 15 reconcile, and `Decent1200` at 69 positions against a
                # 19-key record correctly still does not.
                #
                # DOUBLING NEEDS A WITNESS, and the record is it. "A split board file is
                # one hand" is only true of a SPLIT, and applied to anything else it
                # invents half a keyboard: `joe-scotto/scottokeebs/ScottoPad` places 18
                # switches and published at **36 keys**, matching `scotto36` on 18x2,
                # while its own `body` read `unibody`. An 18-key macropad is not half a
                # 36-key keyboard.
                #
                # Measured over all 277 published designs: 44 matched ONLY by doubling
                # and 43 of them declare `split`. The one that does not is the ScottoPad.
                # So this withholds exactly the fabrication and costs the other 43
                # nothing -- which is the shape ticket 55 asked for when it said doubling
                # a hand's count needs a witness and `placed` alone cannot be one.
                #
                # THE HUMAN OUTRANKS THE RECORD as that witness, and `wired-pierce` is
                # why: its halves talk over an I/O expander, so QMK sees one matrix and
                # sets no split flag -- Forgejo #35's whole subject. Asking the record
                # alone withheld a design a person had already ruled a split in
                # `seed/bodies.toml`, quoting its author. A ruling of `unibody` refuses
                # the doubling for the same reason it would refuse the flag.
                if any(n in counts for n in (placed, seen) if n) or (
                        two_pieces(ruled, split) and
                        any(n * 2 in counts for n in (placed, seen) if n)):
                    fits.append((row, counts, split))
            fits = agreeing_with_board(fits, footprints, soldered, found, records, repo, boms)
            if not fits:
                # THE NUMBERS ARE THE FINDING, not the reason string. `declares` is every
                # count every record in the repo states, so a reader can see at a glance
                # whether this board is off by an alternate, off by a hand, or a keyboard
                # the corpus holds no firmware for at all -- ticket 55's whole question.
                declares = {}
                for other in repo_rows:
                    kb, _ = records.of(other["tree"], other["id"]).keyboard()
                    if kb is None:
                        continue
                    cs, sp = kb.key_counts, kb.split
                    declares[other["id"]] = {
                        "counts": sorted(cs), "split": sp,
                        "names_another_board": names_another_board(
                            other["id"], bset, board_sets[repo])}
                deferB("no firmware record fits this board's key count", repo, bset,
                       name=name, placed=placed, seen=seen, families=sorted(families),
                       declares=declares); continue

            # THE SAME GUARD, PER RECORD. A record can fit on one of its layouts and
            # declare a second that only matches when doubled; without `sp` here that
            # second layout would arrive on a unibody design through the back door.
            counts = sorted({n for _, cs, sp in fits for n in cs
                             for r in (placed, seen)
                             if r and (n == r or (two_pieces(ruled, sp) and n == r * 2))},
                            reverse=True)
            split = any(sp for _, _, sp in fits)
            # THE SAME SECOND SOURCE, asked of every record that fits rather than of one.
            # It has to agree with itself: `pill60` is three controller variants of one
            # PCB -- `blackpill_f401`, `blackpill_f411`, `bluepill` -- and `controller` is
            # a single value, so two form factors over one board is a fact in dispute and
            # the board keeps `Unknown` rather than taking whichever record sorted first.
            #
            # BOTH TREES, since 2026-11-16 (owner's ruling): a ZMK shield's `requires` is
            # the same signal as QMK's `development_board`, and Half A already read it.
            # Measured the day it changed: no design moved.
            if not footprints and not soldered:
                declared = {records.of(r["tree"], r["id"]).built_for().socket
                            for r, _, _ in fits}
                declared.discard(None)
                if len(declared) == 1:
                    footprints = {declared.pop()}
            if socket_controller(footprints):
                controller = socket_controller(footprints)
            elif soldered:
                controller = {"kind": "soldered",
                              "mcu": soldered_chip(found, built_for(r for r, _, _ in fits),
                                                   repo, boms)}
            else:
                deferB("nothing names a controller", repo, bset, name=name,
                       placed=placed, seen=seen,
                       names=sorted({n for e in found for n in (e.get("names") or {})})
                       ); continue

            key = f"{repo}\t" + "\t".join(sorted(bset))
            measured = {(pitches[f"{repo}\t{b}"]["pitch"], pitches[f"{repo}\t{b}"]["mm"])
                        for b in bset if f"{repo}\t{b}" in pitches}
            slug_value = slugify(name)
            paths_value = sorted(bset)
            designs.append({
                "board_paths": paths_value,
                "board_repo": repo,
                "name": name,
                "slug": slug_value,
                "board_key": key,
                "sources": [f"https://github.com/{repo}"],
                # EVERY record that fits, not one. The owner's ruling: one firmware may
                # cover several designs, and the reverse is just as true -- Corne's four
                # rev4 records all declare 42 keys and QMK never mentions cherry or
                # chocolate, so all four run on both boards.
                "firmware": [firmware_ref(reg, r, args.firmware_url)
                             for r, _, _ in fits],
                "layouts": [{"keys": n} for n in counts],
                "switches": [SWITCH[f] for f in sorted(families) if f in SWITCH],
                "buildMethod": {"kind": "pcb" if has_board else "handwire"},
                # This branch never wrote this key before -- an oversight found while
                # wiring the domain side of backlog #18, not a deliberate omission like
                # the one on `pitch` a few lines down. A fan-out design is exactly as
                # measurable as any other; there was no reason its soldering read as
                # `Unknown` when the scan already had an answer.
                # Filled after the names.toml rename below, by the PUBLISHED slug.
                "hardestComponent": None,
                "controller": controller,
                "body": ({"kind": "split", "halves": 2, "reversible": None,
                          "link": split_link(slug_value, repo, paths_value, boards, controller)}
                         if split else {"kind": "unibody"}),
                "licensing": licensing,
                "pitch": ({"kind": measured.copy().pop()[0], "mm": measured.copy().pop()[1]}
                          if len(measured) == 1 else None),
            })

    # Some designs carry BOTH a ZMK shield and a QMK record -- `a_dux`, `eternal_keypad`
    # -- and they are one design with two firmware entries, which is why `firmware` is a
    # list. Merge them rather than emit two pages.
    #
    # MERGED ON THE BOARD, NOT THE NAME. Two repos can call their design the same thing
    # and mean different keyboards: `foostan/crkbd`, `joric/jorne` and `idank/keyboards`
    # each ship a `corne-cherry`, and they are three designs. What makes two rows one
    # design is that they point at the same board files in the same repo.
    #
    # And merge only where the two trees AGREE. A ZMK shield and a QMK record
    # disagreeing about key count or switch family is a finding about the corpus, and
    # picking one silently is the default-fill this project refuses. Those defer.
    # -- One piece or two, where a person has ruled it. Forgejo #35 -------------------
    #
    # `body` comes from QMK's `split.enabled`, which means "this firmware drives two
    # halves over QMK's split transport" -- a WIRING question that we print as a SHAPE
    # answer. Halves joined by an I/O expander present to QMK as one matrix and set
    # nothing, so `torn` and `wired-pierce` published as unibody while their own authors
    # call them split, and `?body=unibody` returned them.
    #
    # APPLIED HERE, before the merge, and the position is the point: `body` is one of
    # the FACTS below, so a ruling arriving after the merge could not settle a QMK/ZMK
    # disagreement about it -- the design would already have deferred. Corrected first,
    # then merged, means the ruling is what the two trees are compared on.
    #
    # A CORRECTION, NOT A MODEL. `reversible` stays `Unknown` here because nothing in
    # this file can see it -- but the inter-half connector CAN be, `link` is read off
    # the same `board_repo`/`board_paths` the design already carries, and it needs
    # asking again here: a design corrected TO split by this loop (torn, wired-pierce)
    # was built as `unibody` above, which carries no `link` key at all.
    corrected = 0
    for d in designs:
        halves = ruled_bodies.get(d["slug"])
        if not halves or halves == _halves_of(d["body"]):
            continue
        d["body"] = ({"kind": "unibody"} if halves == 1 else
                     {"kind": "split", "halves": halves, "reversible": None,
                      "link": split_link(d["slug"], d.get("board_repo"),
                                          d.get("board_paths"), boards,
                                          d.get("controller"), one_chip=True)})
        corrected += 1
    if args.verbose and corrected:
        print(f"{corrected} designs took a human's ruling on one-piece-or-two "
              f"(seed/bodies.toml)")
    # A split's `reversible`, where a person has ruled it. After the correction above,
    # which rebuilds `body` with `reversible: None`.
    for d in designs:
        if d["slug"] in ruled_reversible and d["body"]["kind"] == "split":
            d["body"]["reversible"] = ruled_reversible[d["slug"]]

    # AFTER the body correction above, because a socketed one-piece board is read
    # differently from a socketed split -- see `host_port`.
    for d in designs:
        d["hostPort"] = host_port(d.get("board_repo"), d.get("board_paths"), boards,
                                  d.get("controller"), d["body"])

    FACTS = ("layouts", "switches", "buildMethod", "controller", "body", "licensing")
    merged, byBoard = [], collections.defaultdict(list)
    for d in designs:
        byBoard[d["board_key"] or f"slug:{d['slug']}"].append(d)
    for _, group in byBoard.items():
        if len(group) == 1:
            merged.append(group[0]); continue
        head = group[0]
        clash = [k for k in FACTS if any(g[k] != head[k] for g in group[1:])]
        if clash:
            for g in group:
                fam = "/".join(sorted(x["family"] for x in g["firmware"]))
                defer(f"the trees disagree on {', '.join(clash)} ({fam})",
                      {"id": g["slug"]})
            continue
        head = dict(head)
        for g in group[1:]:
            head["firmware"] += g["firmware"]
            head["sources"] += [s for s in g["sources"] if s not in head["sources"]]
        head["firmware"].sort(key=lambda x: x["family"])
        merged.append(head)
    designs = merged

    # The slug is the public URL and the namespace is flat (ticket 07 decision 8), so a
    # surviving collision is two different keyboards fighting over one page. Nothing
    # picks a winner: both defer, named, and a human renames one in seed/boards.toml.
    clash = {s for s, n in collections.Counter(d["slug"] for d in designs).items() if n > 1}
    if clash:
        for d in designs:
            if d["slug"] in clash:
                defer(f"two designs answer to /d/{d['slug']}, needs a rename", {"id": d["slug"]})
        designs = [d for d in designs if d["slug"] not in clash]

    # -- Firmware the board contradicts is dropped, in both halves (#106) -------------
    #
    # A KEY-COUNT MATCH IS NOT A PAIRING. Half B attaches every record whose layout fits a
    # board's switch count, and `agreeing_with_board` only narrows, never empties; a Half
    # A row a machine confirmed on a name token does no better. Measured 2026-10-02 over
    # the 133 published soldered designs: 6 carried firmware for another chip, all live.
    # `unicorn-f072` showed the Unicorne's STM32F411 on an F072 board; three RP2040
    # grooveboards and `cypher` ran ATmega32U4 records; `pancake-v2` a ZMK shield, which
    # by ZMK's definition sits on a separate controller board.
    #
    # THE BOARD IS THE WITNESS, read with no record (`soldered_chip(found, [])`), and a
    # record goes only on a contradiction `contradicts_board` can state. A board that
    # names no chip keeps its records: silence is Unknown, never a disagreement. The
    # design stays published with what remains, and `firmware: []` reads Unknown since
    # the owner took firmware out of the publication gate (7dcaab9).
    dropped = []
    for d in designs:
        if (d.get("controller") or {}).get("kind") != "soldered" or not d["firmware"]:
            continue
        repo, paths = d.get("board_repo"), set(d.get("board_paths") or [])
        found = [e for e in (boards.get(repo) or []) if e.get("path") in paths]
        chip = soldered_chip(found, [], repo, boms)
        keep = [r for r in d["firmware"]
                if not contradicts_board(ref_record(r).built_for(), chip)]
        if len(keep) == len(d["firmware"]):
            continue
        dropped.append((d["slug"], chip, sorted(r["path"] or r["shield"]
                                                for r in d["firmware"] if r not in keep)))
        d["firmware"] = keep
        d["controller"]["mcu"] = soldered_chip(
            found, [ref_record(r).built_for() for r in keep], repo, boms)
    print(f"{len(dropped)} soldered designs lose firmware their board contradicts:", file=sys.stderr)
    for slug, chip, ids in dropped:
        print(f"  {slug}: board {chip or 'names no chip'}, dropped {', '.join(ids)}", file=sys.stderr)

    # -- And a socketed board, by its socket (see `socket_contradicts`) ----------------
    socket_dropped = []
    for d in designs:
        c = d.get("controller") or {}
        if c.get("kind") != "socket" or not d["firmware"]:
            continue
        gone = [r for r in d["firmware"]
                if socket_contradicts(ref_record(r).built_for(), c.get("footprints"))]
        if gone:
            d["firmware"] = [r for r in d["firmware"] if r not in gone]
            socket_dropped.append((d["slug"], c.get("footprints"),
                                   sorted(r.get("path") or r.get("shield") for r in gone)))
    print(f"{len(socket_dropped)} socketed designs lose Pro Micro firmware their socket "
          "cannot take:", file=sys.stderr)
    for slug, fps, ids in socket_dropped:
        print(f"  {slug}: socket {', '.join(fps)}, dropped {', '.join(ids)}", file=sys.stderr)
    # -- What each QMK record is built for, from QMK ----------------------------------
    #
    # `Controller.Soldered` has always called QMK's `processor` "the honest second source"
    # and `soldered_chip` already reads it -- for a SOLDERED design, where the chip is on
    # the board and that is the end of it. This pass is the other half, and it answers a
    # different question: what is the FIRMWARE built for. For a socketed design that is
    # the only chip fact there is, since the board carries no MCU at all, and it is what
    # tells a builder holding an RP2040 Pro Micro that an `atmega32u4` record will not run
    # on it. Measured: inside the one `pro_micro` footprint, 38 designs target atmega32u4
    # and 5 target RP2040.
    #
    # A SHARED RECORD STATES NOTHING ABOUT ANY ONE DESIGN, and this is the whole reason
    # the pass runs here rather than inside `firmware_ref`: ownership is only knowable
    # once both halves have produced every design. 67 of 324 designs cite a QMK record
    # another design also cites, and `ianklug/grooveboard` is nine designs on one record
    # spanning both atmega and RP2040 hardware -- `soldered_chip` already documents that
    # case as the reason the board file outranks QMK. Attributing that record's chip to
    # all nine would be `sweeq`'s borrowed display again, so shared records are withheld.
    #
    # EXCEPT ON A SOLDERED BOARD THAT NAMES THE RECORD'S CHIP ITSELF (`shared_record_target`
    # says why that case is not in doubt). Measured 2026-10-03: of the 65 refs-bearing
    # designs this withheld from, 18 are soldered and publish the record's chip as their
    # MCU; the board's own BOM or drawing is the witness for those that gain a target
    # here, and a board that names no chip keeps the record withheld, because then its
    # MCU came from this record and agreeing with it proves nothing.
    owners = collections.defaultdict(set)
    for d in designs:
        for ref in d["firmware"]:
            if ref["family"] == "qmk" and ref.get("path"):
                owners[ref["path"]].add(d["slug"])
    targeted = shared = silent = soldered_shared = 0
    for d in designs:
        board_chip = None
        if (d.get("controller") or {}).get("kind") == "soldered":
            repo, paths = d.get("board_repo"), set(d.get("board_paths") or [])
            found = [e for e in (boards.get(repo) or []) if e.get("path") in paths]
            board_chip = soldered_chip(found, [], repo, boms)
        for ref in d["firmware"]:
            if ref["family"] != "qmk" or not ref.get("path"):
                continue
            chip = ref_record(ref).built_for().chip
            if len(owners[ref["path"]]) > 1:
                chip = shared_record_target(d.get("controller"), board_chip, chip)
                if not chip:
                    shared += 1
                    continue
                soldered_shared += 1
            if chip:
                ref["targetMcu"] = chip
                targeted += 1
            else:
                silent += 1
    print(f"{targeted} QMK records name the chip they are built for "
          f"({soldered_shared} of them shared, on a soldered board that names that chip; "
          f"{shared} withheld as shared between designs, {silent} name none)")

    # -- The real name, from seed/names.toml ------------------------------------------
    #
    # BEFORE THE SUMMARY AND BEFORE THE RENAME CHECK, because both are keyed by the
    # PUBLISHED slug and this is what decides it. A design's name is drafted from its own
    # board file path, which is right nearly always and wrong in a way no rule can fix:
    # `Sweep half-swept/half-swept.kicad_pcb` drafts to "half swept", while the author's
    # README is headed "Sweep Half Swept". Only a person reading the README can tell.
    #
    # `seed/boards.toml` has carried a `name` for this since it existed, and it reaches
    # HALF A only -- a Half B design is built from a board no record was attributed to, so
    # there is no `(tree, id)` to key an override on. This file closes that gap for both.
    #
    # THE KEY IS THE DRAFTED SLUG, not the published one. They differ for exactly these
    # rows, and keying on the published slug would unmatch the row the moment it applied,
    # flipping the name back and forth on alternate builds.
    names_file = REPO/"seed/names.toml"
    if names_file.exists():
        called = {e["drafted"]: e for e in tomllib.loads(names_file.read_text())["entry"]
                  if e.get("verdict") == "confirmed" and e.get("name")}
        matched = set()
        for d in designs:
            row = called.get(d["slug"])
            if not row:
                continue
            matched.add(d["slug"])
            d["name"], d["slug"] = row["name"], slugify(row["name"])
        renamed = len(matched)
        # Against the keys that MATCHED, never against the designs' slugs -- those have
        # already been overwritten by the loop above, so every applied row would look
        # unused and the build would fail on its own success.
        unused = sorted(set(called) - matched)
        if unused:
            # LOUD, because a row that matches nothing is a rename that silently did not
            # happen -- and the drafted slug it keys on can move under it when a board
            # file is renamed upstream, which is precisely when someone needs telling.
            raise SystemExit(f"seed/names.toml: no design drafts to {unused} -- "
                             "the drafted slug moved, or the row is stale")
        print(f"{renamed} designs renamed from their README (seed/names.toml)")

    # -- The hardest component, joined by the PUBLISHED slug ---------------------------
    #
    # AFTER THE RENAME, NOT IN EITHER HALF. `build-difficulty-scan.json` is keyed by the
    # slug `designs.json` publishes (`measure_build_difficulty.py` reads it), and the
    # halves only know the DRAFTED slug. Joining there missed every design names.toml
    # renames: `stront-choc` was looked up as `choc`, and six designs whose scan row
    # existed read "Soldering -- not recorded". The same order splay and stagger use.
    for d in designs:
        row = difficulty.get(d["slug"])
        d["hardestComponent"] = ({"parts": row["driven_by"], "tier": row["hardest"]}
                                 if row else None)

    # -- The summary, from seed/summaries.toml ---------------------------------------
    #
    # APPLIED HERE AND NOT IN EITHER HALF, because the file is keyed by SLUG and the slug
    # is not settled until both halves have run and the clash check above has dropped the
    # designs that fight over one. Applying it earlier would attach a sentence to a
    # record that never publishes.
    #
    # CONFIRMED ROWS ONLY, the same rule sources.toml and boards.toml take -- a `drafted`
    # row is a proposal nobody has read. A design with no confirmed row keeps `summary`
    # absent, and the page prints "No summary recorded" rather than a guess.
    summaries = REPO/"seed/summaries.toml"
    if summaries.exists():
        quoted = {e["slug"]: e for e in tomllib.loads(summaries.read_text())["entry"]
                  if e.get("verdict") == "confirmed"}
        applied = 0
        for d in designs:
            row = quoted.get(d["slug"])
            if row and row.get("summary"):
                # NO CITATION FIELD, because the record already carries one. The text is a
                # QUOTATION and a quote without its source would be this site claiming the
                # sentence -- but a summary row only exists where the repo holds ONE
                # design, so `sources` has exactly one entry and it is the README's repo.
                # A second field would be the same URL spelled twice, free to disagree.
                assert row["source"] in d["sources"][0], (
                    f"{d['slug']}: summary quoted from {row['source']}, "
                    f"but the design cites {d['sources']}")
                d["summary"] = row["summary"]
                applied += 1
        print(f"{applied} summaries applied from seed/summaries.toml "
              f"({len(quoted)} confirmed rows)")

    # -- Key wiring, from QMK's own matrix_pins / diode_direction --------------------
    #
    # QMK ONLY (qmk_metadata.wiring's own docstring; scripts/measure_key_wiring.py
    # validates the reading against 508 confirmed rows). ZMK states this in a
    # shield's `.overlay` `kscan` node, a different parser -- `zmk_wiring`, read in the
    # pass after this one, for unibody shields only.
    #
    # A design can carry several QMK firmware refs (`3w6/rev1` and `3w6/rev2` are one
    # design, two revisions), and they describe the same physical PCB, so the first
    # ref that resolves wins -- not the first ref tried. A disagreement between refs
    # is printed rather than silently resolved OR passed to `defer` -- `defer` means
    # "could not publish", and wiring is an extra, so a design still publishes with
    # `wiring` left unset rather than losing the whole record over one non-gate field.
    applied, disagreements = 0, []
    for d in designs:
        resolved = None
        for ref in d["firmware"]:
            if ref["family"] != "qmk" or not ref.get("path") or not wiring_is_its_own(ref):
                continue
            found = qmk_metadata.wiring(ref["path"], reg.root(ref["_tree"]))
            if found is None:
                continue
            if resolved is not None and found != resolved:
                disagreements.append(d["slug"])
                resolved = "disagreement"
                break
            resolved = found
        if resolved in (None, "disagreement"):
            continue
        kind, *rest = resolved
        d["wiring"] = (
            {"kind": "direct_pin", "pins": rest[0]} if kind == "direct" else
            {"kind": "matrix", "rows": rest[0], "cols": rest[1],
             "diodes": "col_to_row" if rest[2] == "COL2ROW" else "row_to_col"}
        )
        applied += 1
    print(f"{applied} designs given a wiring fact read from their own QMK metadata")
    if disagreements:
        print(f"  {len(disagreements)} left unset: their own QMK revisions disagree "
              f"on wiring -- {', '.join(disagreements)}")

    # -- Key wiring, from a ZMK shield's own kscan node -------------------------------
    #
    # SECOND, and only where QMK left it unset: a design with a QMK record keeps what
    # QMK says. The reader follows the DESIGN's body: a unibody design reads its shield
    # with `zmk_wiring.wiring`, a split one with `zmk_wiring.split_wiring`, and each
    # refuses the other's shape, so a shield shared by a unibody and a split design
    # (`reviung34`, one `.overlay` and no halves) still speaks only for the unibody one.
    #
    # Unibody, checked 2026-09-26 against every upstream QMK record of the same keyboard
    # that could be found by name: 15 of 16 agree exactly (tg4x, contra, the four
    # reviungs, crbn, eek, ...); the one that differs, `tidbit` (ZMK 5 x 4, QMK 5 x 6),
    # is in no design here.
    #
    # Split, checked 2026-10-03 the same way, shield by shield: the 13 that read (lily58,
    # helix, ergodash, elephant42, bfo9000, redox, zodiark, jorne, jiran, lotus58,
    # splitreus62, cradio -- and lily58's QMK `lite_rev3`) all match QMK's per-half
    # `matrix_pins` exactly, diode direction included; `splitreus62` is ROW2COL in both.
    # The two whose halves disagree are refused by the reader itself: `quefrency` (QMK
    # rev1 says 6 x 8, ZMK's left half 5 x 7) and `fourier` (QMK 4 x 7, ZMK's left half
    # six columns). Several refs that disagree leave it unset.
    #
    # A SPLIT READING MUST BE ABLE TO HOLD THE DESIGN'S OWN KEYS. The answer is one
    # half's scan, so two halves' worth -- `2 x rows x cols`, or `2 x pins` -- has to
    # reach the largest layout the design publishes. Every split that reads clears this
    # today (lily58: 58 keys in 60 places; the Sweeps: 34 keys on 2 x 17 pins); it is
    # here so a shield attributed to the wrong board is refused rather than published.
    from_zmk, too_small = 0, []
    for d in designs:
        if d.get("wiring") or d["body"]["kind"] not in ("unibody", "split"):
            continue
        split = d["body"]["kind"] == "split"
        resolved = None
        for ref in d["firmware"]:
            shield = shield_of(ref["_tree"], ref.get("shield") or "") if ref["family"] == "zmk" else None
            # A shield's `dir` is relative to the checkout of the tree it was read
            # from, so it is resolved there and nowhere else.
            if not shield or not shield.get("dir") or not wiring_is_its_own(ref):
                continue
            where = reg.local(ref["_tree"])/shield["dir"]
            found = (zmk_wiring.split_wiring(where, shield["id"]) if split else
                     zmk_wiring.wiring(where))
            if found is None:
                continue
            if resolved is not None and found != resolved:
                resolved = "disagreement"
                break
            resolved = found
        if resolved in (None, "disagreement"):
            continue
        if split and not split_reading_holds(resolved, d["layouts"]):
            too_small.append(d["slug"])
            continue
        kind, *rest = resolved
        d["wiring"] = (
            {"kind": "direct_pin", "pins": rest[0]} if kind == "direct" else
            {"kind": "matrix", "rows": rest[0], "cols": rest[1],
             "diodes": "col_to_row" if rest[2] == "COL2ROW" else "row_to_col"}
        )
        from_zmk += 1
    print(f"{from_zmk} designs given a wiring fact read from their own ZMK shield's kscan node")
    if too_small:
        print(f"  {len(too_small)} left unset: their split shield scans fewer keys than "
              f"the design publishes -- {', '.join(too_small)}")

    # A HARDER READING, FOR THE DESIGNS QMK's METADATA CANNOT ANSWER FOR. Only ever
    # fills a slug the loop above left unset -- a CUSTOM_MATRIX board's declarative
    # fields are absent, never wrong, so there is nothing here to overrule.
    from_matrix_reading = 0
    for d in designs:
        if d.get("wiring"):
            continue
        ruling = ruled_wiring.get(d["slug"])
        if not ruling:
            continue
        d["wiring"] = {"kind": "matrix", "rows": ruling["rows"], "cols": ruling["cols"],
                        "diodes": ruling["diodes"]}
        from_matrix_reading += 1
    if from_matrix_reading:
        print(f"{from_matrix_reading} designs given a wiring fact from a person's own "
              f"reading of custom scan code (seed/wiring.toml)")

    # WHICH TREE EACH DESIGN'S RECORDS CAME FROM, in `firmware` order. The same
    # build-time sidecar `design-boards.json` is and for the same stated reason:
    # `designs.json` records no tree, and re-deriving one downstream would mean a second
    # copy of this file's join.
    #
    # AND IT CANNOT BE GUESSED AT, which is why searching the registered roots for a
    # record id is not an option: a QMK fork is not a small extra tree, it is a whole
    # second copy of upstream at whatever vintage it branched. `tompi/vial-qmk` holds
    # `crkbd` and `lily58` too. Only the row knows which of the two copies a design
    # means, and only this loop still has the row. Backlog #64.
    trees_out = REPO/"data/design-trees.json"
    trees_out.write_text(json.dumps(
        {d["slug"]: [f["_tree"] for f in d["firmware"]]
         for d in sorted(designs, key=lambda x: x["slug"])},
        indent=1, sort_keys=True))

    # -- What each design's boards PLACE, beyond switches -- backlog #66 ------------
    #
    # PRESENCE, NEVER A COUNT, and the corpus is emphatic about why. Summing encoder
    # footprints counts POSITIONS x FOOTPRINT VARIANTS, not encoders: `lotus58` reads 38
    # because eight encoder footprints are stacked on the same positions, `chameleon`
    # reads 12 because six optional spots are drawn twice -- once as an encoder pad and
    # once as the hotswap switch that may go there instead -- and `torn` reads 6 from a
    # footprint whose own name is `..._EC11_optional`. A footprint is a hole, not a part,
    # which is the rule this file already applies to switches and controllers.
    #
    # Measured 2026-09-07 over 324 designs: encoder 84 (26%), display 38 (11%), speaker 6,
    # haptic 5, trackball 2, trackpad 1 -- 101 with at least one. QMK's `encoder.rotary`
    # array WOULD give an honest count and is silent for 75% of the corpus, and where both
    # instruments speak they disagree 32% of the time. So the set is what can be stated.
    #
    # READ FROM `extras-scan.json`, which `measure_extras.py` writes off `design-boards.json`
    # -- the same feed-forward loop `pitch-scan` and `bom-scan` already use.
    #
    # ABSENT AND EMPTY ARE DIFFERENT FACTS AND THE FILE ALREADY SEPARATES THEM.
    # `measure_extras.py` skips a design whose boards it could not read -- "an unread
    # board is NOT a design with no extras", its own words -- so a slug MISSING from the
    # scan is ignorance, and a slug present with `[]` is a board that was read and places
    # none. 223 of 324 are the second kind today, and they are a measurement, not a gap.
    #
    # So emit `[]` for measured-empty and emit NOTHING for unmeasured. Collapsing the two
    # (`if fittings:`) would have handed the domain a single silent absence covering both,
    # and the only honest reading left would have been `Unknown` for all 223 -- discarding
    # the measurement. It is also what made a fresh checkout dangerous: no scan file meant
    # every design "kept an empty set", which is `sweeq` claiming a display all over again.
    # Now no scan file means no design says anything.
    #
    # WRITTEN ONLY WHERE THERE IS EVIDENCE, and that is the correction, not an oversight.
    # This briefly emitted `[]` for the 223 designs whose boards matched no footprint
    # name, so the domain could say "read, and bare". It cannot: `measure_extras.py` is a
    # regex over footprint NAMES, which proves a part has a place and never proves it has
    # none. Checked against QMK's own records -- already read by this build for other
    # facts -- **11 of those 223 are contradicted by their own firmware**:
    # `bastardkb/dilemma` declares `POINTING_DEVICE_DRIVER = cirque_pinnacle_spi` while
    # its board file offers only a generic `PinHeader_1x04`, which no pattern here can
    # match. Corpus-wide this scan finds 1 trackpad and QMK declares 10 more.
    #
    # So no match is silence, and silence is what gets written. Widening the instrument to
    # read QMK's declarations is worth doing and would not change this: a ZMK-only design
    # or a module on a connector defeats both instruments the same way.
    #
    # THE GATE IS GONE, 2026-09-07. It was `--extras`, off by default, for exactly as long
    # as the domain had no member to receive the field. Emitting this is simply what the
    # build does now.
    extras_file = REPO/"data/extras-scan.json"
    found_extras = json.loads(extras_file.read_text()) if extras_file.exists() else {}
    applied = 0
    for d in designs:
        found = sorted(found_extras.get(d["slug"]) or [])
        if found:
            d["extensions"] = found
            applied += 1
    print(f"{applied} designs given the extensions their own boards place "
          f"({len(designs) - applied} with no evidence either way)")

    # -- switchMounts: what the switch positions accept, read off the pads -------------
    #
    # Backlog #107 step 9. `measure_switch_pads.py` reads every key position (a switch's
    # centre hole, footprints stacked on it read together) and records what it accepts:
    # solder, a Kailh MX or Choc socket's hole pair, or Mill-Max-wide plated pins. A
    # design accepts what EVERY position on one of its boards does, and what ANY of its
    # boards does (Potato65 ships a hotswap PCB and two solder ones). A board whose
    # positions share nothing (Ergoslab: MX sockets, Choc soldered) is left Unknown until
    # the page can say it per position (step 4).
    #
    # Replaced `switchSocket`, a regex over footprint NAMES that could not prove absence,
    # and read Hillside 46's `pg1350-R_hotswap` as Kailh where its README says Mill-Max.
    pad_scan = json.loads((REPO/"data/switch-pad-scan.json").read_text())
    pad_boards = json.loads((REPO/"data/design-boards.json").read_text())
    MOUNT = {"solder": "solder", "mx": "mx_socket", "choc": "choc_socket", "millmax": "millmax"}
    applied = 0
    for d in designs:
        b = pad_boards.get(d["slug"]) or {}
        accepted = set()
        for p in b.get("paths", []):
            positions = [set(k.split("+")) for k in
                         (pad_scan.get(f"{b['repo']}\t{p}") or {}).get("positions", {}) if k != "-"]
            if positions:
                accepted |= set.intersection(*positions)
        if accepted:
            d["switchMounts"] = sorted(MOUNT[m] for m in accepted)
            applied += 1
    print(f"{applied} designs given the switch mounts their boards accept "
          f"({len(designs) - applied} unread, or with key positions that share nothing)")

    # -- switch overrides, applied last -------------------------------------------------
    #
    # seed/switch-overrides.toml: corrections from a design's own README where the board
    # scans got its switches or sockets wrong. Its header has why it exists.
    overrides = tomllib.loads((REPO/"seed/switch-overrides.toml").read_text()).get("entry", [])
    by_slug = {d["slug"]: d for d in designs}
    for e in overrides:
        d = by_slug.get(e["slug"])
        if d is None:
            sys.exit(f"seed/switch-overrides.toml names {e['slug']!r}, which is not a design")
        if "switches" in e:
            d["switches"] = [{"kind": k} for k in e["switches"]]
        if "switch_mounts" in e:
            d["switchMounts"] = sorted(e["switch_mounts"])
    print(f"{len(overrides)} designs corrected by seed/switch-overrides.toml")

    # -- splay, yes/no/unknown -----------------------------------------------------------
    #
    # The owner's request: "ignore thumb clusters for this detection, otherwise 90% will
    # have splay." `measure_splay.py` is `build_silhouettes.has_splay` -- already scored
    # against 21 of his own by-eye verdicts and agreeing with all 21 -- given a caller for
    # the first time, dropping the thumb cluster's lowest three keys per hand exactly as
    # his condition asked.
    #
    # A REAL THIRD VALUE, NOT "EVIDENCE ONLY" LIKE `extensions`/`switchSocket` ABOVE. Those
    # two are regexes over footprint names that can prove presence and never prove
    # absence, so silence has to mean `Unknown` for both of them. Splay is different: it
    # is read off where a design's own attributed board places every switch, over the
    # WHOLE board rather than a name lexicon that can always be one entry short, so a
    # board that was actually measured and found not to fan is a genuine `false` --
    # `measure_splay.py`'s own module doc has the aggregation rule (`True` wins if any
    # attributed board shows it, `False` only where every usable board was actually
    # judged). What is still `Unknown` is a design with no attributed board, or whose only
    # board's key count matches no layout it publishes -- nobody has measured those at
    # all, and this writes nothing for them rather than guessing.
    splay_file = REPO/"data/splay-scan.json"
    splay_verdicts = json.loads(splay_file.read_text()) if splay_file.exists() else {}
    splayed = unsplayed = 0
    for d in designs:
        if d["slug"] in splay_verdicts:
            verdict = splay_verdicts[d["slug"]]
            d["splay"] = verdict
            splayed += verdict
            unsplayed += not verdict
    print(f"{splayed} designs measured splayed, {unsplayed} measured not splayed "
          f"({len(designs) - splayed - unsplayed} with no verdict either way)")

    # -- stagger, fitted against 38 owner-ruled designs (2026-09-24) --------------------
    #
    # Same shape as splay just above: `measure_stagger.py` is `build_silhouettes
    # .stagger_kind` given a caller, one verdict per design combined across its own
    # attributed board files, `Unknown` wherever no usable file exists or the design's
    # files disagree with each other. `stagger_kind`'s own docstring has the two fitted
    # signals and their margins; `seed/stagger-overrides.toml` has the one owner
    # correction (`choc-v`) applied on top.
    stagger_file = REPO/"data/stagger-scan.json"
    stagger_verdicts = json.loads(stagger_file.read_text()) if stagger_file.exists() else {}
    stagger_counts = {}
    for d in designs:
        if d["slug"] in stagger_verdicts:
            verdict = stagger_verdicts[d["slug"]]
            d["stagger"] = verdict
            stagger_counts[verdict] = stagger_counts.get(verdict, 0) + 1
    print(f"{stagger_counts.get('ortho', 0)} designs measured ortho, "
          f"{stagger_counts.get('row', 0)} row-staggered, "
          f"{stagger_counts.get('column', 0)} column-staggered "
          f"({len(designs) - sum(stagger_counts.values())} with no verdict either way)")

    # -- exposed components, the owner's request 2026-10-04 -----------------------------
    #
    # Same shape as splay: `measure_exposed_components.py` writes one verdict per design,
    # `True` where most of the switch diodes sit on top outside every keycap (Discipline,
    # Mysterium, Un Split), and nothing at all where it could not find the diodes.
    exposed_file = REPO/"data/exposed-components-scan.json"
    exposed_verdicts = json.loads(exposed_file.read_text()) if exposed_file.exists() else {}
    exposed = hidden = 0
    for d in designs:
        if d["slug"] in exposed_verdicts:
            verdict = exposed_verdicts[d["slug"]]
            d["exposedComponents"] = verdict
            exposed += verdict
            hidden += not verdict
    print(f"{exposed} designs measured with exposed components, {hidden} without "
          f"({len(designs) - exposed - hidden} with no verdict either way)")

    # -- power switch, the owner's request 2026-10-04 -----------------------------------
    #
    # `measure_power_switch.py` reads the slide switch off the design's own board files by
    # name, against a lexicon read off every switch-like name in the corpus; its module doc
    # says why "no" is allowed here. Nothing is written where it cannot tell.
    power_file = REPO/"data/power-switch-scan.json"
    power_verdicts = json.loads(power_file.read_text()) if power_file.exists() else {}
    switched = unswitched = 0
    for d in designs:
        if d["slug"] in power_verdicts:
            verdict = power_verdicts[d["slug"]]
            d["powerSwitch"] = verdict
            switched += verdict
            unswitched += not verdict
    print(f"{switched} designs with a power switch on the board, {unswitched} without "
          f"({len(designs) - switched - unswitched} with no verdict either way)")

    # -- board size and copper layers, the owner's call 2026-10-04 ----------------------
    #
    # `measure_board_size.py` reads each design's own board files' Edge.Cuts extent and
    # layer table into seed/board-sizes.toml; its module doc has the rules. `pcb` carries
    # only what was measured, and `plates` only plates from a repo holding this design
    # alone. The build list (#112) reads `pcb`; nothing reads `plates` yet.
    pcb_sizes, plate_sizes = board_sizes(REPO/"seed/board-sizes.toml")
    for d in designs:
        if d["slug"] in pcb_sizes:
            d["pcb"] = pcb_sizes[d["slug"]]
        if d["slug"] in plate_sizes:
            d["plates"] = plate_sizes[d["slug"]]
    stale = (set(pcb_sizes) | set(plate_sizes)) - {d["slug"] for d in designs}
    print(f"{sum(1 for d in designs if {'widthMm', 'halves'} & set(d.get('pcb', {})))} "
          f"designs with a "
          f"measured board size, {sum(1 for d in designs if 'plates' in d)} with plates"
          + (f" ({len(stale)} rows for slugs that no longer publish -- re-run "
             f"measure_board_size.py)" if stale else ""))

    # -- layout names, backlog #23 -------------------------------------------------------
    #
    # TWO SOURCES, THE HUMAN FILE WINS. `layout-names-scan.json` is ZMK's own
    # `display-name`, read only where a shield states more than one (measure_layout_names.py);
    # `seed/layout_names.toml` is a person's reading of a README image for the cases
    # nothing machine-reads, KLOR foremost. Both key by (slug, key count), which is the
    # only field a design's own `layouts` and either source agree on.
    #
    # WRITTEN ONLY WHERE THE COUNT MATCHES AN EXISTING LAYOUT. Never invents a layout a
    # design does not already carry -- naming is additive to a fact this build already
    # established, not a second way to state one. One exception, break-off layouts,
    # below.
    names_file = REPO/"data/layout-names-scan.json"
    zmk_names = json.loads(names_file.read_text()) if names_file.exists() else {}

    names_toml = REPO/"seed/layout_names.toml"
    human_names, breakoffs = {}, {}
    for entry in tomllib.loads(names_toml.read_text()).get("entry", []):
        human_names.setdefault(entry["slug"], {})[entry["keys"]] = entry["name"]
        if entry.get("breakoff"):
            breakoffs.setdefault(entry["slug"], set()).add(entry["keys"])

    # THE ONE EXCEPTION TO "NEVER INVENTS A LAYOUT", and it is narrow on purpose. A
    # break-off design's board places every optional key, so its switch count is an
    # upper bound and the smaller arrangements exist nowhere a machine reads -- KLOR's
    # four are in a README drawing. A person's row marked `breakoff = true` may add one,
    # and only BELOW the largest layout the board already establishes: snapping keys off
    # can only take keys away. A count at or above that bound is not a break-off and
    # stops the build rather than publishing a keyboard nobody drew.
    by_slug = {d["slug"]: d for d in designs}
    added = 0
    for slug, counts in sorted(breakoffs.items()):
        d = by_slug.get(slug)
        if d is None:
            raise SystemExit(f"layout_names.toml: breakoff row for unpublished design '{slug}'")
        have = {layout["keys"] for layout in d["layouts"]}
        bound = max(have)
        for n in sorted(counts - have):
            if n >= bound:
                raise SystemExit(f"layout_names.toml: {slug} breakoff {n} keys is not below "
                                 f"the board's {bound}")
            d["layouts"].append({"keys": n})
            added += 1
        d["layouts"].sort(key=lambda layout: layout["keys"], reverse=True)
    print(f"{added} break-off layouts added from a person's reading of the design's own drawing")

    # ALTERNATE FILLS, seed/board-fills.toml: the same board with different footprints
    # fitted -- `squiggle-goldberg` takes each thumb pair as two 1u keys or one stabilized
    # 2u. The same bound as a break-off, for the same reason: alternates share the board's
    # positions, so the design's own count is the most any fill can reach. Each row is
    # checked against the board's footprints when build_silhouettes.py draws it.
    alternates = 0
    for slug, counts in sorted(board_fills.alternate_counts().items()):
        d = by_slug.get(slug)
        if d is None:
            raise SystemExit(f"board-fills.toml: alternate row for unpublished design '{slug}'")
        have = {layout["keys"] for layout in d["layouts"]}
        for n in sorted(counts - have):
            if n >= max(have):
                raise SystemExit(f"board-fills.toml: {slug} alternate {n} keys is not below "
                                 f"the design's {max(have)}")
            d["layouts"].append({"keys": n})
            alternates += 1
        d["layouts"].sort(key=lambda layout: layout["keys"], reverse=True)
    print(f"{alternates} layouts added from a person's alternate fill of the design's own board")

    named = 0
    for d in designs:
        by_count = {**{int(k): v for k, v in zmk_names.get(d["slug"], {}).items()},
                    **human_names.get(d["slug"], {})}
        if not by_count:
            continue
        for layout in d["layouts"]:
            name = by_count.get(layout["keys"])
            if name:
                layout["name"] = name
                named += 1
    print(f"{named} layouts given the name their own shield or a person states")

    # -- based_on, backlog #24 ---------------------------------------------------------
    #
    # AUTOMATIC FIRST, HUMAN SECOND, AND THE HUMAN WINS A CONFLICT. The fork-flag join
    # is real evidence (a GitHub structural fact) but a narrower one than an explicit
    # README statement -- a person confirming a row in seed/based_on.toml has read
    # something the fork flag cannot see, so their answer stands even where the two
    # would disagree. Needs `board_repo`, still present on every `d` at this point in
    # the build, and not yet stripped below.
    repo_to_slugs = collections.defaultdict(list)
    for d in designs:
        if d.get("board_repo"):
            repo_to_slugs[d["board_repo"].lower()].append(d["slug"])
    automatic_based_on = fork_based_on(ancestry, repo_to_slugs)
    ruled_based_on = based_on_rulings(REPO/"seed/based_on.toml")
    based_on_applied = 0
    for d in designs:
        ancestors = ruled_based_on.get(d["slug"]) or automatic_based_on.get(d["slug"])
        if ancestors:
            d["basedOn"] = ancestors
            based_on_applied += 1
    print(f"{based_on_applied} designs given a credited ancestor "
          f"({len(automatic_based_on)} from GitHub's own fork data, "
          f"{len(ruled_based_on)} from a person's own reading)")

    # -- families, backlog #107 ---------------------------------------------------------
    #
    # After every rename, so a row is keyed by the slug the site actually publishes.
    families = family_rulings(REPO/"seed/families.toml", {d["slug"] for d in designs})
    for d in designs:
        if d["slug"] in families:
            d["family"] = families[d["slug"]]
    print(f"{len(families)} designs in {len(set(families.values()))} families "
          f"(seed/families.toml)")

    boards_out = REPO/"data/design-boards.json"
    boards_out.write_text(json.dumps(
        {d["slug"]: {"repo": d.get("board_repo"), "paths": d.get("board_paths") or []}
         for d in sorted(designs, key=lambda x: x["slug"]) if d.get("board_repo")},
        indent=1, sort_keys=True))
    for d in designs:
        d.pop("board_key", None)
        d.pop("board_paths", None)
        d.pop("board_repo", None)
        for ref in d["firmware"]:
            ref.pop("_tree", None)
            ref.pop("_borrowed", None)

    out = REPO/args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(sorted(designs, key=lambda d: d["slug"]), indent=1))
    # Beside designs.json, for the /designs/families/:family pages: each family's name and the
    # source its grouping cites. `family_rulings` above has already refused a row that
    # no longer matches the corpus, so every name here is one some design carries.
    (out.parent/"families.json").write_text(
        json.dumps(family_sources(REPO/"seed/families.toml"), indent=1))

    # -- Former slugs, so a rename leaves a redirect. Forgejo #38 ---------------------
    #
    # Ticket 07 decision 8: "slugs are mutable and old ones keep resolving. A
    # machine-guessed name is a first draft too, so a rename leaves a redirect rather
    # than a dead link." `seed/renames.toml` is the person's record of each rename; this
    # emits it as a flat `was -> now` map beside `designs.json`, and CHECKS it, because a
    # redirect table nobody validates is a worse failure than the 404 it replaces.
    #
    # THE CHAIN IS RESOLVED HERE, so the map is always one hop. Rename a design twice and
    # both old URLs point at where it is NOW rather than at each other -- which keeps the
    # consumer a dictionary lookup instead of a walk, and makes a cycle a build error
    # rather than an infinite redirect served to a reader.
    live = {d["slug"] for d in designs}
    renames_in = REPO/"seed/renames.toml"
    renames = {}
    if renames_in.exists():
        # `now = false` is a RULED REMOVAL -- `sources.toml`'s own convention for
        # "settled, and the answer is nothing". It earns a row because the file's job is
        # to account for every dead slug, and the difference between a removal a person
        # ruled and a slug nobody looked at is the whole of `check_slug_keys.py`.
        stated = {e["was"]: e["now"]
                  for e in tomllib.loads(renames_in.read_text())["entry"]
                  if e["now"] is not False}
        for was in stated:
            assert was not in live, (
                f"seed/renames.toml: {was!r} is a LIVE slug, so redirecting it would "
                f"shadow a real page")
            now, seen = stated[was], {was}
            while now in stated:
                assert now not in seen, f"seed/renames.toml: rename cycle at {now!r}"
                seen.add(now)
                now = stated[now]
            assert now in live, (
                f"seed/renames.toml: {was!r} redirects to {now!r}, which publishes no "
                f"design -- a 301 to a 404 hides which URL was wrong")
            renames[was] = now
    (out.parent/"renames.json").write_text(
        json.dumps(dict(sorted(renames.items())), indent=1))
    if renames:
        hops = sum(1 for w, n in renames.items() if stated[w] != n)
        print(f"{len(renames)} former slugs redirect (seed/renames.toml)"
              + (f", {hops} of them through a chain" if hops else ""))

    if args.defer_json:
        pathlib.Path(args.defer_json).write_text(json.dumps(
            sorted(unbuilt, key=lambda u: (u["reason"], u.get("repo") or "",
                                           u.get("id") or "")), indent=1))
        print(f"{len(unbuilt)} deferrals written to {args.defer_json}")

    print(f"{len(rows)} confirmed rows -> {len(designs)} complete records "
          f"({100*len(designs)/len(rows):.0f}%)  -> {args.out}")
    print("\nnot built, and why:")
    for reason, n in deferred.most_common():
        print(f"  {n:>4}  {reason}")
        if args.verbose:
            print(f"        e.g. {', '.join(examples[reason])}")


if __name__ == "__main__":
    main()
