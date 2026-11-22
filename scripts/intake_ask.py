#!/usr/bin/env python3
"""Queue the day's questions on the site's moderator page, /moderation -- backlogs #104, #117.

A moderator answers by pressing a button; `intake_answer.py` reads the answer from the
site's database and writes it into the seed. This script only ASKS. It writes nothing to
the repo and rules on nothing, so a run can never change what the site shows.

A QUESTION IS DATA. Each carries its `id` (`kind:key:fingerprint`), a `title` and a
markdown `body` the page shows, its `choices` (the buttons), and whatever its answer needs
to be applied -- a photo question its candidate image URLs and its repo -- so nothing has
to be read back out of the body's markdown. Until 2026-11-16 the body was a GitHub issue's,
and the answer side regex'd the images out of it.

PHOTO. A design that `draft_photo_candidates.py` found repo images for,
that has no photo in `photos.json`, no pick in `photo-decisions.json` and no "none" or
"skip" in `seed/photo-declines.toml`, gets one question: its silhouette from the live site
(`/designs/<slug>/thumbnail.png`, which is the silhouette for any design without a photo)
above the numbered candidates. The silhouette is `photo_contact_sheet.py`'s safety device
carried over: a photo of the wrong board is obvious next to the right board's own shape.

A DECLINE IS KEYED TO THE IMAGES IT SAW. `photo-declines.toml` stores a fingerprint of
the candidate list, so a README that later gains a photo is asked about again, and one
that did not change is never asked twice.

SHARED REPOS ARE NAMED IN THE QUESTION. Variants of one repo are offered the same README
images (the Lily58 mistake, `aa9971a`), so a question whose images another design is also
offered says so, and says to pick a photo of THIS variant or answer none.

BOARD. A repo with exactly ONE drafted `seed/boards.toml` row and at least one unclaimed
board-set that could publish -- it places a switch AND names a controller, the two facts
`build_seed.py` refuses to invent (the same test as `review_boards.py --forecast`) -- gets
one question: the firmware record's own facts above the numbered board-sets, each with the
evidence `review_boards.py --repo` prints. A repo with several queued rows is an
ASSIGNMENT, not a choice, and stays a dev-session job; so does a repo whose boards can
publish nothing however they are ruled.

SOURCE. A drafted `seed/sources.toml` row -- a machine proposed which repo holds a
firmware record's hardware, and its note already carries the checks (owner match, not a
fork, licence) -- gets one yes/no question. The row's verdict is the only thing missing.

DISCOVERY. A repo `discover_repos.py` found on GitHub -- created lately, holding a KiCad
board, not in the corpus -- that has no answer in `seed/discoveries.toml` gets one yes/no
question (#105). Yes queues it for importing; no is final.

THE CAP IS SHARED OUT BY WEIGHT across the kinds (pick), so a backlog of one kind
cannot keep another off the queue.

THE WHOLE QUEUE IS REWRITTEN each run: a question ruled since last night is no longer
generated, so it drops out; an unruled one keeps its id, so an answer already given still
matches it.

Usage: intake_ask.py [--site-queue FILE] [--cap 30] [--site https://keyboardbuilders.net]
"""
import argparse
import hashlib
import json
import pathlib
import sys
import tomllib
from collections import defaultdict

REPO = pathlib.Path(__file__).resolve().parent.parent
SCRATCH = REPO / "data"
DESIGNS = REPO / "src/main/resources/seed/designs.json"
PHOTOS = REPO / "src/main/resources/seed/photos.json"
CANDIDATES = SCRATCH / "photo-candidates.json"
DECISIONS = SCRATCH / "photo-decisions.json"
DECLINES = REPO / "seed/photo-declines.toml"
DISCOVERED = SCRATCH / "discovered.json"
DISCOVERIES = REPO / "seed/discoveries.toml"

# A README can be a whole build-guide gallery -- one has 51 images. Past this many the
# issue is a long scroll on a phone, and the first dozen is where a README puts its
# hero shot; the rest are named as a count with a link to the README itself.
MAX_IMAGES = 12

# Photos first, per the owner (2026-09-27). Later kinds slot in after it.
KIND_ORDER = ["photo", "board", "source", "discovery"]

# The site's copy of the queue, which it reads on every request (Moderation.kt).
QUEUE = pathlib.Path("/var/lib/keyboardbuilders/moderation/questions.json")


def fingerprint(urls):
    return hashlib.sha1("\n".join(urls).encode()).hexdigest()[:12]


