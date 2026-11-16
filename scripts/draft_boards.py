#!/usr/bin/env python3
"""Draft which board file belongs to each confirmed source row.

`build_seed.py` imports **only repos that serve exactly one confirmed row**, because
`board-scan.json` is keyed per board file with nothing tying a file to a row. That
restriction defers **200 rows** -- 43% of the confirmed set -- and it is the single
largest thing standing between the site and its target size. This file removes it.

    seed/sources.toml   which REPO holds a row's hardware   (human-confirmed)
    seed/boards.toml    which BOARD FILE in it is this row's   <- this script

Ticket 44, the firmware-first pass. It attributes rows that already exist; the ~360 boards with no
firmware record of their own are left to the board-first pass and are deliberately not drafted here.

WHY THIS IS DRAFTED AND NOT DERIVED, with the measurement that settles it.
`josefadamcik/soflekeyboard` ships `Sofle_v1/PCB/SofleKeyboard.kicad_pcb` and
`Sofle_v2/PCB/SofleKeyboard.kicad_pcb` -- identical filenames in sibling folders
differing only by a version number, which is byte-for-byte the shape of
`tweetydabird/lotus58`'s `v1.11` and `v1.23`. Sofle's two are **different designs**;
lotus58's twelve are **one**. The paths cannot tell them apart. What can is the
firmware side: lotus58 has 1 QMK record, sofle has records for `sofle/rev1` *and*
`sofle_choc`. So a machine proposes and a human rules -- ticket 19's rule, with a
specific reason rather than a general caution.

THE SIGNALS, strongest first. Every row records which one fired, so a reviewer reads
evidence rather than a verdict:

    sole        the repo holds exactly one board and this is its only row
    path        the row's own human-confirmed `path` scopes the repo to one board
    exact       the record id's last segment equals the board's own design name
    token       every distinguishing token of the id appears in the board's path
    ratio       fuzzy similarity only -- ALWAYS left `drafted`, never auto-confirmed

NOTHING IS DEFAULT-FILLED. A row with no convincing candidate gets `boards = []` and
its candidates in the note, per the standing preference that absent must round-trip as
absent. `no_board` is a real verdict, not a shrug: no switch-placing `.kicad_pcb`
survives to be attributed, so the design dies at the gate under ticket 48's rule and
this file is where that becomes traceable. Its `signal` says which step emptied the
repo -- `scan` when the repo holds no such board, `filtered` when it holds one and
`kind()` set it aside on its path -- because only the first is a fact about the repo.

Usage: draft_boards.py [--write seed/boards.toml] [--report] [--repo SLUG]
"""
import argparse
import collections
import difflib
import json
import os
import pathlib
import re
import subprocess
import sys
import tomllib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import qmk_metadata  # noqa: E402
import trees as tree_registry  # noqa: E402
from check_source_reachability import parse  # noqa: E402
from measure_controller_socket import socket_of, MCU_PACKAGE, MCU_NAMED  # noqa: E402

# Segments that name a part of a design rather than a design. `plate` is the big one
# (76 boards): an FR4 plate places REAL switch footprints, so the footprint-library
# rule that catches 6 KiCad plates cannot see it. camelCase is deliberate -- the first
# version of this wanted a delimiter before `plate` and let `SofleKeyboardTopPlate`
# and `one42_switchPlate` straight through.
#
# `(?<!tem)` because that camelCase branch accepts ANY letter before `plate`, and
# `kbd-template.kicad_pcb` is a 45-switch Pro Micro board (`takashicompany/radialex`)
# that read as a plate on the letters in its name. The lookbehind is narrower than
# demanding a capital `P`, which would have lost `egg58v2_switchplate`.
PART = re.compile(r"(?:^|[/\s_-]|(?<=[a-z]))(?<!tem)(?:plates?|skid[\s_-]?plate)"
                  r"(?:[/\s_.-]|$)", re.I)
RETIRED = re.compile(r"(?:^|/)(?:archive|archived|old|older|deprecated|backup|backups"
                     r"|broken[^/]*|obsolete)(?:/|$)", re.I)
# `-bak` ends a word or it is not a backup marker: unanchored, it matched
# `rp2040-bakeneko65.kicad_pcb` (`lendunistus/rpneko65`, 96 switches) and every other
# Bakeneko that will ever enter the corpus. The other five markers are words that do
# not prefix anything, so they stay as they are.
#
# `tester` ADDED 2026-09-02: a jig is not a design. `vlkv/avalanche` ships
# `layout_tester/layout_tester.kicad_pcb`, 32 switch footprints, and it published as a
# keyboard called **"layout tester"** at 64 keys -- 32 doubled, matching the Avalanche's
# own records, which is the coincidence of a jig carrying one hand's worth of positions.
#
# `tester`, NEVER `test`, and the corpus is what draws the line. Of the 15 board files
# whose path names a test, sample or experiment, only four place a switch:
#
#     tester    AtariJoystickPaddleTester 1, layout_tester 32, DecentKeyboardTester 3
#     EXPERIMENTAL/   Pteron56v04-NN-palm-E, 58 switches -- A REAL KEYBOARD
#
# So `experiment` would cost a design and `test` alone matches only `tests/` and
# `test/cli` directories that place nothing. Every `tester` in the corpus is a jig and
# none is a keyboard. The camelCase branch is needed for `KeyboardTester`, and it is
# PART's, borrowed rather than restated.
ARTEFACT = re.compile(r"(?:copy|kikit|panel|_autosave|-bak(?:[/._-]|$)|duplicate"
                      r"|(?:^|[/\s_-]|(?<=[a-z]))tester)", re.I)

