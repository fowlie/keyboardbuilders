"""Which commit a board file is read at -- HEAD, unless a person pinned it.

ai03-2725/orbit, 2026-11-25. Its `Orbit-PCB/Orbit.kicad_pcb` at HEAD is an unplaced
netlist import: commit aefbeea ("Update orbit-x to production copy") overwrote the Rev 2.2
board in 2020, and the site drew one rectangle with half the switches in an ortho grid.
The real board is still in the repo, at c65a933. Every reader went to `.../HEAD/...`, so
nothing could read it.

A `seed/boards.toml` row may now carry `ref`, a commit, and every board in the row is read
at that commit. CONFIRMED ROWS ONLY, the rule the rest of that file follows. A pin is a
person's ruling that the author's HEAD is wrong, so it is set by hand with a note saying
why, never drafted.

The three board downloaders (`scan_boards.fetch`, `measure_key_pitch.fetch`,
`measure_slot_buses.fetch`) build their URL here, and `check_board_freshness.py` compares
a pinned file with its blob at the pin, so a pin is read once and an author's later
commits never evict it.
"""
import functools
import pathlib
import tomllib
import urllib.parse

REPO = pathlib.Path(__file__).resolve().parent.parent
BOARDS = REPO/"seed/boards.toml"


def pins(rows):
    """`{(repo, path): ref}` for every board of every confirmed row that names a ref."""
    return {(r["source"], path): r["ref"]
            for r in rows if r.get("ref") and r.get("verdict") == "confirmed"
            for path in r.get("boards") or []}


@functools.cache
def seed_pins():
    return pins(tomllib.loads(BOARDS.read_text())["entry"]) if BOARDS.exists() else {}


def ref(repo, path, pinned=None):
    return (seed_pins() if pinned is None else pinned).get((repo, path), "HEAD")


def raw_url(repo, path, pinned=None):
    return (f"https://raw.githubusercontent.com/{repo}/{ref(repo, path, pinned)}/"
            + urllib.parse.quote(path))
