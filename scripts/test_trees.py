"""Tests for trees.Registry -- where a tree's record ids resolve."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from trees import Registry  # noqa: E402

BASE = {"reader": "qmk", "url": "https://github.com/a/kbd", "ref": "main",
        "_local": pathlib.Path("/c/kbd")}


def reg(**fields):
    return Registry([dict(BASE, id="t", **fields)])


class Root(unittest.TestCase):

    def test_a_fork_resolves_under_its_keyboards_folder(self):
        self.assertEqual(reg().root("t"), pathlib.Path("/c/kbd/keyboards"))

    def test_within_moves_the_keyboards_folder_down(self):
        self.assertEqual(reg(within="firmware").root("t"),
                         pathlib.Path("/c/kbd/firmware/keyboards"))

    def test_records_names_the_folder_itself_when_there_is_no_keyboards_folder(self):
        # marcocampione/Hem-keyboard: firmware/hem/keyboard.json, id `hem`.
        self.assertEqual(reg(records="firmware").root("t"), pathlib.Path("/c/kbd/firmware"))

    def test_records_at_the_repo_root(self):
        self.assertEqual(reg(records=".").root("t"), pathlib.Path("/c/kbd"))

    def test_records_and_within_together_is_an_error(self):
        with self.assertRaises(ValueError):
            reg(records="firmware", within="firmware").root("t")


class RecordUrl(unittest.TestCase):

    def test_records_is_the_path_a_builder_follows(self):
        self.assertEqual(reg(records="firmware").record_url("t", "hem"),
                         "https://github.com/a/kbd/tree/main/firmware/hem")

    def test_records_at_the_root_adds_no_dot(self):
        self.assertEqual(reg(records=".").record_url("t", "DASBOB-qmk"),
                         "https://github.com/a/kbd/tree/main/DASBOB-qmk")

    def test_within_keeps_its_keyboards_folder(self):
        self.assertEqual(reg(within="firmware").record_url("t", "x/y"),
                         "https://github.com/a/kbd/tree/main/firmware/keyboards/x/y")


if __name__ == "__main__":
    unittest.main()
