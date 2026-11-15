#!/usr/bin/env python3
"""Open the day's questions for the owner as GitHub issues -- backlog #104, piece 2.

The owner answers from his phone by replying to the issue; `intake_answer.py` reads the
reply and writes it into the seed. This script only ASKS. It writes nothing to the repo
and rules on nothing, so a run can never change what the site shows.

PHOTO. A design that `draft_photo_candidates.py` found repo images for,
that has no photo in `photos.json`, no pick in `photo-decisions.json` and no "none" or
"skip" in `seed/photo-declines.toml`, gets one issue: its silhouette from the live site
(`/designs/<slug>/thumbnail.png`, which is the silhouette for any design without a photo)
above the numbered candidates. The silhouette is `photo_contact_sheet.py`'s safety device
carried over: a photo of the wrong board is obvious next to the right board's own shape.

A DECLINE IS KEYED TO THE IMAGES IT SAW. `photo-declines.toml` stores a fingerprint of
the candidate list, so a README that later gains a photo is asked about again, and one
that did not change is never asked twice.

SHARED REPOS ARE NAMED IN THE ISSUE. Variants of one repo are offered the same README
images (the Lily58 mistake, `aa9971a`), so an issue whose images another design is also
offered says so, and says to pick a photo of THIS variant or answer none.

BOARD. A repo with exactly ONE drafted `seed/boards.toml` row and at least one unclaimed
board-set that could publish -- it places a switch AND names a controller, the two facts
`build_seed.py` refuses to invent (the same test as `review_boards.py --forecast`) -- gets
one issue: the firmware record's own facts above the numbered board-sets, each with the
evidence `review_boards.py --repo` prints. A repo with several queued rows is an
ASSIGNMENT, not a choice, and stays a dev-session job; so does a repo whose boards can
publish nothing however they are ruled.

SOURCE. A drafted `seed/sources.toml` row -- a machine proposed which repo holds a
firmware record's hardware, and its note already carries the checks (owner match, not a
fork, licence) -- gets one yes/no issue. The row's verdict is the only thing missing.

DISCOVERY. A repo `discover_repos.py` found on GitHub -- created lately, holding a KiCad
board, not in the corpus -- that has no answer in `seed/discoveries.toml` gets one yes/no
issue (#105). `y` queues it for importing; `n` is final.

ASKED ALREADY means an OPEN issue carrying the same marker. GitHub is the state, not a
file on the VPS: a closed issue has either been answered -- and its answer is in the
seed, so the design drops out above -- or was closed by hand, which is a "not now" this
run respects until the answer loop records it.

Usage: intake_ask.py [--dry-run DIR] [--cap 20] [--site https://keyboardbuilders.net]
    --dry-run writes each issue as DIR/<kind>-<slug>.md and opens nothing; it needs no
    token. Without it, GH_TOKEN must be the bot's (see github_issues.py).
"""
import argparse
import hashlib
import json
import pathlib
import re
import sys
import tomllib
from collections import defaultdict

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from github_issues import GitHubIssues  # noqa: E402

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

# kind, key, fingerprint. A key is a slug for a photo and `tree:id` for a board, so it may
# hold colons and slashes; the greedy key backtracks to the 12-hex fingerprint's colon.
MARKER = re.compile(r"<!-- intake:(\w+):(\S+):([0-9a-f]{12}) -->")


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
        shown = cand["images"][:MAX_IMAGES]
        fp = fingerprint([i["url"] for i in shown])
        if declines.get(slug) == fp:
            continue
        siblings = [s for s in by_images[fingerprint([i["url"] for i in cand["images"]])]
                    if s != slug]
        questions.append({
            "kind": "photo", "slug": slug, "fp": fp, "n": len(shown),
            "title": f"Photo: {designs[slug]['name']} ({slug})",
            "body": photo_body(slug, designs[slug]["name"], cand, shown, siblings, fp, site,
                               used),
        })
    return questions


def photo_body(slug, name, cand, shown, siblings, fp, site, used):
    lines = [
        f"**Which photo shows {name}?** Its silhouette, from the live site:",
        "",
        f"![silhouette of {slug}]({site}/designs/{slug}/thumbnail.png)",
        "",
        f"[Design page]({site}/designs/{slug}) · [Repo]({cand['repo']})",
    ]
    if siblings:
        lines += ["", f"⚠️ The same images are offered to **{', '.join(sorted(siblings))}**. "
                      f"Pick one only if it shows *this* variant; otherwise reply `none`."]
    lines += [""]
    for n, image in enumerate(shown, 1):
        alt = (f" — alt text: “{image['alt']}”" if image.get("alt")
               else f" — file `{image['path']}`, not in the README" if image.get("path") else "")
        taken = (f" — **already the photo of {', '.join(sorted(used[image['url']]))}**"
                 if used.get(image["url"]) else "")
        lines += [f"### {n}{alt}{taken}", "", f"![{n}]({image['url']})", ""]
    rest = len(cand["images"]) - len(shown)
    if rest > 0:
        lines += [f"*{rest} more image(s) in the repo are not shown here.*", ""]
    lines += [
        "---",
        f"Reply with **`1`–`{len(shown)}`**, **`none`** (no photo of this board here), "
        f"**`skip`** (don't ask again unless the repo's images change), or **an image URL** "
        f"from somewhere else, optionally followed by the page to credit "
        f"(`https://…/photo.jpg https://kbd.news/…`).",
        "",
        f"<!-- intake:photo:{slug}:{fp} -->",
    ]
    return "\n".join(lines)


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
        questions.append({
            "kind": "board", "slug": key, "fp": c["fp"], "n": len(c["free"]),
            "title": f"Board: which board is {key}'s? ({c['repo']})",
            "body": board_body(key, c, counts, rctrl, split),
        })
    return questions


