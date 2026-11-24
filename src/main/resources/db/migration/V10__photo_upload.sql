-- Build photos people add themselves, and the review that puts them on the site.
-- Owner, 2026-11-24: one photo per design, uploads only where a page has none, a yes from a
-- moderator or anyone with 50 points before it shows, +10 points for each approved photo.
--
-- KEYED BY SLUG AS TEXT, for V8's reason: every boot's import opens with `DELETE FROM
-- design`, and a row hanging off `design(id)` would be wiped by the next deploy.
--
-- `status` is `waiting`, `approved` or `rejected`. A removed photo is deleted, which is
-- what takes its 10 points away. `jpg` is the digest the bytes are stored under, the same
-- key the seed's photos use, so `/photos/<jpg>.jpg` serves both.
CREATE TABLE photo_upload (
    id          INTEGER PRIMARY KEY,
    slug        TEXT NOT NULL,
    login       TEXT NOT NULL,
    jpg         TEXT NOT NULL,
    width       INTEGER NOT NULL,
    height      INTEGER NOT NULL,
    status      TEXT NOT NULL DEFAULT 'waiting' CHECK (status IN ('waiting', 'approved', 'rejected')),
    note        TEXT,
    reviewed_by TEXT,
    at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ')),
    reviewed_at TEXT
);

-- One photo per design: at most one waiting or approved upload for a slug at any time.
CREATE UNIQUE INDEX photo_upload_one_per_design ON photo_upload (slug) WHERE status IN ('waiting', 'approved');

-- People whose review rights the owner took away. Points still count; reviewing doesn't.
CREATE TABLE review_revoked (
    login TEXT PRIMARY KEY,
    by_login TEXT NOT NULL,
    at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ'))
);
