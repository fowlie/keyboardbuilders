#!/usr/bin/env python3
"""Write moderators' answers from /moderation into the seed -- backlogs #104, #117.

`intake_ask.py` queues questions in a file the site reads; a moderator answers one by
pressing a button, and the site stores the button's `reply` with their login. This reads
every answer not applied before (from the site's database, read-only), applies it through
the code that already owns each seed file, and commits the lot as one commit. The
moderator's answer is the ruling; this is the pen.

A QUESTION CARRIES WHAT ITS ANSWER NEEDS. A photo question holds its candidate URLs and its
repo, so an answer is applied from the question's own data and never from its markdown.
Until 2026-11-16 the answer side faked a GitHub issue (`number = 0`), regex'd the images out
of the body and ran a typed-reply parser over a button press; the fake number reached
seed/discoveries.toml and seed/photo-declines.toml as `issue = 0`.

THE CHOICES:
    photo      `N`     the Nth image: fetched, normalized and uploaded by `add_photo.add_one`,
                       recorded in `photos.json` and in `photo-decisions.json`
               `none`  no photo of this board among them  -> seed/photo-declines.toml
               `skip`  not sure, don't ask again          -> seed/photo-declines.toml
               a URL, or a URL and the page to credit: a photo from somewhere else, fetched
                       the same way and recorded with the `credit` the page shows
    board      `N`     board-set N is the record's: written to seed/boards.toml as
                       `review_boards.py --rule` writes it, `signal = "human"`, under the
                       drafted name
               `none`  the record's board is not in the repo -> `verdict = "no_board"`
    source     `y`     verdict = confirmed
               `n`     source = false, verdict = confirmed: "I looked, no repo is known"
    discovery  `y`     seed/discoveries.toml, verdict = design: queued for importing
               `n`     verdict = not_design: never asked about again

A decline carries the fingerprint of the images shown, so a README that changes is asked
about again (see intake_ask.py). A board or source question whose choices changed since it
was asked (the repo's boards, the proposed repo) is not applied; tonight's ask asks afresh.
Only the ruling is committed here. Rebuilding designs.json and measuring the new design
takes minutes and touches every derived file, so the nightly `refresh_corpus.sh` does it.

THE COMMIT TOUCHES ONLY WHAT IT WROTE. Every file it writes is on an allowlist, and a
file that already had uncommitted changes is refused before anything is written -- so a
developer's work in progress on the same checkout is never swept into the bot's commit.

Usage: intake_answer.py [--push]
    --push fast-forwards first and pushes the commit to main after. Without it the
    commit stays local, which is how it is tested on a dev machine.
"""
import argparse
import datetime
import json
import pathlib
import re
import sqlite3
import subprocess
import sys
import traceback
import tomllib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import add_photo  # noqa: E402
from intake_ask import DECISIONS, DECLINES, DISCOVERIES, board_choices, source_choices  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
PHOTOS = add_photo.SEED

BOARDS = REPO / "seed/boards.toml"
SOURCES = REPO / "seed/sources.toml"

# The files the bot may commit. Anything else is a bug in this script, not a judgement.
ALLOWED = {PHOTOS, DECISIONS, DECLINES, BOARDS, SOURCES, DISCOVERIES}

# What a question of each kind must carry for its answer to be applied. A question written
# before questions carried data (the file on the server, until the first ask after the
# deploy that brought this) lacks it, and waits for that ask rather than failing.
NEEDS = {"photo": ("images", "repo")}

HTTPS = re.compile(r"https://\S+")


class NotAnAnswer(Exception):
    pass


# A handler's answer for a question that no longer exists in the form it was answered: not
# applied and not a failure. The answer is used up, and tonight's ask asks afresh if needed.
STALE = "stale"


def choice(q, reply):
    """The moderator's `reply` as the choice it names: an int for a numbered candidate, a
    word (`none`, `skip`, `y`, `n`), or `(image, credit)` for a photo given by URL.

    Only the question's own buttons, and for a photo the URL field the site validated;
    anything else is refused rather than guessed at."""
    if any(c["reply"] == reply for c in q["choices"]):
        return int(reply) if reply.isdigit() else reply
    parts = reply.split()
    if q["kind"] == "photo" and 1 <= len(parts) <= 2 and all(HTTPS.fullmatch(p) for p in parts):
        return parts[0], parts[-1]
    raise NotAnAnswer(f"{reply!r} is not one of the question's answers")


