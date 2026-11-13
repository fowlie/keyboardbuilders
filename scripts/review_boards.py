#!/usr/bin/env python3
"""Show one repo's evidence so a human can rule on its board attributions.

`draft_boards.py` proposes and refuses; this is what the refusal is handed to. It
adds no rule and decides nothing -- it puts the board side and the record side of a
repo on one screen, because the queue's unit of decision is a REPO, not a row: you
cannot say which board is `crkbd/rev1`'s without seeing what the other six records
want.

WHAT IT SHOWS AND WHY EACH PIECE EARNS ITS LINE

  boards   the drafted name, the switch families placed on it, and the controller
           footprint it carries. `foostan/crkbd`'s two boards both place a bare
           QFN-56, which is how you can tell they are the rev4 generation and that
           `crkbd/rev1` (development_board: promicro) has no hardware here.
  records  key counts and the controller the FIRMWARE declares. Read as evidence and
           not as a verdict: `percyjw-2/split65`'s record called `promicro` declares
           a soldered atmega32u4 and its `stm32` record declares a socket, so the
           metadata is inverted against its own path. That is exactly the kind of
           thing a machine must not resolve on its own.

THE THREE SHAPES, printed as a heading so the queue can be worked by class:

  fewer boards than rows   ticket 48's `one board, one design` -- the surplus records
                           produce nothing. 58 of the 99 queued rows.
  as many boards as rows   an assignment: every board takes exactly one record, so
                           the best total pairing is forced rather than chosen.
  more boards than rows    which of the N boards is this record's -- and the boards
                           nobody claims are Half B's designs.

WHAT THE BOARD FILE'S OWN COMMIT HISTORY SAYS, the owner's signal, 2026-09-01. Where
the competing records are REVISIONS of one keyboard -- `speedo/v2` against `speedo/v3`
-- exactly one of them is the board that is actually in the repo, and the history often
names it outright: the only commit ever to touch `pcb/speedo.kicad_pcb` is titled
"Speedo v3.0", which is ticket 48's finding arrived at from the log rather than by hand.

**It is printed, never acted on.** Matching version words against commit messages was
measured over all 41 contested repos and produced 8 clean-looking answers of which 4
were wrong: `peej/tripel` "matched" `tripel/right` on a commit reading *"Add split right
shift"*. So this is evidence for a reader, exactly as the controller footprint is --
`draft_boards.py` never sees it.

Shown automatically with `--repo`, since that is the ruling view and one repo is a
handful of API calls. `--history` forces it on for a wider listing. Needs `gh`
authenticated; without it the line says so rather than failing the run.

RECORDING A RULING. `--rule` writes the human's answer back as `signal = "human"`,
which `draft_boards.py` copies through every re-draft untouched. Hand-editing the TOML
would work too and would be lost the next time the drafter runs.

Usage: review_boards.py [--repo SLUG] [--shape SHAPE] [--assign] [--history]
       review_boards.py --rule qmk:hillside/46/0_1 --boards PATH[,PATH] [--name NAME]
       review_boards.py --rule qmk:crkbd/rev1 --no-board "rev1's board is not in the repo"
"""
import argparse
import collections
import itertools
import json
import pathlib
import subprocess
import sys
import tomllib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from draft_boards import load_boards, score_set, render  # noqa: E402
from check_source_reachability import parse  # noqa: E402
from measure_controller_socket import socket_of, MCU_PACKAGE, MCU_NAMED  # noqa: E402
import trees as tree_registry  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
QMK = pathlib.Path.home()/"code/qmk_firmware/keyboards"


def board_facts(paths, repo, scan):
    """What the BOARD says about itself: switch families and controller."""
    fams, foot, sold = set(), set(), False
    for p in paths:
        entry = scan.get(f"{repo}\t{p}") or {}
        fams |= set(entry.get("families") or [])
        for name in entry.get("names") or {}:
            hit = socket_of(name)
            if hit:
                foot.add(hit)
            if MCU_PACKAGE.search(name) or MCU_NAMED.search(name):
                sold = True
    ctrl = "socket " + "/".join(sorted(foot)) if foot else "soldered MCU" if sold else "?"
    return sorted(fams), ctrl


