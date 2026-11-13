"""Tests for observe_42keebs -- one look at each 42keebs listing, written to offer_observation."""
import json
import os
import pathlib
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from observe_42keebs import REPO, look, main, saved_products  # noqa: E402


def product(id, price="2070", cls="in-stock", text="In stock", type="simple", backorder=False,
            currency="EUR", minor=2):
    return {"id": id, "type": type, "is_on_backorder": backorder,
            "stock_availability": {"class": cls, "text": text},
            "prices": {"price": price, "currency_code": currency, "currency_minor_unit": minor}}


class Look(unittest.TestCase):

    def test_a_price_is_cents_before_vat(self):
        self.assertEqual((2070, "EUR", "in_stock"), look(product(4808), "4808"))

    # 4808 on 2026-10-04: "In stock (can be backordered)" is in stock, with backorders
    # allowed once it runs out. Only is_on_backorder says the stock is gone.
    def test_in_stock_that_can_be_backordered_is_in_stock(self):
        self.assertEqual("in_stock", look(product(4808, text="In stock (can be backordered)"), "4808")[2])

    def test_on_backorder_is_backorder(self):
        self.assertEqual("backorder", look(product(4808, cls="available-on-backorder", backorder=True), "4808")[2])

    def test_out_of_stock_is_out_of_stock(self):
        self.assertEqual("out_of_stock", look(product(6198, price="150", cls="out-of-stock"), "6198")[2])

    # Corne Classic 17081 reads in stock while every variation is out: a parent's stock is
    # not the variation's, so a listing must name the variation.
    def test_a_variable_parent_is_refused(self):
        with self.assertRaises(SystemExit):
            look(product(7333, type="variable"), "7333")

    def test_a_price_in_another_currency_or_unit_is_refused(self):
        with self.assertRaises(SystemExit):
            look(product(4808, currency="CZK"), "4808")
        with self.assertRaises(SystemExit):
            look(product(4808, minor=3), "4808")

    def test_the_answer_must_be_for_the_ref_asked(self):
        with self.assertRaises(SystemExit):
            look(product(4809), "4808")


class FromFile(unittest.TestCase):

    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        # A products page is a list; a single variation fetch is one object.
        (self.dir / "page.json").write_text(json.dumps([product(4808), product(855, price="150")]))
        (self.dir / "variations").mkdir()
        (self.dir / "variations" / "7336.json").write_text(json.dumps(product(7336, price="25", type="variation")))
        self.at = 1_791_115_200  # 2026-10-04T12:00:00Z
        for f in [self.dir / "page.json", self.dir / "variations" / "7336.json"]:
            os.utime(f, (self.at, self.at))
        self.db = self.dir / "app.db"
        conn = sqlite3.connect(self.db)
        conn.executescript((REPO / "src/main/resources/db/migration/V7__offer_observation.sql").read_text())
        conn.close()
        self.parts = self.dir / "parts.json"
        self.parts.write_text(json.dumps({"listings": [
            {"shop": "42keebs", "ref": ref, "product": "x", "pack": 1, "url": "https://42keebs.eu/"}
            for ref in ["4808", "855", "7336", "6241"]
        ] + [{"shop": "other", "ref": "1", "product": "x", "pack": 1, "url": "https://other/"}]}))

    def rows(self):
        conn = sqlite3.connect(self.db)
        try:
            return conn.execute("SELECT shop, shop_ref, observed_at, net_minor, currency, stock "
                                "FROM offer_observation ORDER BY shop_ref").fetchall()
        finally:
            conn.close()

    def run_main(self, *extra):
        return main(["--db", str(self.db), "--parts", str(self.parts),
                     "--from-file", str(self.dir / "page.json"), "--from-file", str(self.dir / "variations"),
                     *extra])

    def test_saved_files_and_directories_are_read_by_id(self):
        found = saved_products([self.dir / "page.json", self.dir / "variations"])
        self.assertEqual({"4808", "855", "7336"}, set(found))
        self.assertEqual("2026-10-04T12:00:00Z", found["7336"][1])

    def test_each_listing_with_a_saved_look_becomes_one_row_dated_when_it_was_saved(self):
        self.run_main()
        self.assertEqual([
            ("42keebs", "4808", "2026-10-04T12:00:00Z", 2070, "EUR", "in_stock"),
            ("42keebs", "7336", "2026-10-04T12:00:00Z", 25, "EUR", "in_stock"),
            ("42keebs", "855", "2026-10-04T12:00:00Z", 150, "EUR", "in_stock"),
        ], self.rows())

    def test_a_listing_with_no_saved_look_gets_no_row(self):
        self.run_main()
        self.assertNotIn("6241", [r[1] for r in self.rows()])

    def test_reading_the_same_files_twice_adds_nothing(self):
        self.run_main()
        self.run_main()
        self.assertEqual(3, len(self.rows()))

    def test_a_dry_run_writes_nothing(self):
        self.run_main("--dry-run")
        self.assertEqual([], self.rows())

    def test_a_database_without_the_table_is_refused(self):
        sqlite3.connect(self.dir / "empty.db").close()
        with self.assertRaises(SystemExit):
            main(["--db", str(self.dir / "empty.db"), "--parts", str(self.parts),
                  "--from-file", str(self.dir / "page.json")])


if __name__ == "__main__":
    unittest.main()
