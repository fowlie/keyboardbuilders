"""Tests for the `discovery` question kind (#105): asking about a found repo, recording
the owner's answer."""
import pathlib
import sys
import tempfile
import tomllib
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from intake_answer import append_discovery, parse_yes_no  # noqa: E402
from intake_ask import MARKER, discovery_questions  # noqa: E402

FOUND = {"candidates": {
    "a/kbd": {"description": "a wireless split", "stars": 4, "licence": "MIT",
              "created": "2026-10-20", "pcb": ["pcb/left.kicad_pcb"], "firmware": ["zmk"],
              "switches": 21},
    "b/pad": {"description": "", "stars": 0, "licence": None,
              "created": "2026-10-21", "pcb": ["pad.kicad_pcb"], "firmware": []},
}}


class ParseYesNo(unittest.TestCase):

    def test_yes_and_no_in_any_case(self):
        for text, want in [("y", "y"), (" Yes ", "y"), ("`n`", "n"), ("NO", "n")]:
            self.assertEqual(parse_yes_no(text), want, text)

    def test_anything_else_is_not_an_answer(self):
        for text in ["maybe", "y n", "", "yes it is"]:
            self.assertIsNone(parse_yes_no(text), text)


class Questions(unittest.TestCase):

    def test_one_question_per_candidate_with_a_marker_the_answer_loop_reads(self):
        qs = discovery_questions(FOUND, ruled=set())
        self.assertEqual([q["slug"] for q in qs], ["a/kbd", "b/pad"])
        m = MARKER.search(qs[0]["body"])
        self.assertEqual((m[1], m[2]), ("discovery", "a/kbd"))

    def test_the_body_names_what_the_scan_found(self):
        body = discovery_questions(FOUND, ruled=set())[0]["body"]
        for fact in ["https://github.com/a/kbd", "a wireless split", "pcb/left.kicad_pcb",
                     "zmk", "MIT", "21 switches"]:
            self.assertIn(fact, body)

    def test_a_ruled_repo_is_not_asked_again_whatever_its_case(self):
        qs = discovery_questions(FOUND, ruled={"a/kbd"})
        self.assertEqual([q["slug"] for q in qs], ["b/pad"])


class AppendDiscovery(unittest.TestCase):

    def test_rulings_accumulate_as_entries(self):
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / "discoveries.toml"
            append_discovery(path, "a/kbd", "design", 101)
            append_discovery(path, "b/pad", "not_design", 102)
            rows = tomllib.loads(path.read_text())["entry"]
        self.assertEqual(rows, [
            {"repo": "a/kbd", "verdict": "design", "issue": 101},
            {"repo": "b/pad", "verdict": "not_design", "issue": 102},
        ])


if __name__ == "__main__":
    unittest.main()
