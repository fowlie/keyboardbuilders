#!/usr/bin/env python3
"""A person's ruled fill of a layout-options PCB, checked against the board itself --
see `seed/board-fills.toml`'s header for what a row states and why it exists.

`load()` reads and CHECKS every row, so each of its three readers (`build_silhouettes`,
`measure_stagger`, `measure_splay`) refuses the same bad row the same way: a fill that
names a spot the board does not place, a width its footprint's name contradicts without
saying why, two caps that overlap, or a count the design does not declare stops the
script rather than being drawn or measured.

Returns `{slug: {"board": path, "unit": mm, "hand": None | "left" | "right",
"caps": [[x, y, r, w, h], ...], "alternates": {keys: caps}}}`, millimetres and degrees
for the placement (the footprint's own), key units for the cap. `hand` is set where the
row lists one hand of a split and the drawing mirrors it. `alternates` holds the rows
marked `alternate = true`: the same board filled another way, drawn as its own layout
and never measured -- stagger and splay read the design's own fill only.
"""
import itertools
import json
import pathlib
import sys
import tomllib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from measure_keycaps import _corners, _overlap, stated_width

REPO = pathlib.Path(__file__).resolve().parent.parent

# How far a ruled coordinate may sit from the footprint it names. The scan rounds to
# 0.01 mm and the toml copies it, so this only absorbs a hand-typed last digit; the
# nearest alternate on any board here is millimetres away (tg4x's closest pair of
# alternates is 4.8 mm apart).
MATCH_MM = 0.1


def _fail(slug, why):
    raise SystemExit(f"seed/board-fills.toml: {slug}: {why}")


HANDS = ("left", "right")


def check(row, switches, declared, unit):
    """The row's caps, or SystemExit naming the first thing wrong with it."""
    slug, keys = row["slug"], row.get("key") or []
    # ONE HAND OF A SPLIT, where the board file holds one: `hand` says which, and the
    # keyboard is that hand and its mirror image, so it lists half of `keys`.
    hand = row.get("hand")
    if hand is not None and hand not in HANDS:
        _fail(slug, f"hand = {hand!r}, not one of {HANDS}")
    listed = row["keys"] // 2 if hand else row["keys"]
    if len(keys) != listed or (hand and row["keys"] % 2):
        _fail(slug, f"states keys = {row['keys']}{' for two hands' if hand else ''} "
                    f"but lists {len(keys)}")
    # A WIDTH FOR A FOOTPRINT WHOSE NAME STATES NONE. `ibnuda/squiggle` names its switch
    # footprints `footprints:choc-1` and `footprints:choc-2`, so there is no name to check
    # a ruled width against. `footprints` lets the row state one per footprint NAME, with
    # the evidence in `why`; every key on that footprint must then carry it. It may not
    # overrule a name that does state a width -- that is `name_says`, per key.
    ruled_names = {}
    for name, spec in (row.get("footprints") or {}).items():
        if not spec.get("why"):
            _fail(slug, f"footprint {name} is given a width without a `why`")
        if stated_width(name) is not None:
            _fail(slug, f"footprint {name} already states {stated_width(name)}; "
                        f"a key overrides a name with `name_says`")
        ruled_names[name] = (float(spec["w"]), float(spec.get("h", 1)))
    if row["keys"] not in declared:
        _fail(slug, f"{row['keys']} keys is not a layout the design declares {sorted(declared)}")
    if not switches:
        _fail(slug, f"no switch footprints on record for {row['board']} -- run "
                    f"measure_keycaps.py --only {slug} --refresh")
    caps, used = [], set()
    for k in keys:
        near = {(round(s[0], 2), round(s[1], 2), s[2], s[3]) for s in switches
                if abs(s[0] - k["x"]) <= MATCH_MM and abs(s[1] - k["y"]) <= MATCH_MM}
        if len(near) != 1:
            _fail(slug, f"({k['x']}, {k['y']}) matches {len(near)} switch footprints on "
                        f"{row['board']}, not exactly one")
        x, y, r, name = near.pop()
        if (x, y) in used:
            _fail(slug, f"({x}, {y}) is listed twice")
        used.add((x, y))
        w, h = float(k["w"]), float(k.get("h", 1))
        named = ruled_names.get(name) or stated_width(name)
        if "name_says" in k:
            if not k.get("why"):
                _fail(slug, f"({x}, {y}) overrides its footprint's name without a `why`")
            if named is None or named[0] != float(k["name_says"]):
                _fail(slug, f"({x}, {y}): `name_says = {k['name_says']}` but {name} "
                            f"states {named}")
            if named == (w, h):
                _fail(slug, f"({x}, {y}): {name} already states {named}; drop the override")
        elif named != (w, h):
            _fail(slug, f"({x}, {y}): ruled {w} x {h} but {name} states {named}; "
                        f"say `name_says` and `why` if the name is wrong")
        caps.append([x, y, r, w, h])
    unused = set(ruled_names) - {s[3] for s in switches
                                 if any(abs(s[0] - c[0]) < 0.01 and abs(s[1] - c[1]) < 0.01
                                        for c in caps)}
    if unused:
        _fail(slug, f"`footprints` names {sorted(unused)}, which no listed key sits on")
    corners = [_corners(c, unit) for c in caps]
    for i, j in itertools.combinations(range(len(caps)), 2):
        if _overlap(corners[i], corners[j]):
            _fail(slug, f"caps at {caps[i][:2]} and {caps[j][:2]} overlap")
    return caps


