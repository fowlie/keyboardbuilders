#!/usr/bin/env python3
"""Tests for `firmware_records`, asked through its interface against real files.

Each case writes a miniature firmware tree to a temporary folder -- a two-line
`keyboard.json`, or a shield's `.zmk.yml` beside one `.overlay` -- plus a `trees.toml`
registry pointing at it, so the QMK and ZMK readers behind the seam are the ones under
test. Run from the repo root:

    python3 -m unittest discover -s scripts -p 'test_*.py'
"""
import json
import pathlib
import tempfile
import textwrap
import unittest

import firmware_records
import trees


def transform(keys):
    """A ZMK matrix transform with `keys` positions."""
    rcs = " ".join(f"RC(0,{i})" for i in range(keys))
    return textwrap.dedent(f"""
        / {{
            default_transform: keymap_transform_0 {{
                compatible = "zmk,matrix-transform";
                map = <{rcs}>;
            }};
        }};
        """)


def shield_meta(sid, features=("keys",), siblings=(), requires=()):
    lines = [f"file_format: \"1\"", f"id: {sid}", f"name: {sid}", "type: shield"]
    if requires:
        lines.append(f"requires: [{', '.join(requires)}]")
    if features:
        lines.append(f"features: [{', '.join(features)}]")
    if siblings:
        lines.append("siblings:")
        lines += [f"  - {s}" for s in siblings]
    return "\n".join(lines) + "\n"


