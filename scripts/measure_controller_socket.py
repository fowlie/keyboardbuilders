#!/usr/bin/env python3
"""Measure the socket-vs-soldered signals in designs' own KiCad board files.

Backs the resolution of ticket 27.
This is a MEASUREMENT script, not the extractor: it reports what each signal
would say and where they disagree, so the numbers in that ticket are
reproducible. The importer's extractor is a separate piece of work.

Three findings this script exists to reproduce, over the 31 clones (22 with a
readable board file):

  * Both verdicts have a POSITIVE signal, and the two never co-occur -- 12
    Socket, 10 Soldered, 0 neither. The negative signal the ticket assumed was
    needed is not needed at all.
  * The negative signal would have been WRONG anyway. `handwired-angel` places a
    Pro Micro socket and a crystal; `void_switch_65` places a BlackPill socket
    and the same `SOT-223-3_TabPin2` regulator crkbd places. Identical footprint
    strings, opposite verdicts.
  * QMK's metadata is wrong 5 of the 15 times both it and a board file speak,
    always claiming soldered for a socketed design.

Scope: this reads BOARD FILES ONLY. The second positive socket source that
ticket resolved on -- QMK's own `development_board` field, which reaches 329
records that have no repo at all -- needs QMK paths rather than clones and is
not measured here. So `takashicompany-goat51` prints as Unknown even though QMK
declares it `promicro`; the resolved rule makes it `Socket{pro_micro}`.

Matching follows `extract_switch_footprints.py`'s asymmetry, for the same reason
and now for the fifth time: POSITIVE tokens read the bare name, exclusions read
the whole `library:name`. Here the trap is the string "MCU", which appears on
SOCKET footprints -- `rp2040:MCU_RP2040_Zero_34` is a dev board, and
`MCU_RaspberryPi_and_Boards:RPi_Pico_SMD_TH` is a dev board whose library is
named for MCUs.

Usage:  measure_controller_socket.py <dir-of-clones> [--json]
"""

import argparse
import collections
import json
import pathlib
import re

FOOTPRINT = re.compile(r'\((?:footprint|module)\s+"?([A-Za-z0-9_:.,\-/ ]+)')

# Socket signal: footprints named after a dev board. Every token is a board name,
# never a generic connector word -- a dev-board socket IS a pin header, and so is
# everything else on the board, so no generic header token can be used. Each maps
# to the FORM FACTOR, not the board: `elite_c`, `nice!nano`, `Sea-Picro` and
# `Frood` all fit the Pro Micro footprint, which is why `Mcu::BoardDependent` is
# derivable without knowing which board a builder chose.
SOCKET_TOKENS = {
    "pro_micro": ("promicro", "pro_micro", "pro-micro", "elite-c", "elitec",
                  "nice_nano", "nicenano", "sea-picro", "seapicro", "puchi",
                  "supermini", "frood", "helios", "liatris", "kb2040",
                  "proton_c", "protonc"),
    "seeed_xiao": ("xiao",),
    "blackpill": ("blackpill", "black_pill"),
    "bluepill": ("bluepill", "blue_pill"),
    "rpi_pico": ("rpi_pico", "rp_pico", "pi_pico", "pico_smd", "rpipico"),
    "rp2040_zero": ("rp2040_zero", "rp2040-zero", "rp2040zero"),
    # Backlog #99, 2026-09-25. `adafruit32u4ble` is how SpaceLib spells the Feather 32u4
    # Bluefruit LE on `prkl30` and `dropin-v1`; the first pattern had only `feather` and
    # missed both. Over every name in board-scan.json these two tokens reach three board
    # files, and all three are the dev board: those two, and `fruity60`, whose headers
    # sit in a library named "Adafruit Feather 32u4 Bluefruit LE".
    "feather": ("feather", "adafruit32u4ble"),
}

