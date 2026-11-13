#!/usr/bin/env python3
"""Read the parts a design's own BOM names, per board file.

Found 2026-09-02 while checking a claim about Corne. `foostan/crkbd` ships
`pcbs/corne-cherry/hotswap/jlcpcb/production_files/BOM-corne-cherry.csv`, and its
first data column settles in one line what two other instruments could only
circle around:

    RP2040,"U1,rU1",QFN-56-1EP_7x7mm_P0.4mm_EP3.2x3.2mm,C2040

**A BOM STATES A PART; A FOOTPRINT DESCRIBES A HOLE.** That is the whole reason
this exists. `build_seed.py` has two sources for `Controller.Soldered.mcu` -- a
footprint that happens to name its own part (15 designs) and QMK's `processor`
(41) -- and both are weaker than this. The footprint usually says only
`TQFP-44_10x10mm_P0.8mm`, which is a package and not a chip; QMK's field
describes a firmware target, which on `ianklug/grooveboard` is one record for
nine boards with two different MCUs on them. A BOM is what the author sent to a
fab, so it is the design's own statement of what is on the board.

Measured over the 341 cloned trees: **53 repos ship a BOM `.csv`**.

PER BOARD, NOT PER REPO, and `hazels-garage/bad-wings` is why -- it ships seven
of them. A BOM is joined to the board file it shares the deepest directory with,
so `pcbs/corne-cherry/hotswap/jlcpcb/.../BOM-corne-cherry.csv` reaches
`pcbs/corne-cherry/hotswap/corne-cherry.kicad_pcb` and not the chocolate board
beside it. A BOM whose nearest board is ambiguous is reported and skipped rather
than attached to a guess.

WHAT IT DOES NOT DO. It does not decide anything. This is a scan, like
`board-scan.json` and `licence-scan.json`: it records what each BOM says, and
`build_seed.py` chooses whether a stated part is a fact it publishes. In
particular a BOM naming `ATmega32A` is recorded faithfully even though the
domain's `Mcu` has no member for it -- suppressing it here would hide the reason
a design has no chip recorded.

Usage: scan_boms.py [--trees data/source-trees.json]
                    [--boards data/board-scan.json]
                    [--json | --out PATH] [--jobs N]
"""
import argparse
import collections
import csv
import io
import json
import pathlib
import re
import sys
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from check_source_reachability import parse

REPO = pathlib.Path(__file__).resolve().parent.parent

# A BOM, and not merely a CSV. Repos ship position files (`CPL-*.csv`,
# `*-top-pos.csv`), pick-and-place reports and netlists as CSV too, and a
# position file has a Designator column with no part name at all -- so matching
# every `.csv` would fill this scan with rows that name nothing.
BOM_FILE = re.compile(r"bom", re.I)
NOT_BOM = re.compile(r"cpl[-_]|[-_]pos\b|position|placement|netlist", re.I)

# The column that carries the part. Every BOM dialect spells it differently and
# the order here is precedence: JLCPCB writes `Comment`, KiCad's default BOM
# plugin writes `Value`, and an MPN column is more specific than either when a
# repo bothered to fill one in.
PART_COLUMNS = ("mpn", "manufacturer part number", "part number", "part",
                "comment", "value", "description")
# Kept beside the part because it is what lets a reader check the row: a chip
# with no designator and no footprint is a name in a spreadsheet.
DESIGNATOR_COLUMNS = ("designator", "designators", "reference", "references",
                      "refdes", "ref")
FOOTPRINT_COLUMNS = ("footprint", "package", "package/footprint")


def fetch(slug, path):
    """The file's text, or (None, why). Network, not a clone -- `scan_boards`'s rule."""
    url = f"https://raw.githubusercontent.com/{slug}/HEAD/" + urllib.parse.quote(path)
    try:
        with urllib.request.urlopen(url, timeout=90) as r:
            return r.read().decode("utf-8-sig", "replace"), None
    except Exception as e:                        # noqa: BLE001 -- report, don't raise
        return None, f"{type(e).__name__}: {e}"[:80]


def bom_paths(paths):
    """The BOM files in a repo's tree."""
    return [p for p in paths
            if p.lower().endswith(".csv")
            and BOM_FILE.search(p) and not NOT_BOM.search(p)]


