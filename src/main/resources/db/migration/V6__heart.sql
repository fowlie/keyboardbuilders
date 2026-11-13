-- How many visitors have hearted each design. Owner, 2026-10-01: replace the GitHub star
-- counts with our own hearts, kept centrally and in the visitor's own browser.
--
-- THE SECOND TABLE THAT IS NOT DERIVED FROM THE SEED, after V3's `report`, and it copies
-- that table's two decisions for the same reasons. Ticket 53 ruled a central count out of
-- scope on 2026-08-27 because every boot's `SeedImporter` opens with `DELETE FROM design`,
-- and a visitor-written number hanging off `design(id)` would be wiped by every deploy.
--
-- SO IT DOES NOT REFERENCE `design`. The slug is carried as TEXT, which survives the
-- rebuild; the controller refuses a slug the catalogue does not publish, so a row here is
-- always a design that existed when it was hearted. `seed/renames.toml` handles the few
-- that move.
--
-- NO VISITOR IS RECORDED. Which designs a visitor hearted lives in their own browser's
-- localStorage. The server keeps the total and nothing else, and its rate limit lives in
-- memory (`HeartRate`), the line V3 drew for IP addresses and GDPR.
CREATE TABLE heart (
    slug  TEXT PRIMARY KEY,
    count INTEGER NOT NULL DEFAULT 0 CHECK (count >= 0)
);