# Soldered signal: an MCU-sized IC package. Deliberately pin-count-bearing QFP
# and QFN only. Every non-MCU package in the sample is a different family and is
# excluded by construction: crkbd's `SOIC-8` flash, void_switch_65's five
# `SSOP-24`, 3w6's `TSSOP-24`. Widening this to a bare `soic|ssop` would put a
# socketed board (void_switch_65) on the soldered side.
#
# AMENDED 2026-08-28. The surface-mount list above misses THROUGH-HOLE MCUs entirely,
# and 36 rows were deferred as "nothing names a controller" partly because of it --
# dm9records/plaid, every coseyfannitutti board, basketweave. The pin count is the
# discriminator and the corpus separates cleanly on it:
#
#     DIP-40  x18   ATmega1284P            DIP-20  x1
#     DIP-28  x12   ATmega328P             DIP-18  x1, DIP-16 x1, DIP-14 x1, SMDIP-4 x1
#
# Nothing sits between 20 and 28, so `>= 28` is a gap in the data rather than a
# threshold picked to taste. Below it are optocouplers, logic and EEPROMs -- matching a
# bare `dip-` would put every one of them on the soldered side.
#
# `\bdip-` deliberately does not match `SMDIP-4`: `m` and `d` are both word characters,
# so there is no boundary inside `smdip`. A hypothetical SMDIP-40 would be missed, and
# the corpus has none.
#
# A DIP SOCKET still reads as soldered, which is not a contradiction: 8 of these are
# `DIP-40_W15.24mm_Socket`. The domain's `Socket` means a DEV BOARD plugs in and the
# builder picks the module; a DIP socket holds the CHIP the design chose. The design
# determines it either way, so `Soldered` is the honest side.
#
# THE DELIMITER IS `[-_]`, NOT `-`, amended 2026-09-02. KiCad footprint names use both
# and this one demanded a hyphen, so `breakout_pins:Dual_MCU_QFP_QFN_48_with_breakout_pins`
# read as no controller at all -- five board files, and it is the only footprint in the
# whole corpus the widening admits. Measured over all 3,362 distinct footprint names on
# 975 board files: the hyphen form matches 40, the `[-_]` form matches 41, and the one it
# adds is that footprint. So there is no false-positive surface to argue about -- the pin
# count still does the discriminating, and no non-MCU part in the corpus spells a
# QFP/QFN/DIP size with an underscore.
MCU_PACKAGE = re.compile(
    r"tqfp[-_]\d|lqfp[-_]\d|qfn[-_]\d|vqfn[-_]\d|\bqfp[-_]\d|"
    r"\bdip[-_](?:2[89]|[3-9]\d|\d{3,})", re.I)
# Parts that name themselves, which only 2 of the 10 soldered designs do.
#
# `rp2040` BARE, added 2026-09-02, and it is the first token here that CAN co-occur with
# a socket -- which the header above says never happens, so the exception is worth
# stating. `rp2040:RP2040` is the naked chip, on three board files (`ianklug/grooveboard`,
# beside a `Crystal_SMD_3225` the chip needs and a dev board would not). But the same
# token also appears in the libraries and names of a dozen RP2040 DEV BOARDS --
# `RP2040-Zero_SMD`, `XIAO-RP2040_Reversible`, `RP2040-ProMicro` -- and every one of
# those is already resolved by `socket_of`.
#
# It is safe because the SOCKET SIGNAL WINS, in this file and in `build_seed.py` alike:
# a positive socket verdict is taken before `soldered` is consulted, and all six form
# factors `socket_of` can return are in the importer's `FOOTPRINT` map, so none of them
# can fall through to the soldered branch. Measured: of the 16 distinct footprints naming
# rp2040, 11 already read as a socket, 4 as `RP2040-QFN-56` packages, and this admits
# exactly the remaining one.
MCU_NAMED = re.compile(r"atmega\d|atmega32u|stm32|rp2040|nrf52|mdbt50q", re.I)

