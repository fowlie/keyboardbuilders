"""Tests for intake_publish.sh -- the nightly run's push, when main moved meanwhile (#39).

2026-11-25: a commit landed on main during a 40-minute nightly run, the run's push was
rejected, and its unpushed commit then made every later run fail at its fast-forward.
"""
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent / "intake_publish.sh"


def git(cwd, *args):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True,
                          text=True).stdout.strip()


class Publish(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = pathlib.Path(self.tmp.name)
        self.origin = root / "origin.git"
        git(root, "init", "--quiet", "--bare", "--initial-branch=main", str(self.origin))
        seed = root / "seed"
        git(root, "clone", "--quiet", str(self.origin), str(seed))
        self.configure(seed)
        (seed / "designs.json").write_text("[1]\n")
        (seed / "README").write_text("hello\n")
        git(seed, "add", ".")
        git(seed, "commit", "--quiet", "-m", "start")
        git(seed, "push", "--quiet", "origin", "HEAD:main")
        self.bot, self.person = root / "bot", root / "person"
        for clone in (self.bot, self.person):
            git(root, "clone", "--quiet", str(self.origin), str(clone))
            self.configure(clone)

    def tearDown(self):
        self.tmp.cleanup()

    @staticmethod
    def configure(repo):
        git(repo, "config", "user.name", "t")
        git(repo, "config", "user.email", "t@example.invalid")

    def commit(self, repo, name, text, message):
        (repo / name).write_text(text)
        git(repo, "commit", "--quiet", "-am" if (repo / name).exists() else "-m", message)

    def publish(self):
        return subprocess.run(["bash", str(SCRIPT)], cwd=self.bot, capture_output=True, text=True)

    def origin_log(self):
        return git(self.origin, "log", "--format=%s", "main").splitlines()

    def test_main_moving_during_the_run_is_rebased_onto_and_pushed(self):
        self.commit(self.bot, "designs.json", "[1, 2]\n", "nightly")
        self.commit(self.person, "README", "hello, Ko-fi\n", "person")
        git(self.person, "push", "--quiet", "origin", "HEAD:main")

        run = self.publish()

        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.origin_log(), ["nightly", "person", "start"])

    def test_a_conflict_drops_the_nights_commit_and_leaves_the_checkout_on_main(self):
        self.commit(self.bot, "designs.json", "[1, 2]\n", "nightly")
        self.commit(self.person, "designs.json", "[1, 3]\n", "person")
        git(self.person, "push", "--quiet", "origin", "HEAD:main")

        run = self.publish()

        self.assertEqual(run.returncode, 2, run.stdout + run.stderr)
        self.assertEqual(self.origin_log(), ["person", "start"])
        git(self.bot, "fetch", "--quiet", "origin")
        self.assertEqual(git(self.bot, "rev-parse", "HEAD"), git(self.bot, "rev-parse", "origin/main"))
        self.assertEqual(git(self.bot, "status", "--porcelain"), "")

    def test_nothing_new_on_main_is_a_plain_push(self):
        self.commit(self.bot, "designs.json", "[1, 2]\n", "nightly")
        run = self.publish()
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.origin_log(), ["nightly", "start"])


if __name__ == "__main__":
    unittest.main()