# Tokens that carry no identity: every id has them, so matching on them matches
# everything. `rev`/`v` are here because the version NUMBER is what discriminates.
NOISE = {"handwired", "rev", "v", "ver", "version", "pcb", "kicad", "keyboard",
         "keyboards", "kb", "main", "hardware", "src", "boards", "board"}

# Words that name a PIECE of one keyboard rather than a keyboard. Two board files
# differing only by one of these are one design's two boards, not two designs --
# `dmnosachev/kinesis80` ships `top-pcb/kinesis-top-pcb.kicad_pcb` and
# `thumb-pcb/kinesis-thumb-pcb.kicad_pcb`, which is a Kinesis with a top PCB and a
# thumb PCB, and it read as a four-way puzzle against four controller-variant records.
# Version words are deliberately absent: `Sofle_v1` and `Sofle_v2` differ by a version
# and are two designs, measured on the repo ticket 34 verified.
PIECE = {"top", "bottom", "thumb", "thumbs", "left", "right", "l", "r", "half",
         "halves", "upper", "lower", "main", "mini"}

# Words that name a CONTAINER rather than a keyboard, dropped from a drafted NAME.
# Deliberately not `NOISE`: the matcher drops `rev` and `v` because a version number
# is what discriminates, but a name keeps them -- "Sofle v2" reads as a name where
# "Sofle 2" reads as a typo. `keyboard` is here because 99 boards sit under `PCB/`
# and their leaf is the repo's own word for itself: `SofleKeyboard` names Sofle.
# `main` is here for the same reason as `keyboard`, added 2026-09-02: a repo with ONE
# PCB calls it the main board, and `ashijanken/slimorta` published as **"Slimorta main"**
# from `PCB/Slimorta_main_board/Slimorta_main_board.kicad_pcb`. It is already in `PIECE`,
# but `PIECE` is only stripped from a set of SEVERAL board files -- and a repo whose one
# board is called "main" is naming the board, not distinguishing it from a sibling.
# Measured over all 541 board-sets: exactly one drafted name contains the word, and this
# turns it into "Slimorta".
CONTAINER = {"pcb", "pcbs", "kicad", "cad", "hardware", "board", "boards", "keyboard",
             "keyboards", "kb", "src", "source", "sources", "files", "file", "project",
             "projects", "electronics", "design", "designs", "gerber", "gerbers", "main",
             # Generator output directories. `tarneaux/supersplit` was published as
             # "output", from `output/pcbs/board.kicad_pcb` -- and that is ergogen's
             # DEFAULT output path, so it recurs rather than being one repo's quirk.
             "output", "outputs", "build", "builds", "dist", "export", "exports",
             "generated", "out"}

# Repos whose attribution is another ticket's to make. Listed rather than guessed at,
# because a drafted row here would be a queue item nobody can action.
ELSEWHERE = {
    "joe-scotto/scottokeebs":
        "the switch source is a .shapr CAD file, not a .kicad_pcb -- ticket 44's "
        "CAD-source branch. The row count is ticket 46's and waits on ticket 47",
}


def toks(s):
    """Lower-case alphanumeric runs, split at camelCase AND at letter/digit boundaries.

    The digit split is load-bearing, not tidiness. Without it `sofle/rev1` tokenises to
    `rev1`, which appears nowhere in `Sofle_v1/PCB/SofleKeyboard.kicad_pcb` -- so the
    row that has an obvious board failed to see it and tied on the ones it did match.
    With it, `rev1` -> `rev`, `1` and `Sofle_v1` -> `sofle`, `v`, `1`, and the version
    number does the discriminating it is there to do.
    """
    s = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", " ", s)
    s = re.sub(r"(?<=[A-Za-z])(?=\d)|(?<=\d)(?=[A-Za-z])", " ", s)
    return [t for t in re.split(r"[^A-Za-z0-9]+", s.lower()) if t]


def id_tokens(rid):
    """The tokens of a record id that could identify a board, noise removed.

    Keeps digits: `sofle/rev1` versus `rev2` is discriminated by `1` versus `2` and by
    nothing else, so dropping bare numerals would make every revision look alike.
    """
    return [t for t in toks(rid) if t not in NOISE]


def kind(path):
    """`board`, or why this file is not a design of its own."""
    if RETIRED.search(path):
        return "retired"
    if ARTEFACT.search(path):
        return "artefact"
    if PART.search(path):
        return "part"
    return "board"


# A version, and NOT merely a token that starts with a digit. `45` and `4by3` are
# names -- `eliassjogreen/4by3` and `lighteningab/45keyboard_v1` were both called
# "4by3 4by3" by the greedy version of this. So: a version marker and a number
# (`v1`, `Rev2a`, `R1`, `V0.8.1DHA`), or a dotted number (`1.11`, `0.8.1`).
# A directory that files boards by how many keys they have: `34Keys/`, `30keys/`,
# `36Keys/`. It says nothing about WHICH keyboard, so it is excluded from a name for the
# same reason a bare version is. `shared_stem` already rejected it; this puts the rule in
# the ordinary naming path too, where the board-first pass surfaced `34 Keys Cradio Z`.
BUCKET = re.compile(r"^\d+\s*keys?$", re.I)

VERSION = re.compile(r"^(?:(?:v|ver|version|rev|mk|r)\d[\w.]*|\d+(?:[._]\d+)+)$", re.I)