def load_declines():
    if not DECLINES.exists():
        return {}
    rows = tomllib.loads(DECLINES.read_text()).get("entry", [])
    return {r["slug"]: r["images"] for r in rows}


def photo_questions(site):
    designs = {d["slug"]: d for d in json.loads(DESIGNS.read_text())}
    photos = json.loads(PHOTOS.read_text())
    decisions = json.loads(DECISIONS.read_text()) if DECISIONS.exists() else {}
    declines = load_declines()
    candidates = json.loads(CANDIDATES.read_text())

    by_images = defaultdict(list)
    for slug, cand in candidates.items():
        by_images[fingerprint([i["url"] for i in cand["images"]])].append(slug)

    # The picks already made, by image URL: an image that is another design's photo is
    # nearly always a photo of THAT design, and the issue says so beside it.
    used = defaultdict(list)
    for other, pick in decisions.items():
        used[pick["url"]].append(other)

    questions = []
    for slug, cand in sorted(candidates.items()):
        # A slug no longer published (renamed, declined) has no page to put a photo on.
        if slug not in designs or slug in photos or slug in decisions or not cand["images"]:
            continue
        siblings = [s for s in by_images[fingerprint([i["url"] for i in cand["images"]])]
                    if s != slug]
        q = photo_question(slug, designs[slug]["name"], cand, siblings, site, used)
        if declines.get(slug) != q["id"].rsplit(":", 1)[1]:
            questions.append(q)
    return questions


def photo_question(slug, name, cand, siblings, site, used):
    shown = cand["images"][:MAX_IMAGES]
    urls = [i["url"] for i in shown]
    lines = [
        f"**Which photo shows {name}?** Its silhouette, from the live site:",
        "",
        f"![silhouette of {slug}]({site}/designs/{slug}/thumbnail.png)",
        "",
        f"[Design page]({site}/designs/{slug}) · [Repo]({cand['repo']})",
    ]
    if siblings:
        lines += ["", f"⚠️ The same images are offered to **{', '.join(sorted(siblings))}**. "
                      f"Pick one only if it shows *this* variant; otherwise answer none."]
    lines += [""]
    for n, image in enumerate(shown, 1):
        alt = (f" — alt text: “{image['alt']}”" if image.get("alt")
               else f" — file `{image['path']}`, not in the README" if image.get("path") else "")
        taken = (f" — **already the photo of {', '.join(sorted(used[image['url']]))}**"
                 if used.get(image["url"]) else "")
        lines += [f"### {n}{alt}{taken}", "", f"![{n}]({image['url']})", ""]
    rest = len(cand["images"]) - len(shown)
    if rest > 0:
        lines += [f"*{rest} more image(s) in the repo are not shown here.*"]
    return question("photo", slug, fingerprint(urls), f"Photo: {name} ({slug})", lines,
                    n=len(shown), images=urls, repo=cand["repo"])


def question(kind, key, fp, title, lines, n=None, **data):
    """One question as the site reads it, plus what its answer needs (`data`)."""
    return {"id": f"{kind}:{key}:{fp}", "kind": kind, "slug": key, "title": title,
            "body": "\n".join(lines).strip(), "choices": choices_for(kind, n), **data}


def board_choices():
    """`tree:id` -> the one queued row, its repo, its free board-sets and their fingerprint.

    Shared with `intake_answer.py`, which recomputes it rather than trusting the issue's
    text: the body shows one file per set, and a repo that changed since the question was
    asked must not have an old answer applied to new boards.
    """
    from draft_boards import load_boards
    from review_boards import board_facts

    scan_path = SCRATCH / "board-scan.json"
    scan = json.loads(scan_path.read_text())
    boards, names, _, _ = load_boards(str(scan_path))
    entries = tomllib.loads((REPO / "seed/boards.toml").read_text())["entry"]
    published = set()
    for info in json.loads((SCRATCH / "design-boards.json").read_text()).values():
        for path in info["paths"] or []:
            published.add((info["repo"], path))

    queued = defaultdict(list)
    for e in entries:
        if e["verdict"] == "drafted" and e["signal"] != "elsewhere":
            queued[e["source"]].append(e)

    choices = {}
    for repo, rows in sorted(queued.items()):
        if len(rows) != 1:
            continue
        free = []
        for bset in boards.get(repo, []):
            if any((repo, p) in published for p in bset):
                continue
            fams, ctrl = board_facts(bset, repo, scan)
            if fams and ctrl != "?":
                free.append({"paths": bset, "families": fams, "controller": ctrl,
                             "name": names.get((repo, tuple(bset))) or ""})
        if not free:
            continue
        key = f"{rows[0]['tree']}:{rows[0]['id']}"
        fp = fingerprint([key] + ["|".join(b["paths"]) for b in free])
        choices[key] = {"row": rows[0], "repo": repo, "free": free, "fp": fp, "scan": scan}
    return choices


