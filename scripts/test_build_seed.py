#!/usr/bin/env python3
"""Tests for the small pure rules inside `build_seed.py`'s passes.

`build_seed.main()` is one long pass over the whole corpus and is checked by its output
(`designs.json`, read by the Kotlin tests); the rules here are the parts of it that
decide a single design's field from facts already in hand, pulled out so each decision
can be stated as a case. Run from the repo root:

    python3 -m unittest discover -s scripts -p 'test_*.py'
"""
import unittest

import build_seed
from firmware_records import BuiltFor


class FamilyCredits(unittest.TestCase):
    """A `{kind = "family"}` credit (owner, 2026-11-25) must name a confirmed family:
    a misspelt one would silently drop from the page, the failure families.toml's own
    strictness exists to stop."""

    def test_a_credit_naming_a_known_family_passes(self):
        build_seed.check_family_credits({"lime": [{"kind": "family", "name": "Corne"}]}, {"Corne"})

    def test_a_credit_naming_no_family_stops_the_build(self):
        with self.assertRaises(SystemExit):
            build_seed.check_family_credits({"lime": [{"kind": "family", "name": "Crone"}]}, {"Corne"})

    def test_held_and_external_credits_are_not_its_business(self):
        build_seed.check_family_credits(
            {"x": [{"kind": "held", "slug": "a"}, {"kind": "external", "name": "Dactyl", "url": "u"}]}, set())


class SplitReadingHoldsTheKeys(unittest.TestCase):
    """A split shield's wiring is one half's scan; two halves must reach the design's keys."""

    def test_lily58_fits_in_two_five_by_six_halves(self):
        self.assertTrue(build_seed.split_reading_holds(
            ("matrix", 5, 6, "COL2ROW"), [{"keys": 58}]))

    def test_a_layout_bigger_than_both_halves_is_refused(self):
        self.assertFalse(build_seed.split_reading_holds(
            ("matrix", 4, 6, "COL2ROW"), [{"keys": 36}, {"keys": 50}]))

    def test_the_sweep_fits_exactly_on_two_seventeen_pin_halves(self):
        self.assertTrue(build_seed.split_reading_holds(("direct", 17), [{"keys": 34}]))

    def test_one_key_past_two_direct_halves_is_refused(self):
        self.assertFalse(build_seed.split_reading_holds(("direct", 17), [{"keys": 35}]))


class SharedRecordTarget(unittest.TestCase):
    """What a QMK record that several designs cite is built for, said of one of them."""

    SOLDERED = {"kind": "soldered", "mcu": "atmega32u4"}

    def test_a_soldered_board_that_names_the_records_chip_gets_the_target(self):
        # grooveboard-atmel-au: the board places an ATmega32U4, the shared record builds for one.
        self.assertEqual(build_seed.shared_record_target(
            self.SOLDERED, "atmega32u4", "atmega32u4"), "atmega32u4")

    def test_a_soldered_board_with_a_different_chip_gets_none(self):
        # pisces-v2 shape: an ATmega32U2 on the board, a record built for something else.
        self.assertIsNone(build_seed.shared_record_target(
            {"kind": "soldered", "mcu": "atmega32u2"}, "atmega32u2", "atmega32u4"))

    def test_a_board_that_names_no_chip_gets_none(self):
        # The design's `mcu` may itself have come from this very record (QMK's
        # `processor` is `soldered_chip`'s second source), so agreeing with it would be
        # the record agreeing with itself. Only the board's own drawing or BOM counts.
        self.assertIsNone(build_seed.shared_record_target(
            self.SOLDERED, None, "atmega32u4"))

    def test_a_socketed_design_gets_none(self):
        # The builder picks the module; a shared record says nothing about which.
        self.assertIsNone(build_seed.shared_record_target(
            {"kind": "socket", "footprints": ["pro_micro"]}, None, "atmega32u4"))

    def test_a_record_that_names_no_chip_gets_none(self):
        self.assertIsNone(build_seed.shared_record_target(self.SOLDERED, "atmega32u4", None))

    def test_a_published_mcu_that_disagrees_with_the_board_gets_none(self):
        # Belt and braces: the board's chip and the published one should never differ,
        # and if they ever do the page's own MCU line would contradict the target.
        self.assertIsNone(build_seed.shared_record_target(
            {"kind": "soldered", "mcu": "rp2040"}, "atmega32u4", "atmega32u4"))


