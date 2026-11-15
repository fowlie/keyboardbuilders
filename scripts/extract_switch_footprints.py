#!/usr/bin/env python3
"""Extract switch-footprint families from designs' own KiCad board files.

Backs the resolution of ticket 24.
Reproduces the 5/19 -> 14/19 `switch_footprint` gain that
`10-staging-type-completion.md` measured by hand, using the token table below.

Two rules make it work, both established by measurement:

  * Read footprints **placed** in a board file, never files merely present. This is
    what closes the vendored-library trap: `gregandcin-teaqueen` ships
    `SW_Kailh_Choc_V1.kicad_mod` in a submodule and places 51 MX sockets.
  * Match the exclusion list against `library:name`, not the bare name.
    `MX_PlateSlots:MX100` is a plate cutout whose *name* is indistinguishable
    from a switch; only its library gives it away.

The family is a **set** — every family a footprint name mentions is collected,
because combi footprints are real: `Keebio-Parts:MX-Alps-Choc-1U-NoLED` (prkl30)
and `SofleKeyboard-footprint:CherryMX_KailhLowProfile_Hotswap` (Sofle v1, whose
build guide confirms it takes either MX or Choc).

Usage:  extract_switch_footprints.py <dir-of-clones> [--json]
"""

import argparse
import collections
import json
import pathlib
import re
import sys

# A footprint reference, quoted or bare. **The quoted branch is load-bearing and
# was missing**: KiCad quotes any name containing a space, and the old character
# class stopped at the space, so `"Keyboard parts:SW_MX_Millmax1,524"` -- placed
# 12x on `kraken-jokes/pteron-pcb` -- was captured as the bare word `Keyboard`,
# throwing away the `MX` that names its family. Found 2026-08-22 by ticket 44's
# board scan, where the same truncation produced 2,067 anonymous `Keyboard`
# placements across the corpus.
FOOTPRINT = re.compile(
    r'\((?:footprint|module)\s+(?:"([^"]*)"|([A-Za-z0-9_:.,\-]+))')

# Family tokens, tested as case-insensitive substrings of `library:name`.
# `pg1350` and `lowprofile` are load-bearing: they are the two Choc synonyms in
# the sample that carry no family word (`Kailh_socket_PG1350`, clog;
# `SW_PG1350`, handwired-angel; `CherryMX_KailhLowProfile_Hotswap`, Sofle v1).
FAMILY_TOKENS = [
    ("ChocV1", ("choc", "pg1350", "lowprofile", "low_profile", "low-profile")),
    ("ChocV2", ("pg1353",)),
    ("MX", ("mx", "cherry")),
    ("Alps", ("alps",)),
    ("KailhX", ("kailhx", "pg1425")),
    ("ChocMini", ("pg1232", "choc_mini", "chocmini")),
]

# `pg1232` IS KAILH'S MINI CHOC (Kailh's own name, kailhswitch.com), a smaller switch than
# the PG1350 Choc v1 and NOT INTERCHANGEABLE WITH IT: 14.5 x 13.5 mm against 15 x 15, and
# a different footprint. Ferris draws both, and the PG1350 has a 3.43 mm centre post, two
# 1.70 mm side posts and pins at (-5, 3.8) and (0, 5.9), where the PG1232 has no posts and
# TWO PINS, the only pads wired, at (-4.3, 5.1) and (2.25, 5.4). Two more plated holes at
# (+/-5.3, -4.75) carry no net, on Ferris and on `davidphilipbarr/36keys`' own PG1232
# footprint alike: something mechanical the board holds, not a pin. `choc` alone used to read
# it as Choc v1 (`switches:Choc_Mini_PG1232_Choc_Spacing`, Ferris 0.2 Mini, whose readme
# says "Choc mini switches (PG 1232)"). Where it names PG1232 and not PG1350 too, Choc v1
# is the substring's mistake and is dropped.
#
# A SPACING IS NOT A SWITCH. `switches:Choc_PG1350_MX_Spacing` (Ferris 0.1 Base) is a
# Choc footprint on the MX grid, and its `mx` read as an MX switch. Stripped before the
# tokens are matched. Measured 2026-10-01: two footprints in the corpus, both Ferris.
SPACING_WORDS = re.compile(r"mx[_-]?spacing|choc[_-]?spacing", re.I)

