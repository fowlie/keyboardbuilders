#!/usr/bin/env python3
"""Read the owner's replies to the intake questions and write them into the seed --
backlog #104, piece 3.

`intake_ask.py` opens one GitHub issue per question. This reads the newest reply BY THE
OWNER on each open one, parses it strictly, writes the answer through the scripts that
already own each file, commits the lot as one commit, and closes the answered issues
with the commit's hash. The owner's reply is the ruling; this is the pen.

ONLY THE OWNER ANSWERS. A comment by anyone else -- the bot's own "didn't understand",
a collaborator -- is not an answer. So the bot must not BE the owner: a token for the
owner's account would read its own comments as replies, and this refuses to run on one.

STRICT, AND IT SAYS SO. A reply that does not parse (`2 or 3`, `the second one`) gets one
comment saying what it accepts, and the issue stays open. It is never guessed at. The
comment is posted once per reply, not once per poll.

PHOTO ANSWERS, the one kind so far:
    `N`     the Nth image shown: fetched, normalized and uploaded by `add_photo.add_one`,
            recorded in `photos.json` and in `photo-decisions.json`
    `none`  no photo of this board among them  -> seed/photo-declines.toml
    `skip`  not sure, don't ask again          -> seed/photo-declines.toml
    `URL`   a photo from somewhere else (kbd.news, a builder's post): fetched the same
            way, and recorded with a `credit` the page shows, since the author credit
            does not cover it
    `URL PAGE`  the same, crediting PAGE (the post) rather than the image file itself
A decline carries the fingerprint of the images shown, so a README that changes is asked
about again (see intake_ask.py). A photo that fails to fetch is reported on its issue,
which stays open; the rest of the batch still commits.

BOARD ANSWERS:
    `N`         board-set N is the record's: written to seed/boards.toml as `review_boards.py
                --rule` writes it, `signal = "human"`, under the drafted name
    `N Name`    the same, naming the design
    `none`      the record's board is not in the repo -> `verdict = "no_board"`
The choices are RECOMPUTED, not read back from the issue (it shows one file per set). If
the repo's boards changed since the question was asked, or the row was ruled some other
way, the issue is closed with a note and nothing is written; the next ask run asks afresh.
Only the ruling is committed here. Rebuilding designs.json and measuring the new design
takes minutes and touches every derived file, so the nightly `refresh_corpus.sh` does it.

SOURCE ANSWERS, written in place by `confirm_sources.py`'s own field editor:
    `y`           verdict = confirmed
    `n`           source = false, verdict = confirmed: "I looked, no repo is known"
    `owner/repo`  source corrected, verdict = confirmed

DISCOVERY ANSWERS (#105), appended to seed/discoveries.toml:
    `y`   verdict = design: an open keyboard design, queued for importing
    `n`   verdict = not_design: never asked about again

THE COMMIT TOUCHES ONLY WHAT IT WROTE. Every file it writes is on an allowlist, and a
file that already had uncommitted changes is refused before anything is written -- so a
developer's work in progress on the same checkout is never swept into the bot's commit.

Usage: intake_answer.py [--push] [--owner fowlie]
    --push pulls with --ff-only first and pushes the commit to main after. Without it the
    commit stays local, which is how it is tested on a dev machine.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import traceback
import tomllib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import add_photo  # noqa: E402
from github_issues import GitHubIssues  # noqa: E402
from intake_ask import (DECISIONS, DECLINES, DISCOVERIES, KIND_ORDER, MARKER,  # noqa: E402
                        board_choices, source_choices)

REPO = pathlib.Path(__file__).resolve().parent.parent
PHOTOS = add_photo.SEED

BOARDS = REPO / "seed/boards.toml"
SOURCES = REPO / "seed/sources.toml"

# The files the bot may commit. Anything else is a bug in this script, not a judgement.
ALLOWED = {PHOTOS, DECISIONS, DECLINES, BOARDS, SOURCES, DISCOVERIES}

IMAGE = re.compile(r"^!\[(\d+)\]\((\S+)\)$", re.M)
# `[Repo](...)` since photos are read repo-wide (57dcd00); `[README](...)` on older issues.
# Only `README` was accepted until 2026-10-04, and every picked photo crashed the run.
README = re.compile(r"\[(?:README|Repo)\]\((\S+)\)")

HELP = {"photo": "Didn't understand that. Reply with just a number from `1`–`{n}`, "
                 "`none`, `skip`, or an image URL (optionally followed by the page to "
                 "credit).",
        "board": "Didn't understand that. Reply with a number from `1`–`{n}`, a number and "
                 "a name (`2 Orbit`), or `none`."}
HELP["source"] = "Didn't understand that. Reply `y`, `n`, or the right repo as `owner/repo`."
HELP["discovery"] = "Didn't understand that. Reply `y` or `n`."
SLUG = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
BOARD_HEADING = re.compile(r"^### (\d+) — ", re.M)


def git(*args):
    out = subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True)
    if out.returncode:
        raise SystemExit(f"git {' '.join(args)}: {out.stderr.strip()}")
    return out.stdout.strip()


def latest_owner_reply(comments, owner):
    """The owner's newest comment, and whether anyone has spoken since."""
    mine = [c for c in comments if c["user"]["login"] == owner]
    if not mine:
        return None, False
    last = mine[-1]
    later = any(c["created_at"] > last["created_at"] for c in comments
                if c["user"]["login"] != owner)
    return last, later