# WHERE A RUN-TOGETHER NAME BREAKS INTO WORDS. After a lowercase, always -- that is
# ordinary camelCase. After a DIGIT, only where the capital starts a word rather than
# continuing an alphanumeric code, and `weteor/3w6` is why: `3W6.kicad_pcb` published as
# **"3 W6"**, which is not a keyboard anyone has heard of.
#
# The lookahead is the whole rule. `W` in `3W6` is followed by a digit, so it is inside a
# code and the name stays `3W6`; `L` in `Split65Left` is not, so it splits and the two
# Split65 boards keep the shared stem that groups them. Measured over all 544 board names:
# dropping the digit case altogether changes 16 names and breaks two of them
# (`Hpad 4x5 RGB` glues up, `Split65` loses its grouping); this changes 2 and breaks none.
CAMEL = re.compile(r"(?<=[a-z])(?=[A-Z])|(?<=[0-9])(?=[A-Z](?![0-9]))")


def pretty(seg):
    """One path segment as a name: delimiters to spaces, camelCase split, containers out.

    Empty means the segment says nothing about which keyboard this is -- `PCB/`,
    `kicad files/`, `projects/`. 99 of the 551 boards sit directly under one.
    """
    # A dot BETWEEN digits survives, so `ver1.2` stays one word and reads as the version
    # it is; splitting it gave `ver1` and a bare `2`, and a bare digit is a name.
    text = re.sub(r"\.(?![0-9])|(?<![0-9])\.", " ", seg)
    text = re.sub(r"[^A-Za-z0-9.]+", " ", text)
    # Containers go BEFORE the camelCase split, not after: `KiCad-PCB/keyboard.kicad_pcb`
    # split first, and `autumnisacutie/karina`'s design came out called "Ki".
    words = [w for w in text.split() if w.lower() not in CONTAINER]
    out = []
    for w in words:
        out += [x for x in CAMEL.sub(" ", w).split() if x.lower() not in CONTAINER]
    return " ".join(out)


def identity(name):
    """The words of a name that could identify a keyboard -- a bare version is not one.

    `reviung41/pcb/ver1.2/reviung41.kicad_pcb` and its two siblings all leaf to
    `reviung41`, and the only thing separating them is `ver1.2`. "ver1 2" is unique and
    is not a name, so it does not count as one. Nor is "pcb", which is what
    `dcpedit/masonry`'s `pcb/pcb.kicad_pcb` would otherwise be called.
    """
    if BUCKET.match(name.strip()):
        return []
    return [w for w in name.split()
            if not VERSION.match(w) and w.lower() not in CONTAINER]


def name_parts(path):
    """The segments of a board's path that could name a design, outermost first."""
    segs = path.split("/")
    segs[-1] = re.sub(r"\.kicad_pcb$", "", segs[-1])
    return [s for s in segs if pretty(s) and not BUCKET.match(pretty(s))] or [segs[-1]]


def join(segs):
    """Segments as one name: what an outer segment already says, an inner one drops.

    A version goes last however deep it sits, because the design is what is being
    versioned: `reviung41/ver1.2/reviung41` is "reviung41 ver1 2", not "ver1 2 reviung41".
    """
    named = [pretty(s) or s for s in segs]
    keep = []
    for i, n in enumerate(named):
        words = {w.lower() for w in n.split()}
        if any(words <= {w.lower() for w in o.split()} and (j < i or len(o) > len(n))
               for j, o in enumerate(named) if j != i):
            continue
        if any(n.lower() == k.lower() for k in keep):
            continue
        keep.append(n)
    keep.sort(key=lambda n: not identity(n))
    return " ".join(keep)


def name_candidates(repo, path, pieces=False):
    """Names for one board, narrowest first: its own filename, then a directory at a time.

    A candidate that identifies nothing is not offered -- `PCB_V1/PCB/PCB.kicad_pcb`
    is "Hnah40 Atmega328p V1", not "V1". Only where the whole path identifies nothing
    does the repo lend its own name: `dcpedit/masonry` ships `pcb/pcb.kicad_pcb`, and
    five repos would otherwise all call their keyboard "pcb".
    """
    segs = name_parts(path)
    out = [join(segs[i:]) for i in range(len(segs) - 1, -1, -1)]
    if pieces:
        # A design's several board files differ by a piece word, so the piece cannot be
        # part of its name: `top-pcb/kinesis-top-pcb` names a Kinesis, not a top. A
        # candidate that is NOTHING but a piece is dropped rather than kept -- keeping it
        # is how `beekeeb/piantor`, whose two boards are `pcb/left/keyboard_pcb` and
        # `pcb/right/keyboard_pcb`, came out called "left".
        out = [w for w in (" ".join(x for x in n.split() if x.lower() not in PIECE)
                           for n in out) if w]
    stem = pretty(repo.split("/")[-1]) or repo.split("/")[-1]
    named = [n for n in out if identity(n)]
    if not named:
        tail = out[-1].split() if out else []
        named = [" ".join([stem] + [w for w in tail if VERSION.match(w)])]
    seen, uniq = set(), []
    for n in named:
        if n and slugify(n) not in seen:
            seen.add(slugify(n)); uniq.append(n)
    return uniq or [stem]


def slugify(text):
    s = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return re.sub(r"-{2,}", "-", s)


def name_sets(repo, sets):
    """Name every design in one repo, widening only the ones that would collide.

    Each takes the narrowest name no sibling answers to, and uniqueness is decided at a
    shared width so the widening is symmetric -- `Sofle_v1` and `Sofle_v2` step out to
    their folders together rather than one keeping `SofleKeyboard` by winning a race.
    """
    cands = [name_candidates(repo, b[0], pieces=len(b) > 1) for b in sets]
    names = [None] * len(sets)
    for k in range(max((len(c) for c in cands), default=0)):
        at = [c[min(k, len(c) - 1)] for c in cands]
        taken = collections.Counter(slugify(n) for n in at)
        for i, n in enumerate(at):
            if names[i] is None and taken[slugify(n)] == 1:
                names[i] = n
    return [n or f"{cands[i][-1]} {i + 1}" for i, n in enumerate(names)]


