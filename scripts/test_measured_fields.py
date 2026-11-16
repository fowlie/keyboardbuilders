import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import measured_fields  # noqa: E402
from measured_fields import StaleRuling, resolve  # noqa: E402

LIVE = {"corne", "sesame", "choc-v"}


class Resolve(unittest.TestCase):
    """(scan, owner rulings) -> the value a design publishes."""

    def test_the_scan_stands_where_the_owner_said_nothing(self):
        self.assertEqual(resolve({"corne": True}, {}, LIVE), {"corne": True})

    def test_the_owners_ruling_beats_the_scan(self):
        self.assertEqual(resolve({"sesame": True}, {"sesame": False}, LIVE), {"sesame": False})

    def test_a_ruling_fills_what_the_scan_left_unknown(self):
        self.assertEqual(resolve({}, {"choc-v": "column"}, LIVE), {"choc-v": "column"})

    def test_unknown_stays_unknown(self):
        # No scan verdict and no ruling is no row, never a default.
        self.assertEqual(resolve({"corne": True}, {}, LIVE | {"lily58"}), {"corne": True})

    def test_a_ruling_of_unknown_removes_the_scans_verdict(self):
        # stagger-overrides.toml: a gamepad's keys fit no bucket at all.
        self.assertEqual(resolve({"sesame": "row"}, {"sesame": "unknown"}, LIVE), {})

    def test_a_ruling_for_a_design_that_does_not_publish_fails(self):
        with self.assertRaises(StaleRuling) as e:
            resolve({}, {"gone": True}, LIVE)
        self.assertIn("gone", str(e.exception))

    def test_a_scan_verdict_for_a_design_that_does_not_publish_is_dropped(self):
        # A scan is a cache of measurements, and can outlive a design until it is re-run.
        self.assertEqual(resolve({"gone": True, "corne": False}, {}, LIVE), {"corne": False})


class Rulings(unittest.TestCase):
    def test_reads_slug_and_value_from_an_override_file(self):
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / "splay-overrides.toml"
            path.write_text('[[entry]]\nslug = "sesame"\nsplay = false\nnote = "thumbs"\n')
            self.assertEqual(measured_fields.rulings(path, "splay"), {"sesame": False})

    def test_no_file_is_no_rulings(self):
        self.assertEqual(measured_fields.rulings(pathlib.Path("/nonexistent.toml"), "splay"), {})


class Apply(unittest.TestCase):
    def test_writes_each_resolved_value_onto_its_design(self):
        designs = [{"slug": "corne"}, {"slug": "sesame"}, {"slug": "choc-v"}]
        field = measured_fields.Field("splay", scan={"corne": True, "sesame": True},
                                      rulings={"sesame": False})
        measured_fields.apply(designs, [field])
        self.assertEqual(designs, [{"slug": "corne", "splay": True},
                                   {"slug": "sesame", "splay": False},
                                   {"slug": "choc-v"}])


if __name__ == "__main__":
    unittest.main()
