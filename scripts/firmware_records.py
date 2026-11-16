#!/usr/bin/env python3
"""A firmware record, and what it says -- whichever tree it lives in.

A firmware record is one QMK keyboard or one ZMK shield in a firmware tree, named by
the tree and its id there (`qmk` + `crkbd/rev1`, `zmk` + `corne`). The build asks it
the same questions whichever reader understands it, so the QMK-or-ZMK branch lives
here, once, and callers never write it:

    records = FirmwareRecords(reg)            # reg = trees.load(...)
    rec = records.of("qmk", "crkbd/rev1")
    kb, why = rec.keyboard()                  # key counts, split, halves -- or why not
    rec.built_for()                           # socket, chip, needs a separate controller
    rec.wiring(split=True)                    # ("matrix", 4, 6, "COL2ROW"), or None

The two readers are the adapters behind this module: `qmk_metadata` for a QMK tree,
`extract_zmk_shields` for a ZMK one. A tree whose reader is neither (`hardware`, which
borrows another row's record) holds no records to read.
"""
import collections

import extract_zmk_shields
import qmk_metadata
import zmk_wiring
from measure_controller_socket import DEV_BOARD, FOOTPRINT, PROCESSOR

Keyboard = collections.namedtuple("Keyboard", "key_counts split halves")
Keyboard.__doc__ = """What keyboard a record describes.

`key_counts` is one count per layout the record declares, largest first, and may be
empty: a record can be a keyboard and state no layout. `halves` is None where the
record does not say -- a QMK split always reads as 2, since QMK states no more."""


BuiltFor = collections.namedtuple("BuiltFor", "socket chip needs_separate_controller")
BuiltFor.__doc__ = """What a record is built for, in the site's own words.

`socket` is the form factor the record expects to be plugged into -- a key of
`FOOTPRINT`, so `pro_micro` for an Elite-C or a `requires: [pro_micro]` shield -- or None
where it names none, or one outside the domain. `chip` is a member of `PROCESSOR`, or
None. `needs_separate_controller` is True where the record says the chip is NOT on the
keyboard's own board: any QMK `development_board` at all, inside the domain or not, and
every ZMK shield, which by ZMK's own definition sits on a separate controller board."""


def _zmk_shields(reg):
    """Every ZMK tree's shields, as `{tree: {shield_id: shield}}`.

    PER TREE, because a shield id is only unique within one tree. Until Temper every
    ZMK row read upstream, so one flat dict was enough; a designer's own config repo
    can name a shield `corne` too, and a flat dict would hand one tree's reading to
    the other's row. A tree that isn't checked out reads as no shields, so its rows
    defer like any other unreadable record instead of stopping the build.
    """
    upstream = next(reg.local(t) for t in reg.ids()
                    if reg.reader(t) == "zmk" and reg.upstream(t))
    out = {}
    for tree in reg.ids():
        if reg.reader(tree) != "zmk":
            continue
        root, local = reg.root(tree), reg.local(tree)
        out[tree] = ({s["id"]: s for s in extract_zmk_shields.extract(root, local, upstream)}
                     if root and root.is_dir() else {})
    return out


class FirmwareRecords:
    """Every record the registry's trees hold. Reads every ZMK tree's shields once."""

    def __init__(self, reg):
        self._reg = reg
        self._shields = _zmk_shields(reg)

    def of(self, tree, record_id):
        """The record `record_id` in `tree`. A tree the registry lacks raises KeyError."""
        reader = self._reg.reader(tree)
        if reader == "qmk":
            return _QmkRecord(record_id, self._reg.root(tree))
        if reader == "zmk":
            return _ZmkRecord(self._shields.get(tree, {}).get(record_id),
                              self._reg.local(tree))
        return _NoRecord()


class _QmkRecord:
    def __init__(self, kb_id, root):
        self._id, self._root = kb_id, root

    def keyboard(self):
        counts, split, known = qmk_metadata.facts(self._id, self._root)
        if not known:
            return None, "not in the local QMK tree"
        return Keyboard(counts, split, 2 if split else None), None

    def built_for(self):
        board = qmk_metadata.dev_board(self._id, self._root)
        return BuiltFor(DEV_BOARD.get(board),
                        PROCESSOR.get(qmk_metadata.processor(self._id, self._root)),
                        bool(board))

    def wiring(self, split):
        """`qmk_metadata.wiring`'s reading. QMK states its own matrix, so `split` is
        not needed to read it."""
        return qmk_metadata.wiring(self._id, self._root)


class _ZmkRecord:
    def __init__(self, shield, local):
        self._shield, self._local = shield, local

    def keyboard(self):
        s = self._shield
        if not s or not s["keyboard"]:
            return None, "not a keyboard shield in the ZMK tree"
        return Keyboard(s["key_counts"], s["split"], s["halves"]), None

    def built_for(self):
        sockets = [r for r in (self._shield or {}).get("requires") or [] if r in FOOTPRINT]
        return BuiltFor(sockets[0] if sockets else None, None, True)

    def wiring(self, split):
        """The shield's `kscan` reading, by the reader the DESIGN's body picks: a
        unibody reads `zmk_wiring.wiring`, a split `split_wiring` (one half's scan), and
        each refuses the other's shape. A shield's `dir` is relative to the checkout of
        the tree it was read from, so it is resolved there and nowhere else."""
        s = self._shield
        if not s or not s.get("dir"):
            return None
        where = self._local/s["dir"]
        return zmk_wiring.split_wiring(where, s["id"]) if split else zmk_wiring.wiring(where)


class _NoRecord:
    """What a `hardware` tree holds: nothing to read. Such a row borrows a record."""

    def keyboard(self):
        return None, "no firmware record in this tree"

    def built_for(self):
        return BuiltFor(None, None, False)

    def wiring(self, split):
        return None