def record_facts(paths, repo, scan):
    """Everything the BUILD reads off a board set: switch families and controller.

    Not everything a design has -- key count, split and licence come from the firmware
    record and the repo, and are identical for every candidate of one row by
    construction. So two board sets agreeing here would publish the same record.
    """
    families, footprint, soldered = set(), set(), False
    for path in paths:
        entry = scan.get(f"{repo}\t{path}") or {}
        families |= set(entry.get("families") or [])
        for name in entry.get("names") or {}:
            hit = socket_of(name)
            if hit:
                footprint.add(hit)
            if MCU_PACKAGE.search(name) or MCU_NAMED.search(name):
                soldered = True
    return (tuple(sorted(families)), tuple(sorted(footprint)), soldered)


def shared_stem(paths, repo):
    """What several boards of one design are collectively called.

    The deepest directory they all share, which is the family name when the difference
    between them is a version folder: `reviung41/pcb/ver1.2/...` and `ver1.3` and
    `ver1.4` share `reviung41/pcb`, and `pcb` is a container, so the answer is
    `reviung41` -- NOT `reviung41 ver1.4`, which is what naming it after the winning
    candidate would produce and is worse than leaving it out.

    Where they share no directory at all -- `tweetydabird/lotus58` has `Lotus 58 TRRS`
    and `Lotus 58 USB-C` -- the repo lends its name, which is the same fallback the
    ordinary naming rule uses when a path identifies nothing.
    """
    split = [p.split("/")[:-1] for p in paths]
    common = []
    for parts in zip(*split):
        if len(set(parts)) != 1:
            break
        common.append(parts[0])
    named = [pretty(c) for c in common if pretty(c)]
    stem = " ".join(named)
    # A KEY-COUNT BUCKET IS NOT A NAME. `davidphilipbarr/36keys` files its boards under
    # `30keys/`, `34Keys/`, `36Keys/`, so the deepest shared directory for a row whose
    # candidates sit in one bucket is the bucket -- and `cradio` published as "34 Keys".
    # A stem whose words are all numbers, or all numbers once `keys` is discounted,
    # describes how many switches are on the thing rather than which thing it is.
    # Two ways a shared directory fails to be a name, and both produce something worse
    # than nothing. A bare VERSION -- `glyphkbd_v2`'s candidates share `v2`, slimdash's
    # share `rev1` -- names the revision rather than the keyboard. A key-count BUCKET
    # names how many switches are on it: `davidphilipbarr/36keys` files under `34Keys/`,
    # and `cradio` published as "34 Keys".
    words = [w for w in stem.split() if w.lower() not in ("key", "keys")]
    if identity(stem) and words and not all(w.isdigit() for w in words):
        return stem

    # No usable directory. The FILENAME may still agree: `34Keys/Cradio/cradio.kicad_pcb`
    # and `34Keys/Cradio S/cradio.kicad_pcb` are both `cradio`, which is the design's
    # name where the directories only say how many keys it has.
    leaves = {pretty(re.sub(r"\.kicad_pcb$", "", p.split("/")[-1])) for p in paths}
    if len(leaves) == 1 and next(iter(leaves)):
        return next(iter(leaves))
    return pretty(repo.split("/")[-1]) or repo.split("/")[-1]


def load_boards(cache):
    """repo -> [board-set], and (repo, board-set) -> the design's drafted name.

    A board-set is the list of board files of ONE design.
    """
    scan = json.loads(pathlib.Path(cache).read_text())
    flat, why = collections.defaultdict(list), collections.Counter()
    aside = collections.defaultdict(list)
    for key, val in scan.items():
        if val.get("kind") != "board":
            continue
        repo, path = key.split("\t", 1)
        k = kind(path)
        why[k] += 1
        if k == "board":
            flat[repo].append(path)
        else:
            aside[repo].append((k, path, val.get("switches", 0)))
    out = {}
    for repo, paths in flat.items():
        groups = collections.defaultdict(list)
        for p in paths:
            groups[tuple(t for t in toks(p) if t not in PIECE)].append(p)
        out[repo] = [sorted(g) for g in groups.values()]
        why["pieces merged"] += len(paths) - len(out[repo])
    names = {(repo, tuple(b)): n for repo, sets in out.items()
             for b, n in zip(sets, name_sets(repo, sets))}
    return out, names, why, dict(aside)


def score_set(rid, paths, row_path):
    """Best (score, signal) over a design's board files. A set scores as its best member.

    A design's several boards need not each resemble the record: `kinesis-top-pcb` reads
    as the design and `kinesis-thumb-pcb` barely does, and they are one keyboard.
    """
    best = None
    for p in paths:
        got = score(rid, p, row_path)
        if got and (best is None or got[0] > best[0]):
            best = got
    return best


def score(rid, path, row_path):
    """(score, signal) for one board file. Higher wins; None means no evidence at all."""
    idt = id_tokens(rid)
    pt = set(toks(path))
    tail = toks(rid.split("/")[-1])
    leaf = toks(re.sub(r"\.kicad_pcb$", "", path.split("/")[-1]))

    if row_path and not path.lower().startswith(row_path.rstrip("/").lower() + "/") \
            and path.lower() != row_path.lower():
        return None  # the human already scoped this row to a subdirectory
    if tail and leaf and [t for t in tail if t not in NOISE] == [t for t in leaf if t not in NOISE]:
        return (4.0, "exact")
    if idt and all(t in pt for t in idt):
        return (3.0, "token")
    hit = sum(1 for t in idt if t in pt)
    if hit:
        return (2.0 + hit / max(len(idt), 1), "token")
    ratio = difflib.SequenceMatcher(None, re.sub(r"[^a-z0-9]", "", rid.lower()),
                                    re.sub(r"[^a-z0-9]", "", path.lower())).ratio()
    return (ratio, "ratio") if ratio > 0.35 else None


