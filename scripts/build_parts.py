#!/usr/bin/env python3
"""Turn seed/parts.toml into the JSON the parts module reads at startup (#112).

Same split as `build_controllers.py`: the TOML is what a person read off a shop's listing,
the JSON under `resources` is a build output nobody hand-edits. `PartsCatalog` checks the
same things again on startup; this is the first gate, with the clearer message.

Usage: build_parts.py [--seed seed/parts.toml] [--out src/main/resources/seed/parts.json]
"""
import argparse
import json
import pathlib
import re
import tomllib

REPO = pathlib.Path(__file__).resolve().parent.parent

SLUG = re.compile(r"[a-z0-9]+(-[a-z0-9]+)*")

# The design seed's own tokens (`SeedDesignCatalog.toFootprint`, `toSwitchMount`), so a
# part and a design cannot spell one interface two ways. Only MX and Choc sockets are a
# part you buy; solder pads take nothing and Mill-Max is a later slice.
FOOTPRINTS = {"pro_micro", "seeed_xiao", "rp2040_zero", "rpi_pico", "black_pill", "blue_pill", "feather"}
SOCKETS = {"mx_socket", "choc_socket"}
DIODE_PACKAGES = {"do_35", "sod_123", "sod_123f", "sod_323", "sod_523"}
# `SeedDesignCatalog.toSwitchFootprint`'s tokens, less `bespoke`: a one-off switch of one
# design is never a part on a shelf.
SWITCH_FOOTPRINTS = {"mx", "choc_v1", "choc_v2", "alps", "choc_mini", "kailh_x"}
# What the vendor calls the switch, and nothing else: a colour is not a kind (#115).
SWITCH_KINDS = {"linear", "tactile", "silent_linear", "silent_tactile", "clicky"}
# A switch's spec (#115): only what the vendor's listing states, in these units, per that
# shop and dated. A force the vendor gives without saying which (Kailh Choc's "20 gf") is not
# filed under one; it stays in `vendor_says`, the vendor's own words.
SPEC_NUMBERS = {"actuation_force_gf": (1, 200), "bottom_out_force_gf": (1, 200),
                "actuation_mm": (0.1, 10), "travel_mm": (0.1, 10)}
SPEC_TEXTS = {"housing", "stem", "vendor_says"}
SPEC_KEYS = {"per", "read_on", "factory_lubed", "pins"} | set(SPEC_NUMBERS) | SPEC_TEXTS
NEED_KINDS = {"controller", "switch_sockets", "diodes", "pcb", "switches"}
INTERFACES = "controller_socket, switch_socket, diode or switch"


def check_fits(fits, where):
    if not isinstance(fits, dict) or len(fits) != 1:
        raise SystemExit(f"{where}: fits names one of {INTERFACES}, was {fits!r}")
    (kind, value), = fits.items()
    allowed = {"controller_socket": FOOTPRINTS, "switch_socket": SOCKETS, "switch": SWITCH_FOOTPRINTS}.get(kind)
    if kind == "diode":
        if value not in DIODE_PACKAGES:
            raise SystemExit(f"{where}: diode package {value!r} is not one of {sorted(DIODE_PACKAGES)}")
    elif allowed is None:
        raise SystemExit(f"{where}: fits names one of {INTERFACES}, was {kind!r}")
    elif not value or set(value) - allowed:
        raise SystemExit(f"{where}: {kind} {value!r} -- each must be one of {sorted(allowed)}")


def check_spec(spec, shops, where):
    if not isinstance(spec, dict):
        raise SystemExit(f"{where}: spec is a table")
    unknown = set(spec) - SPEC_KEYS
    if unknown:
        raise SystemExit(f"{where}: spec key {sorted(unknown)} is not one of {sorted(SPEC_KEYS)}")
    if spec.get("per") not in shops:
        raise SystemExit(f"{where}: spec per {spec.get('per')!r} -- whose listing it was read off, a shop slug")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(spec.get("read_on", ""))):
        raise SystemExit(f"{where}: spec read_on is the date the listing was read")
    for key, (low, high) in SPEC_NUMBERS.items():
        if key in spec:
            v = spec[key]
            if isinstance(v, bool) or not isinstance(v, (int, float)) or not low <= v <= high:
                raise SystemExit(f"{where}: {key} is a number from {low} to {high}, was {v!r}")
    if "pins" in spec and spec["pins"] not in (3, 5):
        raise SystemExit(f"{where}: pins is 3 or 5, was {spec['pins']!r}")
    if "factory_lubed" in spec and not isinstance(spec["factory_lubed"], bool):
        raise SystemExit(f"{where}: factory_lubed is true or false, was {spec['factory_lubed']!r}")
    for key in SPEC_TEXTS:
        if key in spec and (not isinstance(spec[key], str) or not spec[key].strip()):
            raise SystemExit(f"{where}: {key} is the vendor's words, or left out")


