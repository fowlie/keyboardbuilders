"""The few GitHub issue calls the intake bot makes -- backlog #104 -- through the `gh` CLI.

`gh` reads GH_TOKEN from the environment. On the VPS that is the BOT ACCOUNT's token, never
the owner's: the answer loop tells the owner's replies from the bot's own "didn't
understand" comments by who wrote them, so a bot posting as the owner would read its own
comments as answers. intake_answer.py refuses to run on the owner's token.

    GH_REPO   default fowlie/keyboardbuilders
    GH_TOKEN  the bot's token; `gh` fails with its own message if it is missing
"""
import json
import os
import re
import subprocess


class GitHubIssues:
    def __init__(self, repo):
        self.repo = repo

    @classmethod
    def from_env(cls):
        return cls(os.environ.get("GH_REPO", "fowlie/keyboardbuilders"))

    def gh(self, *args, stdin=None):
        out = subprocess.run(["gh", *args], input=stdin, capture_output=True, text=True)
        if out.returncode:
            raise SystemExit(f"gh {' '.join(args[:3])}: {out.stderr.strip()[:300]}")
        return out.stdout

    def whoami(self):
        return self.gh("api", "user", "--jq", ".login").strip()

    def issues(self, label, state="open"):
        """Every issue carrying [label]. FILTERED HERE AS WELL AS BY THE SERVER, as the
        Forgejo client had to be: the answer loop closes what this returns."""
        listed = json.loads(self.gh(
            "issue", "list", "-R", self.repo, "--label", label, "--state", state,
            "--limit", "1000", "--json", "number,title,body,labels") or "[]")
        return [i for i in listed if label in {l["name"] for l in i["labels"]}]

    def label_id(self, name, color="8a8a8a"):
        """GitHub labels issues by name. Created on first use; --force makes it a no-op after."""
        self.gh("label", "create", name, "-R", self.repo, "--color", color, "--force")
        return name

    def open_issue(self, title, body, labels):
        url = self.gh("issue", "create", "-R", self.repo, "--title", title,
                      *[a for label in labels for a in ("--label", label)],
                      "--body-file", "-", stdin=body)
        return {"number": int(re.search(r"/issues/(\d+)", url)[1]), "url": url.strip()}

    def comments(self, number):
        """Oldest first, with `user.login` and `created_at`, which the answer loop reads."""
        # One comment per line: `--slurp` would be tidier but needs gh 2.48, and the VPS's
        # Debian package is 2.46.
        lines = self.gh("api", "--paginate", "--jq", ".[]",
                        f"repos/{self.repo}/issues/{number}/comments")
        return [json.loads(line) for line in lines.splitlines() if line.strip()]

    def comment(self, number, body):
        self.gh("issue", "comment", str(number), "-R", self.repo, "--body-file", "-", stdin=body)

    def close(self, number):
        self.gh("issue", "close", str(number), "-R", self.repo)