class BoardSizes(unittest.TestCase):
    """seed/board-sizes.toml, as designs.json carries it: known fields only."""

    TOML = """
[[entry]]
slug        = "solo"
repo        = "a/solo"
drawn       = "half"
width_mm    = 120.5
height_mm   = 95.0
layers      = 2
paths       = ["pcb/solo.kicad_pcb"]
measured_on = 2026-10-04

[[entry]]
slug          = "lopsided"
repo          = "a/lopsided"
drawn         = "half"
half_sizes_mm = [[124.42, 80.37], [129.18, 80.37]]
layers        = 2
paths         = ["left.kicad_pcb", "right.kicad_pcb"]
measured_on   = 2026-10-04

[[entry]]
slug        = "tabbed"
repo        = "a/tabbed"
drawn       = "both halves"
layers      = 2
unknown     = "both halves are drawn in one outline"
paths       = ["ferris.kicad_pcb"]
measured_on = 2026-10-04

[[entry]]
slug        = "versions"
repo        = "a/versions"
layers      = 2
unknown     = "board files differ in size: v1 100.0 x 50.0, v2 120.0 x 50.0"
paths       = ["v1.kicad_pcb", "v2.kicad_pcb"]
measured_on = 2026-10-04

[[entry]]
slug        = "renamed-since"
unknown     = "no attributed board file"
measured_on = 2026-10-04

[[plate]]
slug           = "solo"
repo           = "a/solo"
path           = "plate/solo-plate.kicad_pcb"
role           = "switch plate"
drawn          = "half"
width_mm       = 121.0
height_mm      = 96.0
turned         = true
pieces         = 1
switch_cutouts = 29
layers         = 2
measured_on    = 2026-10-04

[[plate]]
slug        = "solo"
repo        = "a/solo"
path        = "plate/old-plate.kicad_pcb"
unknown     = "no outline drawn on Edge.Cuts"
measured_on = 2026-10-04
"""

    def sizes(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".toml", delete=False) as f:
            f.write(self.TOML)
        return build_seed.board_sizes(f.name)

    def test_a_measured_design_carries_its_size_and_layers(self):
        pcb, _ = self.sizes()
        self.assertEqual(pcb["solo"], {"drawn": "half", "widthMm": 120.5, "heightMm": 95.0,
                                       "copperLayers": 2})

    def test_halves_of_different_sizes_are_each_given(self):
        pcb, _ = self.sizes()
        self.assertEqual(pcb["lopsided"], {
            "drawn": "half", "copperLayers": 2,
            "halves": [{"widthMm": 124.42, "heightMm": 80.37},
                       {"widthMm": 129.18, "heightMm": 80.37}]})

    def test_what_a_file_draws_is_kept_when_the_size_is_unknown(self):
        pcb, _ = self.sizes()
        self.assertEqual(pcb["tabbed"], {"drawn": "both halves", "copperLayers": 2})

    def test_an_unknown_size_is_absent_not_zero(self):
        pcb, _ = self.sizes()
        self.assertEqual(pcb["versions"], {"copperLayers": 2})
        self.assertNotIn("renamed-since", pcb)

    def test_plates_keep_their_path_role_and_only_what_was_measured(self):
        _, plates = self.sizes()
        self.assertEqual(plates["solo"], [
            {"path": "plate/old-plate.kicad_pcb"},
            {"path": "plate/solo-plate.kicad_pcb", "role": "switch plate", "drawn": "half",
             "widthMm": 121.0, "heightMm": 96.0, "copperLayers": 2},
        ])

    def test_no_file_is_no_sizes(self):
        self.assertEqual(build_seed.board_sizes("/nonexistent/board-sizes.toml"), ({}, {}))


class BorrowedFirmwareWiring(unittest.TestCase):
    """A design that borrows another's firmware does not inherit that board's wiring.

    Chocofi runs Corne's ZMK shield, which scans 6 columns; Chocofi's own board wires 5.
    """

    def test_a_design_running_its_own_record_reads_its_wiring(self):
        self.assertTrue(build_seed.wiring_is_its_own({"family": "zmk", "shield": "temper"}))

    def test_a_borrowed_record_says_nothing_about_this_boards_wiring(self):
        self.assertFalse(build_seed.wiring_is_its_own(
            {"family": "zmk", "shield": "corne", "_borrowed": True}))


if __name__ == "__main__":
    unittest.main()