RULINGS = "seed/boards.toml"


def ruled(path):
    """Rows a human has ruled on, `(tree, id) -> [rows]` -- signal `human`, never redrafted.

    A LIST because one record may run on several boards, one row each (2026-11-16: the
    `osprette` shield on Osprette v1 and the Osprangle). Keyed to one row, a re-draft
    kept whichever came last and dropped the rest.

    This file is regenerated from scratch on every run, so without this a re-draft eats
    the review queue's output. The machine's own `confirmed` is NOT protected: it is a
    proposal that a better signal may overturn, and overturning it is the point of
    improving the drafter. A person's ruling is not a proposal.

    WHERE THE RULINGS ARE READ FROM IS `seed/boards.toml` AND NOT `--write`. It used to
    be `args.write or seed/boards.toml`, which is correct for the ordinary re-draft in
    place and silently wrong for every other use: a dry run to a scratch path reads
    rulings from a file that does not exist yet, gets `{}`, and drafts as though nobody
    had ever ruled. Found 2026-09-02 doing exactly that -- the diff showed **12
    `signal = "human"` rows reverting to `drafted`**, including every Hillside, both
    Rosalines, both Bloomers, Cantor, Cantor Remix and the two macropad renames. Nothing
    was lost, because the scratch file was thrown away; copying it over the seed would
    have lost all twelve with no warning. `--keep` is the override for the rare case of
    drafting against a different rulings file.
    """
    if not pathlib.Path(path).exists():
        return {}
    out = collections.defaultdict(list)
    for e in tomllib.loads(pathlib.Path(path).read_text())["entry"]:
        if e.get("signal") == "human":
            out[(e["tree"], e["id"])].append(e)
    return dict(out)


def name_is_unvouched(rid, name, boards):
    """True when NOTHING outside the board's own path supports the drafted name.

    Ticket 44's rule names a design by its board's own directory, and it has now been
    caught out three times by a directory that names something other than a keyboard:
    `klor1_3` was a VERSION, `choc`/`mx` are a SWITCH TYPE, `full` is a SIZE, and
    `kbd-template` is the author's leftover starting file. Forgejo #32 asked for a
    falsifier rather than a fourth one-off correction, and specified its shape:

        "A board-derived name that appears nowhere in its own firmware record's id,
         while the record's id appears nowhere in the board path, is a name worth a
         human's eye. That is a draft_boards.py check, not a rename rule: it should
         flag, not decide."

    FLAG, NOT DECIDE, and that is why this returns a bool for the NOTE rather than
    downgrading the verdict. The board attribution can be perfectly right while the
    name is wrong -- `kicad/kbd-template.kicad_pcb` IS radialex's only board -- and
    `drafted` means excluded from the build, so demoting the row would take a real
    design off the site to fix a string.

    BOTH DIRECTIONS, because either alone is too eager. `takashicompany/radialex` shares
    no token with `kbd-template` either way, which is the case worth flagging; a record
    whose id merely *differs* from a good directory name is routine.
    """
    idt = {t for t in id_tokens(rid) if t not in NOISE}
    namet = {t for t in toks(name) if t not in NOISE}
    patht = {t for p in boards for t in toks(p) if t not in NOISE}
    if not idt or not namet:
        return False
    if namet & idt or patht & idt:
        return False
    # TOKENS ALONE OVER-FLAG, and the misses are all one shape: the same word written
    # with different separators. `gummykey` vs "Gummy Key", `large_lad` vs "largelad",
    # `skeletonnumpad` vs "Skeleton Num Pad" -- nine of seventeen were this, and every
    # one of them is a name the directory rule got RIGHT. Compare the letters too.
    flat_id = re.sub(r"[^a-z0-9]", "", rid.lower())
    flat_name = re.sub(r"[^a-z0-9]", "", name.lower())
    if flat_name and (flat_name in flat_id or flat_id.endswith(flat_name)):
        return False
    return True