def append_discovery(path, repo, verdict, by):
    """One [[entry]] per answer, in the ruling-file shape the rest of seed/ uses."""
    if not path.exists():
        path.write_text(
            "# seed/discoveries.toml -- repos found on GitHub that a moderator ruled on.\n"
            "# Written by scripts/intake_answer.py from an answer on /moderation (backlog\n"
            "# #105); read by scripts/intake_ask.py so nothing is asked twice.\n"
            "#\n"
            "#   design       an open keyboard design: queued for importing\n"
            "#   not_design   not one (a keymap, a tool, a case only, ...): never asked again\n"
            "#\n"
            "# The build reads nothing here yet. A `design` row publishes nothing by itself;\n"
            "# it is the queue #105's step 2 (reading firmware inside the repo) works from.\n")
    with path.open("a") as f:
        f.write(f'\n[[entry]]\nrepo = "{repo}"\nverdict = "{verdict}"\nmoderator = "{by}"\n')


def append_decline(slug, verdict, fp, by):
    """One [[entry]] per decline, in the ruling-file shape the rest of seed/ uses."""
    if not DECLINES.exists():
        DECLINES.write_text(
            "# seed/photo-declines.toml -- designs whose README photos a moderator looked at\n"
            "# and did not pick one from. Written by scripts/intake_answer.py from an answer\n"
            "# on /moderation (backlog #104); read by scripts/intake_ask.py.\n"
            "#\n"
            "#   none   no photo of this board among the images shown\n"
            "#   skip   not sure; don't ask again\n"
            "#\n"
            "# `images` fingerprints the candidates shown, so a README that gains or loses\n"
            "# an image is asked about again. The build reads nothing here: a decline\n"
            "# leaves the photo Unknown, never \"has no photo\".\n")
    with DECLINES.open("a") as f:
        f.write(f'\n[[entry]]\nslug = "{slug}"\nverdict = "{verdict}"\n'
                f'images = "{fp}"\nmoderator = "{by}"\n')


def ruled_by(login):
    return f"a moderator ({login}) on /moderation (intake bot, backlog #117)"


class Run:
    """One check's worth of answers: what gets written, and a line each for the commit.

    Each handler takes `(question, choice, login, key, fp)` and returns None when the
    answer is applied, STALE when its question no longer stands, or why it was not."""

    def __init__(self):
        self.photos = json.loads(PHOTOS.read_text())
        self.decisions = json.loads(DECISIONS.read_text()) if DECISIONS.exists() else {}
        self.boards = None        # boards.toml entries, loaded on the first board answer
        self.choices = None
        self.sources = None       # sources.toml text, edited in place, on first answer
        self.lines = []           # one line each for the commit message
        self.stale = []           # ids whose question changed since it was answered (#38)

    def photo(self, q, picked, by, key, fp):
        if picked in ("none", "skip"):
            append_decline(key, picked, fp, by)
            self.lines.append(f"{key}: {picked}")
            return None
        if isinstance(picked, tuple):
            (url, credit), which = picked, "that image"
        else:
            url, credit, which = q["images"][picked - 1], None, f"image {picked}"
        try:
            result = add_photo.add_one(url, REPO / "build/photos")
        except Exception as e:  # noqa: BLE001 -- any one bad URL, not just AddPhotoError
            return f"couldn't fetch {which}: {e}"
        self.photos[key] = {"jpg": result["jpg"], "width": result["width"],
                            "height": result["height"], "sourceUrl": credit or q["repo"]}
        self.decisions[key] = {"url": url, "repo": q["repo"]}
        if credit:
            self.photos[key]["credit"] = credit
            self.decisions[key]["credit"] = credit
        self.lines.append(f"{key}: photo {picked if credit is None else credit}")
        return None

    def board(self, q, picked, by, key, fp):
        if self.choices is None:
            self.choices = board_choices()
            self.boards = tomllib.loads(BOARDS.read_text())["entry"]
        now = self.choices.get(key)
        if now is None or now["fp"] != fp:
            # Not the moderator's error, and not their answer to apply: the question they
            # answered no longer exists in that form (settled elsewhere, or the repo's
            # boards changed). Tonight's ask asks afresh if it still needs asking.
            return STALE
        tree, rid = now["row"]["tree"], now["row"]["id"]
        row = next(e for e in self.boards if e["tree"] == tree and e["id"] == rid)
        if picked == "none":
            row.update(boards=[], name="", verdict="no_board", signal="human", note=ruled_by(by))
            self.lines.append(f"{key}: no_board")
            return None
        board = now["free"][picked - 1]
        if not board["name"]:
            return f"board {picked} has no drafted name; name it in a dev session"
        row.update(boards=sorted(board["paths"]), name=board["name"], verdict="confirmed",
                   signal="human", note=ruled_by(by))
        self.lines.append(f"{key}: board {picked} ({board['name']})")
        return None

    def source(self, q, picked, by, key, fp):
        from confirm_sources import blocks, field, set_field
        now = source_choices().get(key)
        if now is None or now["fp"] != fp:
            return STALE
        if self.sources is None:
            self.sources = SOURCES.read_text(encoding="utf-8")
        tree, rid = key.split(":", 1)
        pre, bs = blocks(self.sources)
        for i, b in enumerate(bs):
            if (field(b, "tree"), field(b, "id")) != (tree, rid):
                continue
            if picked == "n":
                b = set_field(b, "source", False)
            b = set_field(b, "verdict", "confirmed")
            b = set_field(b, "note", f"{field(b, 'note') or ''} Ruled: {ruled_by(by)}.".strip())
            bs[i] = b
        self.sources = pre + "".join("[[entry]]" + b for b in bs)
        self.lines.append(f"{key}: " + ("no repo known (`source = false`)" if picked == "n"
                                        else "confirmed"))
        return None

    def discovery(self, q, picked, by, key, fp):
        verdict = "design" if picked == "y" else "not_design"
        append_discovery(DISCOVERIES, key, verdict, by)
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