class ReadAttributions(unittest.TestCase):
    """seed/boards.toml: one firmware record may run on several boards, one row each."""

    def row(self, boards, name="", verdict="confirmed", tree="zmk", rid="osprette"):
        return {"tree": tree, "id": rid, "boards": boards, "name": name, "verdict": verdict}

    def test_two_rows_for_one_record_are_two_designs(self):
        # 2026-11-16: the osprette shield runs on v1 and on the Osprangle. Keyed by record
        # alone, the second row silently replaced the first.
        got = build_seed.read_attributions([
            self.row(["v1/pcb/osprette.kicad_pcb"], "osprette v1"),
            self.row(["osprangle/pcb/osprangle.kicad_pcb"], "Osprangle"),
        ])
        self.assertEqual(got[("zmk", "osprette")], [
            ({"v1/pcb/osprette.kicad_pcb"}, "osprette v1"),
            ({"osprangle/pcb/osprangle.kicad_pcb"}, "Osprangle"),
        ])

    def test_only_confirmed_rows_with_boards_count(self):
        got = build_seed.read_attributions([
            self.row(["a.kicad_pcb"], verdict="drafted"),
            self.row([]),
        ])
        self.assertEqual(got, {})

    def test_one_record_claiming_one_board_twice_stops_the_build(self):
        with self.assertRaises(SystemExit):
            build_seed.read_attributions([
                self.row(["v1/pcb/osprette.kicad_pcb"], "a"),
                self.row(["v1/pcb/osprette.kicad_pcb"], "b"),
            ])

    def test_two_records_may_share_a_board(self):
        # qmk:clog and zmk:clog both run on Clog V1 -- that is one design, merged later.
        got = build_seed.read_attributions([
            self.row(["v1/pcb/clog.kicad_pcb"], "clog v1", tree="qmk", rid="clog"),
            self.row(["v1/pcb/clog.kicad_pcb"], "clog v1", tree="zmk", rid="clog"),
        ])
        self.assertEqual(len(got), 2)


class SocketContradicts(unittest.TestCase):
    """A record built for a Pro Micro does not run on a board with no Pro Micro socket.

    `built` is what `firmware_records` reads off the record: an Elite-C QMK record and a
    `requires: [pro_micro]` shield both say `socket="pro_micro"`."""

    PRO_MICRO_SHIELD = BuiltFor("pro_micro", None, True)
    ELITE_C_RECORD = BuiltFor("pro_micro", "atmega32u4", True)
    BLACK_PILL_RECORD = BuiltFor("blackpill", None, True)
    NAMES_NO_SOCKET = BuiltFor(None, None, True)

    def test_clog_v2s_xiao_socket_refuses_the_pro_micro_shield(self):
        # 2026-11-16: Clog V2 and V4 take a Xiao; the clog shield requires pro_micro.
        self.assertTrue(build_seed.socket_contradicts(self.PRO_MICRO_SHIELD, ["seeed_xiao"]))

    def test_a_qmk_elite_c_record_is_a_pro_micro_record(self):
        self.assertTrue(build_seed.socket_contradicts(self.ELITE_C_RECORD, ["seeed_xiao"]))

    def test_a_pro_micro_socket_takes_it(self):
        self.assertFalse(build_seed.socket_contradicts(self.ELITE_C_RECORD, ["pro_micro"]))
        self.assertFalse(build_seed.socket_contradicts(self.PRO_MICRO_SHIELD,
                                                       ["feather", "pro_micro"]))

    def test_a_board_naming_no_socket_is_unknown_not_a_contradiction(self):
        self.assertFalse(build_seed.socket_contradicts(self.ELITE_C_RECORD, []))

    def test_a_record_naming_no_pro_micro_is_left_alone(self):
        self.assertFalse(build_seed.socket_contradicts(self.BLACK_PILL_RECORD, ["pro_micro"]))
        self.assertFalse(build_seed.socket_contradicts(self.NAMES_NO_SOCKET, ["seeed_xiao"]))


class ContradictsBoard(unittest.TestCase):
    """On a board that solders its chip, a record for some other controller goes."""

    def test_corne_v1s_pro_micro_record_is_not_corne_v4s(self):
        # `crkbd/rev1` declares `promicro`; the v4 PCB places its own RP2040.
        self.assertTrue(build_seed.contradicts_board(
            BuiltFor("pro_micro", "atmega32u4", True), "rp2040"))

    def test_a_record_for_another_chip_goes(self):
        # `crkbd/r2g` is Mechboards' ATmega32U4 PCB, not foostan's RP2040 one.
        self.assertTrue(build_seed.contradicts_board(BuiltFor(None, "atmega32u4", False), "rp2040"))

    def test_a_shield_always_goes(self):
        # A ZMK shield sits on a separate controller board by definition.
        self.assertTrue(build_seed.contradicts_board(BuiltFor("pro_micro", None, True), None))

    def test_a_record_for_this_chip_stays(self):
        self.assertFalse(build_seed.contradicts_board(BuiltFor(None, "rp2040", False), "rp2040"))

    def test_a_board_naming_no_chip_contradicts_nothing(self):
        # Silence is Unknown, never a disagreement.
        self.assertFalse(build_seed.contradicts_board(BuiltFor(None, "atmega32u4", False), None))
