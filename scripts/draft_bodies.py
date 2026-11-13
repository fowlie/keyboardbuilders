#!/usr/bin/env python3
"""Queue the designs whose one-piece-or-two we get from the wrong question, and record
the ruling.

`build_seed.py` takes `body` from QMK's `split.enabled`. That flag means ***"this
firmware drives two halves over QMK's split transport"*** -- it is a question about
wiring, and we print the answer as a question about shape. A two-piece keyboard whose
halves are joined by an I/O expander presents to QMK as one matrix and correctly sets
nothing, so `torn`, `wired-pierce` and `3w6` publish as **unibody** while their own
authors call them split. `body` is in the facet rail, so `?body=unibody` returns split
keyboards and `?body=split` hides them. Forgejo #35.

    ~/code/qmk_firmware                    layout macros and `split.enabled`  (machine)
    data/board-scan.json     the board files a design carries   (machine)
    src/main/resources/seed/designs.json   what the build published last      (machine)
    seed/bodies.toml                       one piece or two                   <- this script

THE REAL SIGNAL IS THE CONNECTOR AND IT IS NOT AVAILABLE YET. A board that places a
TRRS jack is not a unibody, and that is a physical fact readable from the board file --
map ticket 52, open and waiting on an
owner ruling. This file does not pre-empt it: it is an override for eleven designs we
currently state wrongly, and when the connector lands it should shrink or vanish.

THE DRAFTER PROPOSES NOTHING, the same design as `draft_licences.py` and for the same
reason -- the best signal available is measured too weak to be a verdict:

    LAYOUT_split_RxC_T   QMK's own convention for a split matrix. 43 designs carry
                         one, 32 are already recorded split, and 11 are not. But
                         `chouchou` carries `LAYOUT_split_2x4_2` and its README says
                         *"a minimalist unibody keyboard designed to be used with the
                         Taipo layout"*. The macro names a matrix SHAPE, and a monoblock
                         can have one -- `andean-condor` calls itself a "48 key monoblock
                         split keyboard" in as many words.
    left/right files     exact when present, present once. `torn` ships
                         `torn_left/torn_left.kicad_pcb` and `torn_right/...`. A split
                         PCB is usually one reversible board, so this is nearly always
                         absent.
    the author's words   the summary, where the repo wrote one. Noisy in the other
                         direction: "split backspace", "a light split" and "split 5x12
                         ortholinear" are all keycaps and layouts rather than halves.

None of the three is safe alone, and the false positives do not overlap, which is why
the row carries all three and a person reads them together.

AN OVERRIDE FILE, NOT A CENSUS. A row exists only where a signal CONTRADICTS what the
build published. The 230 designs nobody needs to rule are absent by construction: a row
for a design nobody checked is a claim nobody checked.

KEYED BY SLUG, like `seed/summaries.toml`, and re-drafting reports a row whose slug no
longer names a design rather than dropping it -- a rename must not silently discard a
ruling. Ticket 07 decision 8 makes renames cheap, which is exactly what makes this the
failure to guard.

Usage: draft_bodies.py [--write [PATH]] [--slug SLUG]
       draft_bodies.py --rule torn --halves 2 --note "ships torn_left and torn_right"
       draft_bodies.py --rule chouchou --halves 1 --note "its README says unibody"
"""
import argparse
import base64
import collections
import json
import os
import pathlib
import re
import subprocess
import sys
import tomllib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import qmk_metadata  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
SCAN = REPO/"data/board-scan.json"
BUILT = REPO/"src/main/resources/seed/designs.json"
SEED = REPO/"seed/bodies.toml"
DEFAULT_QMK = os.path.expanduser("~/code/qmk_firmware")

# QMK's convention for a split MATRIX: `LAYOUT_split_3x5_3`. Anchored on purpose -- a
# bare `split` substring matches `LAYOUT_60_ansi_split_bs`, `split_lshift` and
# `split_space`, which are split KEYCAPS, and adds 14 false positives.
SPLIT_MACRO = re.compile(r"^LAYOUT_split_\d+x\d+_\d+$")
# The author's own word, as a whole word. Still noisy -- see the docstring -- so it is
# evidence on the row and never a gate.
SPLIT_WORD = re.compile(r"\bsplit\b", re.I)
HANDED = re.compile(r"(?:^|[/_\-])(left|right)(?:[/_\-]|\.)", re.I)