def git(*args):
    out = subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True)
    if out.returncode:
        raise SystemExit(f"git {' '.join(args)}: {out.stderr.strip()}")
    return out.stdout.strip()


def read_answers(db):
    """`{question_id: (reply, login)}` from the site's database, opened READ-ONLY: the
    site is the only writer. No database yet is no answers."""
    db = pathlib.Path(db)
    if not db.exists():
        return {}
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        return {qid: (reply, login) for qid, reply, login in
                con.execute("SELECT question_id, reply, by_login FROM moderation_answer")}
    finally:
        con.close()


def site_answers(run, questions_file, db, applied_file, checked=None):
    """Apply every answer not applied before. Returns `(used ids, failures)`: an answer is
    used once applied, or once its question turned out stale.

    `applied_file` is the bot's own memory of what it has written, so nothing is applied
    twice. An answer whose question is no longer in the file is left alone: tonight's
    queue no longer holds it, so nothing describes what it answered.
    """
    applied_file = pathlib.Path(applied_file)
    done = set(json.loads(applied_file.read_text())) if applied_file.exists() else set()
    questions_file = pathlib.Path(questions_file)
    by_id = ({q["id"]: q for q in json.loads(questions_file.read_text())["questions"]}
             if questions_file.exists() else {})
    ok, failed = [], []
    answers = read_answers(db)
    # STAMPED RIGHT AFTER THE READ (#117). The site lets a moderator undo an answer given
    # after this stamp. Stamping before the read would let one the bot just read be
    # "undone" on the site but applied anyway; stamping after the slow work below would
    # hide answers given meanwhile, though the next check still applies them.
    if checked:
        write_checked(checked, datetime.datetime.now(datetime.timezone.utc)
                      .strftime("%Y-%m-%dT%H:%M:%SZ"))
    for qid, (reply, login) in sorted(answers.items()):
        if qid in done or qid not in by_id:
            continue
        q = by_id[qid]
        kind, key, fp = parse_id(qid)
        if any(field not in q for field in NEEDS.get(kind, ())):
            continue
        try:
            problem = getattr(run, kind)(q, choice(q, reply), login, key, fp)
        except NotAnAnswer as e:
            problem = str(e)
        except Exception:  # noqa: BLE001 -- one bad answer must not sink the run
            traceback.print_exc()
            problem = "crashed, see the log"
        if problem == STALE:
            print(f"{qid}: its question changed since it was answered; not applied")
            run.stale.append(qid)
            ok.append(qid)
        elif problem:
            failed.append(f"{qid}: {problem}")
        else:
            ok.append(qid)
    return ok, failed


