"""Walk QMK keyboards/ and emit one record per keyboard, merging info.json /
keyboard.json up the ancestor chain like QMK does.

THE RECORD RULE IS QMK'S OWN, COPIED FROM ITS SOURCE, NOT INFERRED. From
`lib/python/qmk/keyboard.py::list_keyboards`:

    kb_wildcard = os.path.join(base_path, "**", 'keyboard.json')
    paths = [p for p in glob(kb_wildcard, recursive=True)
             if os.path.sep + 'keymaps' + os.path.sep not in p]

So a record is **any directory holding a `keyboard.json`**, excluding anything
under a `keymaps/` directory. There is no leaf test and `info.json` does not
count -- `info.json` is data merged INTO a record, never a record itself.

This is the seventh time this map has caught its own instrument measuring
something other than the corpus, and it is the reason the rule is now copied
rather than reasoned about. Two earlier rules and what they got wrong:

  * "a directory containing keymaps/" -- the rule this script shipped with, and
    3,334 records. It misses every revision directory that holds the real
    configuration but ships no keymaps/ of its own: `1upkeyboards/sweet16/v1`,
    `25keys/zinc/rev1`, `40percentclub/gherkin/pro_micro`.
  * "holds info.json/keyboard.json with no such descendant" -- the correction
    proposed on ticket 28. It reaches the right total but by a different route,
    and it admits `info.json`-only directories that QMK does not build.

`--verify` checks the output against `qmk list-keyboards`, which is the
authority. It agrees on all 3,690 today. Run it after any change here.
"""
import json, os, re, subprocess, sys
from glob import glob

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import qmk_metadata

ROOT = os.path.join(os.path.expanduser(os.environ.get('QMK_DIR', '~/code/qmk_firmware')), 'keyboards')


# QMK reads its own metadata with `hjson` (lib/python/qmk/json_schema.py), which
# accepts JSON5: `//` comments, trailing commas, unquoted keys. Strict json.load
# rejects 80 of the 3,690 records. `hjson` is not installed here, so this strips
# the two forms that actually occur in the tree and RE-RAISES anything else --
# never `except: pass`, which is how a silently-empty record gets counted as a
# record that declares nothing.
#
# The bug this replaced was mine, found 2026-08-20 while measuring the Scotto
# boards: 15 of 28 `handwired/scottokeebs/*` records reported zero layouts, which
# read as "declares no layout" when the truth was "the file has // comments in
# it". Eighth instrument fault on this map, and the first self-inflicted one.
_LINE_COMMENT = qmk_metadata._LINE_COMMENT
_TRAILING_COMMA = qmk_metadata._TRAILING_COMMA
_tolerant = qmk_metadata.tolerant


UNPARSED = []


def load(d):
    out = {}
    for name in ('info.json', 'keyboard.json'):
        p = os.path.join(d, name)
        if not os.path.exists(p):
            continue
        raw = open(p, errors='replace').read()
        for attempt in (raw, _tolerant(raw)):
            try:
                out.update(json.loads(attempt))
                break
            except ValueError as exc:
                last = exc
        else:
            # Recorded, never swallowed. A record whose metadata we cannot read is
            # a known unknown; a record we silently emptied is a wrong number.
            UNPARSED.append((os.path.relpath(p, ROOT), str(last)))
    return out


def record_dirs():
    """QMK's own rule, verbatim. Returns paths relative to ROOT."""
    sep = os.sep
    paths = [
        p for p in glob(os.path.join(ROOT, '**', 'keyboard.json'), recursive=True)
        if sep + 'keymaps' + sep not in p
    ]
    return sorted({os.path.relpath(os.path.dirname(p), ROOT) for p in paths})


def build():
    recs = []
    for rel in record_dirs():
        parts = rel.split(os.sep)
        merged = {}
        for i in range(len(parts) + 1):
            merged.update(load(os.path.join(ROOT, *parts[:i]) if i else ROOT))
        layouts = merged.get('layouts') or {}
        matrix = merged.get('matrix_pins')
        recs.append(dict(
            id=rel,
            top=parts[0],
            leaf=parts[-1],
            keyboard_name=merged.get('keyboard_name'),
            manufacturer=merged.get('manufacturer'),
            maintainer=merged.get('maintainer'),
            url=merged.get('url'),
            processor=merged.get('processor'),
            development_board=merged.get('development_board'),
            layouts=sorted(layouts.keys()),
            # Door B needed these and had to re-walk the tree by hand.
            key_counts={k: len(v.get('layout', [])) for k, v in layouts.items()},
            matrix_pins=matrix,
            wiring=(
                'direct' if isinstance(matrix, dict) and 'direct' in matrix
                else 'matrix' if isinstance(matrix, dict)
                else None
            ),
        ))
    return recs


def verify(recs):
    """Compare against `qmk list-keyboards`, the authority."""
    try:
        out = subprocess.run(['qmk', 'list-keyboards'], cwd=os.path.dirname(ROOT),
                             capture_output=True, text=True, timeout=600)
    except (OSError, subprocess.TimeoutExpired) as exc:
        print(f"verify: cannot run qmk list-keyboards: {exc}", file=sys.stderr)
        return 2
    authoritative = {line.strip() for line in out.stdout.splitlines() if line.strip()}
    ours = {r['id'].replace(os.sep, '/') for r in recs}
    print(f"qmk list-keyboards: {len(authoritative)}   this script: {len(ours)}")
    only_qmk, only_ours = sorted(authoritative - ours), sorted(ours - authoritative)
    if not only_qmk and not only_ours:
        print("AGREE exactly")
        return 0
    print(f"DISAGREE: {len(only_qmk)} only in qmk, {len(only_ours)} only here")
    for label, rows in (("only in qmk", only_qmk), ("only here", only_ours)):
        for row in rows[:15]:
            print(f"  {label}: {row}")
    return 1


if __name__ == '__main__':
    args = sys.argv[1:]
    recs = build()
    if '--verify' in args:
        sys.exit(verify(recs))
    if args:
        json.dump(recs, open(args[0], 'w'))
    print(f"records: {len(recs)}")
    if UNPARSED:
        print(f"UNPARSEABLE metadata files: {len(UNPARSED)}", file=sys.stderr)
        for path, err in UNPARSED[:10]:
            print(f"  {path}: {err[:70]}", file=sys.stderr)
        if len(UNPARSED) > 10:
            print(f"  ... and {len(UNPARSED) - 10} more", file=sys.stderr)