def macros(rid, qmk_root):
    """Every layout macro a QMK record declares, inherited up the path as QMK does."""
    found = set()
    parts = rid.split("/")
    for i in range(len(parts), 0, -1):
        for fn in ("keyboard.json", "info.json"):
            path = qmk_root.joinpath(*parts[:i])/fn
            if not path.exists():
                continue
            try:
                found |= set(qmk_metadata.load(path).get("layouts") or {})
            except ValueError:
                continue
    return found


# A sentence in which the author says what shape the keyboard is. Deliberately narrow:
# the bare word `split` is this signal's own false positive ("split backspace", "a light
# split", "split 5x12 ortholinear"), so a line only counts when it says one PIECE or two.
SHAPE_SENTENCE = re.compile(
    r"\b(unibody|monoblock|one[- ]piece|split keyboard|keyboard kit|"
    r"is a split|halves)\b", re.I)


def readme(repo, subdir=None):
    """The lines of a repo's README in which its author states the keyboard's shape.

    Read through `gh`, the same choice `review_boards.py` makes for commit history and
    for the same reason: there is no clone archive on this machine. A repo holding
    several designs keeps its shape claims in the design's own folder, so `subdir` is
    tried first -- `hazels-garage/bad-wings` says nothing at the root and calls v2 a
    "36 key unibody split" one level down.

    Returns `[]` when it cannot look, which leaves the row's other evidence standing.
    """
    for target in ([f"{subdir}/README.md"] if subdir else []) + ["readme"]:
        api = (f"repos/{repo}/contents/{target}" if target.endswith(".md")
               else f"repos/{repo}/readme")
        try:
            out = subprocess.run(["gh", "api", api, "--jq", ".content"],
                                 capture_output=True, text=True, timeout=40)
        except (OSError, subprocess.SubprocessError):
            return []
        if out.returncode != 0:
            continue
        try:
            text = base64.b64decode(out.stdout).decode("utf-8", "replace")
        except ValueError:
            continue
        hits = [ln.strip() for ln in text.splitlines()
                if SHAPE_SENTENCE.search(ln) and len(ln.strip()) < 300]
        if hits:
            return hits[:2]
    return []


def evidence(design, qmk_root, with_readme=False):
    """The three signals for one published design, as a list of human-readable lines.

    Returns `[]` where nothing contradicts what was published, which is what keeps this
    file an override rather than a census.
    """
    published = design["body"]["kind"]
    lines, contradicts = [], False

    ids = [f["path"] for f in design["firmware"] if f.get("path")]
    split_macros = sorted(m for rid in ids for m in macros(rid, qmk_root)
                          if SPLIT_MACRO.match(m))
    if split_macros and published == "unibody":
        contradicts = True
        lines.append(f"QMK declares {', '.join(sorted(set(split_macros)))} "
                     f"-- a split matrix -- while `split.enabled` is absent")

    hands = sorted({m.group(1).lower() for p in design.get("board_paths") or []
                    for m in [HANDED.search(p)] if m})
    if len(hands) == 2 and published == "unibody":
        contradicts = True
        lines.append("its board files are named left and right: "
                     + ", ".join(design["board_paths"]))

    summary = design.get("summary") or ""
    if summary and SPLIT_WORD.search(summary) and published == "unibody":
        # Evidence only, never the trigger: "split backspace" and "a light split" are
        # this signal's own false positives and they are common.
        lines.append(f'the author wrote: "{summary[:150]}"')

    if contradicts and with_readme:
        # THE STRONGEST EVIDENCE AND THE SLOWEST, so it is fetched only for rows that
        # already qualify. Eight of the eleven are settled by it outright, in the
        # author's own words, and it is the only signal that ever says UNIBODY -- every
        # machine one can only ever argue for a split.
        sub = None
        paths = design.get("board_paths") or []
        if paths and "/" in paths[0]:
            sub = paths[0].split("/")[0]
        for line in readme(design["repo"], sub):
            lines.append(f'README: "{line[:180]}"')

    return lines if contradicts else []