# QMK's `development_board`, mapped to the form factor the design accepts. Ticket 27
# decision 3, the owner's ruling: a declared `development_board` IS a positive statement
# that the design takes a dev board, so it counts as a socket signal -- and for 73% of
# the records that declare one it is the only thing that can ever speak, because they
# name no third-party repo at all.
#
# THE BOARD FILE STILL WINS where both speak, because it is the design's own drawing.
# `clog` declares `elite_c` and draws `kbd:ProMicro_v3.5`; both are right, and the
# footprint is the more general truth -- `Socket` names a FORM FACTOR, never a board, so
# Elite-C, nice!nano, Sea-Picro, Frood and the RP2040 Pro Micros all land on `pro_micro`.
# That is why this table is six-to-one at the top and why `Mcu::BoardDependent` is
# derivable without knowing which board a builder actually bought.
#
# Values outside it are NOT forced to the nearest member: ZMK's `arduino_uno` and
# `makerdiary_nrf52840_m2` are not one of the domain's six, so a row naming one defers.
DEV_BOARD = {
    "promicro": "pro_micro", "elite_c": "pro_micro", "proton_c": "pro_micro",
    "promicro_rp2040": "pro_micro", "kb2040": "pro_micro", "elite_pi": "pro_micro",
    "bluepill": "bluepill",
    "blackpill_f401": "blackpill", "blackpill_f411": "blackpill",
}

# THE CHIP, once the board file has already established that there IS one. A different
# question from `MCU_PACKAGE` above, and the difference is the whole reason both exist:
# that pattern asks "is an MCU-sized package placed here", this asks "does the part name
# itself". `TQFP-44_10x10mm_P0.8mm` answers the first and must never answer the second --
# it is very probably an ATmega32U4 and probability is not provenance, which is
# `Controller.Soldered`'s own doc comment and ticket 27's measurement.
#
# WIDENED 2026-09-07 from three members to fourteen, which is what the corpus actually
# names. The old comment here read "a widen is the owner's, and a nearest-member fit is
# the default-fill this refuses" -- the second half still stands and is why this is a
# closed list rather than a regex over anything chip-shaped. Measured across every source
# this build reads: atmega32u4 182, RP2040 45, STM32F072 16, atmega32a 13, atmega328p 12,
# atmega32u2 11, STM32F401 9, STM32F411 7, STM32F103 2, and one each of STM32F042,
# atmega16u2, STM32G431, STM32F303. `nrf52840` has no QMK declaration in this corpus and
# stays because ZMK designs carry it and the board files name it. `stm32g0b1` joined
# 2026-09-29: Pando's own record declares it, and its page read "chip not recorded".
#
# LONGEST ALTERNATIVE FIRST, and it is not cosmetic: `atmega32u4` and `atmega32a` share a
# prefix, so a regex listing the short one first matches `atmega32` inside `atmega32u4`
# and silently mislabels every 32u4 in the corpus as a 32a. Python's `re` alternation is
# leftmost-first, not longest-match.
CHIPS = [
    "atmega32u4", "atmega32u2", "atmega328p", "atmega16u2", "atmega32a",
    "rp2040", "nrf52840",
    "stm32f072", "stm32f042", "stm32f103", "stm32f303", "stm32f401", "stm32f411",
    "stm32g0b1", "stm32g431",
]
SELF_NAMED_CHIP = re.compile("|".join(sorted(CHIPS, key=len, reverse=True)), re.I)

# QMK's `processor`, the honest second source -- `Controller.Soldered` names it as such
# and calls it "a separate pass". `qmk_metadata.processor` lower-cases what it returns, so
# this is an identity map over the vocabulary and exists to REJECT anything outside it: a
# chip QMK names that the domain has no member for must stay `Unknown` rather than being
# fitted to its nearest neighbour.
PROCESSOR = {c: c for c in CHIPS}


def chip_of(full_name):
    """The chip a footprint names ITSELF, or None. Never the package."""
    hit = SELF_NAMED_CHIP.search(bare(full_name))
    return hit.group(0).lower() if hit else None


def chip_in_bom(part):
    """The chip a BOM row states, or None.

    A BOM row is a STATEMENT and a footprint is a description, so this reads the
    whole field rather than a bare name -- `RP2040` in a JLCPCB `Comment` column is
    the author telling a fab what to place. Still only the three the domain
    enumerates: measured over the 35 published designs whose BOM could be joined to
    their board, the parts named outside them are `ATmega32U2` x3, `ATmega328P` x2,
    `STM32F072` x2, `STM32G431` and a bare `AVR`.
    """
    hit = SELF_NAMED_CHIP.search(part)
    return hit.group(0).lower() if hit else None


