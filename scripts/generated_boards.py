#!/usr/bin/env python3
"""Board files a repo describes but does not commit -- where they are, and how to read one.

An Ergogen repo holds a `config.yaml` and its footprints, and the `.kicad_pcb` those
generate is left to Releases or never shipped at all (jyap808/pando58). Every board
instrument here reads a board by `(repo, path)` from GitHub at HEAD, so a board nobody
committed was invisible: no board, no design. `generate_ergogen.py` runs the repo's
own config and writes what it produces here, at the path Ergogen would have written it
inside the repo -- `ergogen/output/pcbs/pando58_left.kicad_pcb` -- so every downstream
key, cache and seed row names it exactly as if the author had committed it.

A CACHE, NOT A RECORD. The directory is gitignored and `generate_ergogen.py` refills
it; nothing here is the author's file, and nothing is kept that a re-run cannot make.
`manifest.json` says which commit and which engine produced each repo's files, so a
reader can tell a generated board from a fetched one and a re-run knows what is stale.

Callers ask two things and no others:

    generated_boards.read(repo, path)   -> the file's text, or None if not generated
    generated_boards.listing()          -> {repo: [path, ...]} for every generated file
"""
import json
import pathlib

REPO = pathlib.Path(__file__).resolve().parent.parent
ROOT = REPO/"data/generated-boards"
MANIFEST = ROOT/"manifest.json"


def local(repo, path, root=ROOT):
    """Where a generated board sits, or None. Keyed case-insensitively on the repo,
    because GitHub slugs are and sources.toml is not (fetch_source_trees.py)."""
    f = root/repo.lower()/path
    return f if f.is_file() else None


def read(repo, path, root=ROOT):
    f = local(repo, path, root)
    return f.read_text(errors="replace") if f else None


def manifest(root=ROOT):
    f = root/"manifest.json"
    return json.loads(f.read_text()) if f.exists() else {}


def listing(root=ROOT):
    """{repo: [path, ...]}, the repo spelled as the manifest spells it."""
    return {repo: e.get("boards") or [] for repo, e in manifest(root).items()}
