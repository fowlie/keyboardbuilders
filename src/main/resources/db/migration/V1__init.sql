-- v0.5 schema. Forgejo #2.
--
-- Transcribed from `architecture.md` §3.5, which is STALE in three places. Each
-- correction is marked CORRECTION below with the decision that made it; the tickets
-- are the source of truth for the schema and the document is behind them.
--
-- SCOPE. `design`, `revision`, `revision_field`, `field_confirmation`,
-- `field_verification`, `controller_board`, `design_facet`, the FTS pair, and an empty
-- `user` table purely as the FK target for the nullable author columns. `proposal*`,
-- `vote`, `reputation_event` and `github_identity` are the community and auth layers
-- and are out of scope for v0.5.
--
-- WHY A MIGRATION AND NOT A BARE schema.sql. #2 argued for the latter: the deployed
-- database is a freshly built file every time, so there is never data to evolve. That
-- holds for production and not for the dev loop, where `app.db` persists across
-- restarts and `spring.sql.init` would re-run this DDL on every boot and fail on
-- tables that already exist. Flyway's history table makes application idempotent. The
-- issue left the door open for exactly this -- "deferred, not banned".
--
-- json_extract(), NOT the ->> operator. They mean the same thing, but ->> arrived in
-- SQLite 3.38 (2022) and the operator is stored VERBATIM in sqlite_master, so every
-- tool that opens the file has to parse it. Anything on an older SQLite -- and Go and
-- Rust drivers commonly bundle one -- reports the whole database as "malformed database
-- schema" rather than as an unsupported operator. termdbms did exactly that.
-- json_extract() has been available since 3.9 (2015) and costs nothing here.
--
-- PRAGMA foreign_keys is deliberately NOT set here. It is per-connection, so setting
-- it in a migration would arm it for Flyway's connection and no other. It belongs on
-- the JDBC URL, where every connection gets it -- see application.properties.

-- Empty in v0.5, and it exists only so the nullable author columns have something to
-- reference. Seeding bot principals instead was weighed and rejected by
-- ticket 08 decision 2: a `user` row is a PRINCIPAL, carrying reputation and a role,
-- so an importer bot would acquire moderation rights and a place in v1's contributor
-- surfaces. Zero schema cost, paid for in a permanently fake principal.
CREATE TABLE user (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    handle        TEXT NOT NULL UNIQUE,
    display_name  TEXT NOT NULL,
    avatar_url    TEXT,
    reputation    INTEGER NOT NULL DEFAULT 1 CHECK (reputation >= 0),
    role          TEXT NOT NULL DEFAULT 'member'
                  CHECK (role IN ('member','moderator','admin')),
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ'))
);

CREATE TABLE controller_board (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    name      TEXT NOT NULL UNIQUE,
    mcu       TEXT NOT NULL CHECK (json_valid(mcu)),   -- serialised `Mcu`
    footprint TEXT NOT NULL,
    usb       TEXT,
    charger   INTEGER NOT NULL DEFAULT 0 CHECK (charger IN (0,1)),
    -- projection of Mcu::radio(); a generated column, so it cannot drift
    has_radio INTEGER GENERATED ALWAYS AS (json_extract(mcu, '$.radio')) VIRTUAL,
    added_by  INTEGER REFERENCES user(id)
);

CREATE TABLE design (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    slug       TEXT NOT NULL UNIQUE,
    -- CORRECTION 1, ticket 08 decision 2: "design.created_by goes nullable under the
    -- same rule". §3.5 prints NOT NULL, and an imported design has no author to point
    -- at -- v0.5 has no `user` rows at all, so as printed it could not hold the corpus.
    created_by INTEGER REFERENCES user(id),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ')),
    -- SQLite honours DEFERRABLE INITIALLY DEFERRED, so the design<->revision cycle can
    -- be closed inside one transaction.
    head_id    INTEGER REFERENCES revision(id) DEFERRABLE INITIALLY DEFERRED,
    status     TEXT NOT NULL DEFAULT 'live'
               CHECK (status IN ('live','hidden','duplicate')),
    score      INTEGER NOT NULL DEFAULT 0          -- denormalised from vote, rebuildable
);

-- Immutable. Nothing ever UPDATEs a row in this table.
CREATE TABLE revision (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    design_id   INTEGER NOT NULL REFERENCES design(id) ON DELETE CASCADE,
    seq         INTEGER NOT NULL CHECK (seq >= 1),
    parent_id   INTEGER REFERENCES revision(id),
    -- CORRECTION 2, ticket 08 decision 2. §3.5 prints NOT NULL; every import row needs
    -- a null author, and the paired CHECK is what keeps that from becoming a hole:
    -- a machine origin has no author and a human origin must have one.
    author_id   INTEGER REFERENCES user(id),
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ')),
    -- CORRECTION 3, ticket 08 decision 2: `ai_verification` is a fifth origin. An AI
    -- CORRECTION is an ordinary revision and must appear in `?kinds=corrected`
    -- truthfully; only an AI *confirmation* writes no revision at all.
    origin      TEXT NOT NULL CHECK (origin IN
                  ('creation','direct_edit','merged_proposal','rollback','import',
                   'ai_verification')),
    summary     TEXT NOT NULL,
    payload     TEXT NOT NULL CHECK (json_valid(payload)
                                      AND json_extract(payload, '$.v') IS NOT NULL),
    doc_version TEXT GENERATED ALWAYS AS (json_extract(payload, '$.v')) VIRTUAL,
    UNIQUE (design_id, seq),
    -- The first revision, and only the first, has no parent. This is the one that makes
    -- the append-only bookkeeping real rather than decorative: `seq` and `parent_id` are
    -- written honestly from the first import, so corrections later cannot clobber them.
    CHECK ((seq = 1) = (parent_id IS NULL)),
    CHECK ((author_id IS NULL) = (origin IN ('import','ai_verification')))
);

