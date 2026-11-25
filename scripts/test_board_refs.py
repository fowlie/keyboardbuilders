"""Tests for board_refs -- reading a board file at the commit a person pinned it to."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from board_refs import pins, raw_url  # noqa: E402

ORBIT = {"tree": "qmk", "id": "ai03/orbit", "source": "ai03-2725/orbit",
         "boards": ["Orbit-PCB/Orbit.kicad_pcb"], "verdict": "confirmed",
         "ref": "c65a933"}


class Pins(unittest.TestCase):

    def test_a_confirmed_row_with_a_ref_pins_each_of_its_boards(self):
        row = dict(ORBIT, boards=["l.kicad_pcb", "r.kicad_pcb"])
        self.assertEqual(pins([row]), {("ai03-2725/orbit", "l.kicad_pcb"): "c65a933",
                                       ("ai03-2725/orbit", "r.kicad_pcb"): "c65a933"})

    def test_a_row_without_a_ref_pins_nothing(self):
        row = {k: v for k, v in ORBIT.items() if k != "ref"}
        self.assertEqual(pins([row]), {})

    def test_an_unconfirmed_row_pins_nothing(self):
        self.assertEqual(pins([dict(ORBIT, verdict="drafted")]), {})


class RawUrl(unittest.TestCase):

    def test_a_pinned_board_is_read_at_its_commit(self):
        p = pins([ORBIT])
        self.assertEqual(raw_url("ai03-2725/orbit", "Orbit-PCB/Orbit.kicad_pcb", p),
                         "https://raw.githubusercontent.com/ai03-2725/orbit/c65a933/"
                         "Orbit-PCB/Orbit.kicad_pcb")

    def test_any_other_board_is_read_at_head(self):
        self.assertEqual(raw_url("a/kbd", "pcb/my board.kicad_pcb", {}),
                         "https://raw.githubusercontent.com/a/kbd/HEAD/pcb/my%20board.kicad_pcb")


if __name__ == "__main__":
    unittest.main()