def draft(built, qmk_root, keep=None, with_readme=False):
    """One row per published design a signal contradicts, plus every human ruling."""
    keep = keep or {}
    out, why = [], collections.Counter()
    seen = set()
    for design in sorted(built, key=lambda d: d["slug"]):
        slug = design["slug"]
        seen.add(slug)
        if slug in keep:
            out.append(keep[slug])
            why["kept, a human ruled"] += 1
            continue
        lines = evidence(design, qmk_root, with_readme)
        if not lines:
            continue
        out.append({"slug": slug, "name": design["name"], "halves": 0,
                    "verdict": "drafted", "signal": "split-macro",
                    "note": "; ".join(lines)})
        why["queued for a human"] += 1
    # A RULING WHOSE DESIGN VANISHED. Kept and reported rather than dropped: the design
    # may have been renamed, and silently discarding the answer is how a corpus loses a
    # human's work between two rebuilds.
    for slug, row in sorted(keep.items()):
        if slug not in seen:
            out.append(row)
            why["ruled, but no design answers to that slug now"] += 1
    return out, why


def ruled(path):
    """Rows a person wrote, copied through every re-draft untouched."""
    p = pathlib.Path(path)
    if not p.exists():
        return {}
    return {e["slug"]: e for e in tomllib.loads(p.read_text()).get("entry", [])
            if e.get("signal") == "human"}


def rulings(path):
    """The designs a person has ruled, keyed by slug. CONFIRMED ROWS ONLY.

    A `drafted` row carries `halves = 0`, which is the absence of a ruling and not a
    keyboard in zero pieces. Reading one would publish nonsense, so the build sees
    confirmed rows only and every unruled design behaves exactly as it did before this
    file existed -- which is what makes adding it a no-op until somebody rules a row.
    """
    p = pathlib.Path(path)
    if not p.exists():
        return {}
    return {e["slug"]: e["halves"] for e in tomllib.loads(p.read_text()).get("entry", [])
            if e.get("verdict") == "confirmed" and e.get("halves")}


def toml_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def render(recs):
    head = [
        "# seed/bodies.toml -- is this keyboard one piece or two, where the firmware",
        "# flag we derive it from is answering a different question. Queued by",
        "# scripts/draft_bodies.py; every filled row is a PERSON's ruling. Forgejo #35.",
        "#",
        "# `halves` is the count of physical pieces: 1 unibody, 2 a split, 3 or more for",
        "# a keyboard like `tripel`. On a `drafted` row it is 0 -- the absence of a",
        "# ruling, never a keyboard in zero pieces -- and the build reads confirmed rows",
        "# only, so the two can never be confused.",
        "#",
        "#   drafted    the queue's own row: a signal contradicts what we published,",
        "#              nobody has ruled, and the design publishes exactly as before",
        "#   confirmed  settled, and the ONLY verdict the build reads",
        "#",
        "# WE DERIVE `body` FROM QMK's `split.enabled`, WHICH MEANS \"the firmware drives",
        "# two halves over QMK's split transport\" -- a wiring question, printed as a",
        "# shape answer. Halves joined by an I/O expander present as one matrix and set",
        "# nothing, so `torn` and `wired-pierce` publish as unibody. The real signal is",
        "# the inter-half connector on the board, which is map ticket 52 and open.",
        "#",
        "# `note` on a drafted row is the EVIDENCE, so the ruling can be made from this",
        "# file alone. On a confirmed row it is the person's reasoning, and that is what",
        "# a later reader needs most. `signal = \"human\"` survives every re-draft.",
        "#",
        "# The strongest machine signal is `LAYOUT_split_RxC_T`, and it is NOT a verdict:",
        "# `chouchou` carries one and its README calls it \"a minimalist unibody keyboard\".",
        "# The macro names a matrix shape, and a monoblock can have one.",
        "",
    ]
    body = []
    for r in recs:
        body += [
            "[[entry]]",
            f'slug    = {toml_str(r["slug"])}',
            f'name    = {toml_str(r["name"])}',
            f'halves  = {r["halves"]}',
            f'verdict = {toml_str(r["verdict"])}',
            f'signal  = {toml_str(r["signal"])}',
            f'note    = {toml_str(r["note"])}',
            "",
        ]
    return "\n".join(head + body)


