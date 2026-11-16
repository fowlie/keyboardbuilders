"""A measured field's published value: the scan's verdict, unless the owner ruled otherwise.

Splay, stagger, exposed components and power switch are each measured off a design's own
board files into `data/<field>-scan.json`, and three of them have a `seed/<field>-overrides
.toml` where the owner corrected designs the measurement got wrong. This is the one place
the two meet, under one rule:

  * the owner's ruling wins over the scan;
  * a ruling of "unknown" removes the scan's verdict (a gamepad's keys fit no stagger);
  * no verdict and no ruling is no value -- Unknown, never a default;
  * a ruling for a design that does not publish stops the build (a rename leaves
    seed/renames.toml its own row, never one here).

So a scan file holds only what was measured, and is re-derivable from the boards
(DECISIONS section 9). Until 2026-11-16 each measure script folded its own overrides into
its scan, and each handled a stale slug differently.
"""
import json
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

UNKNOWN = "unknown"


class StaleRuling(SystemExit):
    pass


def rulings(path, key):
    """{slug: value} from an override file's `[[entry]]` rows; nothing if there is no file."""
    path = Path(path)
    if not path.exists():
        return {}
    return {e["slug"]: e[key] for e in tomllib.loads(path.read_text()).get("entry", [])}


def resolve(scan, owner, live):
    """{slug: value} for the designs in `live`, the owner's ruling over the scan's verdict."""
    stale = sorted(set(owner) - set(live))
    if stale:
        raise StaleRuling(f"owner rulings name designs that do not publish: {', '.join(stale)}")
    out = {s: v for s, v in scan.items() if s in live}
    for slug, value in owner.items():
        if value == UNKNOWN:
            out.pop(slug, None)
        else:
            out[slug] = value
    return out


@dataclass
class Field:
    name: str                       # the designs.json key
    scan: dict
    rulings: dict = field(default_factory=dict)


def load(repo, name, scan, overrides=None, key=None):
    """A Field from `data/<scan>` and, if it has one, `seed/<overrides>`'s `key` column."""
    scan_path = Path(repo) / scan
    return Field(name,
                 json.loads(scan_path.read_text()) if scan_path.exists() else {},
                 rulings(Path(repo) / overrides, key) if overrides else {})


def fields(repo):
    """Every measured field the build publishes, in the order it reports them."""
    return [
        load(repo, "splay", "data/splay-scan.json", "seed/splay-overrides.toml", "splay"),
        load(repo, "stagger", "data/stagger-scan.json", "seed/stagger-overrides.toml", "stagger"),
        load(repo, "exposedComponents", "data/exposed-components-scan.json",
             "seed/exposed-components-overrides.toml", "exposed"),
        load(repo, "powerSwitch", "data/power-switch-scan.json"),
    ]


def apply(designs, measured):
    """Write each field's resolved value onto its design. Returns {name: {value: count}}."""
    live = {d["slug"] for d in designs}
    counts = {}
    for f in measured:
        try:
            values = resolve(f.scan, f.rulings, live)
        except StaleRuling as e:
            raise StaleRuling(f"{f.name}: {e}") from None
        tally = counts.setdefault(f.name, {})
        for d in designs:
            if d["slug"] in values:
                d[f.name] = values[d["slug"]]
                tally[values[d["slug"]]] = tally.get(values[d["slug"]], 0) + 1
    return counts
