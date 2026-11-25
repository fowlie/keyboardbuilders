"""Tests for check_board_freshness.changed_files -- which board files to read again."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from check_board_freshness import changed_files, pinned_listing, with_pins  # noqa: E402

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


class Pinned(unittest.TestCase):
    """A pinned board is the file at its commit, so HEAD moving is not a change to it."""

    def test_a_pinned_path_takes_its_sha_at_the_pin(self):
        at = {("a/kbd", "c65a933"): {"pcb/kbd.kicad_pcb": ("pinned", 1100)}}
        got = pinned_listing("a/kbd", {"pcb/kbd.kicad_pcb": ("head", 421)},
                             {("a/kbd", "pcb/kbd.kicad_pcb"): "c65a933"},
                             lambda repo, ref: at[(repo, ref)])
        self.assertEqual(got, {"pcb/kbd.kicad_pcb": ("pinned", 1100)})

    def test_newly_pinning_a_board_read_at_head_reads_it_again(self):
        files = pinned_listing("a/kbd", {"pcb/kbd.kicad_pcb": ("head", 421)},
                               {("a/kbd", "pcb/kbd.kicad_pcb"): "c65a933"},
                               lambda repo, ref: {"pcb/kbd.kicad_pcb": ("pinned", 1100)})
        changed, _ = changed_files(MAPPING, {"a/kbd": files}, {KEY: "head"}, {})
        self.assertEqual(changed, [KEY])

    def test_a_pinned_board_with_no_stored_sha_is_read_again(self):
        """Orbit's HEAD reading predates byte counts, so the size test cannot see it came
        from HEAD -- and storing the pin's SHA beside it would trust it forever."""
        changed, _ = changed_files(MAPPING, listing("pinned", 1100), {},
                                   {KEY: {"switches": 60}}, pinned={KEY})
        self.assertEqual(changed, [KEY])

    def test_a_pinned_board_already_read_at_its_pin_is_left_alone(self):
        changed, _ = changed_files(MAPPING, listing("pinned", 1100), {KEY: "pinned"},
                                   {KEY: {"switches": 60}}, pinned={KEY})
        self.assertEqual(changed, [])

    def test_a_pinned_board_no_design_holds_is_checked_anyway(self):
        """Orbit: taken off the site, then pinned -- the last build never mapped it."""
        got = with_pins({}, {("a/kbd", "pcb/kbd.kicad_pcb"): "c65a933"})
        self.assertEqual(got, {"pin:a/kbd": {"repo": "a/kbd", "paths": ["pcb/kbd.kicad_pcb"]}})

    def test_a_pinned_board_a_design_holds_is_not_added_twice(self):
        self.assertEqual(with_pins(MAPPING, {("a/kbd", "pcb/kbd.kicad_pcb"): "c65a933"}),
                         MAPPING)

    def test_a_repo_with_no_pins_is_untouched(self):
        files = {"pcb/kbd.kicad_pcb": ("head", 421)}
        self.assertEqual(pinned_listing("a/kbd", files, {}, None), files)


if __name__ == "__main__":
    unittest.main()
