"""Tests for draft_boards.ruled -- which human rows a re-draft must copy through."""
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from draft_boards import ruled  # noqa: E402

ROWS = '''
[[entry]]
tree = "zmk"
id = "osprette"
boards = ["v1/pcb/osprette.kicad_pcb"]
signal = "human"

[[entry]]
tree = "zmk"
id = "osprette"
boards = ["osprangle/pcb/osprangle.kicad_pcb"]
signal = "human"

[[entry]]
tree = "qmk"
id = "other"
boards = ["x.kicad_pcb"]
signal = "sole"
'''


class Ruled(unittest.TestCase):

    def test_every_human_row_for_a_record_is_kept(self):
        # Keyed by record alone, a re-draft kept the last osprette row and dropped v1.
        with tempfile.NamedTemporaryFile("w", suffix=".toml", delete=False) as f:
            f.write(ROWS)
        got = ruled(f.name)
        self.assertEqual([e["boards"] for e in got[("zmk", "osprette")]],
                         [["v1/pcb/osprette.kicad_pcb"], ["osprangle/pcb/osprangle.kicad_pcb"]])
        self.assertNotIn(("qmk", "other"), got)


if __name__ == "__main__":
    unittest.main()
