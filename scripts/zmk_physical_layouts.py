#!/usr/bin/env python3
"""Read a ZMK shield's own `zmk,physical-layout` nodes: every key's real position,
size and rotation -- not just its count (`measure_layout_names.py`'s job) or its
name.

Backs backlog #23's remaining gap: `jorne`'s three named layouts (44/42/36) come
from ZMK's own physical-layout nodes, and `build_silhouettes.py`'s `merged_layouts`
only ever reads QMK's `keyboard.json`/`info.json` -- a different tree, a different
format, geometry this build has never drawn from before.

THE ONE THING THIS FORMAT STATES THAT QMK'S NEVER DOES: a key's rotation about an
ARBITRARY PIVOT, not its own center. ZMK's own docs
(docs/hardware-integration/physical-layouts.md) name the seven fields as
`w h x y r rx ry`, all in CENTI-units (hundredths of a key unit; rotation in
hundredths of a degree, positive clockwise) -- `rx`/`ry` a SEPARATE point the key
spins around, which routinely differs from its own center. `ferris`'s thumb cluster
is the clearest real example: two keys share pivot (4.30, 4.55), the other two share
(7.70, 4.55), and NONE of the four keys' own unrotated centers sit on either point --
the whole cluster fans out from two shared points, not four independent spins.

Usage: physical_layouts(shield_dir, zmk_root) -> {key_count: Geometry}
"""
import math
import pathlib
import re

PHYSICAL_LAYOUT = re.compile(
    r'compatible\s*=\s*"zmk,physical-layout"\s*;(.*?)\n\s*\}', re.S)

# `(-3000)` is how devicetree spells a negative integer cell -- parens, no space --
# so the parens are optional, not decorative, and dropping them silently loses every
# rotated-the-other-way key a splayed layout has (half of any symmetric fan).
NUM = r'\(?(-?\d+)\)?'
KEY = re.compile(r'key_physical_attrs\s+' + r'\s+'.join([NUM] * 7))


def _sources(shield_dir, zmk_root):
    """A shield's own dtsi/overlay files, plus whatever `#include <layouts/...>`
    pulls in -- `jorne` states one physical layout of its own and reuses corne's
    5-column/6-column ones this same way, exactly as `measure_layout_names.py`
    already found for its display-names.
    """
    paths = sorted(shield_dir.glob("*.dtsi")) + sorted(shield_dir.glob("*.overlay"))
    seen = set(paths)
    for dtsi in list(paths):
        text = dtsi.read_text(errors="ignore")
        for inc in re.findall(r'#include\s+<(layouts/[^>]+)>', text):
            path = zmk_root/"app"/"dts"/inc
            if path.exists() and path not in seen:
                seen.add(path)
                paths.append(path)
    return paths


def _rotate(cx, cy, px, py, degrees):
    """`(cx, cy)` rotated about pivot `(px, py)` by `degrees` clockwise -- ZMK's own
    stated convention, and also SVG `rotate()`'s in a y-down canvas, so nothing here
    needs a sign flip on its way to the page.
    """
    theta = math.radians(degrees)
    dx, dy = cx - px, cy - py
    return (
        px + dx * math.cos(theta) - dy * math.sin(theta),
        py + dx * math.sin(theta) + dy * math.cos(theta),
    )


def _key_tuple(raw):
    """One `key_physical_attrs` reading (centi-units) to this project's own
    `[x, y, w, h, turn]` (key units, plain degrees) -- the shape
    `Silhouettes.kt`'s `drawGeometry` already draws, which rotates a key about ITS
    OWN center and nothing else.

    Folding the pivot in here, rather than carrying `rx`/`ry` any further, rests on
    one identity: rotating a rigid shape about an external point is the same motion
    as moving its center to where that rotation would put it, then spinning the
    shape, unchanged, about its OWN new center. So the key's center is rotated about
    `(rx, ry)` first, and the stored rectangle is re-based on the result -- no
    approximation, the same rigid motion restated in terms this codebase already
    knows how to draw.
    """
    w, h, x, y, r, rx, ry = (int(n) for n in raw)
    w, h, x, y, rx, ry = (n / 100 for n in (w, h, x, y, rx, ry))
    r = r / 100
    cx, cy = x + w / 2, y + h / 2
    if r:
        cx, cy = _rotate(cx, cy, rx, ry, r)
    return [round(cx - w / 2, 2), round(cy - h / 2, 2), round(w, 2), round(h, 2), round(r, 2)]


def physical_layouts(shield_dir, zmk_root):
    """`{key_count: {"w":, "h":, "keys": [...]}}`, one entry per DISTINCT key count
    this shield states or includes across every `zmk,physical-layout` node it has --
    same ambiguity rule `measure_layout_names.py` applies to names: a count two
    nodes both claim is dropped, not guessed at.
    """
    by_count, ambiguous = {}, set()
    for path in _sources(shield_dir, zmk_root):
        text = path.read_text(errors="ignore")
        for node in PHYSICAL_LAYOUT.findall(text):
            raws = KEY.findall(node)
            if not raws:
                continue
            n = len(raws)
            if n in by_count:
                ambiguous.add(n)
                continue
            by_count[n] = raws

    out = {}
    for n, raws in by_count.items():
        if n in ambiguous:
            continue
        keys = [_key_tuple(raw) for raw in raws]
        # Re-based to the bounding box's own top-left, same convention
        # `build_silhouettes.geometry()` uses for a QMK layout -- a pivot-folded key
        # near the edge of a splayed cluster can land slightly negative, and nothing
        # downstream expects that.
        min_x = min(k[0] for k in keys)
        min_y = min(k[1] for k in keys)
        keys = [[round(k[0] - min_x, 2), round(k[1] - min_y, 2), k[2], k[3], k[4]]
                for k in keys]
        out[n] = {
            "w": round(max(k[0] + k[2] for k in keys), 2),
            "h": round(max(k[1] + k[3] for k in keys), 2),
            "keys": keys,
        }
    return out


if __name__ == "__main__":
    import argparse
    import json
    import os

    ap = argparse.ArgumentParser(description=__doc__,
                                  formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("shield", help="a shield directory name under app/boards/shields")
    ap.add_argument("--zmk", default=os.path.expanduser("~/code/zmk"))
    args = ap.parse_args()

    zmk_root = pathlib.Path(args.zmk)
    shield_dir = zmk_root/"app"/"boards"/"shields"/args.shield
    print(json.dumps(physical_layouts(shield_dir, zmk_root), indent=1, sort_keys=True))