def board_questions():
    import trees as tree_registry
    from review_boards import record_facts

    reg = tree_registry.load()
    questions = []
    for key, c in board_choices().items():
        row = c["row"]
        if row["tree"] in reg and reg.reader(row["tree"]) == "qmk":
            counts, rctrl, split = record_facts(row["id"], reg.root(row["tree"]))
        else:
            counts, rctrl, split = [], "(ZMK shield: see its own files)", False
        questions.append(question("board", key, c["fp"],
                                  f"Board: which board is {key}'s? ({c['repo']})",
                                  board_body(key, c, counts, rctrl, split), n=len(c["free"])))
    return questions


def board_body(key, c, counts, rctrl, split):
    from review_boards import history
    repo, row, free, scan = c["repo"], c["row"], c["free"], c["scan"]
    lines = [
        f"**Which board in [{repo}](https://github.com/{repo}) is the firmware record "
        f"`{key}`'s?**",
        "",
        f"The record says: **{', '.join(map(str, counts)) or '?'} keys**, {rctrl}"
        f"{', split' if split else ''}.",
        "",
        f"The drafter couldn't call it: {row['note']}",
        "",
    ]
    for n, board in enumerate(free, 1):
        bset, fams, ctrl, name = (board["paths"], board["families"], board["controller"],
                                  board["name"] or "?")
        switches = sorted({scan.get(f"{repo}\t{p}", {}).get("switches") for p in bset}
                          - {None})
        folder = bset[0].rsplit("/", 1)[0] if "/" in bset[0] else ""
        lines += [
            f"### {n} — {name}",
            "",
            f"[`{bset[0]}`](https://github.com/{repo}/tree/HEAD/{folder})"
            + (f" (+{len(bset) - 1} more file(s))" if len(bset) > 1 else ""),
            "",
            f"{', '.join(fams)} · {ctrl} · "
            f"{'/'.join(map(str, switches)) or '?'} switch footprints",
        ]
        commits, why = history(repo, bset[0], limit=3)
        lines += ["", f"History: {why}"] + [f"- {c}" for c in commits or []] + [""]
    return lines


def source_choices():
    """`tree:id` -> a drafted sources.toml row and the fingerprint of what it proposes."""
    rows = tomllib.loads((REPO / "seed/sources.toml").read_text())["entry"]
    out = {}
    for r in rows:
        if r["verdict"] != "drafted" or not r.get("source"):
            continue
        key = f"{r['tree']}:{r['id']}"
        out[key] = {"row": r, "fp": fingerprint([key, str(r["source"]), str(r.get("path"))])}
    return out


def source_questions():
    questions = []
    for key, c in source_choices().items():
        r = c["row"]
        where = f"https://github.com/{r['source']}" + (
            f"/tree/HEAD/{r['path']}" if r.get("path") else "")
        lines = [
            f"**Is [{r['source']}]({where}) the hardware for the firmware record `{key}`"
            + (f" ({r['name']})" if r.get("name") else "") + "?**",
            "",
            f"Proposed by: `{r.get('signal')}`" + (f" · path `{r['path']}`"
                                                   if r.get("path") else ""),
            "",
            f"> {r.get('note') or '(no note)'}",
        ]
        questions.append(question("source", key, c["fp"],
                                  f"Source: is {r['source']} {key}'s hardware?", lines))
    return questions


# GitHub's own placeholders, said in words a phone reader needs no glossary for.
LICENCE_WORDS = {None: "none found", "NOASSERTION": "a file GitHub can't identify"}


def ruled_discoveries():
    """Repos the owner has already answered for, lowercased."""
    if not DISCOVERIES.exists():
        return set()
    return {r["repo"].lower() for r in tomllib.loads(DISCOVERIES.read_text())["entry"]}


