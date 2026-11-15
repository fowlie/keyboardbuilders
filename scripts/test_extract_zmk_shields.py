import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from extract_zmk_shields import extract  # noqa: E402

META = """file_format: "1"
id: temper
name: Chocofi Temper
type: shield
requires: [pro_micro]
features:
  - keys
siblings:
  - temper_left
  - temper_right
"""

DTSI = """/ {
    default_transform: keymap_transform_0 {
        compatible = "zmk,matrix-transform";
        columns = <10>;
        rows = <4>;
        map = <
            RC(0,0) RC(0,1) RC(0,2)
            RC(1,0) RC(1,1) RC(1,2)
        >;
    };
};
"""


class OutOfTreeShield(unittest.TestCase):
    """A shield in a designer's own repo, not under upstream's app/boards/shields."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.tree = pathlib.Path(self.tmp.name)/"temper-zmk-config"
        shield = self.tree/"boards/shields/temper"
        shield.mkdir(parents=True)
        (shield/"temper.zmk.yml").write_text(META)
        (shield/"temper.dtsi").write_text(DTSI)
        self.upstream = pathlib.Path(self.tmp.name)/"zmk"
        self.upstream.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def test_reads_a_shield_outside_any_upstream_checkout(self):
        [s] = extract(self.tree/"boards/shields", self.tree, self.upstream)
        self.assertEqual(s["id"], "temper")
        self.assertTrue(s["keyboard"])
        self.assertTrue(s["split"])
        self.assertEqual(s["halves"], 2)
        self.assertEqual(s["key_counts"], [6])
        self.assertEqual(s["requires"], ["pro_micro"])

    def test_dir_is_relative_to_the_tree_it_came_from(self):
        [s] = extract(self.tree/"boards/shields", self.tree, self.upstream)
        self.assertEqual(s["dir"], "boards/shields/temper")


if __name__ == "__main__":
    unittest.main()
