#!/usr/bin/env python3
"""Look at each 42keebs listing once and append what it costs to offer_observation (#112).

DEV MACHINE ONLY, and nothing schedules it: the parts module is local until the owner
says otherwise. Read-only towards the shop, one GET at a time to its public Store API,
`/wp-json/wc/store/v1/products/{ref}`, for each 42keebs listing in parts.json.

`--from-file` reads saved Store API JSON instead (a products page, a single product, or a
directory of either), so a demo or a test needs no network. A saved look is dated by the
file's modification time, when the look happened, not by when this script read it.

Each look becomes one row: the net price in cents, the currency, and one of three stock
states. A listing with no look gets no row, which the build list shows as no offer;
nothing is guessed. The table must exist: start the app once so Flyway applies V7.

Usage: observe_42keebs.py --db app.db [--parts src/main/resources/seed/parts.json]
                          [--from-file PATH ...] [--dry-run]
"""
import argparse
import datetime
import json
import pathlib
import sqlite3
import sys
import time
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
API = "https://42keebs.eu/wp-json/wc/store/v1/products/{ref}"
SHOP = "42keebs"


def look(product, ref):
    """(net_minor, currency, stock) from one Store API product, or refuse it."""
    if str(product["id"]) != ref:
        raise SystemExit(f"asked for {ref}, the answer is {product['id']}")
    # A variable product's own stock is not its variations' (Corne Classic 17081 read in
    # stock with every variation out), so a listing names the variation.
    if product["type"] == "variable":
        raise SystemExit(f"{ref} is a variable product: list its variation ids instead")
    prices = product["prices"]
    if prices["currency_code"] != "EUR" or prices["currency_minor_unit"] != 2:
        raise SystemExit(f"{ref}: {prices['currency_code']} in units of 10^-{prices['currency_minor_unit']}, "
                         f"not EUR cents")
    # "In stock (can be backordered)" (4808) is in stock with backorders allowed for when
    # it runs out; only is_on_backorder says the stock is gone.
    availability = product["stock_availability"]["class"]
    if availability == "out-of-stock":
        stock = "out_of_stock"
    elif product["is_on_backorder"] or availability == "available-on-backorder":
        stock = "backorder"
    else:
        stock = "in_stock"
    return int(prices["price"]), "EUR", stock


def utc(seconds):
    return datetime.datetime.fromtimestamp(int(seconds), datetime.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def saved_products(paths):
    """Every product in the saved files, by id, each with the time its file was saved."""
    found = {}
    files = []
    for path in map(pathlib.Path, paths):
        files += sorted(path.glob("*.json")) if path.is_dir() else [path]
    for f in files:
        data = json.loads(f.read_text())
        for product in data if isinstance(data, list) else [data]:
            found[str(product["id"])] = (product, utc(f.stat().st_mtime))
    return found


def fetch(ref):
    request = urllib.request.Request(API.format(ref=ref),
                                     headers={"User-Agent": "keyboardbuilders.net parts check"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response), utc(time.time())


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", required=True)
    ap.add_argument("--parts", default=str(REPO/"src/main/resources/seed/parts.json"))
    ap.add_argument("--from-file", action="append", default=[],
                    help="saved Store API JSON, a file or a directory; repeatable")
    ap.add_argument("--dry-run", action="store_true", help="print the rows, write none")
    args = ap.parse_args(argv)

    refs = [l["ref"] for l in json.loads(pathlib.Path(args.parts).read_text())["listings"] if l["shop"] == SHOP]
    conn = sqlite3.connect(args.db)
    try:
        if not conn.execute("SELECT 1 FROM sqlite_master WHERE name = 'offer_observation'").fetchone():
            raise SystemExit(f"{args.db} has no offer_observation table: start the app once to apply V7")
        saved = saved_products(args.from_file) if args.from_file else None
        written = 0
        for i, ref in enumerate(refs):
            if saved is not None:
                if ref not in saved:
                    print(f"  {ref:>6}  no saved look, no row")
                    continue
                product, at = saved[ref]
            else:
                if i:
                    time.sleep(1)
                product, at = fetch(ref)
            net, currency, stock = look(product, ref)
            row = (SHOP, ref, at, net, currency, stock)
            print(f"  {ref:>6}  {at}  {net / 100:>8.2f} {currency}  {stock}")
            if args.dry_run:
                continue
            seen = conn.execute("SELECT 1 FROM offer_observation WHERE shop = ? AND shop_ref = ? AND observed_at = ?",
                                row[:3]).fetchone()
            if not seen:
                conn.execute("INSERT INTO offer_observation (shop, shop_ref, observed_at, net_minor, currency, stock) "
                             "VALUES (?, ?, ?, ?, ?, ?)", row)
                written += 1
        conn.commit()
    finally:
        conn.close()
    print(f"{written} rows -> {args.db}" + (" (dry run)" if args.dry_run else ""), file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