# Matched against `library:name`, because a plate cutout's *name* can be
# indistinguishable from a switch (`MX_PlateSlots:MX100`) and only the library
# gives it away. Every token here must be specific enough not to collide with a
# real library name: a bare `tab` wrongly excluded `index-tab:Kailh_socket_MX`.
# Split in two 2026-08-22 by ticket 44's board scan, because one list could not
# hold both jobs. `hole` was excluding **switches**: a switch footprint on a PCB
# *is* the drill holes for its pins, so authors name it that way --
# `Library:MX-1-00U-Hole` (474 placements), `kbd:SW_Hole` (255),
# `footprints:hole-switch` (98), `keebLib:pg1353_voidHoles`. All say their family
# outright and all were thrown away.
#
# ABSOLUTE wins over everything: a plate cutout is not a switch however it is
# named, which is the whole reason exclusions read `library:name` --
# `MX_PlateSlots:MX100` says MX and is still a hole in a plate.
#
# `tact` AND THE PART NUMBERS JOINED THE LIST 2026-09-07, and they belong beside `push`
# and `reset` for the same reason: a TACTILE PUSHBUTTON IS NOT A KEY SWITCH. `ergoslab`
# places `footprints:SW_TACT_ALPS_SKQGABE010` -- an ALPS-BRAND tact button, almost
# certainly its reset -- and the substring `alps` made this corpus publish `ergoslab` as
# taking **Alps keyboard switches**, which it does not. Its author's own summary says
# "a combination of Cherry MX (or compatible) and Kailh Choc".
#
# `alps` here is a brand and the brand makes tact switches too, which is the whole trap:
# the token is right about the manufacturer and wrong about the part. Found by
# `measure_summary_agreement.py`, which is what the author summaries are for.
#
# `silkscreen` JOINED 2026-10-01 with Choc Mini's `pg1232` token: Ferris draws its switch
# as art, `silkscreens:pg1232_top` and six more, and the token made seven pictures into
# seven keys (34 -> 41). Art is not a key however it is named.
#
# Measured before changing it, both directions: exactly ONE design publishes a fabricated
# `alps` from a tact footprint, and exactly ONE footprint in the whole corpus would be
# newly excluded -- that same one. The part numbers are listed alongside `tact` because a
# name like `SKQGABE010` or `TVBP06` says pushbutton to a person and nothing to a regex.
# A POWER SWITCH, the slide switch a wireless board turns its battery off with. Its own
# list because two readers need it: `measure_power_switch.py` reports it, and it must
# never count as a key. Until 2026-10-04 three of these names did -- `switch_SSSS811101`,
# `Switch_MSK-12C01-07`, `switch-MSK-12C02-smd` -- because they say "switch" and nothing
# here refused them: chocofly's board read 62 switches for 60 keys, 41-1350's 43 for 41.
# Every name was read off the corpus by hand: MSK-12 and its JLCPCB number C128955, C&K
# PCM12 and JS202011, SS-12D, Alps SSSS811101, EG1218, and swoop's bare `Power`.
# `slider-switch` JOINED 2026-11-15 with Urchin, whose board names its MSK-12C02 (per its
# BOM) `urchin-footprints:smt-slider-switch` and read "no power switch" until then. The
# one footprint in the corpus with that name.
POWER_SWITCH = re.compile(
    r"msk-?12|c128955|pcm12|js[12]02011|ss-?12d|ssss8|eg1218|power_?switch|:power$"
    r"|slider-switch",
    re.I,
)
# A one-position DIP slide switch can switch the battery or a boot mode, and its name does
# not say which (macrocat, index-tab, omega). Not a key, and not an answer either way.
MAYBE_POWER_SWITCH = re.compile(r"sw_dip_spstx01_slide", re.I)

ABSOLUTE_NOT_A_SWITCH = re.compile(
    r"stabil|plateslot|plate_slot|keyplate|backplate|mountinghole|mounting_hole"
    r"|spdt|spst|push|encoder|reset|slider|logo|fiducial|breakaway|mousebite"
    r"|tact|skqg|skrk|tvbp|evqp|b3f|b3s|silkscreen"
    r"|" + POWER_SWITCH.pattern,
    re.I,
)

# WEAK loses to a family token: `hole` alone means nothing, but `MX...Hole` means
# an MX switch. Kept as a separate list rather than deleted because on a
# footprint that names no family at all, `hole` is still the right read.
WEAK_NOT_A_SWITCH = re.compile(r"hole|thread|npth|\bvia\b", re.I)