def discovery_questions(found=None, ruled=None):
    """One yes/no per repo `discover_repos.py` found that nobody has ruled on (#105).

    The key is the repo itself and the fingerprint is fixed: the question is "is this a
    keyboard design", which a new commit does not change, so a `n` is final.
    """
    if found is None:
        found = json.loads(DISCOVERED.read_text()) if DISCOVERED.exists() else {}
    if ruled is None:
        ruled = ruled_discoveries()
    questions = []
    for repo, c in sorted(found.get("candidates", {}).items()):
        if repo.lower() in ruled:
            continue
        fp = fingerprint([repo])
        pcb = c["pcb"][:5] + ([f"… and {len(c['pcb']) - 5} more"] if len(c["pcb"]) > 5 else [])
        lines = [
            f"**Is [{repo}](https://github.com/{repo}) a keyboard design the site should "
            f"have?**",
            "",
            f"> {c['description'] or '(no description)'}",
            "",
            f"Created {c['created']} · {c['stars']} stars · licence "
            f"{LICENCE_WORDS.get(c['licence'], c['licence'])}",
            "",
            "KiCad boards: " + ", ".join(f"`{p}`" for p in pcb)
            + (f" — the largest places {c['switches']} switches" if c.get("switches") else ""),
            "",
            "Firmware signs: " + (", ".join(c["firmware"]) or "none found (that proves "
                                  "nothing either way)"),
        ]
        questions.append(question("discovery", repo, fp,
                                  f"Found: is {repo} a keyboard design?", lines))
    return questions


# -- The site's moderator queue, backlog #117 ------------------------------------------

def choices_for(kind, n):
    """The buttons for a question. `reply` is what the site stores and `intake_answer.choice`
    reads back. No free text (owner, 2026-11-15) beyond a photo question's URL field."""
    yes_no = [{"reply": "y", "label": "Yes"}, {"reply": "n", "label": "No"}]
    numbered = [{"reply": str(i), "label": str(i)} for i in range(1, (n or 0) + 1)]
    return {
        "discovery": yes_no,
        "source": [{"reply": "y", "label": "Yes, that's its repo"},
                   {"reply": "n", "label": "No repo is known"}],
        "photo": numbered + [{"reply": "none", "label": "None of these"},
                             {"reply": "skip", "label": "Not sure, skip"}],
        "board": numbered + [{"reply": "none", "label": "Its board isn't in the repo"}],
    }[kind]


# How many of each kind one round hands out. Photos come first and most: a design with
# no picture is the weakest page on the site, and a photo answer is the quickest to give.
# Discovery is next, since it is the only way a new design arrives.
WEIGHTS = {"photo": 3, "discovery": 2, "board": 1, "source": 1}


def pick(questions, cap):
    """At most `cap` questions, shared out by WEIGHTS so that no kind is starved.

    A single cap over a list sorted by kind let the earlier kinds fill the whole queue
    for as long as they sat unanswered, so discovery questions -- the only way a new
    design arrives -- were never shown. Each round now takes up to WEIGHTS[kind] of each
    kind, and what a kind does not have goes to the others in later rounds. Within a
    kind the order is the order generated. The result is grouped by kind, in KIND_ORDER.
    """
    by_kind = {k: [q for q in questions if q["kind"] == k] for k in KIND_ORDER}
    taken = {k: 0 for k in KIND_ORDER}
    left = cap
    while left > 0:
        progressed = False
        for k in sorted(KIND_ORDER, key=lambda k: -WEIGHTS[k]):
            for _ in range(WEIGHTS[k]):
                if left > 0 and taken[k] < len(by_kind[k]):
                    taken[k] += 1
                    left -= 1
                    progressed = True
        if not progressed:
            break
    return [q for k in KIND_ORDER for q in by_kind[k][:taken[k]]]


def write_questions_file(path, questions):
    """Write the whole queue at once: to a temp file beside it, then a rename, so the
    site never reads half a file."""
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps({"questions": questions}, indent=1, ensure_ascii=False) + "\n")
    tmp.replace(path)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--site-queue", type=pathlib.Path, metavar="FILE", default=QUEUE,
                    help="where the site reads the questions")
    ap.add_argument("--cap", type=int, default=30)
    ap.add_argument("--site", default="https://keyboardbuilders.net")
    args = ap.parse_args()

    questions = (photo_questions(args.site) + board_questions() + source_questions()
                 + discovery_questions())
    # The cap keeps a night's work bounded.
    todo = pick(questions, args.cap)
    write_questions_file(args.site_queue, todo)
    print(f"queued {len(todo)} question(s) on /moderation; "
          f"{len(questions) - len(todo)} more waiting")
    return 0


if __name__ == "__main__":
    sys.exit(main())
