"""The hardest component on each design's board, across the whole corpus.

Follow-up to Forgejo #16, which measured only the MCU package and so answered a third
of the question. SOLDERING DIFFICULTY IS A PROPERTY OF THE WHOLE DESIGN: it is `max()`
over every component someone has to solder, and for the 139 SOCKETED designs the MCU
contributes nothing at all -- reviung41 takes a Pro Micro, so the hardest thing on it
is an LED.

THE TIERS BELOW ARE A JUDGEMENT, NOT A MEASUREMENT, and they are spelled out here so
they can be argued with and changed in one place. What is measured is which footprints
each board carries; what is ruled is how hard each one is.

READS THE DESIGN'S OWN BOARDS, VIA `attributed_boards.py`, and that is a correction
rather than a refactor. This used to take a clone root and `rglob` every `.kicad_pcb`
in the repo, re-deriving the slug-to-board attribution by scoring slug tokens against
paths -- so a design's verdict could be set by a NEIGHBOUR's board. `attributed_boards.py`
carries the measurement; 15 of 228 designs were wrong that way in the sibling scan.

Two rules that are not obvious and that the corpus forced:

BUILDER-CHOICE FOOTPRINTS TAKE THE EASIER TIER. `diode_do35_sod123`, `diode-hybrid-back`
and `usb_hybrid_tht` accept a through-hole part OR a surface-mount one; the builder
picks. Scoring them at the harder tier would report a board as hard when the easy build
is the one most people do.

A FOOTPRINT IS NOT AN OBLIGATION. Per-key RGB is frequently optional -- the board works
without it -- and this scan cannot tell an optional LED from a required one. So the
number below is the hardest part PRESENT, which is an upper bound on the hardest part
REQUIRED. That gap is real and is reported rather than hidden.
"""
import argparse
import collections
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from attributed_boards import REPO, SCRATCH, load  # noqa: E402
from measure_controller_socket import bare, socket_of  # noqa: E402


# Ordinal, worst wins. The names are the builder's question, not the datasheet's.
TIERS = ["through_hole", "smd_passive", "smd_hand", "smd_fine"]

# Builder chooses; the easy option is available. Checked FIRST, so it wins over the
# surface-mount half of its own name.
HYBRID = re.compile(r"hybrid|do35_sod123|do-35_sod-123|smd_tht|tht_smd|th_smd"
                    r"|sod123_axial", re.I)

RULES = [
    # (tier, pattern) -- first match wins, so order is the ruling.
    # TRRS/RJ45 jacks, 2.54mm pin headers and EC11 rotary encoders joined 2026-09-11:
    # these are through-hole connectors this lexicon had no token for at all, so a
    # board naming only one of them -- e.g. `foostan/cradio`'s `MJ-4PP-9` jack plus a
    # switch and a reset button -- fell out of `parts_of` empty and read `unmeasured`
    # rather than the easy board it is. Measured against the 56 unmeasured designs of
    # 2026-09-11: 44 carry one of these four tokens and nothing harder; checked against
    # every OTHER design's footprints first and none of these four tokens collides with
    # an existing smd_* tier, so nothing gets demoted.
    ("through_hole", re.compile(r"do-3[45]|\bdo35\b|led_d\d|_tht\b|\bdip-\d|axial|p7\.62mm|p5\.08mm"
                                r"|trrs|pj-3\d\d|pj320|mj-4pp|rj45|p2\.54mm|rotaryencoder|encoder_alps|\bec11", re.I)),
    # `2020` is anchored to an LED family on purpose: a bare `2020` matched
    # `sw_spdt_js202011aqn`, a slide switch, and put its whole design in the top tier.
    ("smd_fine",     re.compile(r"qfn-\d|vqfn-\d|sod-882|0402|1005metric"
                                r"|(?:ws2812|sk6812)[a-z]*-?2020"
                                r"|usb_c|type-c|sot-23-6|usblc6", re.I)),
    ("smd_hand",     re.compile(r"sod-123|sod-323|sk6812|ws2812|tqfp-\d|lqfp-\d|\bqfp-\d|sot-23|usb_mini|usb-mini|usb_b_micro|plcc4", re.I)),
    ("smd_passive",  re.compile(r"0805|0603|1206|1608metric|2012metric", re.I)),
    # LAST-RESORT FALLBACKS, checked only once every named-package rule above has
    # missed -- backlog 2026-09-17, found via `span` claiming "no SMD soldering"
    # with an SMD diode on its own board. `D3_SMD_v2` and `Crystal_SMD_3225-4Pin_…`
    # say SMD outright but name no package this lexicon knows, so they fell through
    # `tier_of` as if they were not solderable parts at all -- not `Unknown`, just
    # invisible, which is how the board's one recognised footprint (a TRRS jack)
    # won the "hardest part" question by default. Measured against the corpus: 100
    # distinct unrecognised `smd`-named footprints across 131 designs, of which 13
    # flip from a confident "no SMD" to a real SMD tier once these two lines exist
    # (`span`, `sweeq`, `sweeq-mx`, `ajisai74`, `andean-condor`, `jiran-cherry`,
    # `jiran-hotswap`, `la-nc`, `lancer-hotswap`, `molecule`, `scatter42`,
    # `wired-pierce`, `xr63-gl`) -- see backlog.md for the full measurement.
    #
    # FPC connectors are checked first and land one tier higher: a 0.5-1.0mm-pitch
    # flex-cable connector is genuinely fine work, the same class the named
    # `usb_c`/`type-c` rule above already puts at `smd_fine`, not a guess at the
    # generic fallback's tier.
    ("smd_fine", re.compile(r"fpc", re.I)),
    # A BARE "smd" WITH NO PACKAGE CODE IS A JUDGEMENT, NOT A MEASUREMENT, same as
    # every tier above it -- `smd_hand` matches the corpus's existing SOD-123/SOT-23
    # tier, the smallest defensible guess above "definitely not through-hole" for a
    # name that states surface-mount and nothing more specific. Argue with this one
    # first if it turns out wrong.
    ("smd_hand", re.compile(r"smd", re.I)),
]