-- The per-field provenance projection. Derived from consecutive snapshots, disposable,
-- rebuildable. PK order is (design_id, field, seq) so `?field=mcu` is a seek, not a scan.
CREATE TABLE revision_field (
    design_id   INTEGER NOT NULL REFERENCES design(id) ON DELETE CASCADE,
    revision_id INTEGER NOT NULL REFERENCES revision(id) ON DELETE CASCADE,
    seq         INTEGER NOT NULL,
    field       TEXT NOT NULL,                    -- Field::slug()
    kind        TEXT NOT NULL CHECK (kind IN
                  ('filled','corrected','cleared','extended','trimmed')),
    old_json    TEXT,
    new_json    TEXT,
    PRIMARY KEY (design_id, field, seq)
) WITHOUT ROWID;
CREATE INDEX revision_field_by_rev ON revision_field(revision_id);
CREATE INDEX revision_field_hot    ON revision_field(field, kind);

-- Which human endorsed a value without changing it. `user_id` stays NOT NULL: this
-- table holds human endorsements by definition, and the AI's equivalent is
-- `field_verification` below. Empty in v0.5.
CREATE TABLE field_confirmation (
    design_id   INTEGER NOT NULL REFERENCES design(id) ON DELETE CASCADE,
    field       TEXT NOT NULL,
    revision_id INTEGER NOT NULL REFERENCES revision(id) ON DELETE CASCADE,
    user_id     INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ')),
    PRIMARY KEY (design_id, field, user_id)
) WITHOUT ROWID;

-- Ticket 01: AI verification with a citation. It is a separate table rather than
-- provenance inside the value, and revocation is what decided that -- dropping one
-- model's assertions is one DELETE rather than rewriting every affected document.
--
-- The PK is (design_id, field, model) and `design_id` does not exist before
-- publication, so verification facts gathered against a staging record must be carried
-- on that record and written here in the SAME transaction that publishes it.
CREATE TABLE field_verification (
    design_id   INTEGER NOT NULL REFERENCES design(id) ON DELETE CASCADE,
    field       TEXT    NOT NULL,                 -- Field::slug()
    revision_id INTEGER NOT NULL REFERENCES revision(id) ON DELETE CASCADE,
    model       TEXT    NOT NULL,
    cite_url    TEXT    NOT NULL,
    cite_commit TEXT    NOT NULL,
    cite_quote  TEXT    NOT NULL,
    at          INTEGER NOT NULL,
    PRIMARY KEY (design_id, field, model)
) WITHOUT ROWID;
CREATE INDEX field_verification_by_model ON field_verification(model, field);

CREATE TABLE design_search (
    design_id   INTEGER PRIMARY KEY REFERENCES design(id) ON DELETE CASCADE,
    revision_id INTEGER NOT NULL REFERENCES revision(id),
    name        TEXT NOT NULL,
    slug        TEXT NOT NULL,
    hay_a       TEXT NOT NULL,          -- name, slug, designer
    hay_b       TEXT NOT NULL,          -- summary
    hay_c       TEXT NOT NULL,          -- board names, firmware, notes
    facets      TEXT NOT NULL CHECK (json_valid(facets)),   -- for rendering, not filtering
    keys        INTEGER NOT NULL,
    -- Inert in v0.5: voting is the community layer, so nothing writes this.
    score       INTEGER NOT NULL DEFAULT 0,
    -- NULL means "we have no star count", NOT zero stars -- the default-fill this
    -- project refuses.
    stars       INTEGER,
    archived    INTEGER NOT NULL DEFAULT 0,
    last_commit TEXT,
    updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ'))
);
-- v0.5 default order. SQLite sorts NULLs last under DESC, which is exactly the band
-- wanted: designs with no star count fall below every ranked one and tie-break on
-- `last_commit`, rather than sorting as if they had 0 stars. Load-bearing and
-- non-obvious, so it gets its own test when the ranker lands.
CREATE INDEX design_search_rank
  ON design_search(archived ASC, stars DESC, last_commit DESC);

-- Weighted full text. External content table, so the text is not stored twice.
-- FTS5 external content tables are NOT self-maintaining: keeping them in sync is the
-- projection's job, done explicitly rather than in triggers so the whole projection
-- stays one auditable function.
CREATE VIRTUAL TABLE design_fts USING fts5(
    hay_a, hay_b, hay_c,
    content = 'design_search', content_rowid = 'design_id',
    tokenize = 'porter unicode61'
);

-- Fuzzy name matching: indexed substring LIKE.
CREATE VIRTUAL TABLE design_name_trgm USING fts5(
    name, content = 'design_search', content_rowid = 'design_id', tokenize = 'trigram'
);

-- The filter and count path. Long and thin, covering index. Holds only `live` designs
-- by projection invariant: joining `design` to filter status inside the hot path cost
-- 30-40% and made the planner build a transient automatic index.
CREATE TABLE design_facet (
    design_id INTEGER NOT NULL REFERENCES design(id) ON DELETE CASCADE,
    key       TEXT NOT NULL,
    value     TEXT NOT NULL,
    -- NULL for non-numeric facets; carries the integer for `keys` and friends, so a
    -- numeric facet is both exactly selectable and range-queryable.
    num       INTEGER,
    PRIMARY KEY (design_id, key, value)
) WITHOUT ROWID;
CREATE INDEX design_facet_lookup ON design_facet(key, value, design_id);
CREATE INDEX design_facet_num ON design_facet(key, num) WHERE num IS NOT NULL;