# Retained under its old name so nothing importing it breaks; it is the absolute
# list, which is what every previous caller meant by it.
NOT_A_SWITCH = ABSOLUTE_NOT_A_SWITCH

# Passives and connectors, which ARE reliably named across every library in the
# sample. Listed so the unclassified report stays small enough to act on: without
# this, every board "blocks" on its diodes, which are more numerous than its
# switches on 5 of the 24 boards measured.
# Reference-designator prefixes are anchored to the start of the name: an
# unanchored `D_` matches `void_switch`, which hid the one genuine `Bespoke`
# switch in the sample. Every token here is anchored or long enough not to
# collide -- the same lesson as the bare `tab` above, learnt three times.
PASSIVE = re.compile(
    r"diode|resistor|capacitor|\bLED\b|SK6812|SK6803|WS2812|SOD-?\d|SOT-?\d"
    r"|_0805|_0603|_0402|_1206|(?:^|:)[RCDQUYLF]_|conn|header|usb|jack|oled"
    r"|screw|fuse|crystal|regulator|solderjumper|ssop|qfn|tqfp|pin_header",
    re.I,
)

# FOOTPRINTS WHOSE NAME STATES NO FAMILY, read from the footprint itself. Keyed by the
# exact `library:name`, never a token: a token is a claim about every name that contains
# it, and `dual` is also a diode (`Keebio-Parts:Diode-dual`), a TRRS jack and a
# dual-MCU breakout elsewhere in this corpus. An exact name is a claim about one
# footprint, and each row says which repo's footprint was read to make it. Checked
# 2026-09-26 against board-scan.json: every name below is placed by that repo alone.
#
# `ibnuda/squiggle`, libs/footprints/{1u,125u,15u,2u}-dual.kicad_mod (and the same
# footprints copied into the `nodiodes` board's own library): MX's 3.99 mm centre hole
# and 1.8 mm side holes at +/-5.08, with each pin an OVAL pad centred exactly between the
# MX and the Alps pin -- (-3.405, -3.27) between MX (-3.81, -2.54) and Alps (-2.5, -4.0),
# (2.52, -4.79) between MX (2.54, -5.08) and Alps (2.5, -4.5) -- then the pair mirrored,
# so one PCB serves either hand. An MX/Alps hybrid, which is what the repo's README says
# of every variant but Low Profile: "MX and Alps style switches". `1u-minimal` has the
# MX holes and plain round pads at the same spots, and is placed by no board.
#
# `foostan/crkbd`, pcbs/corne-chocolate/hotswap/corne-chocolate.kicad_pcb, read
# 2026-09-28: `choc` alone reads ChocV1, but `choc12` is foostan's name for a footprint
# that takes both. Its centre hole is 5.0 mm, which is Choc v2's post; a v1's smaller post
# fits it too. It has the 1.9 mm side posts at +/-5.5 that both share, and one v1 hotswap
# socket's pads. The README says the same of the board: "for Kailh choc v1 and v2
# switches". The `_rotary_encoder_ec12` spelling is excluded as an encoder before this
# table is read, so it is not listed.
STATED_FAMILIES = {
    **{f"{lib}:{w}-dual": frozenset({"MX", "Alps"})
       for lib in ("footprints", "nodiodes") for w in ("1u", "125u", "15u", "2u")},
    **{f"kbd:keyswitch_choc12_hotswap_{w}": frozenset({"ChocV1", "ChocV2"})
       for w in ("1u", "1.5u")},
}

# A board carrying fewer placements than this is not a keyboard board. Kept low
# because a macropad is a real design: kb-elmo-twelvekey places exactly 12.
MIN_PLACEMENTS = 5


def placed_names(text):
    """Every `library:name` a board file places, in order, quoted or bare.

    The one place the two-group regex is unpacked. Callers must use this rather
    than `FOOTPRINT.findall`, which returns tuples.
    """
    return [q or bare for q, bare in FOOTPRINT.findall(text)]