def draft(seed, cache, keep=None, qmk=None, zmk=None):
    boards, names, why, aside = load_boards(cache)
    scan = json.loads(pathlib.Path(cache).read_text())
    keep = keep or {}
    rows = [r for r in parse(seed)
            if r.get("verdict") == "confirmed" and r.get("source")]
    per_repo = collections.Counter(r["source"] for r in rows)
    # The RECORD side, needed only by `contention_is_free`. Two lookups because the two
    # contention sites reach it differently: one has the repo, the other has ids.
    reg = tree_registry.load(qmk=qmk or DEFAULT_QMK, zmk=zmk or DEFAULT_ZMK)
    shields = load_shields(zmk or DEFAULT_ZMK)
    by_repo = collections.defaultdict(list)
    by_id = {}
    for r in rows:
        by_repo[r["source"]].append(r)
        by_id[(r["source"], r["id"])] = r

    # First pass: who wants which board, and how badly. A second row wanting the same
    # board is evidence the match is not decisive, and it is only visible repo-wide.
    claims = collections.defaultdict(dict)
    for r in rows:
        for bset in boards.get(r["source"], []):
            got = score_set(r["id"], bset, r.get("path"))
            if got:
                claims[(r["source"], tuple(bset))][r["id"]] = got[0]
    for key, who in claims.items():
        top = max(who.values())
        claims[key] = {k: v for k, v in who.items() if top - v < 0.5}

    out = []
    for r in rows:
        repo, rid = r["source"], r["id"]
        if (r["tree"], rid) in keep:
            out.extend(keep[(r["tree"], rid)])
            why["kept, a human ruled"] += 1
            continue
        cand = boards.get(repo, [])
        rec = {"tree": r["tree"], "id": rid, "source": repo, "boards": [],
               "name": "", "verdict": "drafted", "signal": "none", "note": ""}
        if not cand:
            # WHICH step emptied the repo, because they are different findings and only
            # one of them is about the repo. `no_board` used to say "the scan found
            # nothing" even where the scan HAD found a board and `kind()` set it aside
            # on its path -- a row crediting a component that disagreed with it, which
            # is how `takashicompany/radialex` and `lendunistus/rpneko65` sat unread in
            # a file a human reviews. A reviewer cannot re-check a claim about a step
            # that did not make it.
            dropped = aside.get(repo, [])
            if dropped:
                rec.update(verdict="no_board", signal="filtered",
                           note="the scan found "
                                f"{len(dropped)} switch-placing .kicad_pcb here and the "
                                "path filter set every one aside: "
                                + "; ".join(f"{p} ({k}, {sw} switches)"
                                            for k, p, sw in sorted(dropped))
                                + " -- if any of those is the design, this row is wrong")
            else:
                rec.update(verdict="no_board", signal="scan",
                           note="the scan read this repo and found no switch-placing "
                                ".kicad_pcb at all; the design dies at the gate")
            out.append(rec)
            continue
        if repo in ELSEWHERE:
            rec.update(signal="elsewhere", note=ELSEWHERE[repo])
            out.append(rec)
            continue
        if len(cand) == 1 and per_repo[repo] == 1:
            rec.update(boards=cand[0], name=names[(repo, tuple(cand[0]))],
                       verdict="confirmed", signal="sole",
                       note="one board in the repo, one row -- nothing to choose")
            out.append(rec)
            continue
        if len(cand) == 1 and per_repo[repo] > 1:
            # Several rows, one board. Where they are REVISIONS this is not a
            # confirmation, and `cozykeys/speedo` is why: v2 and v3 both resemble
            # `pcb/speedo.kicad_pcb`, and ticket 48 established v2's board was NEVER in
            # the repo -- one commit, titled "Speedo v3.0". Naming v2 would tell a
            # builder to flash firmware for a board that is not there.
            #
            # Where they are CONTROLLER VARIANTS it is: `handwired/pill60/blackpill_f401`,
            # `_f411` and `bluepill` are three ways to build the one Pill60, and all
            # three flash onto it. `contention_is_free` is the line between the two.
            rivals = by_repo[repo]
            if contention_is_free(rivals, reg, shields):
                rec.update(boards=cand[0], name=names[(repo, tuple(cand[0]))],
                           verdict="confirmed", signal="shared",
                           note=f"{len(rivals)} records want the repo's one design and "
                                f"none supersedes another -- they agree on key count and "
                                f"splitness, and differ by variant rather than version "
                                f"({', '.join(sorted(r['id'] for r in rivals))}), so all "
                                f"of them run on it")
                out.append(rec)
                continue
            rec.update(signal="contended",
                       note=f"{per_repo[repo]} rows contend for the repo's one design "
                            f"({', '.join(cand[0])}) -- they may be controller or "
                            f"revision variants of it, and a row whose own board was "
                            f"never committed owns nothing at all")
            out.append(rec)
            continue
        scored = [(s, sig, bset) for bset in cand
                  for s, sig in [score_set(rid, bset, r.get("path")) or (0, "")] if sig]
        scored.sort(key=lambda x: -x[0])
        if not scored:
            rec["note"] = (f"{len(cand)} designs' worth of boards in the repo, none "
                           f"resembling this id: "
                           + ", ".join(b[0] for b in cand[:4]))
            out.append(rec)
            continue
        best, sig, bset = scored[0]
        runner = scored[1][0] if len(scored) > 1 else 0
        # A clear winner on real evidence confirms itself. `ratio` never does, a
        # near-tie never does -- that is where a wrong answer would be invisible --
        # and a board another row wants just as much never does either.
        rivals = claims[(repo, tuple(bset))]
        if len(rivals) > 1 and max(rivals.values()) - min(rivals.values()) < 0.5:
            # NOT `contention_is_free` -- the rule above deliberately stops at repos
            # holding ONE design. `pierrechevalier83/ferris` is why, measured 2026-09-01:
            # its six records score alike on four of its five boards, so letting them
            # share here attributed `ferris/0_2/mini` to the bling PCB, the compact PCB
            # and the high PCB at once, and cost `base-ferris` its page. Where the repo
            # holds several designs, a record wanting this board may belong to another,
            # and "all of them run on it" stops being a true sentence.
            rec.update(signal="contended",
                       note=f"{len(rivals)} rows all match {bset[0]!r} within 0.5 "
                            f"({', '.join(sorted(rivals))}) -- one board, one owner")
            out.append(rec)
            continue
        if sig in ("exact", "token") and best - runner >= 0.5:
            rec.update(boards=bset, name=names[(repo, tuple(bset))],
                       verdict="confirmed", signal=sig,
                       note=f"{len(cand)} designs' worth of boards in the repo; "
                            f"matched on {sig}"
                            + (f"; {len(bset)} board files are one design"
                               if len(bset) > 1 else ""))
        elif tie_is_free(rid, scored, repo, scan):
            # A TIE THAT CHANGES NOTHING IS NOT A DECISION. `gtips/reviung`'s
            # `reviung41` ships ver1.2, ver1.3 and ver1.4; all three score 4.00 and all
            # three are MX with the same controller, so whichever is picked the
            # published record is identical. Refusing to choose kept the design off the
            # site entirely, which is a worse answer than either candidate.
            #
            # The row takes ALL the tied boards, because that is the true statement:
            # they are revisions of one design and this build cannot tell them apart.
            # Naming follows the shared stem rather than the winner, or `reviung41`
            # would publish as "reviung41 ver1.4".
            tied = sorted(p for _, _, bset in scored
                          if best - _score_of(bset, scored) < 0.5 for p in bset)
            rec.update(boards=tied, name=shared_stem(tied, repo),
                       verdict="confirmed", signal="tied",
                       note=f"{len(scored)} candidates tie at {best:.2f} and agree on "
                            f"every fact the build reads, so the choice between them "
                            f"cannot change the record; all {len(tied)} taken as one "
                            f"design's revisions")
        else:
            rec.update(signal=sig,
                       note=(f"{len(cand)} designs, best {sig} match {bset[0]!r} "
                             f"({best:.2f}) but runner-up scores {runner:.2f} -- "
                             f"too close to call"))
        out.append(rec)

    # THE NAME FALSIFIER, Forgejo #32. Run as a pass over the finished rows rather than
    # inline, because it reads the drafted NAME and the name is only settled once a board
    # has been chosen. It never changes a verdict -- see `name_is_unvouched`.
    for rec in out:
        if rec.get("signal") == "human" or not rec.get("name"):
            continue
        if name_is_unvouched(rec["id"], rec["name"], rec["boards"]):
            why["name unvouched -- flagged for a human"] += 1
            rec["note"] = (rec["note"] + " -- " if rec["note"] else "") + (
                f"NAME UNVOUCHED: nothing outside the board path supports "
                f"{rec['name']!r}; the firmware record is {rec['id']!r} and they share "
                f"no token. Ticket 44 names a design by its board's directory, which is "
                f"wrong when the directory names a version, a switch type or a template")
    return out, why