def check(seed, boards, designs):
    """Refuse a seed the site would misread. [boards] and [designs] are the slugs the
    controller catalogue and the corpus publish."""
    shops = {s["slug"] for s in seed["shop"]}
    if len(shops) != len(seed["shop"]):
        raise SystemExit("duplicate shop slug")
    for s in seed["shop"]:
        if not re.fullmatch(r"[A-Z]{3}", s["currency"]) or not isinstance(s["prices_include_vat"], bool):
            raise SystemExit(f"{s['slug']}: currency is three capitals and prices_include_vat a boolean")
        if any(not isinstance(rate, int) or not 0 <= rate <= 100 for rate in s["vat"].values()):
            raise SystemExit(f"{s['slug']}: a VAT rate is a whole percent, was {s['vat']}")

    products = [p["slug"] for p in seed["part"]] + [k["slug"] for k in seed["kit"]]
    for slug in products:
        if not SLUG.fullmatch(slug):
            raise SystemExit(f"not a slug: {slug!r}")
    if "own" in products:
        # The build list's "I have this" in the URL (`?diodes=own`, #113).
        raise SystemExit("'own' is not a product slug: the build list's URL uses it for \"I have this\"")
    if len(set(products)) != len(products):
        raise SystemExit("duplicate product slug across parts and kits")

    for p in seed["part"]:
        if "dev_board" in p:
            # Name, footprint and firmware come from the controller catalogue, never copied.
            if set(p) != {"slug", "dev_board"}:
                raise SystemExit(f"{p['slug']}: a dev board part carries only its slug and dev_board")
            if p["dev_board"] not in boards:
                raise SystemExit(f"{p['slug']}: dev_board {p['dev_board']!r} is not in seed/controllers.toml")
        else:
            if not p.get("name"):
                raise SystemExit(f"{p['slug']}: no name")
            check_fits(p.get("fits"), p["slug"])
            if "controller_socket" in p["fits"]:
                raise SystemExit(f"{p['slug']}: a controller part is a dev_board row, not a fits")
            if "spec" in p:
                if "switch" not in p["fits"]:
                    raise SystemExit(f"{p['slug']}: only a switch has a spec")
                check_spec(p["spec"], shops, p["slug"])
            if "switch_kind" in p:
                if "switch" not in p["fits"]:
                    raise SystemExit(f"{p['slug']}: only a switch has a switch_kind")
                if p["switch_kind"] not in SWITCH_KINDS:
                    raise SystemExit(f"{p['slug']}: switch_kind {p['switch_kind']!r} is not one of {sorted(SWITCH_KINDS)}")

    for k in seed["kit"]:
        if k["shop"] not in shops:
            raise SystemExit(f"{k['slug']}: no shop {k['shop']!r}")
        if k["based_on"] not in designs:
            raise SystemExit(f"{k['slug']}: based_on {k['based_on']!r} names no design")
        for line in k["contents"]:
            if "quantity" in line and (not isinstance(line["quantity"], int) or line["quantity"] < 1):
                raise SystemExit(f"{k['slug']}: {line['text']!r} -- a count is at least one, or left out")
            if "kind" in line and line["kind"] not in NEED_KINDS:
                raise SystemExit(f"{k['slug']}: kind {line['kind']!r} is not one of {sorted(NEED_KINDS)}")
        for need in k["needs"]:
            check_fits(need.get("fits"), f"{k['slug']} needs")
            if not isinstance(need.get("quantity"), int) or need["quantity"] < 1:
                raise SystemExit(f"{k['slug']}: a need asks for at least one, was {need.get('quantity')!r}")

    seen = set()
    for row in seed["listing"]:
        key = (row["shop"], row["ref"])
        if key in seen:
            raise SystemExit(f"two listings for {row['shop']} {row['ref']}")
        seen.add(key)
        if row["shop"] not in shops:
            raise SystemExit(f"listing {row['ref']}: no shop {row['shop']!r}")
        if row["product"] not in products:
            raise SystemExit(f"listing {row['ref']}: no product {row['product']!r}")
        if not isinstance(row["pack"], int) or row["pack"] < 1:
            raise SystemExit(f"listing {row['ref']}: a pack holds at least one, was {row['pack']!r}")
        if not row["url"].startswith("https://"):
            raise SystemExit(f"listing {row['ref']}: url {row['url']!r}")

    packaged = [d["design"] for d in seed["diode_package"]]
    if len(set(packaged)) != len(packaged):
        raise SystemExit("two diode packages for one design")
    for d in seed["diode_package"]:
        if d["design"] not in designs:
            raise SystemExit(f"diode_package: {d['design']!r} names no design")
        if d["package"] not in DIODE_PACKAGES:
            raise SystemExit(f"{d['design']}: diode package {d['package']!r} is not one of {sorted(DIODE_PACKAGES)}")
        if not d.get("source"):
            raise SystemExit(f"{d['design']}: a measured package cites where it was measured")


def to_json(seed):
    kits = [dict(k, read_on=str(k["read_on"])) for k in seed["kit"]]
    parts = [dict(p, spec=dict(p["spec"], read_on=str(p["spec"]["read_on"]))) if "spec" in p else p
             for p in seed["part"]]
    return {"shops": seed["shop"], "parts": parts, "kits": kits,
            "listings": seed["listing"], "diode_packages": seed["diode_package"]}


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed", default="seed/parts.toml")
    ap.add_argument("--out", default="src/main/resources/seed/parts.json")
    args = ap.parse_args()

    seed = tomllib.loads((REPO/args.seed).read_text())
    boards = {b["slug"] for b in tomllib.loads((REPO/"seed/controllers.toml").read_text())["entry"]}
    designs = {d["slug"] for d in json.loads((REPO/"src/main/resources/seed/designs.json").read_text())}
    check(seed, boards, designs)

    out = REPO/args.out
    out.write_text(json.dumps(to_json(seed), indent=1, ensure_ascii=False) + "\n")
    print(f"{len(seed['shop'])} shops, {len(seed['part'])} parts, {len(seed['kit'])} kits, "
          f"{len(seed['listing'])} listings, {len(seed['diode_package'])} diode packages -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
