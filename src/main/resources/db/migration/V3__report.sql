-- What a reader told us is wrong, and what they told us is missing.
--
-- THE FIRST TABLE THAT IS NOT DERIVED FROM THE SEED, and that is the whole reason it
-- looks unlike its neighbours. Every other table in this schema is a projection of
-- `seed/` -- `SeedImporter` opens with `DELETE FROM design` and rebuilds them on every
-- boot, which is safe precisely because git holds the truth and the database is a
-- disposable cache of it. A report has no seed file behind it. If it were lost on a
-- restart it would be lost for good.
--
-- SO IT DOES NOT REFERENCE `design`, AND THAT IS NOT AN OVERSIGHT. Every other table
-- here hangs off `design(id)` with ON DELETE CASCADE, so copying the house pattern
-- would hand every report to the next boot's `DELETE FROM design` -- silently, on an
-- ordinary deploy, with no error anywhere. The slug is carried as TEXT instead: it is
-- the identifier the reader actually saw in their URL bar, it survives the rebuild, and
-- `seed/renames.toml` already exists for the few that change.
--
-- NULLABLE `slug`, because `kind = 'missing'` is a report about a design that by
-- definition has no row here yet. `url` is where that one carries its payload.
--
-- NO IP ADDRESS, DELIBERATELY. Rate limiting happens in memory in `ReportRate` and
-- nothing about the sender is written down. This site stores nothing about its visitors
-- today and an abuse column is a poor reason to start -- under GDPR an address is
-- personal data, and the obligation that comes with the first one is the same as for a
-- million. `email` is different: it is volunteered, optional, and exists only so a
-- correction can be answered.
CREATE TABLE report (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    kind     TEXT NOT NULL
             CHECK (kind IN ('fact','photo','source','missing','other')),
    -- The design this is about, as it appeared in the URL. NULL only for 'missing'.
    slug     TEXT,
    -- A repo or product page. The one field that makes a 'missing' report actionable.
    url      TEXT,
    details  TEXT NOT NULL,
    email    TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ')),
    -- Set by hand once a report has been dealt with, so the queue can be read as
    -- "what is still open" rather than as an ever-growing log. No UI writes this in
    -- v0.5: `sqlite3 app.db` is the whole moderation surface, on purpose.
    handled_at TEXT
);

-- The only query this table has: what is still open, newest first.
CREATE INDEX report_open ON report (handled_at, created_at DESC);