def classify(full_name):
    """Return the set of switch families a `library:name` string names.

    Matching is deliberately **asymmetric**: exclusions read the whole
    `library:name`, but family tokens read only the bare name. A library is named
    for everything it holds, not for what this footprint is -- matching families
    against `MX_Alps_Hybrid:MXOnly-1U` yields a phantom `Alps` for a board that
    places MX only.
    """
    if ABSOLUTE_NOT_A_SWITCH.search(full_name):
        return frozenset()
    if full_name in STATED_FAMILIES:
        return STATED_FAMILIES[full_name]
    name = SPACING_WORDS.sub("", full_name.split(":")[-1].lower())
    fams = frozenset(
        family
        for family, tokens in FAMILY_TOKENS
        if any(token in name for token in tokens)
    )
    if "ChocMini" in fams and "pg1350" not in name:
        fams -= {"ChocV1"}
    # A family token beats the weak list: `MX-1-00U-Hole` is an MX switch, while
    # a bare `MountingHole` names nothing and stays excluded.
    if not fams and WEAK_NOT_A_SWITCH.search(full_name):
        return frozenset()
    return fams


def scan_board(path):
    """Classify one .kicad_pcb. Returns (families, switch_count, unclassified)."""
    try:
        text = path.read_text(errors="ignore")
    except OSError as exc:
        print(f"warning: cannot read {path}: {exc}", file=sys.stderr)
        return frozenset(), 0, collections.Counter()

    families, count = set(), 0
    placements = collections.Counter(placed_names(text))

    for full_name, placed in placements.items():
        matched = classify(full_name)
        if matched:
            families |= matched
            count += placed

    # Footprints we could not classify and cannot dismiss as passives. These are
    # the candidates for a switch the token table has never seen -- the `Bespoke`
    # case (void_switch_65 places 64 `void_switch:VoidSwitch_1u_SMD`).
    unclassified = collections.Counter(
        {
            name: placed
            for name, placed in placements.items()
            if not classify(name)
            and not NOT_A_SWITCH.search(name)
            and not PASSIVE.search(name)
        }
    )
    return frozenset(families), count, unclassified


def scan_design(root):
    """Scan one design/repo directory. Returns a dict of findings."""
    boards, families = [], set()
    unclassified = collections.Counter()

    for path in sorted(root.rglob("*.kicad_pcb")):
        board_families, count, board_unclassified = scan_board(path)
        if count < MIN_PLACEMENTS:
            # Not a keyboard board, but it may still hold an unnamed switch.
            for name, placed in board_unclassified.items():
                if placed >= MIN_PLACEMENTS:
                    unclassified[name] += placed
            continue
        boards.append(
            {
                "path": str(path.relative_to(root)),
                "families": sorted(board_families),
                "switch_placements": count,
            }
        )
        families |= board_families
        for name, placed in board_unclassified.items():
            if placed >= MIN_PLACEMENTS:
                unclassified[name] += placed

    return {
        "design": root.name,
        "switch_footprint": sorted(families) or ["Unknown"],
        "boards": boards,
        # Feeds `19-multi-design-fanout.md`: two or more switch-bearing boards
        # means the source fans out and needs a human-confirmed enumeration.
        "fans_out": len(boards) >= 2,
        # Feeds decision 3 of this ticket. These are *candidates*, not verdicts:
        # confirming one is the switch needs the design's key count from QMK's
        # `layouts`, which the build has and this standalone script does not.
        # Frequency alone is not enough -- diodes outnumber switches on 5 of the
        # 24 boards measured, and void_switch_65's 70 transistors outrank its 64
        # switches.
        "unclassified_candidates": dict(unclassified.most_common(3)),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=pathlib.Path, help="directory of design clones")
    parser.add_argument("--json", action="store_true", help="emit JSON")
    args = parser.parse_args()

    if not args.root.is_dir():
        parser.error(f"{args.root} is not a directory")

    results = [
        scan_design(child) for child in sorted(args.root.iterdir()) if child.is_dir()
    ]

    if args.json:
        json.dump(results, sys.stdout, indent=2)
        print()
        return

    known = fanned = flagged = 0
    for result in results:
        families = result["switch_footprint"]
        if families != ["Unknown"]:
            known += 1
        fanned += result["fans_out"]
        flag = ""
        candidates = result["unclassified_candidates"]
        if candidates:
            flagged += 1
            top, placed = next(iter(candidates.items()))
            flag = f"  ? unnamed candidate {top} x{placed}"
        marker = "*" if result["fans_out"] else " "
        print(f"{result['design']:34s}{marker} {','.join(families):22s}{flag}")

    print(
        f"\n{len(results)} designs · {known} with a family · "
        f"{fanned} fan out (*) · {flagged} with an unnamed candidate (?) "
        f"needing the key-count cross-check"
    )


if __name__ == "__main__":
    main()