def record_facts(rid, root=None):
    """What the FIRMWARE declares. QMK inherits down the path, most specific first.

    `root` is the row's own tree, backlog #64 -- a record hosted in an author's
    unmerged fork is read by QMK's reader from that fork's checkout. Defaults to
    upstream, which is where every record but one lives.
    """
    root = root or QMK
    counts, dev, proc, split = set(), None, None, False
    parts = rid.split("/")
    for i in range(len(parts), 0, -1):
        for fn in ("keyboard.json", "info.json"):
            path = root.joinpath(*parts[:i])/fn
            if not path.exists():
                continue
            try:
                data = json.loads(path.read_text(errors="ignore"))
            except ValueError:
                continue
            for layout in (data.get("layouts") or {}).values():
                if layout.get("layout"):
                    counts.add(len(layout["layout"]))
            dev = dev or data.get("development_board")
            proc = proc or data.get("processor")
            split = split or bool(data.get("split", {}).get("enabled"))
    ctrl = f"socket {dev}" if dev else f"soldered {proc}" if proc else "?"
    return sorted(counts, reverse=True), ctrl, split


def history(repo, path, limit=6):
    """The commits that touched one board file, newest first.

    Read over the network through `gh` rather than from a clone, the same choice
    `scan_boards.py` makes and for the same reason: there is no clone archive on this
    machine, and a reviewer wants the log of the file in front of them, not a checkout.

    Every source in the corpus is a GitHub URL -- `build_seed.py` builds them as
    `https://github.com/{repo}` unconditionally -- so there is no other host to handle
    yet. Returns a reason string instead of raising when it cannot look.
    """
    try:
        out = subprocess.run(
            ["gh", "api", f"repos/{repo}/commits?path={path}&per_page=100",
             "--jq", '.[] | "\\(.commit.author.date[0:10])  \\(.commit.message '
                     '| split("\\n")[0])"'],
            capture_output=True, text=True, timeout=40)
    except (OSError, subprocess.SubprocessError) as err:
        return None, f"could not read the history ({type(err).__name__})"
    if out.returncode != 0:
        return None, "could not read the history (is `gh` authenticated?)"
    lines = [ln for ln in out.stdout.splitlines() if ln.strip()]
    if not lines:
        return [], "no commits touch this path under that name (renamed? vendored?)"
    return lines[:limit], f"{len(lines)} commits" + (
        ", newest first" if len(lines) <= limit else f", newest {limit} of them")