def load(designs, keycap_scan, path=REPO/"seed/board-fills.toml", unit_of=None):
    """Every row of `path`, checked. `designs` is designs.json's list; `keycap_scan` is
    keycap-scan.json's dict. `unit_of` reads the board's millimetres per key unit from
    the ruled caps (the caller's own instrument, so the two cannot disagree)."""
    if not path.exists():
        return {}
    by_slug = {d["slug"]: d for d in designs}
    out = {}
    rows = tomllib.loads(path.read_text()).get("fill", [])
    # THE DESIGN'S OWN FILL FIRST, then its alternates, whatever order the file lists
    # them in: an alternate belongs to a fill and must use that fill's board and hand.
    alternates = [r for r in rows if r.get("alternate")]
    for row in [r for r in rows if not r.get("alternate")]:
        slug = row["slug"]
        if slug in out:
            _fail(slug, "has two rows that are not `alternate`")
        design = by_slug.get(slug)
        if design is None:
            _fail(slug, "names no design")
        board = ((keycap_scan.get(slug) or {}).get("boards") or {}).get(row["board"])
        if board is None:
            _fail(slug, f"{row['board']} is not one of its attributed board files in "
                        f"keycap-scan.json")
        declared = {l["keys"] for l in design["layouts"]}
        points = [[k["x"], k["y"], 0, 1, 1] for k in row.get("key") or []]
        unit = unit_of(points) if unit_of else 19.05
        if not unit:
            _fail(slug, "the ruled keys give no key pitch to measure")
        out[slug] = {"board": row["board"], "unit": unit, "hand": row.get("hand"),
                     "caps": check(row, board.get("switches"), declared, unit),
                     "alternates": {}}
    for row in alternates:
        slug, fill = row["slug"], out.get(row["slug"])
        if fill is None:
            _fail(slug, "has an `alternate` row but no fill of its own")
        if row["board"] != fill["board"] or row.get("hand") != fill["hand"]:
            _fail(slug, "an `alternate` row must name its fill's board and hand")
        if row["keys"] == max({l["keys"] for l in by_slug[slug]["layouts"]}) \
                or row["keys"] in fill["alternates"]:
            _fail(slug, f"`alternate` keys = {row['keys']} is the design's own count or "
                        "another alternate's")
        board = keycap_scan[slug]["boards"][row["board"]]
        declared = {l["keys"] for l in by_slug[slug]["layouts"]}
        fill["alternates"][row["keys"]] = check(row, board.get("switches"), declared,
                                                fill["unit"])
    return out


def alternate_counts(path=REPO/"seed/board-fills.toml"):
    """`{slug: {keys, ...}}` from the `alternate` rows, unchecked -- for `build_seed.py`
    to add as layouts, BEFORE designs.json exists for `load` to check them against. The
    check still runs: `build_silhouettes.py` loads every row and stops on a bad one."""
    if not path.exists():
        return {}
    out = {}
    for row in tomllib.loads(path.read_text()).get("fill", []):
        if row.get("alternate"):
            out.setdefault(row["slug"], set()).add(row["keys"])
    return out


def ruled_positions(designs, keycap_path=REPO/"data/keycap-scan.json"):
    """`{slug: {board: [(x, y, r), ...]}}` -- each ruled fill as the switch positions a
    builder fits, for `measure_stagger.py` and `measure_splay.py` to read IN PLACE OF
    that board file's merged positions in `position-scan.json`. The merged positions of
    a layout-options board count its alternates too (tg4x reads 51 against 45), so the
    count guard both scripts apply would otherwise refuse it."""
    from measure_keycaps import unit_of
    scan = json.loads(pathlib.Path(keycap_path).read_text()) if pathlib.Path(keycap_path).exists() else {}
    return {slug: {fill["board"]: [(c[0], c[1], c[2]) for c in fill["caps"]]}
            for slug, fill in load(designs, scan, unit_of=unit_of).items()}


if __name__ == "__main__":
    from measure_keycaps import unit_of
    designs = json.loads((REPO/"src/main/resources/seed/designs.json").read_text())
    scan = json.loads((REPO/"data/keycap-scan.json").read_text())
    for slug, fill in load(designs, scan, unit_of=unit_of).items():
        print(slug, fill["board"], len(fill["caps"]), "caps, unit", fill["unit"], "-- checked")
