"""Tests for draft_boards.ruled -- which human rows a re-draft must copy through."""
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from draft_boards import render, ruled  # noqa: E402

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


class Render(unittest.TestCase):

    def test_a_pinned_row_keeps_its_ref_through_a_redraft(self):
        """The file is regenerated nightly; a ref it dropped would unpin the board."""
        row = {"tree": "qmk", "id": "ai03/orbit", "source": "ai03-2725/orbit",
               "boards": ["Orbit-PCB/Orbit.kicad_pcb"], "name": "Orbit",
               "verdict": "confirmed", "signal": "human", "note": "n", "ref": "c65a933"}
        import tomllib
        self.assertEqual(tomllib.loads(render([row]))["entry"][0]["ref"], "c65a933")

    def test_an_unpinned_row_gets_no_ref_line(self):
        row = {"tree": "qmk", "id": "x", "source": "a/x", "boards": [], "name": "",
               "verdict": "drafted", "signal": "none", "note": ""}
        self.assertNotIn("ref", render([row]).split("[[entry]]", 1)[1])


if __name__ == "__main__":
    unittest.main()
