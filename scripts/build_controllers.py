#!/usr/bin/env python3
"""Turn seed/controllers.toml and seed/firmwares.toml into the JSON the site reads at startup.

Same split `build_seed.py` draws between `seed/` and `src/main/resources/seed/`:
`seed/controllers.toml` is what a human (here, a research pass) states, and the JSON
under `resources` is a build output nobody hand-edits. Its own file, not folded into
`build_seed.py`, because the controller catalogue is independent of the corpus -- it
does not change when a design is added, only when the research itself does.

BOTH CATALOGUES IN ONE SCRIPT, on purpose: the boards' `firmware` column and the
firmwares' `mcu_families_excluded` are two halves of one claim, and the cross-check below
can only run if one process has read both. Two scripts would let a board and a firmware
disagree with nothing to notice.

Usage: build_controllers.py [--seed seed/controllers.toml]
                             [--out src/main/resources/seed/controllers.json]
                             [--firmware-seed seed/firmwares.toml]
                             [--firmware-out src/main/resources/seed/firmwares.json]
"""
import argparse
import json
import pathlib
import tomllib

REPO = pathlib.Path(__file__).resolve().parent.parent

# The families `FirmwareRef.familyKey()` produces, plus the one custom family the catalogue
# actually names. A typo here is a board nobody can see.
FIRMWARE = {"qmk", "zmk", "kmk", "bluemicro"}

# `McuFamily`'s members plus the explicit `unknown`. Kept in the same shape as FIRMWARE and
# checked for the same reason, one failure mode sharper: `mcu_family` is what
# `ControllerFit.kt` reads to RULE A DESIGN OUT, so a misspelling does not blank a page --
# it silently promotes every ruled-out design back into "shares the footprint", which reads
# as a working page. `unknown` is spelled out rather than allowed as an absent key so that
# a typo'd field name fails here instead of becoming an Unknown nobody chose.
MCU_FAMILIES = {"avr8", "rp2040", "nrf52", "stm32", "samd21", "unknown"}

# `IMPOSSIBLE = {("qmk","nrf52"), ("zmk","avr8")}` USED TO LIVE HERE and is gone. It was
# a third copy of two rules that also existed in `ControllerFit.FirmwareBar` and in
# `controller-catalogue.md` §3.4's table -- and the copies had already drifted (the §3.4
# table misquoted ZMK's FAQ, and that misquote was copied into Kotlin labelled
# "verbatim"). The rules now live once, as data, in `seed/firmwares.toml` with a source
# per claim, and `impossible_pairs()` below derives this set from that file.
#
# The cross-check itself stays, because the research doc asked for it (§3.3: "a
# consistency check against MCU family would catch data-entry errors") and because the
# error it catches is the nastiest available here: a board asserting a firmware its own
# chip cannot run does not fail at startup and does not blank a page. It publishes a
# design as buildable when it is not, on the one field a builder shops from.
WIRELESS_STATES = {"yes", "no", "unknown"}

# The receptacle ON a dev board, backlog #97. `usb` is optional -- omitted is Unknown, and
# `seed/controllers.toml`'s header says why that is honest -- but a value present must be
# one of these, or a typo would read as a board with no known port.
USB_PORTS = {"usb_c", "micro_b", "mini_b"}


def impossible_pairs(firmwares):
    """(firmware, mcu_family) pairs no board may claim, straight off the catalogue."""
    return {(f["family"], fam) for f in firmwares for fam in f["mcu_families_excluded"]}


