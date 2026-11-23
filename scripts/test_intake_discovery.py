"""Tests for the `discovery` question kind (#105): asking about a found repo, recording
the owner's answer."""
import pathlib
import sys
import tempfile
import tomllib
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from intake_answer import append_discovery  # noqa: E402
from intake_ask import discovery_questions, pick  # noqa: E402

FOUND = {"candidates": {
    "a/kbd": {"description": "a wireless split", "stars": 4, "licence": "MIT",
              "created": "2026-10-20", "pcb": ["pcb/left.kicad_pcb"], "firmware": ["zmk"],
              "switches": 21},
    "b/pad": {"description": "", "stars": 0, "licence": None,
              "created": "2026-10-21", "pcb": ["pad.kicad_pcb"], "firmware": []},
}}


class Questions(unittest.TestCase):

    def test_one_question_per_candidate(self):
        qs = discovery_questions(FOUND, ruled=set())
        self.assertEqual([q["slug"] for q in qs], ["a/kbd", "b/pad"])

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
            append_discovery(path, "a/kbd", "design", "fowlie")
            append_discovery(path, "b/pad", "not_design", "someone")
            rows = tomllib.loads(path.read_text())["entry"]
        self.assertEqual(rows, [
            {"repo": "a/kbd", "verdict": "design", "moderator": "fowlie"},
            {"repo": "b/pad", "verdict": "not_design", "moderator": "someone"},
        ])


class Cap(unittest.TestCase):
    """The cap is shared out across kinds, so a backlog cannot starve discovery."""

    @staticmethod
    def qs(**counts):
        return [{"kind": k, "id": f"{k}{i}"} for k, n in counts.items() for i in range(n)]

    def kinds(self, picked):
        return {k: sum(q["kind"] == k for q in picked)
                for k in ("photo", "board", "source", "discovery")}

    def test_a_backlog_does_not_starve_discovery(self):
        picked = pick(self.qs(photo=1, board=14, source=15, discovery=81), 30)
        self.assertEqual(30, len(picked))
        got = self.kinds(picked)
        self.assertEqual(1, got["photo"])
        self.assertGreater(got["discovery"], got["board"])
        self.assertGreater(got["discovery"], got["source"])

    def test_photos_get_the_largest_share_when_there_are_many(self):
        got = self.kinds(pick(self.qs(photo=50, board=50, source=50, discovery=50), 30))
        self.assertEqual({"photo": 14, "board": 4, "source": 4, "discovery": 8}, got)

    def test_unused_share_goes_to_the_others(self):
        picked = pick(self.qs(discovery=81), 30)
        self.assertEqual(30, self.kinds(picked)["discovery"])

    def test_fewer_than_the_cap_are_all_kept_in_kind_order(self):
        picked = pick(self.qs(discovery=2, photo=1), 20)
        self.assertEqual(["photo0", "discovery0", "discovery1"], [q["id"] for q in picked])


class AutoApprove(unittest.TestCase):
    SAFE = {"licence": "MIT", "firmware": ["zmk"], "switches": 40, "description": "a split"}

    def test_a_candidate_with_every_sign_is_safe(self):
        from auto_approve_discoveries import is_safe
        self.assertTrue(is_safe(self.SAFE))

    def test_any_missing_sign_makes_it_unsafe(self):
        from auto_approve_discoveries import is_safe
        for key, bad in [("licence", None), ("licence", "NOASSERTION"), ("firmware", []),
                         ("switches", 19), ("description", "  ")]:
            self.assertFalse(is_safe({**self.SAFE, key: bad}), key)

    def test_ruled_repos_are_left_alone(self):
        from auto_approve_discoveries import safe_unruled
        found = {"candidates": {"a/x": self.SAFE, "B/Y": self.SAFE}}
        self.assertEqual(["a/x"], safe_unruled(found, {"b/y"}))


if __name__ == "__main__":
    unittest.main()