def stale_line(ids):
    """The check's one line about answers thrown away as stale, or None if there were none.

    SAID ON ITS OWN LINE, for intake_poll.sh to notify (#38). A stale answer counts as
    handled, so the check ended with "no new answers" for nine days while every board and
    source answer was being thrown away by a parsing bug."""
    if not ids:
        return None
    return f"{len(ids)} answer(s) not applied, their question changed: " + ", ".join(ids)


def parse_id(qid):
    """`kind:key:fingerprint` -> its three parts. A board or source key carries its tree
    (`qmk:ai03/orbit`), so only the first and the last colon split."""
    kind, rest = qid.split(":", 1)
    key, fp = rest.rsplit(":", 1)
    return kind, key, fp


def write_checked(path, at):
    """Stamp when this check read the answers, for the site (#117): it shows a moderator
    when the next check comes, and lets an answer given AFTER this stamp be undone, since
    no check has read it yet. Temp file and rename, like the questions file."""
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps({"at": at}) + "\n")
    tmp.replace(path)


def mark_applied(applied_file, ids):
    """Remember `ids` as written. Called only AFTER the commit, so a failed commit or push
    leaves them to be applied again rather than silently lost."""
    applied_file = pathlib.Path(applied_file)
    done = set(json.loads(applied_file.read_text())) if applied_file.exists() else set()
    applied_file.parent.mkdir(parents=True, exist_ok=True)
    applied_file.write_text(json.dumps(sorted(done | set(ids)), indent=1) + "\n")


def refuse_dirty():
    dirty = [p for p in ALLOWED if p.exists()
             and git("status", "--porcelain", "--", str(p.relative_to(REPO)))]
    if dirty:
        raise SystemExit("uncommitted changes in files the bot writes, refusing: "
                         + ", ".join(str(p.relative_to(REPO)) for p in dirty))


def commit_answers(run, subject, push):
    """Write, commit only the allowlisted files, push. Returns the short sha."""
    run.write()
    allowed = {str(p.relative_to(REPO)) for p in ALLOWED}
    changed = [p for p in sorted(allowed)
               if (REPO / p).exists() and git("status", "--porcelain", "--", p)]
    git("add", "--", *changed)
    git("commit", "--quiet", "-m", subject + "\n\n" + "\n".join(run.lines), "--", *changed)
    # CHECKED ON THE COMMIT ITSELF, not on the list handed to it: what matters is what
    # landed, and a pathspec or hook surprise would show up here and nowhere else.
    landed = set(git("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD").split())
    if landed - allowed:
        git("reset", "--soft", "HEAD~1")
        raise SystemExit(f"commit touched files outside the allowlist, undone: "
                         f"{sorted(landed - allowed)}")
    if push:
        git("push", "--quiet", "origin", "HEAD:main")
    return git("rev-parse", "--short", "HEAD")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--push", action="store_true")
    ap.add_argument("--questions-file", type=pathlib.Path,
                    default=pathlib.Path("/var/lib/keyboardbuilders/moderation/questions.json"))
    ap.add_argument("--answers-db", type=pathlib.Path,
                    default=pathlib.Path("/var/lib/keyboardbuilders/app.db"))
    ap.add_argument("--applied", type=pathlib.Path,
                    default=pathlib.Path("/var/lib/intake/applied-answers.json"))
    args = ap.parse_args()

    refuse_dirty()
    if args.push:
        # FETCH AND FAST-FORWARD, not `pull`: a `pull.rebase = true` config refuses to
        # run beside any uncommitted change, and a merge that only fast-forwards does not.
        git("fetch", "--quiet", "origin")
        git("merge", "--ff-only", "--quiet", "@{u}")
    run = Run()
    ok, failed = site_answers(run, args.questions_file, args.answers_db, args.applied,
                              checked=args.questions_file.with_name("checked.json"))
    if run.lines:
        sha = commit_answers(run, f"Record {len(run.lines)} moderator answer(s) from /moderation",
                             args.push)
        print(f"committed {sha}: {len(run.lines)} answer(s)" + ("" if args.push else " -- not pushed"))
    else:
        print("no new answers")
    if stale_line(run.stale):
        print(stale_line(run.stale))
    # AFTER THE COMMIT, so a failed commit or push leaves them to be applied again.
    mark_applied(args.applied, ok)
    for f in failed:
        print(f, file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