# The ticket's hypothesis, kept ONLY so its falsification stays reproducible.
# These are not used in any verdict.
FALSIFIED_NEGATIVE = {
    "crystal": re.compile(r"crystal|xtal|resonator", re.I),
    "regulator": re.compile(r"regulator|sot-223|sot-89|ap2112|xc6206", re.I),
}


def bare(full_name):
    return full_name.split(":")[-1].strip().lower()


def socket_of(full_name):
    """The form factor a footprint names, or None.

    The BARE name decides, and the library name is consulted only when the bare name
    says nothing. That order matters: a library is a bag of parts and its name describes
    the bag, so letting it outvote the footprint would call a diode in `promicro.pretty`
    a controller.

    As a fallback it is measured safe. Across all 973 board files exactly ONE footprint
    has a library naming a socket while its own name does not, and it is a typo in the
    repo -- `promicro:ProMico_mini`, missing the `r`. Zero false positives, one design
    recovered.
    """
    name = bare(full_name)
    for form_factor, tokens in SOCKET_TOKENS.items():
        if any(token in name for token in tokens):
            return form_factor
    library = full_name.split(":")[0].strip().lower() if ":" in full_name else ""
    for form_factor, tokens in SOCKET_TOKENS.items():
        if any(token in library for token in tokens):
            return form_factor
    return None


def scan_design(root):
    placements = collections.Counter()
    for path in root.rglob("*.kicad_pcb"):
        try:
            placements.update(placed_names(path.read_text(errors="ignore")))
        except OSError:
            continue

    sockets, packages, negative = {}, {}, {}
    for full_name, placed in placements.items():
        form_factor = socket_of(full_name)
        if form_factor:
            sockets.setdefault(form_factor, {})[full_name.strip()] = placed
            continue
        if MCU_PACKAGE.search(bare(full_name)) or MCU_NAMED.search(bare(full_name)):
            packages[full_name.strip()] = placed
        for label, pattern in FALSIFIED_NEGATIVE.items():
            if pattern.search(full_name):
                negative.setdefault(label, {})[full_name.strip()] = placed

    # Socket wins over package. Unexercised in the sample -- no design places
    # both -- but specified so a board carrying a socket and an IO expander in a
    # QFN cannot render "the MCU is soldered", which is the expensive direction.
    if sockets:
        verdict = "Socket{" + ",".join(sorted(sockets)) + "}"
    elif packages:
        verdict = "Soldered"
    elif placements:
        verdict = "Unknown (board file resolved to neither -- LEXICON GAP)"
    else:
        verdict = "Unknown (no board file)"

    return {
        "design": root.name,
        "verdict": verdict,
        "readable_board": bool(placements),
        "sockets": sockets,
        "mcu_packages": packages,
        "falsified_negative": negative,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=pathlib.Path, help="directory of design clones")
    parser.add_argument("--json", action="store_true", help="emit JSON")
    args = parser.parse_args()
    if not args.root.is_dir():
        parser.error(f"{args.root} is not a directory")

    results = [scan_design(child) for child in sorted(args.root.iterdir()) if child.is_dir()]

    if args.json:
        json.dump(results, __import__("sys").stdout, indent=2)
        print()
        return

    counts = collections.Counter()
    for result in results:
        kind = result["verdict"].split("{")[0].split(" (")[0]
        counts[kind] += 1
        flags = []
        if result["falsified_negative"] and "Socket" in result["verdict"]:
            flags.append("+" + ",".join(sorted(result["falsified_negative"])) + " ON A SOCKET")
        print(f"{result['design']:33s} {result['verdict']:46s} {' '.join(flags)}")

    readable = sum(r["readable_board"] for r in results)
    print(f"\n{len(results)} designs, {readable} with a readable board file")
    for kind, n in counts.most_common():
        print(f"  {kind:10s} {n}")
    gaps = [r["design"] for r in results if "LEXICON GAP" in r["verdict"]]
    print(f"  lexicon gaps: {len(gaps)} {gaps}")


if __name__ == "__main__":
    main()