def forecast(boards, names, scan, entries):
    """What is the queue still WORTH, in designs -- not in rows.

    Ticket 44 opened on the row count: *"that restriction defers 200 rows -- 43% of the
    confirmed set -- and it is the single largest thing standing between the site and
    its target size."* That was true when Half B did not exist. Half B publishes a board
    that no row claims, so it has been eating the queue from the other end, and a row
    whose repo has no unclaimed board left cannot add a design however it is ruled.

    THE CEILING IS PER REPO, and it is `min(rows queued, board-sets Half A could still
    publish)` -- because eight `dumbpad` rows over two unclaimed board-sets is two
    designs, not eight. A board-set counts only if it could actually complete a record:
    it must place a switch family AND name a controller, which are the two facts
    `build_seed.py` refuses to invent. A set failing either is blocked on something this
    queue cannot rule.

    It is an upper bound and says so: a ruling can also assign a set to a row whose
    licence does not resolve, or whose count nothing corroborates.
    """
    pub = set()
    design_boards = REPO/"data/design-boards.json"
    if design_boards.exists():
        for info in json.loads(design_boards.read_text()).values():
            for path in info["paths"] or []:
                pub.add((info["repo"], path))
    queued = collections.Counter(e["source"] for e in entries
                                 if e["verdict"] == "drafted")
    rows = []
    for repo, n_rows in queued.items():
        free, blocked = [], collections.Counter()
        for bset in boards.get(repo, []):
            if any((repo, p) in pub for p in bset):
                continue
            fams, ctrl = board_facts(bset, repo, scan)
            if not fams:
                blocked["no switch family"] += 1
            elif ctrl == "?":
                blocked["no controller"] += 1
            else:
                free.append(bset)
        rows.append((min(n_rows, len(free)), n_rows, len(free), blocked, repo))
    total = sum(r[0] for r in rows)
    print(f"THE QUEUE'S CEILING: {total} designs from {sum(queued.values())} queued rows, "
          f"over {len(queued)} repos")
    print("  min(rows, unclaimed board-sets that place a switch AND name a controller)\n")
    print(" ceil rows  free  blocked                      repo")
    for ceil, n, free, blocked, repo in sorted(rows, reverse=True):
        why = ", ".join(f"{v} {k}" for k, v in blocked.most_common()) or "—"
        print(f"  {ceil:4d} {n:4d}  {free:4d}  {why:27s}  {repo}")
    dead = sum(1 for r in rows if r[0] == 0)
    print(f"\n{dead} of {len(rows)} queued repos have NO unclaimed board a ruling could "
          f"publish, and their {sum(r[1] for r in rows if r[0] == 0)} rows are bookkeeping.")
    return 0


def shape(n_boards, n_rows):
    if n_boards < n_rows:
        return "fewer boards than rows"
    if n_boards == n_rows:
        return "as many boards as rows"
    return "more boards than rows"


def best_assignment(rows, sets, paths):
    """Every board takes exactly one record: score all pairings, keep the best total.

    Only used where the counts match. Brute force -- the largest such repo has 3 rows,
    so the 6 permutations cost nothing and a heuristic would cost trust.
    """
    grid = {}
    for r in rows:
        for j, bset in enumerate(sets):
            got = score_set(r["id"], bset, paths.get((r["tree"], r["id"])))
            grid[(r["id"], j)] = got[0] if got else 0.0
    best, total = None, -1.0
    for perm in itertools.permutations(range(len(sets))):
        got = sum(grid[(r["id"], j)] for r, j in zip(rows, perm))
        if got > total:
            best, total = perm, got
    runner = -1.0
    for perm in itertools.permutations(range(len(sets))):
        if perm == best:
            continue
        runner = max(runner, sum(grid[(r["id"], j)] for r, j in zip(rows, perm)))
    return best, total, runner, grid


