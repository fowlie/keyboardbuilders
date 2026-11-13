#!/usr/bin/env python3
"""The firmware-tree registry: given a row's `tree`, who reads it and from where.

Backlog #64. `seed/trees.toml` is the file; this is the only thing that reads it.

Four questions, and callers ask no others:

    reg = trees.load(qmk=args.qmk, zmk=args.zmk)
    reg.reader("cheapino-vial")       -> "qmk"        which reader understands it
    reg.root("cheapino-vial")         -> Path(.../keyboards)   where a record id resolves
    reg.local("zmk")                  -> Path(~/code/zmk)      the checkout itself
    reg.record_url("cheapino-vial", "cheapino")
        -> "https://github.com/tompi/vial-qmk/tree/cheapinov2/keyboards/cheapino"

`root` and `local` are two different things and conflating them is the one trap
here. `qmk_metadata` resolves a record id under `<checkout>/keyboards`, while
`extract_zmk_shields.py` wants the checkout root and walks down to the shields
itself. So `local` is what was cloned and `root` is where an id starts.

`record_url` RETURNS NONE FOR AN UPSTREAM TREE, and that is the whole contract
`FirmwareRef.url` is built on: null means "the record is where you expect it",
so every design already in the corpus is unchanged and only a fork-hosted record
carries an address.

The registry is also the enumeration: `reader()` on a tree no entry names raises
rather than falling through to ZMK, which is what an unchecked `else` branch did
for the four years `tree` was two literals.
"""
import pathlib
import tomllib

REPO = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = REPO/"seed/trees.toml"

# WHERE A RECORD ID STARTS, per reader. This is the reader's own path convention,
# not a fact about any one tree, which is why it lives beside the reader name and
# not in the TOML: every QMK tree in existence puts its records under `keyboards/`,
# and a registry entry repeating that would be a chance for one of them to disagree.
# What a tree CAN differ in is where that `keyboards/` sits: a hardware repo that ships
# its own firmware keeps it in a subfolder, so an entry may name one as `within`.
# And some hardware repos have no `keyboards/` at all -- `marcocampione/Hem-keyboard`
# keeps its record at `firmware/hem/keyboard.json` -- so an entry may instead name the
# folder record ids resolve from outright, as `records` (`"."` for the repo root). One or
# the other: `within` keeps the reader's convention and `records` drops it.
SUBPATH = {"qmk": "keyboards", "zmk": "app/boards/shields"}


class Registry:
    """The parsed registry. Build it with [load]."""

    def __init__(self, entries):
        self._entries = {e["id"]: e for e in entries}

    def __contains__(self, tree):
        return tree in self._entries

    def ids(self):
        return list(self._entries)

    def entry(self, tree):
        try:
            return self._entries[tree]
        except KeyError:
            raise KeyError(
                f"no tree {tree!r} in seed/trees.toml -- known: "
                f"{', '.join(self._entries)}") from None

    def reader(self, tree):
        """Which reader understands this tree's records: `qmk`, `zmk` or `hardware`."""
        return self.entry(tree)["reader"]

    def local(self, tree):
        """The checkout this build reads, or None for a tree with nothing to check out."""
        return self.entry(tree).get("_local")

    def _subpath(self, tree):
        """The path from the checkout to where record ids start, or None."""
        e = self.entry(tree)
        if "records" in e:
            if "within" in e:
                raise ValueError(f"tree {tree!r} names both `records` and `within`")
            return pathlib.PurePosixPath(e["records"])
        sub = SUBPATH.get(self.reader(tree))
        if not sub:
            return None
        return pathlib.PurePosixPath(e.get("within") or ".")/sub

    def root(self, tree):
        """Where a record id resolves from -- `local` plus the reader's own subpath."""
        base, sub = self.local(tree), self._subpath(tree)
        return base/sub if base and sub is not None else None

    def upstream(self, tree):
        """Is this the canonical home for its reader?"""
        return bool(self.entry(tree).get("upstream"))

    def record_url(self, tree, record_id):
        """The folder a builder clones to get this record, or None if it is upstream.

        None is the interesting half: it is what keeps every design already in the
        corpus free of a `url` field, so the domain's default stays "upstream".
        """
        e = self.entry(tree)
        if self.upstream(tree) or not e.get("url"):
            return None
        sub = self._subpath(tree)
        parts = [e["url"].rstrip("/"), "tree", e.get("ref") or "HEAD"]
        if sub is not None and str(sub) != ".":
            parts.append(str(sub))
        parts.append(record_id)
        return "/".join(parts)


def load(path=REGISTRY, **overrides):
    """Read the registry. `overrides` maps a tree id to a checkout path.

    The overrides are the existing `--qmk` and `--zmk` flags: six scripts take
    them and they still win over the entry's `local`, so no command line changes.
    An override naming a tree the registry does not hold is a caller's typo and
    raises here rather than being ignored.
    """
    with open(path, "rb") as handle:
        entries = tomllib.load(handle).get("entry") or []
    reg = Registry(entries)
    for tree, where in overrides.items():
        if where is not None:
            reg.entry(tree)["local"] = str(where)
    for e in entries:
        e["_local"] = (pathlib.Path(e["local"]).expanduser()
                       if e.get("local") else None)
    return reg


if __name__ == "__main__":
    reg = load()
    for tid in reg.ids():
        where = ("upstream" if reg.upstream(tid)
                 else "no tree" if reg.root(tid) is None else "fork")
        print(f"{tid:16} reader={reg.reader(tid):9} {where:9} root={reg.root(tid)}")