URL = r"<?(https?://[^\s<>`]+)>?"


def parse_photo(text, n):
    """`3`, `none`, `skip`, `IMAGE-URL`, `IMAGE-URL PAGE-URL` -- case, whitespace and
    backticks aside. Nothing else. A URL comes back as `(image, credit)`."""
    raw = text.strip().strip("`").strip()
    word = raw.lower()
    if word in ("none", "skip"):
        return word
    if word.isdigit() and 1 <= int(word) <= n:
        return int(word)
    m = re.fullmatch(URL + r"(?:\s+" + URL + ")?", raw)
    if m:
        return m[1], m[2] or m[1]
    return None


def parse_board(text, n):
    """`2`, `2 Some Name`, `none`. One line; the name is kept as typed."""
    t = text.strip().strip("`").strip()
    if t.lower() == "none":
        return "none", None
    m = re.fullmatch(r"(\d+)(?:[ \t]+([^\n]+))?", t)
    if m and 1 <= int(m[1]) <= n:
        return int(m[1]), (m[2].strip() if m[2] else None)
    return None, None


def parse_source(text):
    """`y`, `n`, or `owner/repo`."""
    t = text.strip().strip("`").strip()
    if t.lower() in ("y", "yes", "n", "no"):
        return t.lower()[0]
    t = re.sub(r"^https?://github\.com/", "", t).rstrip("/")
    return t if SLUG.fullmatch(t) else None


def parse_yes_no(text):
    """`y`/`yes`/`n`/`no`, case, whitespace and backticks aside. Nothing else."""
    t = text.strip().strip("`").strip().lower()
    return t[0] if t in ("y", "yes", "n", "no") else None


def append_discovery(path, repo, verdict, issue):
    """One [[entry]] per answer, in the ruling-file shape the rest of seed/ uses."""
    if not path.exists():
        path.write_text(
            "# seed/discoveries.toml -- repos found on GitHub that the owner ruled on.\n"
            "# Written by scripts/intake_answer.py from his reply to an `intake/discovery`\n"
            "# issue (backlog #105); read by scripts/intake_ask.py so nothing is asked twice.\n"
            "#\n"
            "#   design       an open keyboard design: queued for importing\n"
            "#   not_design   not one (a keymap, a tool, a case only, ...): never asked again\n"
            "#\n"
            "# The build reads nothing here yet. A `design` row publishes nothing by itself;\n"
            "# it is the queue #105's step 2 (reading firmware inside the repo) works from.\n")
    with path.open("a") as f:
        f.write(f'\n[[entry]]\nrepo = "{repo}"\nverdict = "{verdict}"\nissue = {issue}\n')


