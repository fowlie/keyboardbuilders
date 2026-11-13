#!/usr/bin/env python3
"""Read key counts, split-ness and controller footprint out of a ZMK checkout.

The 31 confirmed ZMK rows in seed/sources.toml had no layouts and no split flag,
because the scripts that read this tree expected it at `scripts/zmk` and it is not
there. It is a normal checkout; point --zmk at it.

Three facts come out, each stated by ZMK rather than inferred:

  * **layouts** -- every `zmk,matrix-transform` in the shield is one arrangement
    the design can be built in, and its key count is the number of `RC(r,c)`
    entries in its `map`. Corne declares two, 42 and 36, which is exactly the
    `layouts` SET the domain model allows: both come off one PCB.
  * **split** -- a shield with `siblings:` in its `.zmk.yml` builds as two or more
    firmware images, one per half. That IS the split flag; nothing is guessed.
  * **controller footprint** -- `requires: [pro_micro]` names the socket the shield
    drops into. ZMK's own word, so no footprint-name lexicon is needed here.

What it does NOT answer: `reversible`. A shield describes firmware, and whether one
PCB serves both hands is a fact about the board.

Usage: extract_zmk_shields.py [--zmk ~/code/zmk] [--json]
"""
import argparse
import json
import os
import pathlib
import re
from collections import Counter

# `map = < RC(0,0) RC(0,1) ... >` inside a `compatible = "zmk,matrix-transform"`
# node. Counting RC() is the key count -- a transform lists exactly the positions
# that exist, which is why a 5-column Corne and a 6-column Corne differ here.
TRANSFORM = re.compile(
    r'compatible\s*=\s*"zmk,matrix-transform".*?map\s*=\s*<(.*?)>', re.S)
RC = re.compile(r"RC\s*\(")

# A physical layout states positions instead, for shields that declare no matrix
# transform of their own.
PHYSICAL = re.compile(
    r'compatible\s*=\s*"zmk,physical-layout".*?keys\s*=\s*<(.*?)>;', re.S)
KEY_ENTRY = re.compile(r"&key_physical_attrs")

# Last resort: a shield with a plain grid and no holes declares no transform at all,
# because ZMK's identity transform already describes it. Then the scan itself is the
# count -- rows x cols. `pancake` is 12 col-gpios by 4 row-gpios, and its keymap
# carries 48 bindings per layer, which is the check that this is not arithmetic
# wishful thinking. Do NOT use this where a transform exists: a transform is
# narrower than the grid precisely when the grid has holes.
GPIO_LIST = re.compile(r"(col-gpios|row-gpios|input-gpios)\s*=(.*?);", re.S)
GPIO_ENTRY = re.compile(r"<\s*&")


def yaml_ish(text):
    """The `.zmk.yml` files are flat enough that a line scan beats a dependency.

    Handles `key: value`, `key: [a, b]`, and `key:` followed by `  - item` lines,
    which is every shape these files use.
    """
    out, current = {}, None
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        if raw.startswith((" ", "\t")) and raw.lstrip().startswith("- "):
            if current:
                out.setdefault(current, []).append(raw.lstrip()[2:].strip().strip('"\''))
            continue
        if ":" not in raw:
            continue
        key, _, value = raw.partition(":")
        key, value = key.strip(), value.strip()
        current = key
        if not value:
            continue
        if value.startswith("[") and value.endswith("]"):
            out[key] = [v.strip().strip('"\'') for v in value[1:-1].split(",") if v.strip()]
        else:
            out[key] = value.strip('"\'')
    return out


def key_counts(shield_dir, zmk_root):
    """One count per arrangement the shield declares, largest first."""
    counts = []
    sources = sorted(shield_dir.rglob("*.dtsi")) + sorted(shield_dir.rglob("*.overlay"))
    included = set()
    for path in sources:
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        for body in TRANSFORM.findall(text):
            n = len(RC.findall(body))
            if n:
                counts.append(n)
        # A shield may pull its arrangements in from app/dts/layouts/.
        for inc in re.findall(r'#include\s+<(layouts/[^>]+)>', text):
            included.add(inc)

    if counts:
        return sorted(set(counts), reverse=True)

    for inc in sorted(included):
        path = zmk_root/"app"/"dts"/inc
        if not path.exists():
            continue
        text = path.read_text(errors="ignore")
        for body in TRANSFORM.findall(text):
            n = len(RC.findall(body))
            if n:
                counts.append(n)
        for body in PHYSICAL.findall(text):
            n = len(KEY_ENTRY.findall(body))
            if n:
                counts.append(n)
    if counts:
        return sorted(set(counts), reverse=True)

    # Nothing declared an arrangement, so read the scan matrix itself.
    for path in sources:
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        pins = {}
        for kind, body in GPIO_LIST.findall(text):
            pins[kind] = len(GPIO_ENTRY.findall(body))
        if pins.get("input-gpios"):
            counts.append(pins["input-gpios"])          # one GPIO per key, no matrix
        elif pins.get("col-gpios") and pins.get("row-gpios"):
            counts.append(pins["col-gpios"] * pins["row-gpios"])
    return sorted(set(counts), reverse=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zmk", default=os.path.expanduser("~/code/zmk"))
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    zmk = pathlib.Path(args.zmk)
    shields_dir = zmk/"app"/"boards"/"shields"
    if not shields_dir.is_dir():
        raise SystemExit(f"no shields under {shields_dir}")

    out = []
    for meta_path in sorted(shields_dir.rglob("*.zmk.yml")):
        meta = yaml_ish(meta_path.read_text(errors="ignore"))
        if meta.get("type") != "shield":
            continue
        shield_dir = meta_path.parent
        siblings = meta.get("siblings") or []
        out.append({
            "id": meta.get("id"),
            "name": meta.get("name"),
            "url": meta.get("url"),
            "dir": str(shield_dir.relative_to(zmk)),
            "requires": meta.get("requires") or [],
            "features": meta.get("features") or [],
            # `features: [keys]` is ZMK's own word for "this is a keyboard".
            # `nice_view` is `[display]` and `nice_view_adapter` states none; both
            # are accessories, and having no key count is the right answer for them
            # rather than a gap in this scan.
            "keyboard": "keys" in (meta.get("features") or []),
            "split": bool(siblings),
            "halves": len(siblings) if siblings else None,
            "key_counts": key_counts(shield_dir, zmk),
        })

    if args.json:
        print(json.dumps(out, indent=1, sort_keys=True))
        return

    boards = [s for s in out if s["keyboard"]]
    with_keys = [s for s in boards if s["key_counts"]]
    print(f"{len(out)} shields, {len(boards)} of them keyboards; "
          f"{len(with_keys)}/{len(boards)} have a readable key count "
          f"({100*len(with_keys)/len(boards):.0f}%)")
    print(f"split: {sum(1 for s in boards if s['split'])}   "
          f"unibody: {sum(1 for s in boards if not s['split'])}")
    print(f"\ncontroller footprint stated by `requires`:")
    for req, n in Counter(r for s in out for r in s["requires"]).most_common(8):
        print(f"  {req:<28} {n}")
    print(f"\nshields declaring more than one arrangement: "
          f"{sum(1 for s in boards if len(s['key_counts']) > 1)}")
    missing = [s["id"] for s in boards if not s["key_counts"]]
    if missing:
        print(f"\nno key count found ({len(missing)}): {', '.join(missing)}")


if __name__ == "__main__":
    main()
