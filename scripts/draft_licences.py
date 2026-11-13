#!/usr/bin/env python3
"""Queue the repos whose licence files the build cannot settle, and record the ruling.

`build_seed.py` fills all three licence slots from ONE file, because one file is all
almost every repo gives us. Seven repos give more than one and cannot be reduced to a
single root file, so they defer -- and until this script existed they deferred into
nothing. The gate said "needs a ruling" and there was nowhere to put one.

    data/licence-scan.json   what files a repo holds   (machine)
    seed/licences.toml                     which file covers which slot   <- this script

THE DRAFTER PROPOSES NOTHING, and that is the whole design rather than an omission.
Every other file in `seed/` is drafted by a machine and confirmed by a person, because
in each of those a machine CAN propose: a token match, a first paragraph, a board in a
folder. Here it cannot. `foostan/crkbd` holds CC-BY-4.0 and MIT and states nowhere
which covers the PCB; the filenames (`LICENSE_CC`, `LICENSE_MIT`) are a convention, and
the owner's rule of 2026-08-26 is explicit that picking one on a convention "would be
inventing the answer to the question the three slots exist to ask". So machine rows
carry EMPTY slots and exist only to be a queue -- the evidence gathered onto one line
so a person can rule without opening the scan.

AN OVERRIDE FILE, NOT A CENSUS. A row exists only for a repo the build cannot settle
alone. The 333 repos with one licence are absent by construction, not by oversight: a
row for a repo nobody needed to rule is a claim nobody checked.

THE SIGNAL SAYS WHICH GATE, so the queue can be worked by class:

    root-tie      several licences at the repo root and no way to prefer one. The
                  common shape, and the rulable one: 4 of 6 are a code licence beside
                  a hardware one the author NAMED (`LICENSE-models`, `LICENSE_CC`).
    no-root       several licences, none of them at the root. `satorusaka/15k-macropad`
                  -- the root rule has nothing to bite on.
    unclassified  a licence file the scanner could not name. A person reads it and says
                  what it is; that is a different job from splitting slots between files.

WHY `documentation` IS ALWAYS ASKED FOR AND NEVER GUESSED. Measured over the built
corpus: all 234 records carry the same licence in all three slots, so the slot has never
once held a value of its own and no scan produces one. `--rule` therefore requires all
three explicitly -- including `--documentation`, which is the one a reviewer will
otherwise fill in by reflex. It is the terms on the README and the build guide, and it
is what decides whether this site may republish a photo (`product.md` §12).

Usage: draft_licences.py [--report] [--repo SLUG] [--write seed/licences.toml]
       draft_licences.py --rule foostan/crkbd \\
           --hardware CC-BY-4.0 --firmware MIT --documentation CC-BY-4.0 \\
           --note "LICENSE_CC covers the PCBs, LICENSE_MIT the firmware and kbd lib"
"""
import argparse
import collections
import json
import pathlib
import sys
import tomllib

REPO = pathlib.Path(__file__).resolve().parent.parent
SCAN = REPO/"data/licence-scan.json"
SEED = REPO/"seed/licences.toml"
SLOTS = ("hardware", "firmware", "documentation")

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from build_seed import licence_slots  # noqa: E402


def blocked(scan):
    """The repos `licence_slots` cannot settle, with the signal naming which gate.

    Asked of `build_seed` itself rather than reimplemented here. The two used to be
    separate copies of the same twenty lines and had already drifted; a queue that
    disagrees with the gate it feeds is worse than no queue.

    `repo not scanned` is deliberately NOT queued. It is a hole in the scan, not a
    question for a person, and a row inviting someone to rule on a repo nobody has
    read would be exactly the unchecked claim this file refuses to carry.
    """
    out = []
    for repo in sorted(scan):
        scanned = scan[repo]
        if not isinstance(scanned, dict) or scanned.get("status") != "ok":
            continue
        _, why = licence_slots(repo, scanned, {})
        if not why:
            continue
        own = [f for f in scanned["files"] if not f["vendored"]]
        if why == "licence not recognised":
            signal = "unclassified"
        elif any("/" not in f["path"] for f in own):
            signal = "root-tie"
        else:
            signal = "no-root"
        out.append({"repo": repo, "signal": signal, "files": own})
    return out


def evidence(files):
    """The candidate files on one line, root ones first -- the reviewer's whole input."""
    ordered = sorted(files, key=lambda f: ("/" in f["path"], f["path"]))
    return "; ".join(f"{f['path']} = {f['licence']}" for f in ordered)


def ruled(path):
    """Rows a PERSON has ruled, keyed by repo -- copied through every re-draft untouched.

    This file is regenerated from scratch, so without this a re-draft eats the queue's
    output. Copied WHOLE, evidence included: refreshing a human row's `files` from a
    later scan would quietly restate what they ruled against, and a ruling made on
    evidence that has since changed should surface as a conflict a person looks at,
    not as a line that edits itself.
    """
    p = pathlib.Path(path)
    if not p.exists():
        return {}
    return {e["repo"]: e for e in tomllib.loads(p.read_text()).get("entry", [])
            if e.get("signal") == "human"}


