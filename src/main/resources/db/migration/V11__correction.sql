-- Corrections to a design's measured facts, made on the site (owner, 2026-11-26).
-- Splay, stagger and switch mounts to start. A designer's own correction applies at once
-- and is never reviewed; anyone else's waits for a yes.
--
-- THE DATABASE LEADS FOR THESE, which is #117's "revisit when on-site editing arrives":
-- the seed's designs.json is rebuilt only nightly, and a designer who fixes a fact must see
-- it straight away. Every import applies the `applied` rows over the seed, so the page,
-- the filters and the counts all agree. The answer loop copies them into the seed's
-- override files afterwards, so the repo stays complete open data.
--
-- KEYED BY SLUG AS TEXT, for V8's reason: every import opens with `DELETE FROM design`.
--
-- `fact` is `splay`, `stagger` or `switch_mounts`. `value` is the seed's own token
-- (`yes`/`no`, `ortho`/`row`/`column`, or a comma list of `solder`, `mx_socket`,
-- `choc_socket`, `millmax`). The latest applied row for a slug and fact wins.
CREATE TABLE correction (
    id          INTEGER PRIMARY KEY,
    slug        TEXT NOT NULL,
    fact        TEXT NOT NULL CHECK (fact IN ('splay', 'stagger', 'switch_mounts')),
    value       TEXT NOT NULL,
    login       TEXT NOT NULL,
    status      TEXT NOT NULL CHECK (status IN ('applied', 'waiting', 'rejected')),
    reviewed_by TEXT,
    at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now')),
    reviewed_at TEXT
);

CREATE INDEX correction_by_slug ON correction (slug, fact);
