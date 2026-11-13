"""github_issues.py drives the `gh` CLI. A fake `gh` on PATH records each call and answers
with canned JSON, so these check the commands sent and how the answers are read."""
import json
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from github_issues import GitHubIssues  # noqa: E402

FAKE_GH = r'''#!/usr/bin/env python3
import json, os, sys
log = os.environ["FAKE_GH_LOG"]
args = sys.argv[1:]
stdin = sys.stdin.read() if "--body-file" in args else None
with open(log, "a") as f:
    f.write(json.dumps({"args": args, "stdin": stdin}) + "\n")
answers = json.loads(os.environ["FAKE_GH_ANSWERS"])
for prefix, out in answers:
    if args[:len(prefix)] == prefix:
        sys.stdout.write(out)
        break
'''


class GhTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        d = Path(self.dir.name)
        gh = d / "gh"
        gh.write_text(FAKE_GH)
        gh.chmod(gh.stat().st_mode | stat.S_IEXEC)
        self.log = d / "log.jsonl"
        self.env = {"PATH": f"{d}:{os.environ['PATH']}", "FAKE_GH_LOG": str(self.log)}
        self.tracker = GitHubIssues("fowlie/keyboardbuilders")

    def tearDown(self):
        self.dir.cleanup()

    def run_with(self, answers, call):
        old = dict(os.environ)
        os.environ.update(self.env, FAKE_GH_ANSWERS=json.dumps(answers))
        try:
            return call()
        finally:
            os.environ.clear()
            os.environ.update(old)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def test_whoami_is_the_token_s_login(self):
        login = self.run_with([[["api", "user"], "keyboardbuilders-bot\n"]], self.tracker.whoami)
        self.assertEqual("keyboardbuilders-bot", login)

    def test_issues_lists_open_issues_with_the_label_and_keeps_only_those(self):
        listed = [
            {"number": 7, "title": "a", "body": "x", "labels": [{"name": "intake/photo"}]},
            # GitHub filters by label itself; one without it must still not get through.
            {"number": 8, "title": "b", "body": "y", "labels": [{"name": "bug"}]},
        ]
        found = self.run_with([[["issue", "list"], json.dumps(listed)]],
                              lambda: self.tracker.issues("intake/photo"))
        self.assertEqual([7], [i["number"] for i in found])
        args = self.calls()[0]["args"]
        self.assertEqual(["issue", "list", "-R", "fowlie/keyboardbuilders", "--label", "intake/photo",
                          "--state", "open"], args[:8])

    def test_open_issue_returns_the_number_from_the_url_gh_prints(self):
        issue = self.run_with(
            [[["issue", "create"], "https://github.com/fowlie/keyboardbuilders/issues/42\n"]],
            lambda: self.tracker.open_issue("A title", "A body\nwith `code`", ["intake/photo"]))
        self.assertEqual(42, issue["number"])
        call = self.calls()[0]
        self.assertIn("--label", call["args"])
        self.assertEqual("A body\nwith `code`", call["stdin"], "body goes on stdin, unquoted")

    def test_label_id_creates_the_label_if_missing_and_is_its_name(self):
        name = self.run_with([], lambda: self.tracker.label_id("intake/photo"))
        self.assertEqual("intake/photo", name)
        self.assertEqual(["label", "create", "intake/photo"], self.calls()[0]["args"][:3])
        self.assertIn("--force", self.calls()[0]["args"])

    def test_comments_have_the_author_login_and_time_the_answer_loop_reads(self):
        page = [{"user": {"login": "fowlie"}, "body": "y", "created_at": "2026-11-13T10:00:00Z"}]
        got = self.run_with([[["api"], "\n".join(json.dumps(c) for c in page * 2) + "\n"]], lambda: self.tracker.comments(42))
        self.assertEqual(2, len(got), "a comment per line, across pages")
        self.assertEqual("fowlie", got[0]["user"]["login"])
        self.assertIn("repos/fowlie/keyboardbuilders/issues/42/comments", self.calls()[0]["args"])

    def test_comment_and_close(self):
        self.run_with([], lambda: (self.tracker.comment(42, "Thanks"), self.tracker.close(42)))
        comment, close = self.calls()
        self.assertEqual(["issue", "comment", "42"], comment["args"][:3])
        self.assertEqual("Thanks", comment["stdin"])
        self.assertEqual(["issue", "close", "42"], close["args"][:3])


if __name__ == "__main__":
    unittest.main()