DEFAULT_QMK = os.path.expanduser("~/code/qmk_firmware")
DEFAULT_ZMK = os.path.expanduser("~/code/zmk")


def load_shields(zmk):
    """ZMK's side of `declares`, via the extractor rather than a second reader.

    Shelled out for the same reason `build_seed.py` shells out to it: the extractor owns
    what a shield is, and a copy here would be a second answer to that question. A tree
    that is not there yields nothing rather than raising -- ZMK rows then decline to be
    free, which is the safe direction.
    """
    try:
        out = subprocess.run([sys.executable,
                              str(pathlib.Path(__file__).resolve().parent
                                  / "extract_zmk_shields.py"),
                              "--zmk", zmk, "--json"],
                             capture_output=True, text=True, check=True)
        return {e["id"]: e for e in json.loads(out.stdout)}
    except (subprocess.CalledProcessError, OSError, ValueError):
        return {}


# A version, as a record id spells one: `v2`, `rev1`, `ver1.2`. NOT `lite`, `promicro`
# or `left` -- those name a variant that coexists with its siblings rather than
# superseding them, and the difference is the whole of `contention_is_free`.
VERSION_TOKEN = re.compile(r"^(?:v|rev|ver)[\d._]*\d[\w.]*$")


def declares(row, reg, shields):
    """What the FIRMWARE says a row is: its key counts and whether it is split.

    The two facts the site reads off a record. Everything else on a design page comes
    from the board, so two records agreeing here publish the same page.

    READ FROM THE ROW'S OWN TREE, backlog #64: `tree` is a key into `seed/trees.toml`,
    so a record hosted in an author's unmerged fork is read by QMK's reader from that
    fork's checkout. Asking upstream for it would return `known = False` and drop the
    row out of every contention it is part of, silently.
    """
    if reg.reader(row["tree"]) == "qmk":
        counts, split, known = qmk_metadata.facts(row["id"], reg.root(row["tree"]))
        return (tuple(sorted(counts)), split) if known else None
    shield = shields.get(row["id"])
    if not shield or not shield.get("keyboard"):
        return None
    return (tuple(sorted(shield["key_counts"])), shield["split"])


def contention_is_free(rows, reg, shields):
    """Several records want one board. Can they simply all have it?

    Yes when both hold, and the second is the owner's, 2026-09-01:

      1. THEY PUBLISH THE SAME PAGE. Same key counts, same splitness -- so picking
         wrong between them cannot change a single field. `tie_is_free` applies this
         to tied BOARDS; this is the mirror, tied RECORDS, and the board-first pass already made a
         design cite several records ("one firmware could cover multiple designs").

      2. THEY ARE NOT VERSIONS OF EACH OTHER. `Pill60` has three controller builds
         -- `blackpill_f401`, `blackpill_f411`, `bluepill` -- and all three really do
         flash onto the one PCB, so listing all three is a true statement. `speedo/v2`
         and `speedo/v3` are not like that: exactly one of them is the PCB in the
         repo, and the site prints this field as "the path a builder types to flash
         it", so naming the wrong one tells someone to brick their build. Measured on
         `cozykeys/speedo`: the only commit ever to touch `pcb/speedo.kicad_pcb` is
         titled "Speedo v3.0", so v2's board was never uploaded -- ticket 48's finding,
         arrived at again from the git history rather than by hand.

    Version cases go to `review_boards.py`, which prints that history for the human.
    """
    facts = {declares(r, reg, shields) for r in rows}
    if None in facts or len(facts) != 1:
        return False
    tokens = [set(re.split(r"[/_\-]", r["id"].lower())) for r in rows]
    shared = set.intersection(*tokens)
    return not any(VERSION_TOKEN.match(tok)
                   for one in tokens for tok in one - shared)


def _score_of(bset, scored):
    return next(sc for sc, _, b in scored if b is bset)


