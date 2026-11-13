-- Designs their author took off the site. Backlog #116, owner 2026-11-10: authors sign in
-- with GitHub and may unpublish their own design.
--
-- KEYED BY SLUG AS TEXT, NOT A REFERENCE TO `design`, for V6's reason: every boot's import
-- opens with `DELETE FROM design`, and a row hanging off `design(id)` would be wiped by the
-- next deploy, putting the page back up without the author knowing. The import reads this
-- table and leaves these slugs out, so every query over `design` hides them at once.
--
-- `by_login` is the GitHub login that did it, the only thing kept about the person: it is
-- what an "I didn't do this" email gets checked against.
CREATE TABLE unpublished (
    slug     TEXT PRIMARY KEY,
    by_login TEXT NOT NULL,
    at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ'))
);
