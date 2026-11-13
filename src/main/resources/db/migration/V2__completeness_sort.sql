-- Backlog #46: `?sort=complete` needs a real column to ORDER BY, the same reason
-- `stars`/`keys`/`last_commit` are columns here rather than derived at request time --
-- `Sort.kt`'s own rule that the URL vocabulary and the ORDER BY vocabulary cannot drift
-- apart. The percentage itself is still computed by `Design.completeness()` at import
-- time and at render time independently; this column exists ONLY so SQLite can order by
-- it, the same relationship `stars` already has to `RepoActivity`.
ALTER TABLE design_search ADD COLUMN completeness INTEGER NOT NULL DEFAULT 0;

CREATE INDEX design_search_completeness ON design_search(archived ASC, completeness DESC, name ASC);
