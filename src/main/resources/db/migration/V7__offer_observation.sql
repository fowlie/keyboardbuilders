-- What a shop charged for one listing, and whether it had any, when someone looked
-- (backlog #112, the offer layer). One row per look. APPEND-ONLY: the table is the price
-- history, and the build list reads the newest row per listing.
--
-- KEYED BY SHOP AND THE SHOP'S OWN REF, with no reference to `design`, for V6's reason:
-- every boot's `SeedImporter` opens with `DELETE FROM design`. Which product a ref is
-- lives in `seed/parts.toml`, so a corrected listing needs no migration.
--
-- `observed_at` is UTC in whole seconds, spelled one way, because the newest row is found
-- with MAX(observed_at), a text comparison: '12:00:00.5Z' would sort before '12:00:00Z'.
-- `net_minor` is the shop's price in cents before VAT, as 42keebs' Store API gives it.
-- EUR only until a second currency has somewhere to be converted.
CREATE TABLE offer_observation (
    shop        TEXT    NOT NULL,
    shop_ref    TEXT    NOT NULL,
    observed_at TEXT    NOT NULL
        CHECK (observed_at GLOB '[0-9][0-9][0-9][0-9]-[0-1][0-9]-[0-3][0-9]T[0-2][0-9]:[0-5][0-9]:[0-5][0-9]Z'),
    net_minor   INTEGER NOT NULL CHECK (net_minor >= 0),
    currency    TEXT    NOT NULL CHECK (currency = 'EUR'),
    stock       TEXT    NOT NULL CHECK (stock IN ('in_stock', 'backorder', 'out_of_stock')),
    PRIMARY KEY (shop, shop_ref, observed_at)
);
