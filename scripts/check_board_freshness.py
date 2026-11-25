#!/usr/bin/env python3
"""Forget every cached reading of a board file that has changed upstream, so it is read again.

Forgejo #142, 2026-10-04: Sango's silhouette did not match its photo. Its key positions
had been read from an older revision of its board file -- 39 positions where today's file
places 72 -- and nothing ever read it again, because every per-board cache in this
pipeline is filled once and trusted forever. Koca58 was the same (0 cached, 58 today).

ONE GITHUB CALL PER REPO. The tree listing gives every file's blob SHA and size, so a
changed board is found without downloading any board. The SHAs are kept in
`board-shas.json`, and once a board has one, a different SHA is the whole test.

A BOARD WITH NO SHA YET is compared with what `board-scan.json` read, which is a length
in CHARACTERS, while GitHub's size is in bytes. The first version compared the two and
flagged 62 boards, every one checked a false alarm from non-ASCII text (`yosino58`'s
Japanese, untouched since 2019). Re-reading those was not free: the readers have changed
since the caches were filled, and yosino58 re-read at 28 positions a hand where its cache
says 29, which dropped it from the catalogue. So a size mismatch is only a reason to
download that board and compare characters.

WHAT IS FORGOTTEN is every cache that reads a board file once and keeps it: the three keyed
by board file and the three keyed by design. The nightly run's own scans then refill them,
because each reads whatever its cache is missing. Caches that are recomputed every run
(pitch, extras, splay, stagger) need nothing.

A file the listing no longer has is not evicted: that is `check_source_paths.py`'s report,
and forgetting it would only turn a known reading into a gap.

Usage: check_board_freshness.py [--dry-run] [--jobs 8]
"""
import argparse
import json
import pathlib
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import board_refs  # noqa: E402
from scan_boards import fetch  # noqa: E402
from source_dossier import gh  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
SCRATCH = REPO/"data"
SHAS = SCRATCH/"board-shas.json"

BY_FILE = ["board-scan.json", "switch-positions.json", "switch-pad-scan.json"]
BY_DESIGN = ["position-scan.json", "keycap-scan.json", "exposed-components-boards.json"]


def listing(repo, ref="HEAD"):
    """`{path: (sha, size)}` for every file at the repo's `ref`, or None if unreadable."""
    d = gh(f"repos/{repo}/git/trees/{ref}?recursive=1")
    if not d:
        return None
    return {e["path"]: (e["sha"], e.get("size")) for e in d.get("tree", [])
            if e.get("type") == "blob"}


def with_pins(mapping, pinned):
    """`mapping` plus every pinned board no published design holds yet.

    A pin is usually set on a board taken off the site because HEAD was wrong, so the last
    build's `design-boards.json` does not list it, and without this its HEAD readings would
    stay cached and be what the build publishes from on the night it is pinned. Keyed
    `pin:repo` -- no design-keyed cache has that key, so only the per-file caches are
    evicted for it; the per-design ones follow the night after it publishes."""
    held = {(m["repo"], p) for m in mapping.values() for p in m["paths"]}
    extra = {}
    for (repo, path) in pinned:
        if (repo, path) not in held:
            extra.setdefault(f"pin:{repo}", {"repo": repo, "paths": []})["paths"].append(path)
    return {**mapping, **extra}


def pinned_listing(repo, files, pinned, listing_at):
    """`files` with each pinned path's blob taken at its pin (board_refs.py), not HEAD.

    A pin is a person ruling the author's HEAD wrong, so HEAD moving is not a change to
    the board we read. And a board read at HEAD and then pinned has a stored SHA that
    differs from the pin's, so pinning it evicts the HEAD reading and the scans read the
    pinned file instead."""
    refs = {ref for (r, _), ref in pinned.items() if r == repo}
    if not refs:
        return files
    out = dict(files)
    for ref in refs:
        at = listing_at(repo, ref) or {}
        for (r, path), want in pinned.items():
            if r == repo and want == ref and path in at:
                out[path] = at[path]
    return out


def same_text(key, scanned):
    """Does the board file today have the length, in characters, that the scan read?"""
    repo, path = key.split("\t", 1)
    text, _, _ = fetch(repo, path, cap=0)
    return text is not None and len(text) == scanned[key].get("bytes")


def changed_files(mapping, listings, shas, scanned, verify=same_text, pinned=frozenset()):
    """The `repo\\tpath` keys whose board file is not the one we read, and the new SHAs.

    A `pinned` key with no stored SHA is always changed: whatever is cached for it may be a
    HEAD reading, and storing the pin's SHA beside it would trust that reading forever."""
    changed, current = [], {}
    for m in mapping.values():
        files = listings.get(m["repo"])
        if files is None:
            continue
        for path in m["paths"]:
            if path not in files:
                continue
            key = f"{m['repo']}\t{path}"
            sha, size = files[path]
            current[key] = sha
            if key in shas:
                if shas[key] != sha:
                    changed.append(key)
            elif key in pinned:
                changed.append(key)
            elif (key in scanned and scanned[key].get("bytes") not in (None, size)
                  and not verify(key, scanned)):
                changed.append(key)
    return sorted(set(changed)), current


def evict(changed, mapping, dry_run):
    """Drop every cached reading of the changed files; returns what was dropped, per cache."""
    designs = {slug for slug, m in mapping.items()
               if any(f"{m['repo']}\t{p}" in changed for p in m["paths"])}
    dropped = {}
    for name, keys in [(n, changed) for n in BY_FILE] + [(n, designs) for n in BY_DESIGN]:
        path = SCRATCH/name
        if not path.exists():
            continue
        cache = json.loads(path.read_text())
        gone = [k for k in keys if k in cache]
        for k in gone:
            del cache[k]
        if gone and not dry_run:
            path.write_text(json.dumps(cache, indent=1, sort_keys=True))
        dropped[name] = len(gone)
    return sorted(designs), dropped


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true", help="report, forget nothing")
    ap.add_argument("--jobs", type=int, default=8)
    args = ap.parse_args()

    mapping = json.loads((SCRATCH/"design-boards.json").read_text())
    scanned = json.loads((SCRATCH/"board-scan.json").read_text())
    shas = json.loads(SHAS.read_text()) if SHAS.exists() else {}

    pinned = board_refs.seed_pins()
    mapping = with_pins(mapping, pinned)
    repos = sorted({m["repo"] for m in mapping.values()})
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        listings = dict(zip(repos, pool.map(listing, repos)))
    listings = {r: f if f is None else pinned_listing(r, f, pinned, listing)
                for r, f in listings.items()}
    unread = [r for r, v in listings.items() if v is None]

    changed, current = changed_files(mapping, listings, shas, scanned,
                                     pinned={f"{r}\t{p}" for r, p in pinned})
    designs, dropped = evict(changed, mapping, args.dry_run)

    if not args.dry_run:
        SHAS.write_text(json.dumps({**shas, **current}, indent=1, sort_keys=True))
    print(f"{len(repos)} repos listed ({len(unread)} unreadable), "
          f"{len(current)} board files fingerprinted")
    print(f"{len(changed)} changed since read -> designs re-read next: "
          f"{', '.join(designs) or 'none'}")
    for name, n in dropped.items():
        if n:
            print(f"  {'would drop' if args.dry_run else 'dropped'} {n:>3} from {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