def check_firmwares(firmwares, path):
    families = [f["family"] for f in firmwares]
    if len(set(families)) != len(families):
        raise SystemExit(f"duplicate family in {path}")
    unknown = set(families) - FIRMWARE
    if unknown:
        raise SystemExit(f"{path}: family {sorted(unknown)} not in {sorted(FIRMWARE)}")
    for f in firmwares:
        for key in ("mcu_families_supported", "mcu_families_excluded"):
            if key not in f:
                raise SystemExit(f"{f['family']}: no {key} -- state an empty list rather than omitting it")
            bad = set(f[key]) - (MCU_FAMILIES - {"unknown"})
            if bad:
                raise SystemExit(f"{f['family']}.{key}: unknown mcu family {sorted(bad)}")
        # THE CHECK THIS FILE MOST NEEDS. A family in both lists is a firmware that both
        # runs and cannot run on a chip; whichever half is wrong, the site would publish
        # one of them as fact. It cannot be caught by reading either list alone.
        both = set(f["mcu_families_supported"]) & set(f["mcu_families_excluded"])
        if both:
            raise SystemExit(
                f"{f['family']}: {sorted(both)} is in BOTH mcu_families_supported and "
                f"mcu_families_excluded -- one of the two citations is wrong"
            )
        if f.get("wireless") not in WIRELESS_STATES:
            raise SystemExit(
                f"{f['family']}: wireless is {f.get('wireless')!r}, not one of "
                f"{sorted(WIRELESS_STATES)} -- 'unknown' is a real state and must be stated"
            )
        if not f.get("source"):
            raise SystemExit(f"{f['family']}: no source -- every claim in this file cites one")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed", default="seed/controllers.toml")
    ap.add_argument("--out", default="src/main/resources/seed/controllers.json")
    ap.add_argument("--firmware-seed", default="seed/firmwares.toml")
    ap.add_argument("--firmware-out", default="src/main/resources/seed/firmwares.json")
    args = ap.parse_args()

    rows = tomllib.loads((REPO/args.seed).read_text())["entry"]
    firmwares = tomllib.loads((REPO/args.firmware_seed).read_text())["entry"]
    check_firmwares(firmwares, args.firmware_seed)
    impossible = impossible_pairs(firmwares)

    # Checked here rather than left to a startup exception, and `firmware` is checked at all
    # because an empty or misspelled set does not crash anything -- it silently removes a
    # board from every design that sockets its footprint, which reads as a research gap. The
    # 2026-09-04 RP2040 correction (docs/research/controller-catalogue.md §3.4) was the
    # value-level version of that failure: 16 pages said "nothing runs your firmware".
    slugs = [row["slug"] for row in rows]
    if len(set(slugs)) != len(slugs):
        raise SystemExit(f"duplicate slug in {args.seed}: a URL is a promise")
    for row in rows:
        if not row.get("firmware"):
            raise SystemExit(f"{row['slug']}: empty firmware -- no design would ever see this board")
        unknown = set(row["firmware"]) - FIRMWARE
        if unknown:
            raise SystemExit(f"{row['slug']}: unknown firmware {sorted(unknown)}, not in {sorted(FIRMWARE)}")
        if "mcu_family" not in row:
            raise SystemExit(f"{row['slug']}: no mcu_family -- state 'unknown' rather than omitting it")
        if row["mcu_family"] not in MCU_FAMILIES:
            raise SystemExit(
                f"{row['slug']}: unknown mcu_family {row['mcu_family']!r}, not in {sorted(MCU_FAMILIES)}"
            )
        if "usb" in row and (not row["usb"] or set(row["usb"]) - USB_PORTS):
            raise SystemExit(
                f"{row['slug']}: usb {row['usb']!r} -- each must be one of {sorted(USB_PORTS)}, "
                f"and an unknown port is an omitted key, not an empty list"
            )
        for firmware in row["firmware"]:
            if (firmware, row["mcu_family"]) in impossible:
                raise SystemExit(
                    f"{row['slug']}: claims {firmware} on a {row['mcu_family']} chip, which that "
                    f"firmware's own documentation rules out (seed/firmwares.toml) -- one of "
                    f"the two fields is wrong"
                )

    out = REPO/args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rows, indent=1))

    firmware_out = REPO/args.firmware_out
    firmware_out.write_text(json.dumps(firmwares, indent=1))

    print(f"{len(firmwares)} firmwares -> {args.firmware_out}")
    for f in firmwares:
        print(f"  {f['family']:<10} wireless={f['wireless']:<8}"
              f"runs={','.join(f['mcu_families_supported']) or '-':<28}"
              f"never={','.join(f['mcu_families_excluded']) or '-'}")
    print(f"{len(rows)} controller boards -> {args.out}")
    by_footprint = {}
    for row in rows:
        by_footprint.setdefault(row["footprint"], []).append(row["name"])
    for footprint, names in sorted(by_footprint.items()):
        print(f"  {footprint:<12} {len(names)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