def draft(scan, keep):
    recs = []
    for row in blocked(scan):
        if row["repo"] in keep:
            recs.append(keep[row["repo"]]); continue
        recs.append({"repo": row["repo"],
                     "hardware": [], "firmware": [], "documentation": [],
                     "verdict": "drafted", "signal": row["signal"],
                     "note": evidence(row["files"])})
    # A ruling outlives the ambiguity that prompted it. If upstream deletes a licence
    # file the repo stops being blocked and drops out of `blocked()`, but the person's
    # row stays -- and `licence_slots` still prefers it, because a ruling is a statement
    # about the repo and not a patch for a scan.
    for repo, row in keep.items():
        if not any(r["repo"] == repo for r in recs):
            recs.append(row)
    return sorted(recs, key=lambda r: r["repo"])


def toml_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def toml_list(xs):
    return "[" + ", ".join(toml_str(x) for x in xs) + "]"


def render(recs):
    lines = [
        "# seed/licences.toml -- which licence file covers which slot, where the repo",
        "# holds more than one and does not say. Queued by scripts/draft_licences.py;",
        "# every filled row is a PERSON's ruling. Keyed by REPO, because a licence is a",
        "# property of the repository and not of a firmware record.",
        "#",
        "#   drafted    the queue's own row: empty slots, nobody has ruled -- the repo",
        "#              defers exactly as it did before this file existed",
        "#   confirmed  settled, and the ONLY verdict the build reads",
        "#",
        "# EMPTY SLOTS MEAN TWO DIFFERENT THINGS and `verdict` is what separates them.",
        "# On a `drafted` row `[]` is the absence of a ruling. On a `confirmed` row `[]`",
        "# is a ruling -- `Unstated`, all rights reserved -- which is a real answer a",
        "# repo can deserve. The build reads confirmed rows only, so the two never meet.",
        "#",
        "# `signal = \"human\"` is copied through every re-draft untouched, evidence and",
        "# all. Nothing else in this file is machine-proposed: the drafter fills no slot",
        "# ever, under the owner's rule of 2026-08-26 that choosing between a repo's own",
        "# licence files is inventing the answer the three slots exist to ask. A machine",
        "# row's `signal` names the gate instead -- `root-tie` several at the root,",
        "# `no-root` several with none at the root, `unclassified` a file we cannot name.",
        "#",
        "# `note` on a drafted row is the EVIDENCE, root files first, so the ruling can be",
        "# made from this file alone. On a confirmed row it is the person's reasoning, and",
        "# that is the thing a later reader needs most.",
        "#",
        "# `documentation` is asked for explicitly and never inferred. Measured: all 234",
        "# built records carry one licence in all three slots, so this slot has never held",
        "# a value of its own -- which makes it the one a reviewer fills in by reflex. It",
        "# is the terms on the README and the build guide, and it decides whether this",
        "# site may republish a photo.",
        "",
    ]
    for r in recs:
        lines.append("[[entry]]")
        lines.append(f"repo          = {toml_str(r['repo'])}")
        for slot in SLOTS:
            lines.append(f"{slot:<13} = {toml_list(r[slot])}")
        lines.append(f"verdict       = {toml_str(r['verdict'])}")
        lines.append(f"signal        = {toml_str(r['signal'])}")
        lines.append(f"note          = {toml_str(r['note'])}")
        lines.append("")
    return "\n".join(lines)


def report(recs, only=None):
    by_signal = collections.defaultdict(list)
    for r in recs:
        by_signal[r["signal"]].append(r)
    for signal in sorted(by_signal):
        rows = [r for r in by_signal[signal] if not only or r["repo"] == only]
        if not rows:
            continue
        print(f"\n=== {signal}   [{len(rows)} repos]")
        for r in rows:
            slots = "  ".join(f"{s}={','.join(r[s]) or '--'}" for s in SLOTS)
            print(f"  {r['repo']}")
            print(f"      {r['note']}")
            print(f"      {r['verdict']}: {slots}")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--scan", default=str(SCAN))
    ap.add_argument("--write", nargs="?", const=str(SEED), metavar="PATH",
                    help="write the queue file (default seed/licences.toml)")
    ap.add_argument("--repo", help="report one repo only")
    ap.add_argument("--rule", metavar="REPO", help="record a person's ruling for one repo")
    for slot in SLOTS:
        ap.add_argument(f"--{slot}", nargs="*", metavar="SPDX",
                        help=f"licence ids for the {slot} slot; pass with no value for "
                             "`Unstated` (all rights reserved)")
    ap.add_argument("--note", default="", help="why -- the reasoning, for the next reader")
    args = ap.parse_args()

    scan = json.loads(pathlib.Path(args.scan).read_text())
    keep = ruled(args.write or SEED)

    if args.rule:
        missing = [s for s in SLOTS if getattr(args, s) is None]
        if missing:
            ap.error("a ruling fills every slot; missing " + ", ".join(f"--{s}" for s in missing)
                     + ". Pass the flag with no value to rule a slot `Unstated`.")
        if not args.note:
            ap.error("--note is required: a ruling nobody can re-check is not a ruling")
        keep[args.rule] = {"repo": args.rule,
                           **{s: list(getattr(args, s)) for s in SLOTS},
                           "verdict": "confirmed", "signal": "human", "note": args.note}

    recs = draft(scan, keep)
    if args.rule or args.write:
        path = pathlib.Path(args.write or SEED)
        path.write_text(render(recs), encoding="utf-8")
        print(f"wrote {path}")

    n = collections.Counter(r["verdict"] for r in recs)
    print(f"{len(recs)} repos queued: {n['confirmed']} ruled, {n['drafted']} awaiting a person")
    report(recs, args.repo)


if __name__ == "__main__":
    main()
