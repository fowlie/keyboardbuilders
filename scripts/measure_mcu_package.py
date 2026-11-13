"""#16, third pass: the attribution is `seed/boards.toml`'s, not this script's.

The first pass scanned a whole repo per design, so any repo holding several designs came
back ambiguous by construction -- 11 of the 16. The second pass attributed board files
by scoring slug tokens against paths, which fixed the grooveboards and left a rule this
file had no business owning.

**THAT RULE NOW EXISTS ELSEWHERE AND IS CONFIRMED BY A PERSON.** `seed/boards.toml` says
which board file belongs to which design, `build_seed.py` writes the result to
`design-boards.json`, and `attributed_boards.py` reads it. So the token scoring, the
tie-guard and the whole-repo fallback are gone -- along with the `via` field that
recorded which of them fired, because there is now only one answer and it is not a guess.

The reason to care is not tidiness. Token scoring reads a NEIGHBOUR's board whenever it
cannot separate two slugs, and putting a QFN package on a design that ships TQFP is
exactly the wrong-value-indistinguishable-from-right failure this measurement exists to
avoid. `attributed_boards.py` carries what that cost in the sibling scans.

Usage: measure_mcu_package.py
"""
import argparse
import collections
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from attributed_boards import REPO, SCRATCH, load  # noqa: E402
from measure_controller_socket import MCU_PACKAGE, bare, socket_of  # noqa: E402

SHAPE = re.compile(r"(tqfp|lqfp|vqfn|qfn|qfp|dip)-(\d+)", re.I)


def packages_in(names):
    """Every MCU package shape the board draws, as `tqfp-44`. A socket is not one."""
    out = []
    for full in names:
        name = bare(full)
        if MCU_PACKAGE.search(name) and not socket_of(full):
            shape = SHAPE.search(name)
            if shape:
                out.append(f"{shape.group(1).lower()}-{shape.group(2)}")
    return out


def main():
    argparse.ArgumentParser(description=__doc__,
                            formatter_class=argparse.RawDescriptionHelpFormatter
                            ).parse_args()
    rows, names_for = load()
    # Only a soldered design has a package to read: a socketed one does not fix its chip.
    designs = [{"slug": d["slug"], "name": d["name"], "url": (d.get("sources") or [""])[0]}
               for d in rows if (d.get("controller") or {}).get("kind") == "soldered"]

    verdict, hist, per_design = collections.Counter(), collections.Counter(), []
    for design in designs:
        kinds = sorted(set(packages_in(names_for(design["slug"]))))
        state = "none" if not kinds else ("one" if len(kinds) == 1 else "several")
        verdict[state] += 1
        if state == "one":
            hist[kinds[0]] += 1
        per_design.append({**design, "packages": kinds, "verdict": state})

    n = len(designs)
    print(f"  soldered designs, {n} of {len(rows)}\n")
    for state in ("one", "several", "none"):
        print(f"  {state:<10}{verdict[state]:>5}   {verdict[state] * 100 // n}%")
    print("\n  packages:")
    for package, count in hist.most_common():
        print(f"     {package:<10} {count}")
    print("\n  several -- a design whose boards draw more than one package:")
    for row in per_design:
        if row["verdict"] == "several":
            print(f"     {row['slug']:<26} {row['packages']}")

    out = SCRATCH/"mcu-package-scan.json"
    out.write_text(json.dumps(per_design, indent=1))
    print(f"\n  {len(per_design)} designs -> {out.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
