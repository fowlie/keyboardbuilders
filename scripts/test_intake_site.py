"""Backlog #117: the bot's questions go to a file the site reads, and moderators' answers
come back from the site's database -- no GitHub issues."""
import json
import pathlib
import sqlite3
import sys
import tempfile
import tomllib
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import intake_answer  # noqa: E402
from intake_ask import choices_for, discovery_questions, for_site, write_questions_file  # noqa: E402
from intake_answer import read_answers, site_answers, write_checked  # noqa: E402

FOUND = {"candidates": {
    "a/kbd": {"description": "a wireless split", "stars": 4, "licence": "MIT",
              "created": "2026-10-20", "pcb": ["pcb/left.kicad_pcb"], "firmware": ["zmk"]},
}}

# The table V9__moderation_answer.sql makes in the app's database.
SCHEMA = """CREATE TABLE moderation_answer (question_id TEXT PRIMARY KEY, reply TEXT NOT NULL,
            by_login TEXT NOT NULL, at TEXT NOT NULL DEFAULT 'now')"""


class Choices(unittest.TestCase):

    def test_yes_no_kinds(self):
        for kind in ("discovery", "source"):
            self.assertEqual([c["reply"] for c in choices_for(kind, None)], ["y", "n"], kind)

    def test_a_photo_offers_each_image_then_none_and_skip(self):
        self.assertEqual([c["reply"] for c in choices_for("photo", 3)],
                         ["1", "2", "3", "none", "skip"])

    def test_a_board_offers_each_board_then_none(self):
        self.assertEqual([c["reply"] for c in choices_for("board", 2)], ["1", "2", "none"])


class ForSite(unittest.TestCase):

    def setUp(self):
        self.q = for_site(discovery_questions(FOUND, ruled=set())[0])

    def test_the_id_is_kind_slug_and_fingerprint(self):
        kind, slug, fp = self.q["id"].split(":", 2)
        self.assertEqual((kind, slug), ("discovery", "a/kbd"))
        self.assertTrue(fp)

    def test_the_issue_footer_and_marker_are_gone(self):
        self.assertNotIn("Reply **`y`**", self.q["body"])
        self.assertNotIn("<!--", self.q["body"])
        self.assertIn("a wireless split", self.q["body"])

    def test_it_carries_its_buttons(self):
        self.assertEqual([c["reply"] for c in self.q["choices"]], ["y", "n"])


class QuestionsFile(unittest.TestCase):

    def test_written_whole_in_the_shape_the_site_reads(self):
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / "moderation" / "questions.json"
            write_questions_file(path, [for_site(q) for q in discovery_questions(FOUND, ruled=set())])
            data = json.loads(path.read_text())
            leftovers = [p.name for p in path.parent.iterdir() if p.name != "questions.json"]
        self.assertEqual([q["slug"] for q in data["questions"]], ["a/kbd"])
        self.assertEqual(leftovers, [], "no temp file left behind")


class Answers(unittest.TestCase):

    def db(self, d, rows):
        path = pathlib.Path(d) / "app.db"
        con = sqlite3.connect(path)
        con.execute(SCHEMA)
        con.executemany("INSERT INTO moderation_answer (question_id, reply, by_login) VALUES (?, ?, ?)", rows)
        con.commit()
        con.close()
        return path

    def test_read_from_the_sites_database(self):
        with tempfile.TemporaryDirectory() as d:
            path = self.db(d, [("discovery:a/kbd:fp1", "y", "fowlie")])
            self.assertEqual(read_answers(path), {"discovery:a/kbd:fp1": ("y", "fowlie")})

    def test_a_missing_database_is_no_answers(self):
        self.assertEqual(read_answers(pathlib.Path("/nonexistent/app.db")), {})

    def test_an_answer_becomes_a_ruling_once(self):
        q = for_site(discovery_questions(FOUND, ruled=set())[0])
        with tempfile.TemporaryDirectory() as d:
            d = pathlib.Path(d)
            questions = d / "questions.json"
            write_questions_file(questions, [q])
            db = self.db(d, [(q["id"], "y", "fowlie")])
            applied = d / "applied.json"
            discoveries = d / "discoveries.toml"
            with mock.patch.object(intake_answer, "DISCOVERIES", discoveries):
                run = intake_answer.Run(None)
                first = site_answers(run, questions, db, applied)
                intake_answer.mark_applied(applied, first[0])
                again = site_answers(intake_answer.Run(None), questions, db, applied)
            rows = tomllib.loads(discoveries.read_text())["entry"]
        self.assertEqual(first, ([q["id"]], []))
        self.assertEqual(again, ([], []), "applied answers are not applied twice")
        self.assertEqual(rows, [{"repo": "a/kbd", "verdict": "design", "issue": 0}])

    def test_an_answer_not_marked_applied_is_applied_again(self):
        """Marking happens after the commit; if the commit fails, the answer comes back."""
        q = for_site(discovery_questions(FOUND, ruled=set())[0])
        with tempfile.TemporaryDirectory() as d:
            d = pathlib.Path(d)
            questions = d / "questions.json"
            write_questions_file(questions, [q])
            db = self.db(d, [(q["id"], "y", "fowlie")])
            with mock.patch.object(intake_answer, "DISCOVERIES", d / "discoveries.toml"):
                site_answers(intake_answer.Run(None), questions, db, d / "applied.json")
                again = site_answers(intake_answer.Run(None), questions, db, d / "applied.json")
        self.assertEqual(again, ([q["id"]], []))


class Checked(unittest.TestCase):
    """The site shows when the bot next looks, and lets an answer be undone until then."""

    def test_the_time_of_the_check_is_written_beside_the_questions(self):
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / "moderation" / "checked.json"
            write_checked(path, "2026-11-16T10:20:30Z")
            data = json.loads(path.read_text())
            leftovers = [p.name for p in path.parent.iterdir() if p.name != "checked.json"]
        self.assertEqual(data, {"at": "2026-11-16T10:20:30Z"})
        self.assertEqual(leftovers, [], "no temp file left behind")

    def test_a_check_stamps_the_time_it_read_the_answers(self):
        with tempfile.TemporaryDirectory() as d:
            d = pathlib.Path(d)
            checked = d / "checked.json"
            site_answers(intake_answer.Run(None), d / "questions.json", d / "app.db",
                         d / "applied.json", checked=checked)
            at = json.loads(checked.read_text())["at"]
        self.assertRegex(at, r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")


if __name__ == "__main__":
    unittest.main()
