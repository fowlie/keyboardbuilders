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

    def fork(self, tree, reader):
        """Another checkout of `reader`'s kind, registered as `tree`."""
        sub = "keyboards" if reader == "qmk" else "app/boards/shields"
        (self.base/tree/sub).mkdir(parents=True)
        self.entries.append({"id": tree, "reader": reader, "local": str(self.base/tree)})

    def qmk(self, path, data, tree="qmk"):
        """Write `data` as `<tree>/keyboards/<path>`."""
        f = self.base/tree/"keyboards"/path
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(json.dumps(data))

    def shield(self, sid, keys=None, tree="zmk", **meta):
        """Write a shield folder `<tree>/app/boards/shields/<sid>`."""
        d = self.base/tree/"app/boards/shields"/sid
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
        self.fork("temper", "zmk")
        self.shield("corne", keys=42, siblings=("corne_left", "corne_right"))
        self.shield("corne", keys=36, tree="temper")
        records = self.records()
        self.assertEqual(records.of("zmk", "corne").keyboard()[0].key_counts, [42])
        self.assertEqual(records.of("temper", "corne").keyboard()[0].key_counts, [36])

    def test_a_tree_not_checked_out_reads_as_no_shields(self):
        self.entries.append({"id": "gone", "reader": "zmk",
                             "local": str(self.base/"not-cloned")})
        self.assertEqual(self.records().of("gone", "corne").keyboard(),
                         (None, "not a keyboard shield in the ZMK tree"))


if __name__ == "__main__":
    unittest.main()