def tie_is_free(rid, scored, repo, scan):
    """Do the tied candidates publish the same record?

    Only ever asked when the drafter was about to refuse. It answers yes when the
    top-scoring candidates -- everything within 0.5 of the best, the same window the
    contention rule uses -- read identically off the board scan. Two boards agreeing
    there agree on the whole record, because everything else a design carries comes from
    the firmware record and the repo, which are the same for every candidate of one row.

    Deliberately NOT a claim that the boards are the same board. It is the weaker and
    checkable claim that this build cannot tell them apart, which is what makes choosing
    between them free rather than arbitrary.
    """
    best = max(sc for sc, _, _ in scored)
    tied = [b for sc, _, b in scored if best - sc < 0.5]
    if len(tied) < 2:
        return False
    return len({record_facts(b, repo, scan) for b in tied}) == 1


def toml_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def render(recs):
    head = pathlib.Path(__file__).with_suffix("").name
    lines = [
        "# seed/boards.toml -- which board file in a repo belongs to which firmware row.",
        f"# Drafted by scripts/{head}.py. Keyed (tree, id), like seed/sources.toml --",
        "# four designs carry both a ZMK shield and a QMK record, so an id alone names two rows.",
        "#",
        "#   drafted    a machine proposed it, nobody has ruled -- EXCLUDED from the build",
        "#   confirmed  settled, use it",
        "#",
        "# A row with `signal = \"human\"` was ruled by a PERSON and is copied through every",
        "# re-draft untouched -- this file is regenerated from scratch, and without that the",
        "# review queue's output would be eaten by the next run. The machine's own",
        "# `confirmed` is not protected: it is a proposal a better signal may overturn.",
        "#   no_board   no switch-placing .kicad_pcb survives to be attributed, so this",
        "#              row produces no design (ticket 48). A finding, not a shrug --",
        "#              and `signal` says which step emptied the repo: `scan` if it holds",
        "#              no such board, `filtered` if the path filter set every one aside.",
        "#",
        "# `signal` says WHY: `sole` one board one row, `path` the human's own subdirectory,",
        "# `exact` the id's tail equals the board's name, `token` every id token appears in",
        "# the path, `ratio` fuzzy only -- which never auto-confirms. `tied` means several",
        "# candidates scored the same AND agree on every fact the build reads, so the choice",
        "# between them cannot change the published record -- the row takes them all.",
        "# `filtered` is the one signal that names OUR mistake rather than the repo's shape:",
        "# the note lists the boards the path filter dropped, so a reviewer can overrule it.",
        "#",
        "# `name` is the DESIGN's name, read from the board's own directory -- ticket 44's",
        "# rule, and the reason the site is not named by the firmware id. It is a first",
        "# draft: the machine cannot capitalise `Sofle_v2_soldered` into `Sofle v2 Soldered`,",
        "# so normalising it is part of confirming the row. The slug follows the name, and",
        "# ticket 07 decision 8 makes a rename cheap -- old slugs keep resolving.",
        "",
    ]
    for r in recs:
        lines.append("[[entry]]")
        lines.append(f"tree     = {toml_str(r['tree'])}")
        lines.append(f"id       = {toml_str(r['id'])}")
        lines.append(f"source   = {toml_str(r['source'])}")
        inner = ", ".join(toml_str(b) for b in r["boards"])
        lines.append(f"boards   = [{inner}]")
        lines.append(f"name     = {toml_str(r['name'])}")
        lines.append(f"verdict  = {toml_str(r['verdict'])}")
        lines.append(f"signal   = {toml_str(r['signal'])}")
        lines.append(f"note     = {toml_str(r['note'])}")
        lines.append("")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("seed", nargs="?", default="seed/sources.toml")
    ap.add_argument("--cache", default="data/board-scan.json")
    ap.add_argument("--write", metavar="PATH", help="write the drafted file")
    ap.add_argument("--keep", metavar="PATH", default=RULINGS,
                    help="where the human rulings are read from (default seed/boards.toml)")
    ap.add_argument("--repo", help="show the drafting for one repo and stop")
    ap.add_argument("--qmk", default=DEFAULT_QMK)
    ap.add_argument("--zmk", default=DEFAULT_ZMK)
    args = ap.parse_args()

    recs, why = draft(args.seed, args.cache,
                      keep=ruled(args.keep),
                      qmk=args.qmk, zmk=args.zmk)

    if args.repo:
        for r in recs:
            if r["source"] == args.repo:
                print(f"{r['tree']}:{r['id']:34} [{r['verdict']:9}] {r['signal']:6} "
                      f"{r['boards']}\n    {r['note']}")
        return 0

    v = collections.Counter(r["verdict"] for r in recs)
    elsewhere = sum(1 for r in recs if r["signal"] == "elsewhere")
    s = collections.Counter(r["signal"] for r in recs if r["verdict"] == "confirmed")
    print(f"{len(recs)} confirmed source rows\n")
    print(f"  {v['confirmed']:4} attributed to a board")
    for sig, n in s.most_common():
        print(f"       {n:4} by {sig}")
    print(f"  {v['drafted'] - elsewhere:4} need a human -- too close to call, or nothing "
          f"resembling the id")
    print(f"  {elsewhere:4} another ticket's to attribute (see `elsewhere` notes)")
    print(f"  {v['no_board']:4} no board in the repo at all (ticket 48 drops them)")
    print(f"\nboard files set aside as not-a-design-of-their-own:")
    for k, n in why.most_common():
        if k != "board":
            print(f"  {n:4} {k}")
    print(f"  {why['board']:4} board")

    if args.write:
        pathlib.Path(args.write).write_text(render(recs), encoding="utf-8")
        print(f"\nwrote {args.write}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