def append_decline(slug, verdict, fp, issue):
    """One [[entry]] per decline, in the ruling-file shape the rest of seed/ uses."""
    if not DECLINES.exists():
        DECLINES.write_text(
            "# seed/photo-declines.toml -- designs whose README photos the owner looked at\n"
            "# and did not pick one from. Written by scripts/intake_answer.py from his reply\n"
            "# to an `intake/photo` issue (backlog #104); read by scripts/intake_ask.py.\n"
            "#\n"
            "#   none   no photo of this board among the images shown\n"
            "#   skip   not sure; don't ask again\n"
            "#\n"
            "# `images` fingerprints the candidates shown, so a README that gains or loses\n"
            "# an image is asked about again. The build reads nothing here: a decline\n"
            "# leaves the photo Unknown, never \"has no photo\".\n")
    with DECLINES.open("a") as f:
        f.write(f'\n[[entry]]\nslug = "{slug}"\nverdict = "{verdict}"\n'
                f'images = "{fp}"\nissue = {issue}\n')


class Run:
    """One poll's worth of answers: what gets written, and what each issue is told."""

    def __init__(self, tracker):
        self.tracker = tracker
        self.photos = json.loads(PHOTOS.read_text())
        self.decisions = json.loads(DECISIONS.read_text()) if DECISIONS.exists() else {}
        self.boards = None        # boards.toml entries, loaded on the first board answer
        self.choices = None
        self.sources = None       # sources.toml text, edited in place, on first answer
        self.answered = []        # (issue number, note for its closing comment)
        self.lines = []           # one line each for the commit message

    def photo(self, issue, key, fp, reply, spoken_since):
        images = {int(k): url for k, url in IMAGE.findall(issue["body"])}
        answer = parse_photo(reply["body"], len(images))
        if answer is None:
            return HELP["photo"].format(n=len(images))
        if answer in ("none", "skip"):
            append_decline(key, answer, fp, issue["number"])
            self.answered.append((issue["number"],
                                  f"recorded `{answer}` in seed/photo-declines.toml"))
            self.lines.append(f"{key}: {answer}")
            return None
        repo = README.search(issue["body"])[1]
        if isinstance(answer, tuple):
            (url, credit), which = answer, "that image"
        else:
            url, credit, which = images[answer], None, f"image {answer}"
        try:
            result = add_photo.add_one(url, REPO / "build/photos")
        except Exception as e:  # noqa: BLE001 -- any one bad URL, not just AddPhotoError
            return f"Couldn't fetch {which}: {e}. Pick another, or `none`."
        self.photos[key] = {"jpg": result["jpg"], "width": result["width"],
                            "height": result["height"], "sourceUrl": credit or repo}
        self.decisions[key] = {"url": url, "repo": repo}
        if credit:
            self.photos[key]["credit"] = credit
            self.decisions[key]["credit"] = credit
        self.answered.append((issue["number"], f"{which} is now the photo "
                                               f"({result['width']}×{result['height']})"
                                               + (f", credited to {credit}" if credit else "")))
        self.lines.append(f"{key}: photo {answer if credit is None else credit}")
        return None

    def board(self, issue, key, fp, reply, spoken_since):
        n = len(BOARD_HEADING.findall(issue["body"]))
        answer, name = parse_board(reply["body"], n)
        if answer is None:
            return HELP["board"].format(n=n)
        if self.choices is None:
            self.choices = board_choices()
            self.boards = tomllib.loads(BOARDS.read_text())["entry"]
        choice = self.choices.get(key)
        if choice is None or choice["fp"] != fp:
            # Not an error of the owner's, and not his answer to apply: the question he
            # answered no longer exists in that form. Say so and let the next run re-ask.
            self.tracker.comment(issue["number"],
                                 "This row was settled elsewhere, or the repo's boards "
                                 "changed since I asked, so I haven't applied this. If it "
                                 "still needs you, a fresh question will follow.")
            self.tracker.close(issue["number"])
            return None
        tree, rid = choice["row"]["tree"], choice["row"]["id"]
        row = next(e for e in self.boards if e["tree"] == tree and e["id"] == rid)
        note = f"the owner's reply to GitHub #{issue['number']} (intake bot, backlog #104)"
        if answer == "none":
            row.update(boards=[], name="", verdict="no_board", signal="human", note=note)
            self.answered.append((issue["number"], "recorded that its board isn't in "
                                                   "the repo (`no_board`)"))
            self.lines.append(f"{key}: no_board")
            return None
        picked = choice["free"][answer - 1]
        name = name or picked["name"]
        if not name:
            return f"Board {answer} has no drafted name -- reply `{answer} Its Name`."
        row.update(boards=sorted(picked["paths"]), name=name, verdict="confirmed",
                   signal="human", note=note)
        self.answered.append((issue["number"], f"board {answer} is now `{key}`'s, named "
                                               f"“{name}”; the nightly refresh builds it"))
        self.lines.append(f"{key}: board {answer} ({name})")
        return None

    def source(self, issue, key, fp, reply, spoken_since):
        from confirm_sources import blocks, field, set_field
        answer = parse_source(reply["body"])
        if answer is None:
            return HELP["source"]
        choice = source_choices().get(key)
        if choice is None or choice["fp"] != fp:
            self.tracker.comment(issue["number"],
                                 "This row was settled elsewhere, or its proposed repo "
                                 "changed since I asked, so I haven't applied this.")
            self.tracker.close(issue["number"])
            return None
        if self.sources is None:
            self.sources = SOURCES.read_text(encoding="utf-8")
        tree, rid = key.split(":", 1)
        pre, bs = blocks(self.sources)
        said = f"owner, GitHub #{issue['number']} (intake bot, backlog #104)"
        for i, b in enumerate(bs):
            if (field(b, "tree"), field(b, "id")) != (tree, rid):
                continue
            if answer == "n":
                b = set_field(b, "source", False)
                what = "no repo known (`source = false`)"
            elif answer != "y":
                b = set_field(b, "source", answer)
                what = f"source corrected to `{answer}`"
            else:
                what = "confirmed"
            b = set_field(b, "verdict", "confirmed")
            b = set_field(b, "note", f"{field(b, 'note') or ''} Ruled: {said}.".strip())
            bs[i] = b
        self.sources = pre + "".join("[[entry]]" + b for b in bs)
        then = "" if answer == "n" else "; the nightly refresh builds it"
        self.answered.append((issue["number"], f"`{key}`: {what}{then}"))
        self.lines.append(f"{key}: {what}")
        return None

    def discovery(self, issue, key, fp, reply, spoken_since):
        answer = parse_yes_no(reply["body"])
        if answer is None:
            return HELP["discovery"]
        verdict = "design" if answer == "y" else "not_design"
        append_discovery(DISCOVERIES, key, verdict, issue["number"])
        self.answered.append((issue["number"], f"recorded `{key}` as `{verdict}` in "
                                               f"seed/discoveries.toml"))
        self.lines.append(f"{key}: {verdict}")
        return None

    def write(self):
        # IN THE FILES' OWN FORMAT -- insertion order, two-space indent, final newline --
        # so an answer's diff is its own entry and nothing else. boards.toml goes through
        # the drafter's own renderer, which round-trips the file byte for byte.
        PHOTOS.write_text(json.dumps(self.photos, indent=2) + "\n")
        DECISIONS.write_text(json.dumps(self.decisions, indent=2) + "\n")
        if self.sources is not None:
            SOURCES.write_text(self.sources, encoding="utf-8")
        if self.boards is not None:
            from draft_boards import render
            BOARDS.write_text(render(self.boards), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--push", action="store_true")
    ap.add_argument("--owner", default="fowlie")
    args = ap.parse_args()

    tracker = GitHubIssues.from_env()
    if tracker.whoami() == args.owner:
        raise SystemExit(f"GH_TOKEN is {args.owner}'s own -- the bot needs its own "
                         f"account, or it would read its own comments as answers")

    dirty = [p for p in ALLOWED if p.exists()
             and git("status", "--porcelain", "--", str(p.relative_to(REPO)))]
    if dirty:
        raise SystemExit("uncommitted changes in files the bot writes, refusing: "
                         + ", ".join(str(p.relative_to(REPO)) for p in dirty))
    if args.push:
        # FETCH AND FAST-FORWARD, not `pull`: a `pull.rebase = true` config refuses to
        # run beside any uncommitted change, and a merge that only fast-forwards does not.
        git("fetch", "--quiet", "origin")
        git("merge", "--ff-only", "--quiet", "@{u}")

    run = Run(tracker)
    failed = []
    for kind in KIND_ORDER:
        handle = getattr(run, kind)
        for issue in tracker.issues(f"intake/{kind}"):
            m = MARKER.search(issue.get("body") or "")
            if not m or m[1] != kind:
                continue
            key, fp, number = m[2], m[3], issue["number"]
            reply, spoken_since = latest_owner_reply(tracker.comments(number), args.owner)
            if reply is None:
                continue
            # ONE BAD ISSUE MUST NOT SINK THE RUN. A crash here once left earlier answers
            # written but uncommitted, and the dirty-file guard above then refused every
            # poll for three days (2026-10-01 to 10-04). Report it, keep going, commit
            # what did work, and fail afterwards so the phone still hears about it.
            try:
                complaint = handle(issue, key, fp, reply, spoken_since)
            except Exception:  # noqa: BLE001
                traceback.print_exc()
                failed.append(number)
                continue
            if complaint and not spoken_since:
                tracker.comment(number, complaint)

    crashed = (f"crashed on {', '.join(f'#{n}' for n in failed)}, see the log above"
               if failed else None)
    answered = run.answered
    if not answered:
        print("no new answers")
        if crashed:
            print(crashed, file=sys.stderr)
            return 1
        return 0
    run.write()

    allowed = {str(p.relative_to(REPO)) for p in ALLOWED}
    changed = [p for p in sorted(allowed)
               if (REPO / p).exists() and git("status", "--porcelain", "--", p)]
    issues = ", ".join(f"#{n}" for n, _ in answered)
    git("add", "--", *changed)
    git("commit", "--quiet", "-m",
        f"Record the owner's answers to {issues}\n\n" + "\n".join(run.lines)
        + "\n\nWritten by scripts/intake_answer.py (backlog #104) from his replies.",
        "--", *changed)
    # CHECKED ON THE COMMIT ITSELF, not on the list handed to it: what matters is what
    # landed, and a pathspec or hook surprise would show up here and nowhere else.
    landed = set(git("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD").split())
    if landed - allowed:
        git("reset", "--soft", "HEAD~1")
        raise SystemExit(f"commit touched files outside the allowlist, undone: "
                         f"{sorted(landed - allowed)}")
    sha = git("rev-parse", "--short", "HEAD")
    if args.push:
        git("push", "--quiet", "origin", "HEAD:main")

    for number, note in answered:
        tracker.comment(number, f"Thanks — {note}, in {sha}.")
        tracker.close(number)
    print(f"committed {sha}: {len(answered)} answer(s) ({issues})"
          + ("" if args.push else " -- not pushed"))
    if crashed:
        print(crashed, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
