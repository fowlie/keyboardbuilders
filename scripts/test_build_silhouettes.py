"""Tests for build_silhouettes.undo_half_turn -- the panel's half-turned hand."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from build_silhouettes import mirrored, undo_half_turn  # noqa: E402

# A hand of four: three keys in a row, and an angled thumb key below and to the right.
HAND = [(0.0, 0.0, 0.0), (19.0, 0.0, 0.0), (38.0, 0.0, 0.0), (38.0, 19.0, 15.0)]


class UndoHalfTurn(unittest.TestCase):

    def test_the_hand_with_its_thumbs_up_is_turned_back(self):
        # Dilemma v2's shape: the other hand mirrored top-to-bottom, its switches at 180.
        upside = [(x, 100 - y, (180 - r) % 360) for x, y, r in HAND]

        first, second = undo_half_turn((HAND, upside))

        self.assertEqual(first, HAND, "the upright hand moved")
        thumb = next(p for p in second if round(p[2]) % 360 != 0)
        centre = sum(y for _, y, _ in second) / len(second)
        self.assertGreater(thumb[1], centre, "the turned hand's thumb is not below its centre")

    def test_the_upright_hand_is_never_the_one_turned_even_at_180(self):
        # The same pair with the switch angles swapped: still the thumbs-up hand turns.
        upright_at_180 = [(x, y, (r + 180) % 360) for x, y, r in HAND]
        upside_at_0 = [(x, 100 - y, (-r) % 360) for x, y, r in HAND]

        first, second = undo_half_turn((upright_at_180, upside_at_0))

        self.assertEqual(first, upright_at_180)
        self.assertNotEqual(second, upside_at_0)

    def test_both_hands_at_180_is_a_library_orientation_and_left_alone(self):
        both = [(x, y, 180.0) for x, y, _ in HAND]
        self.assertEqual(undo_half_turn((both, both)), (both, both))

    def test_both_hands_upright_are_left_alone(self):
        self.assertEqual(undo_half_turn((HAND, HAND)), (HAND, HAND))


class Mirrored(unittest.TestCase):

    def test_keys_flip_to_the_other_edge_and_their_angles_turn_the_other_way(self):
        drawing = {"w": 4.0, "h": 1.0, "keys": [[0.0, 0.0, 2.0, 1.0], [3.0, 0.0, 1.0, 1.0, 15.0]]}

        flipped = mirrored(drawing)["keys"]

        self.assertEqual(flipped[0], [2.0, 0.0, 2.0, 1.0])   # the wide key now at the right
        self.assertEqual(flipped[1], [0.0, 0.0, 1.0, 1.0, -15.0])


if __name__ == "__main__":
    unittest.main()