def rule(args):
    """Write one human ruling into seed/boards.toml, re-rendered by the drafter's own writer."""
    tree, _, rid = args.rule.partition(":")
    path = REPO/"seed/boards.toml"
    entries = tomllib.loads(path.read_text())["entry"]
    hit = next((e for e in entries if e["tree"] == tree and e["id"] == rid), None)
    if hit is None:
        print(f"no row {tree}:{rid} in seed/boards.toml", file=sys.stderr)
        return 1
    if args.no_board:
        hit.update(boards=[], name="", verdict="no_board", signal="human", note=args.no_board)
    elif args.boards:
        boards, names, _, _ = load_boards(str(REPO/"data/board-scan.json"))
        want = sorted(b.strip() for b in args.boards.split(","))
        drafted = names.get((hit["source"], tuple(want)))
        hit.update(boards=want, name=args.name or drafted or "", verdict="confirmed",
                   signal="human", note=args.note)
        if not hit["name"]:
            print("no name: pass --name, or check the board paths group as one design",
                  file=sys.stderr)
            return 1
    else:
        print("--rule needs either --boards or --no-board", file=sys.stderr)
        return 1
    path.write_text(render(entries), encoding="utf-8")
    print(f"{tree}:{rid} -> {hit['verdict']}  {hit['name']!r}  {hit['boards']}")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", help="one repo's full dossier")
    ap.add_argument("--shape", help="only repos of this shape (substring match)")
    ap.add_argument("--assign", action="store_true",
                    help="for equal-count repos, print the forced best pairing")
    ap.add_argument("--history", action="store_true",
                    help="print each board file's commit log (implied by --repo)")
    ap.add_argument("--forecast", action="store_true",
                    help="what the queue is worth in DESIGNS rather than rows")
    ap.add_argument("--rule", metavar="TREE:ID", help="record a human ruling on one row")
    ap.add_argument("--boards", help="comma-separated board paths the row owns")
    ap.add_argument("--name", help="the design's name; defaults to the drafted one")
    ap.add_argument("--note", default="", help="why, in the reviewer's own words")
    ap.add_argument("--no-board", metavar="WHY",
                    help="rule that this row owns no board, with the reason")
    args = ap.parse_args()

    if args.rule:
        return rule(args)

    # Which reader each row's tree wants, and which checkout answers for it -- a row
    # whose record sits in an author's unmerged fork is still a QMK record. Backlog #64.
    reg = tree_registry.load()
    scan = json.loads((REPO/"data/board-scan.json").read_text())
    boards, names, _, _ = load_boards(str(REPO/"data/board-scan.json"))
    entries = tomllib.loads((REPO/"seed/boards.toml").read_text())["entry"]
    queued = collections.defaultdict(list)
    for e in entries:
        if e["verdict"] == "drafted" and e["signal"] != "elsewhere":
            queued[e["source"]].append(e)
    paths = {(r["tree"], r["id"]): r.get("path") for r in parse(str(REPO/"seed/sources.toml"))}

    if args.forecast:
        return forecast(boards, names, scan, entries)

    # `--repo` is the ruling view, so the evidence a ruling wants is on by default there.
    show_history = args.history or bool(args.repo)
    targets = [args.repo] if args.repo else sorted(queued)
    shown = 0
    for repo in targets:
        rows = queued.get(repo) or []
        if not rows:
            continue
        sets = boards.get(repo, [])
        kind = shape(len(sets), len(rows))
        if args.shape and args.shape not in kind:
            continue
        shown += 1
        print(f"\n=== {repo}   [{kind}: {len(sets)} boards, {len(rows)} queued]")
        for i, bset in enumerate(sets):
            fams, ctrl = board_facts(bset, repo, scan)
            print(f"  board {i}  {names[(repo, tuple(bset))]!r}")
            print(f"           {bset[0]}" + (f"  (+{len(bset)-1} more)" if len(bset) > 1 else ""))
            print(f"           switches {fams or '—'}, {ctrl}")
            if show_history:
                # Only the first file of a set: the others are the same design, and the
                # point is which VERSION this design is, not what each file has seen.
                lines, why = history(repo, bset[0])
                print(f"           history: {why}")
                for line in lines or []:
                    print(f"             {line}")
        for r in rows:
            if r["tree"] in reg and reg.reader(r["tree"]) == "qmk":
                counts, ctrl, split = record_facts(r["id"], reg.root(r["tree"]))
            else:
                counts, ctrl, split = [], "(zmk)", False
            print(f"  row      {r['tree']}:{r['id']}")
            print(f"           {counts or '—'} keys, {ctrl}{', split' if split else ''}")
            print(f"           drafter: {r['note']}")
        if args.assign and kind == "as many boards as rows" and sets:
            perm, total, runner, _ = best_assignment(rows, sets, paths)
            print(f"  --> forced pairing (total {total:.2f}, next best {runner:.2f}):")
            for r, j in zip(rows, perm):
                print(f"        {r['tree']}:{r['id']:<34} -> {names[(repo, tuple(sets[j]))]!r}")
                print(f"        {'':<36}    {sets[j][0]}")
    print(f"\n{shown} repos shown")
    return 0


if __name__ == "__main__":
    sys.exit(main())