def board_body(key, c, counts, rctrl, split):
    from review_boards import history
    repo, row, free, scan, fp = c["repo"], c["row"], c["free"], c["scan"], c["fp"]
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
    lines += [
        "---",
        f"Reply **`1`–`{len(free)}`** to confirm that board under its drafted name, "
        f"**`2 Some Name`** to confirm it and name the design, or **`none`** if this "
        f"record's board isn't in the repo.",
        "",
        f"<!-- intake:board:{key}:{fp} -->",
    ]
    return "\n".join(lines)


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
        body = "\n".join([
            f"**Is [{r['source']}]({where}) the hardware for the firmware record `{key}`"
            + (f" ({r['name']})" if r.get("name") else "") + "?**",
            "",
            f"Proposed by: `{r.get('signal')}`" + (f" · path `{r['path']}`"
                                                   if r.get("path") else ""),
            "",
            f"> {r.get('note') or '(no note)'}",
            "",
            "---",
            "Reply **`y`** to confirm, **`n`** if no repo is known for it, or "
            "**`owner/repo`** to confirm a different repo.",
            "",
            f"<!-- intake:source:{key}:{c['fp']} -->",
        ])
        questions.append({"kind": "source", "slug": key, "fp": c["fp"],
                          "title": f"Source: is {r['source']} {key}'s hardware?",
                          "body": body})
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
            "",
            "---",
            "Reply **`y`** if it's an open keyboard design (it joins the queue for "
            "importing), or **`n`** if not (never asked again).",
            "",
            f"<!-- intake:discovery:{repo}:{fp} -->",
        ]
        questions.append({"kind": "discovery", "slug": repo, "fp": fp,
                          "title": f"Found: is {repo} a keyboard design?",
                          "body": "\n".join(lines)})
    return questions


# -- The site's moderator queue, backlog #117 ------------------------------------------

def choices_for(kind, n):
    """The buttons for a question. Each `reply` is exactly what a reply on the old GitHub
    issue said, so intake_answer's parsers read it unchanged. No free text (owner,
    2026-11-15): a photo URL or a corrected repo is a dev session's job now."""
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


def for_site(q):
    """A question as the site reads it. The issue's footer (how to reply) and its hidden
    marker go: the buttons replace both, and `id` carries what the marker did."""
    body = q["body"]
    cut = body.rfind("\n---\n")
    if cut != -1:
        body = body[:cut]
    body = re.sub(r"\s*<!-- intake:.*?-->\s*", "\n", body).strip()
    return {"id": f"{q['kind']}:{q['slug']}:{q['fp']}", "kind": q["kind"], "slug": q["slug"],
            "title": q["title"], "body": body, "choices": choices_for(q["kind"], q.get("n"))}


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
    ap.add_argument("--dry-run", type=pathlib.Path, metavar="DIR")
    ap.add_argument("--cap", type=int, default=20)
    ap.add_argument("--site", default="https://keyboardbuilders.net")
    ap.add_argument("--site-queue", type=pathlib.Path, metavar="FILE",
                    help="write the questions to the site's moderator queue (#117), "
                         "not GitHub issues")
    args = ap.parse_args()

    questions = (photo_questions(args.site) + board_questions() + source_questions()
                 + discovery_questions())
    questions.sort(key=lambda q: KIND_ORDER.index(q["kind"]))

    if args.site_queue:
        # THE WHOLE QUEUE, REWRITTEN: a question ruled since last night is no longer
        # generated, so it drops out; an unruled one keeps its id, so an answer already
        # given still matches it. The cap still keeps a night's work bounded.
        todo = questions[:args.cap]
        write_questions_file(args.site_queue, [for_site(q) for q in todo])
        print(f"queued {len(todo)} question(s) on /moderation; "
              f"{len(questions) - len(todo)} more waiting")
        return 0

    tracker = None if args.dry_run else GitHubIssues.from_env()
    open_now = set()
    if tracker:
        for kind in KIND_ORDER:
            for issue in tracker.issues(f"intake/{kind}"):
                m = MARKER.search(issue.get("body") or "")
                if m:
                    open_now.add((m[1], m[2]))
    waiting = [q for q in questions if (q["kind"], q["slug"]) not in open_now]
    todo = waiting[:args.cap]

    if args.dry_run:
        args.dry_run.mkdir(parents=True, exist_ok=True)
        for q in todo:
            safe = re.sub(r"[^A-Za-z0-9_.-]", "_", q["slug"])
            (args.dry_run / f"{q['kind']}-{safe}.md").write_text(
                f"# {q['title']}\n\n{q['body']}\n")
    else:
        labels = {k: tracker.label_id(f"intake/{k}") for k in KIND_ORDER}
        for q in todo:
            issue = tracker.open_issue(q["title"], q["body"], [labels[q["kind"]]])
            print(f"  #{issue['number']}  {q['title']}")

    # One line a notification can carry as it is.
    verb = "would open" if args.dry_run else "opened"
    print(f"{verb} {len(todo)} question(s); {len(waiting) - len(todo)} more waiting, "
          f"{len(open_now)} already open")
    return 0


if __name__ == "__main__":
    sys.exit(main())