def read_rows(text):
    """Every (part, designator, footprint) a BOM states.

    THE HEADER IS FOUND, NOT ASSUMED TO BE LINE 1. Several BOMs open with the
    project name, a date, or a blank line before the column names -- KiCad's own
    exporter does it -- so a reader that trusts the first row gets a header of
    `["Sofle v2 BOM"]` and finds no part column at all. The first row containing
    a recognised part column IS the header.

    The delimiter is sniffed because semicolon-separated BOMs exist (a European
    spreadsheet default), and a comma-reader given one returns every row as a
    single field, which looks exactly like a BOM that names nothing.
    """
    try:
        dialect = csv.Sniffer().sniff(text[:4000], delimiters=",;\t")
    except csv.Error:
        dialect = csv.excel
    rows = list(csv.reader(io.StringIO(text), dialect))
    for i, row in enumerate(rows):
        lower = [c.strip().lower() for c in row]
        part_at = next((lower.index(c) for c in PART_COLUMNS if c in lower), None)
        if part_at is None:
            continue
        des_at = next((lower.index(c) for c in DESIGNATOR_COLUMNS if c in lower), None)
        fp_at = next((lower.index(c) for c in FOOTPRINT_COLUMNS if c in lower), None)
        out = []
        for r in rows[i + 1:]:
            if part_at >= len(r) or not r[part_at].strip():
                continue
            out.append({
                "part": r[part_at].strip(),
                "designator": r[des_at].strip() if des_at is not None
                and des_at < len(r) else None,
                "footprint": r[fp_at].strip() if fp_at is not None
                and fp_at < len(r) else None,
            })
        return out, lower[part_at]
    return [], None


def nearest_board(bom_path, board_paths):
    """The board file this BOM belongs to, by deepest shared directory.

    Returns None where the answer is a tie, which is not a failure to report as
    an error: a repo with one BOM at its root and three boards under it has
    genuinely not said which board the BOM is for, and attaching it to whichever
    sorted first is the wholesale guess this file exists to avoid.
    """
    if len(board_paths) == 1:
        return board_paths[0]
    bom_dirs = bom_path.split("/")[:-1]

    def shared(board):
        dirs = board.split("/")[:-1]
        n = 0
        for a, b in zip(bom_dirs, dirs):
            if a != b:
                break
            n += 1
        return n

    scored = sorted(((shared(b), b) for b in board_paths), reverse=True)
    if not scored or scored[0][0] == 0:
        return None
    if len(scored) > 1 and scored[1][0] == scored[0][0]:
        return None
    return scored[0][1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trees", default="data/source-trees.json")
    ap.add_argument("--boards", default="data/board-scan.json")
    ap.add_argument("--sources", default="seed/sources.toml")
    ap.add_argument("--out", default="data/bom-scan.json")
    ap.add_argument("--json", action="store_true", help="print instead of writing --out")
    ap.add_argument("--jobs", type=int, default=8)
    args = ap.parse_args()

    trees = json.loads((REPO/args.trees).read_text())
    scan = json.loads((REPO/args.boards).read_text())
    confirmed = {r["source"] for r in parse(str(REPO/args.sources))
                 if r.get("verdict") == "confirmed" and r.get("source")}

    boards_by_repo = collections.defaultdict(list)
    for key, val in scan.items():
        repo, path = key.split("\t", 1)
        if val.get("kind") == "board":
            boards_by_repo[repo].append(path)

    jobs = []
    for repo in sorted(confirmed):
        paths = (trees.get(repo) or {}).get("paths") or []
        for p in bom_paths(paths):
            jobs.append((repo, p))
    print(f"{len(jobs)} BOM files across "
          f"{len({r for r, _ in jobs})} of {len(confirmed)} confirmed repos",
          file=sys.stderr)

    out, unattached, unreadable = {}, [], []
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        fetched = list(pool.map(lambda j: (j, *fetch(*j)), jobs))
    for (repo, path), text, err in fetched:
        if err:
            unreadable.append((repo, path, err)); continue
        rows, column = read_rows(text)
        if not rows:
            unreadable.append((repo, path, "no part column")); continue
        board = nearest_board(path, sorted(boards_by_repo.get(repo) or []))
        if not board:
            unattached.append((repo, path, len(rows))); continue
        out[f"{repo}\t{board}"] = {
            "bom": path, "column": column, "parts": rows,
        }

    print(f"  {len(out):4} joined to a board file", file=sys.stderr)
    print(f"  {len(unattached):4} could not be joined to one board", file=sys.stderr)
    for repo, path, n in unattached[:10]:
        print(f"         {repo} {path} ({n} parts)", file=sys.stderr)
    print(f"  {len(unreadable):4} unreadable or not a BOM", file=sys.stderr)
    for repo, path, why in unreadable[:10]:
        print(f"         {repo} {path}: {why}", file=sys.stderr)

    text = json.dumps(out, indent=1, sort_keys=True)
    if args.json:
        print(text)
    else:
        (REPO/args.out).write_text(text)
        print(f"-> {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
