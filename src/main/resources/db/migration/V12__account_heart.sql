-- Which designs a signed-in person has hearted. Owner, 2026-11-27: hearts on every card,
-- for anyone, and a signed-in person's hearts kept with their account -- the browser's own
-- list merged in when they sign in.
--
-- `heart` STAYS THE COUNT. A signed-in heart adds to it once per person (this table's
-- unique key says whether it already did); the merge copies a browser's list in without
-- adding, because those hearts were counted when they were pressed.
--
-- KEYED BY SLUG AS TEXT, for V6's reason: the seed import deletes `design` on every boot.
-- `id` is only the order hearted, which /favorites shows. `login` is stored lowercase.
CREATE TABLE account_heart (
    id    INTEGER PRIMARY KEY,
    login TEXT NOT NULL,
    slug  TEXT NOT NULL,
    at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ')),
    UNIQUE (login, slug)
);