def rule(args, recs, built):
    """Record one person's answer, re-rendered by this file's own writer.

    A DESIGN THE QUEUE CANNOT SEE IS STILL RULABLE, and `3w6` is why. It is a two-piece
    keyboard joined by an I/O expander -- its README says so in its title and names the
    part -- but it carries a plain `LAYOUT`, no handed board files and no split macro,
    so not one machine signal here contradicts what we published. Forgejo #35 predicted
    exactly this: "whatever rule lands, it will not reach that one".

    A queue that only accepts answers to questions it thought to ask is a queue that
    cannot be corrected by someone who knows more than it does. So the queue proposes
    rows and a person may rule any published design.
    """
    hit = next((r for r in recs if r["slug"] == args.rule), None)
    if hit is None:
        design = next((d for d in built if d["slug"] == args.rule), None)
        if design is None:
            print(f"no design answers to {args.rule!r} -- check the slug against "
                  f"src/main/resources/seed/designs.json", file=sys.stderr)
            return 1
        hit = {"slug": design["slug"], "name": design["name"], "halves": 0,
               "verdict": "drafted", "signal": "none", "note": ""}
        recs.append(hit)
        recs.sort(key=lambda r: r["slug"])
    hit.update(halves=args.halves, verdict="confirmed", signal="human", note=args.note)
    SEED.write_text(render(recs), encoding="utf-8")
    shape = "unibody" if args.halves == 1 else f"{args.halves} pieces"
    print(f"{args.rule} -> {shape}")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--built", default=str(BUILT))
    ap.add_argument("--qmk", default=DEFAULT_QMK)
    ap.add_argument("--write", nargs="?", const=str(SEED), metavar="PATH",
                    help="write the queue file")
    ap.add_argument("--slug", help="report one design only")
    ap.add_argument("--readme", action="store_true",
                    help="add each author's own words about the shape (needs `gh`)")
    ap.add_argument("--rule", metavar="SLUG", help="record a person's ruling")
    ap.add_argument("--halves", type=int, help="1 unibody, 2 a split, 3+ for a tripel")
    ap.add_argument("--note", default="", help="why -- the reasoning, for the next reader")
    args = ap.parse_args()

    built = json.loads(pathlib.Path(args.built).read_text())
    boards = json.loads((REPO/"data/design-boards.json").read_text())
    for d in built:
        d["board_paths"] = (boards.get(d["slug"]) or {}).get("paths") or []
        d["repo"] = (boards.get(d["slug"]) or {}).get("repo") or ""
    recs, why = draft(built, pathlib.Path(args.qmk)/"keyboards", keep=ruled(SEED),
                      with_readme=args.readme)

    if args.rule:
        if args.halves is None:
            ap.error("--halves is required: 1 unibody, 2 a split, 3+ for a tripel")
        if args.halves < 1:
            ap.error("--halves counts physical pieces, so it starts at 1")
        if not args.note:
            ap.error("--note is required: a ruling nobody can re-check is not a ruling")
        return rule(args, recs, built)

    shown = [r for r in recs if not args.slug or r["slug"] == args.slug]
    for r in shown:
        state = "unibody" if r["halves"] == 1 else (
            f'{r["halves"]} pieces' if r["halves"] else "unruled")
        print(f'{r["slug"]:<22} [{r["verdict"]:9}] {state:<9} {r["name"]}')
        print(f'    {r["note"]}')
    print(f"\n{len(shown)} rows")
    for k, n in why.most_common():
        print(f"  {n:4} {k}")

    if args.write:
        pathlib.Path(args.write).write_text(render(recs), encoding="utf-8")
        print(f"\nwrote {args.write}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
