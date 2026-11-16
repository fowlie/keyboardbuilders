"""Tests for render_boards' two per-board decisions: skip it, and what to record."""
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from render_boards import is_fresh, entry_to_record  # noqa: E402

DONE = {"glb": "g1", "png": "p1", "uploaded": ["glb", "png"]}


class IsFresh(unittest.TestCase):

    def setUp(self):
        self.out = pathlib.Path(tempfile.mkdtemp())

    def test_with_upload_the_bucket_is_enough_without_local_files(self):
        # 2026-11-16: the VPS had never held the laptop's renders, so every board read as
        # stale and all 434 were rendered again.
        self.assertTrue(is_fresh(DONE, "g1", "p1", self.out, upload=True))

    def test_with_upload_a_board_not_fully_uploaded_is_stale(self):
        have = dict(DONE, uploaded=["glb"])
        self.assertFalse(is_fresh(have, "g1", "p1", self.out, upload=True))

    def test_a_changed_board_is_stale(self):
        self.assertFalse(is_fresh(DONE, "g2", "p1", self.out, upload=True))

    def test_without_upload_the_local_files_must_exist(self):
        self.assertFalse(is_fresh(DONE, "g1", "p1", self.out, upload=False))
        (self.out/"g1.glb").write_bytes(b"x")
        (self.out/"p1.png").write_bytes(b"x")
        self.assertTrue(is_fresh(DONE, "g1", "p1", self.out, upload=False))


class EntryToRecord(unittest.TestCase):

    def test_a_failed_export_keeps_the_render_it_already_had(self):
        # 2026-11-16: a container that could not read its mount failed all 434 boards,
        # and renders.json went from 334 designs to 2.
        failed = {"glb": None, "png": None, "uploaded": [], "notes": ["glb absent"]}
        self.assertEqual(entry_to_record(DONE, failed), DONE)

    def test_a_failed_export_with_nothing_before_is_recorded(self):
        failed = {"glb": None, "png": None, "uploaded": [], "notes": ["glb absent"]}
        self.assertEqual(entry_to_record({}, failed), failed)

    def test_a_good_export_replaces_the_old_one(self):
        new = {"glb": "g2", "png": "p2", "uploaded": ["glb", "png"]}
        self.assertEqual(entry_to_record(DONE, new), new)


if __name__ == "__main__":
    unittest.main()