class Fixture(unittest.TestCase):
    """A temporary folder holding an upstream QMK and an upstream ZMK checkout."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.base = pathlib.Path(self._tmp.name)
        self.entries = [
            {"id": "qmk", "reader": "qmk", "upstream": True, "local": str(self.base/"qmk")},
            {"id": "zmk", "reader": "zmk", "upstream": True, "local": str(self.base/"zmk")},
            {"id": "hardware", "reader": "hardware"},
        ]
        (self.base/"qmk/keyboards").mkdir(parents=True)
        (self.base/"zmk/app/boards/shields").mkdir(parents=True)

    def tearDown(self):
        self._tmp.cleanup()

    def fork(self, tree, reader, records=None):
        """Another checkout of `reader`'s kind, registered as `tree`. `records` is the
        registry's own override for where ids start, as Temper's config repo needs."""
        sub = records or ("keyboards" if reader == "qmk" else "app/boards/shields")
        (self.base/tree/sub).mkdir(parents=True)
        entry = {"id": tree, "reader": reader, "local": str(self.base/tree)}
        if records:
            entry["records"] = records
        self.entries.append(entry)

    def qmk(self, path, data, tree="qmk"):
        """Write `data` as `<tree>/keyboards/<path>`."""
        f = self.base/tree/"keyboards"/path
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(json.dumps(data))

    def shield(self, sid, keys=None, tree="zmk", within="app/boards/shields", **meta):
        """Write a shield folder `<tree>/<within>/<sid>`."""
        d = self.base/tree/within/sid
        d.mkdir(parents=True, exist_ok=True)
        (d/f"{sid}.zmk.yml").write_text(shield_meta(sid, **meta))
        if keys:
            (d/f"{sid}.overlay").write_text(transform(keys))

    def records(self):
        toml = self.base/"trees.toml"
        toml.write_text("".join(
            "[[entry]]\n" + "".join(
                f"{k} = {json.dumps(v) if not isinstance(v, bool) else str(v).lower()}\n"
                for k, v in e.items()) + "\n"
            for e in self.entries))
        return firmware_records.FirmwareRecords(trees.load(toml))


def layout(n):
    return {"layout": [{"x": i, "y": 0} for i in range(n)]}


class QmkKeyboard(Fixture):

    def test_a_split_record_says_its_count_and_two_halves(self):
        self.qmk("crkbd/rev1/keyboard.json",
                 {"layouts": {"LAYOUT_split_3x6_3": layout(42), "LAYOUT_split_3x5_3": layout(36)},
                  "split": {"enabled": True}})
        kb, why = self.records().of("qmk", "crkbd/rev1").keyboard()
        self.assertIsNone(why)
        self.assertEqual((kb.key_counts, kb.split, kb.halves), ([42, 36], True, 2))

    def test_a_unibody_record_states_no_halves(self):
        self.qmk("planck/keyboard.json", {"layouts": {"LAYOUT": layout(48)}})
        kb, _ = self.records().of("qmk", "planck").keyboard()
        self.assertEqual((kb.key_counts, kb.split, kb.halves), ([48], False, None))

    def test_a_revision_inherits_its_parents_layouts(self):
        self.qmk("lily58/info.json", {"layouts": {"LAYOUT": layout(58)},
                                      "split": {"enabled": True}})
        self.qmk("lily58/rev1/keyboard.json", {"processor": "atmega32u4"})
        kb, _ = self.records().of("qmk", "lily58/rev1").keyboard()
        self.assertEqual((kb.key_counts, kb.split), ([58], True))

    def test_a_record_with_no_layouts_is_a_keyboard_of_no_stated_count(self):
        # Half A defers it as "no key count", which is a different finding from a
        # record that is not there at all.
        self.qmk("bare/keyboard.json", {"processor": "rp2040"})
        kb, why = self.records().of("qmk", "bare").keyboard()
        self.assertIsNone(why)
        self.assertEqual(kb.key_counts, [])

    def test_a_record_not_in_the_checkout_says_so(self):
        self.assertEqual(self.records().of("qmk", "nowhere").keyboard(),
                         (None, "not in the local QMK tree"))

    def test_a_fork_reads_from_its_own_checkout(self):
        self.fork("cheapino-vial", "qmk")
        self.qmk("cheapino/info.json", {"layouts": {"LAYOUT": layout(36)},
                                        "split": {"enabled": True}}, tree="cheapino-vial")
        records = self.records()
        self.assertEqual(records.of("cheapino-vial", "cheapino").keyboard()[0].key_counts, [36])
        self.assertEqual(records.of("qmk", "cheapino").keyboard(),
                         (None, "not in the local QMK tree"))


class ZmkKeyboard(Fixture):

    def test_a_split_shield_says_its_count_and_halves(self):
        self.shield("corne", keys=42, siblings=("corne_left", "corne_right"))
        kb, why = self.records().of("zmk", "corne").keyboard()
        self.assertIsNone(why)
        self.assertEqual((kb.key_counts, kb.split, kb.halves), ([42], True, 2))

    def test_a_unibody_shield_states_no_halves(self):
        self.shield("reviung41", keys=41)
        kb, _ = self.records().of("zmk", "reviung41").keyboard()
        self.assertEqual((kb.key_counts, kb.split, kb.halves), ([41], False, None))

    def test_an_accessory_shield_is_not_a_keyboard(self):
        self.shield("nice_view", features=("display",))
        self.assertEqual(self.records().of("zmk", "nice_view").keyboard(),
                         (None, "not a keyboard shield in the ZMK tree"))

    def test_a_shield_not_in_the_tree_is_not_a_keyboard_shield(self):
        self.assertEqual(self.records().of("zmk", "nowhere").keyboard(),
                         (None, "not a keyboard shield in the ZMK tree"))

    def test_a_shield_id_belongs_to_its_own_tree(self):
        # Temper's own config repo could name a shield `corne` too; each tree answers
        # for its own.
        self.fork("temper", "zmk", records="boards/shields")
        self.shield("corne", keys=42, siblings=("corne_left", "corne_right"))
        self.shield("corne", keys=36, tree="temper", within="boards/shields")
        records = self.records()
        self.assertEqual(records.of("zmk", "corne").keyboard()[0].key_counts, [42])
        self.assertEqual(records.of("temper", "corne").keyboard()[0].key_counts, [36])

    def test_a_tree_not_checked_out_reads_as_no_shields(self):
        self.entries.append({"id": "gone", "reader": "zmk",
                             "local": str(self.base/"not-cloned")})
        self.assertEqual(self.records().of("gone", "corne").keyboard(),
                         (None, "not a keyboard shield in the ZMK tree"))


class QmkBuiltFor(Fixture):
    """What a QMK record is built for: `development_board` or `processor`."""

    def defaults(self, **boards):
        """QMK's own `data/mappings/defaults.hjson`, which says what a dev board runs."""
        f = self.base/"qmk/data/mappings/defaults.hjson"
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(json.dumps({"development_board": {
            b: {"processor": chip} for b, chip in boards.items()}}))

    def test_an_elite_c_record_is_built_for_a_pro_micro_and_its_chip(self):
        self.defaults(elite_c="atmega32u4")
        self.qmk("clog/keyboard.json", {"development_board": "elite_c"})
        self.assertEqual(self.records().of("qmk", "clog").built_for(),
                         firmware_records.BuiltFor("pro_micro", "atmega32u4", True))

    def test_every_pro_micro_drop_in_is_a_pro_micro(self):
        # One list, where the footprint fallback and the socket filter used to keep
        # two that disagreed: `proton_c` was only in one, `liatris` only in the other.
        for board in ("promicro", "proton_c", "liatris", "helios", "kb2040"):
            with self.subTest(board):
                self.qmk(f"{board}_kb/keyboard.json", {"development_board": board})
                self.assertEqual(
                    self.records().of("qmk", f"{board}_kb").built_for().socket, "pro_micro")

    def test_a_black_pill_record_names_its_own_form_factor(self):
        self.qmk("pill60/keyboard.json", {"development_board": "blackpill_f411"})
        self.assertEqual(self.records().of("qmk", "pill60").built_for().socket, "blackpill")

    def test_a_dev_board_outside_the_domain_still_needs_a_separate_controller(self):
        # No nearest member: the socket stays unknown, and the record still says the
        # chip is not on the keyboard's own board.
        self.qmk("uno/keyboard.json", {"development_board": "arduino_uno"})
        self.assertEqual(self.records().of("qmk", "uno").built_for(),
                         firmware_records.BuiltFor(None, None, True))

    def test_a_record_naming_its_processor_is_built_for_a_chip_on_the_board(self):
        self.qmk("crkbd/rev4_0/keyboard.json", {"processor": "RP2040"})
        self.assertEqual(self.records().of("qmk", "crkbd/rev4_0").built_for(),
                         firmware_records.BuiltFor(None, "rp2040", False))

    def test_a_chip_outside_the_domain_is_none_not_the_nearest(self):
        self.qmk("odd/keyboard.json", {"processor": "STM32H743"})
        self.assertIsNone(self.records().of("qmk", "odd").built_for().chip)

    def test_the_most_specific_file_owns_the_dev_board(self):
        # `kin80/blackpill401` and `kin80/blackpill411` are two dev boards under one
        # parent; the parent's answer must not reach either.
        self.qmk("kin80/info.json", {"development_board": "promicro"})
        self.qmk("kin80/blackpill401/keyboard.json", {"development_board": "blackpill_f401"})
        self.assertEqual(self.records().of("qmk", "kin80/blackpill401").built_for().socket,
                         "blackpill")

    def test_a_record_not_in_the_checkout_states_nothing(self):
        self.assertEqual(self.records().of("qmk", "nowhere").built_for(),
                         firmware_records.BuiltFor(None, None, False))


class ZmkBuiltFor(Fixture):
    """A shield, by ZMK's own definition, sits on a separate controller board."""

    def test_a_pro_micro_shield(self):
        self.shield("clog", keys=34, requires=("pro_micro",))
        self.assertEqual(self.records().of("zmk", "clog").built_for(),
                         firmware_records.BuiltFor("pro_micro", None, True))

    def test_a_xiao_shield(self):
        self.shield("osprette", keys=34, requires=("seeed_xiao",))
        self.assertEqual(self.records().of("zmk", "osprette").built_for().socket, "seeed_xiao")

    def test_a_board_outside_the_domain_leaves_the_socket_unknown(self):
        self.shield("m2", keys=60, requires=("makerdiary_nrf52840_m2",))
        self.assertEqual(self.records().of("zmk", "m2").built_for(),
                         firmware_records.BuiltFor(None, None, True))

    def test_even_an_unreadable_shield_is_a_shield(self):
        self.assertTrue(self.records().of("zmk", "nowhere").built_for().needs_separate_controller)


if __name__ == "__main__":
    unittest.main()
