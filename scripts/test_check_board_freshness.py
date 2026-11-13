"""Tests for check_board_freshness.changed_files -- which board files to read again."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from check_board_freshness import changed_files  # noqa: E402

MAPPING = {"kbd": {"repo": "a/kbd", "paths": ["pcb/kbd.kicad_pcb"]}}
KEY = "a/kbd\tpcb/kbd.kicad_pcb"


def listing(sha, size):
    return {"a/kbd": {"pcb/kbd.kicad_pcb": (sha, size)}}


class ChangedFiles(unittest.TestCase):

    def test_a_new_sha_is_a_changed_board(self):
        changed, current = changed_files(MAPPING, listing("new", 10), {KEY: "old"}, {})
        self.assertEqual(changed, [KEY])
        self.assertEqual(current, {KEY: "new"})

    def test_the_same_sha_is_left_alone_whatever_the_size(self):
        changed, _ = changed_files(MAPPING, listing("same", 99), {KEY: "same"},
                                   {KEY: {"bytes": 10}})
        self.assertEqual(changed, [])

    def test_without_a_sha_a_size_mismatch_is_only_a_reason_to_look(self):
        # yosino58: Japanese text makes bytes and characters differ on an unchanged file.
        scanned = {KEY: {"bytes": 10}}
        unchanged, _ = changed_files(MAPPING, listing("s", 14), {}, scanned,
                                     verify=lambda k, s: True)
        changed, _ = changed_files(MAPPING, listing("s", 14), {}, scanned,
                                   verify=lambda k, s: False)
        self.assertEqual(unchanged, [])
        self.assertEqual(changed, [KEY])

    def test_a_board_gone_from_the_listing_is_not_forgotten(self):
        changed, _ = changed_files(MAPPING, {"a/kbd": {}}, {KEY: "old"}, {})
        self.assertEqual(changed, [])


if __name__ == "__main__":
    unittest.main()
