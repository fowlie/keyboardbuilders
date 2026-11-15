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


class ZmkShieldsPerTree(unittest.TestCase):
    """A shield is looked up in its OWN tree: Temper's lives in its config repo."""

    def setUp(self):
        import pathlib, tempfile
        from trees import Registry
        self.tmp = tempfile.TemporaryDirectory()
        base = pathlib.Path(self.tmp.name)

        def shield(root, sid, keys):
            d = root/sid
            d.mkdir(parents=True)
            (d/f"{sid}.zmk.yml").write_text(
                f"id: {sid}\ntype: shield\nfeatures:\n  - keys\n")
            (d/f"{sid}.dtsi").write_text(
                'compatible = "zmk,matrix-transform"; map = <'
                + " RC(0,0)" * keys + " >;")

        upstream, fork = base/"zmk", base/"temper-zmk-config"
        shield(upstream/"app/boards/shields", "corne", 42)
        shield(fork/"boards/shields", "temper", 36)
        shield(fork/"boards/shields", "corne", 34)  # same id as upstream's, on purpose
        entries = [
            {"id": "zmk", "reader": "zmk", "upstream": True, "_local": upstream},
            {"id": "temper", "reader": "zmk", "records": "boards/shields", "_local": fork},
            {"id": "gone", "reader": "zmk", "records": "boards/shields",
             "_local": base/"never-cloned"},
            {"id": "qmk", "reader": "qmk", "_local": base/"qmk"},
        ]
        self.shields = build_seed.zmk_shields(Registry(entries))

    def tearDown(self):
        self.tmp.cleanup()

    def test_a_fork_tree_shield_is_found_in_that_tree(self):
        self.assertEqual(self.shields["temper"]["temper"]["key_counts"], [36])

    def test_the_same_id_in_two_trees_does_not_collide(self):
        self.assertEqual(self.shields["zmk"]["corne"]["key_counts"], [42])
        self.assertEqual(self.shields["temper"]["corne"]["key_counts"], [34])

    def test_a_tree_not_checked_out_gives_no_shields_rather_than_a_crash(self):
        self.assertEqual(self.shields["gone"], {})

    def test_only_zmk_trees_are_read(self):
        self.assertNotIn("qmk", self.shields)


if __name__ == "__main__":
    unittest.main()