# What KIND of part it is, for reporting which class sets the maximum.
CLASS = [
    ("mcu",     re.compile(r"tqfp-\d|lqfp-\d|qfn-\d|vqfn-\d|\bqfp-\d|\bdip-(?:2[89]|[3-9]\d)", re.I)),
    # `type-c` and `usblc6` carry no "usb" in the name but are the USB parts; `sot-23-6`
    # on a keyboard is overwhelmingly the USB protection IC, so it is reported as `ic`
    # rather than guessed into `usb`.
    ("usb",     re.compile(r"usb|type-c", re.I)),
    ("led",     re.compile(r"sk6812|ws2812|neopixel|(?<!o)led_", re.I)),
    ("diode",   re.compile(r"\bd_|diode|sod-\d|do-3[45]", re.I)),
    ("passive", re.compile(r"^[rcl]_\d|_\d{4}_\d+metric|^[rcl]_\d{4}", re.I)),
    ("ic",      re.compile(r"sot-\d|soic|ssop|tssop", re.I)),
    # Joined 2026-09-11 alongside the `through_hole` tokens of the same name -- without
    # these, a design whose ONLY solderable part is a TRRS jack (`cantor`: one footprint,
    # `trrs-pj-320a`) reported "hardest: through-hole (other)", which answers WHAT tier
    # and throws away WHAT PART, the one thing `other` cannot mean anything useful for.
    # Measured: 50 of the corpus's 52 `other`-only designs are exactly these two kinds.
    ("connector", re.compile(r"trrs|pj-3\d\d|pj320|mj-4pp|rj45|p2\.54mm", re.I)),
    ("encoder",   re.compile(r"rotaryencoder|encoder_alps|\bec11", re.I)),
    ("other",   re.compile(r".", re.I)),
]

def tier_of(name):
    if HYBRID.search(name):
        return "through_hole"
    for tier, rx in RULES:
        if rx.search(name):
            return tier
    return None

def class_of(name):
    for kind, rx in CLASS:
        if rx.search(name):
            return kind
    return "other"

def parts_of(names):
    """Every solderable part on one design's boards, as `(tier, class, name)`."""
    out = []
    for full in names:
        # A dev-board socket is headers, not the part it names.
        if socket_of(full):
            continue
        name = bare(full)
        tier = tier_of(name)
        if tier:
            out.append((tier, class_of(name), name))
    return out


def main():
    rows, names_for = load()
    verdict, drivers, per = collections.Counter(), collections.Counter(), []
    for design in rows:
        slug = design["slug"]
        found = parts_of(names_for(slug))
        if not found:
            verdict["unmeasured"] += 1
            per.append({"slug": slug, "hardest": None})
            continue
        worst = max(TIERS.index(t) for t, _, _ in found)
        hardest = TIERS[worst]
        driving = sorted({(k, n) for t, k, n in found if TIERS.index(t) == worst})
        verdict[hardest] += 1
        for kind in {k for k, _ in driving}:
            drivers[(hardest, kind)] += 1
        per.append({"slug": slug, "hardest": hardest,
                    "driven_by": sorted({k for k, _ in driving}),
                    "footprints": [n for _, n in driving][:6]})
    return rows, verdict, drivers, per


if __name__ == "__main__":
    argparse.ArgumentParser(description=__doc__,
                            formatter_class=argparse.RawDescriptionHelpFormatter
                            ).parse_args()
    rows, verdict, drivers, per = main()
    n = len(rows)
    print(f"  hardest component present, {n} designs\n")
    for t in TIERS + ["unmeasured"]:
        bar = "#" * (verdict[t] * 40 // n)
        print(f"  {t:<14}{verdict[t]:>5}  {verdict[t]*100//n:>3}%  {bar}")
    print("\n  what sets the maximum:")
    for (t, k), c in sorted(drivers.items(), key=lambda x: -x[1])[:12]:
        print(f"     {t:<13} {k:<7} {c}")
    out = SCRATCH/"build-difficulty-scan.json"
    out.write_text(json.dumps(per, indent=1))
    print(f"\n  {len(per)} designs -> {out.relative_to(REPO)}")
