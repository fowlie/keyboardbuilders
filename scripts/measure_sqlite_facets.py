#!/usr/bin/env python3
"""Reproduce every SQLite measurement in architecture.md §3 and §4.

Builds a synthetic corpus, applies the schema from §3.5, and runs:

  * the two facet-filter formulations (§3.3) — json_each vs the thin table
  * the self-exclusion facet-count query (§4.3), verified against a brute-force
    reference implementation at every filter depth
  * FTS5 bm25() weighted ranking, and rank combined with facet predicates (§4.2)
  * the FTS5 trigram tokenizer as the pg_trgm stand-in (§3.1)
  * the full commit() write transaction, to size the single-writer pool (§3.6)

Every number in those sections came from this script. Run it before trusting any
of them, and re-run it against the *real* imported corpus once the QMK + ZMK
importer exists — the facet distribution here is synthetic and more uniform than
reality, and skew changes aggregate costs (§9.8).

    python3 scripts/measure_sqlite_facets.py [--designs 4000] [--keep]

Requires only the Python standard library. Reports the SQLite version it used,
because the generated-column and trigram behaviour below is version-dependent.
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import random
import sqlite3
import statistics
import sys
import tempfile
import time

MCU = ["rp2040", "nrf52840", "atmega32u4", "stm32f303", "esp32s3"]
FOOTPRINT = ["mx", "choc_v1", "choc_v2", "alps"]
FORM = ["split", "unibody", "one_handed"]
FIRMWARE = ["qmk", "zmk", "kmk", "vial"]
PITCH = ["mx_19_05", "choc_18x17", "choc_18x18"]
LIGHTING = ["none", "underglow", "per_key"]
KEYS = [34, 36, 42, 44, 48, 58, 64, 68, 87, 104]

SCHEMA = """
PRAGMA foreign_keys = ON;

CREATE TABLE design (
    id      INTEGER PRIMARY KEY,
    slug    TEXT NOT NULL UNIQUE,
    head_id INTEGER REFERENCES revision(id) DEFERRABLE INITIALLY DEFERRED,
    status  TEXT NOT NULL DEFAULT 'live'
            CHECK (status IN ('live','hidden','duplicate')),
    score   INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE revision (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    design_id   INTEGER NOT NULL REFERENCES design(id) ON DELETE CASCADE,
    seq         INTEGER NOT NULL CHECK (seq >= 1),
    parent_id   INTEGER REFERENCES revision(id),
    author_id   INTEGER NOT NULL,
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ')),
    origin      TEXT NOT NULL CHECK (origin IN
                  ('creation','direct_edit','merged_proposal','rollback','import')),
    summary     TEXT NOT NULL,
    payload     TEXT NOT NULL
                CHECK (json_valid(payload) AND payload ->> '$.v' IS NOT NULL),
    -- §3.2: a JSON function inside a generated column. The docs say only
    -- "deterministic" functions are allowed without listing which JSON functions
    -- qualify; this is the assertion that they do.
    doc_version TEXT GENERATED ALWAYS AS (payload ->> '$.v') VIRTUAL,
    UNIQUE (design_id, seq),
    CHECK ((seq = 1) = (parent_id IS NULL))
);
CREATE INDEX revision_doc_version ON revision(doc_version);

CREATE TABLE design_search (
    design_id   INTEGER PRIMARY KEY REFERENCES design(id) ON DELETE CASCADE,
    revision_id INTEGER NOT NULL REFERENCES revision(id),
    name        TEXT NOT NULL,
    slug        TEXT NOT NULL,
    hay_a       TEXT NOT NULL,
    hay_b       TEXT NOT NULL,
    hay_c       TEXT NOT NULL,
    facets      TEXT NOT NULL CHECK (json_valid(facets)),
    keys        INTEGER NOT NULL,
    score       INTEGER NOT NULL DEFAULT 0,
    updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ'))
);
CREATE INDEX design_search_rank ON design_search(score DESC, updated_at DESC);

-- §3.3: THE filter and count path. Holds only `live` designs, by projection
-- invariant — joining `design` to filter status inside the hot path cost 30-40%.
CREATE TABLE design_facet (
    design_id INTEGER NOT NULL REFERENCES design(id) ON DELETE CASCADE,
    key       TEXT NOT NULL,
    value     TEXT NOT NULL,
    PRIMARY KEY (design_id, key, value)
) WITHOUT ROWID;
CREATE INDEX design_facet_lookup ON design_facet(key, value, design_id);

CREATE VIRTUAL TABLE design_fts USING fts5(
    hay_a, hay_b, hay_c,
    content = 'design_search', content_rowid = 'design_id',
    tokenize = 'porter unicode61'
);
CREATE VIRTUAL TABLE design_name_trgm USING fts5(
    name, content = 'design_search', content_rowid = 'design_id',
    tokenize = 'trigram'
);
"""

# §4.3, the final formulation. Order matters and so do the CROSS JOINs.
DDL_TEMP = [
    "CREATE TEMP TABLE sel(grp TEXT NOT NULL, val TEXT NOT NULL,"
    " PRIMARY KEY(grp,val)) WITHOUT ROWID",
    "CREATE TEMP TABLE selgrp(grp TEXT PRIMARY KEY) WITHOUT ROWID",
    "CREATE TEMP TABLE hit(design_id INTEGER NOT NULL, grp TEXT NOT NULL,"
    " PRIMARY KEY(design_id,grp)) WITHOUT ROWID",
    "CREATE TEMP TABLE cand(design_id INTEGER PRIMARY KEY, m INTEGER NOT NULL)",
]

Q_HIT = """
INSERT INTO hit(design_id, grp)
  SELECT DISTINCT f.design_id, f.key
  FROM sel s CROSS JOIN design_facet f ON f.key = s.grp AND f.value = s.val
"""
Q_HIT_FTS = Q_HIT + """
  WHERE f.design_id IN (SELECT rowid FROM design_fts WHERE design_fts MATCH ?)
"""
Q_COUNTS = """
SELECT f.key, f.value, count(*) FROM design_facet f
  JOIN cand c ON c.design_id = f.design_id
  WHERE c.m = :n AND f.key NOT IN (SELECT grp FROM selgrp)
  GROUP BY 1,2
UNION ALL
SELECT f.key, f.value, count(*) FROM cand c
  CROSS JOIN selgrp g
  LEFT JOIN hit h ON h.design_id = c.design_id AND h.grp = g.grp
  CROSS JOIN design_facet f ON f.design_id = c.design_id AND f.key = g.grp
  WHERE c.m - (h.grp IS NOT NULL) = :n - 1
  GROUP BY 1,2
"""


def build(db: sqlite3.Connection, n_designs: int, seed: int = 7) -> None:
    rng = random.Random(seed)
    db.executescript(SCHEMA)
    rows_rev, rows_design, rows_search = [], [], []
    for i in range(1, n_designs + 1):
        mcu = rng.choice(MCU)
        wireless = mcu in ("nrf52840", "esp32s3") and rng.random() < 0.4
        conn = "wireless" if wireless else "wired"
        form = rng.choices(FORM, [0.3, 0.6, 0.1])[0]
        foot = sorted({rng.choice(FOOTPRINT)} |
                      ({rng.choice(FOOTPRINT)} if rng.random() < 0.15 else set()))
        fw = sorted({rng.choice(FIRMWARE)} |
                    ({rng.choice(FIRMWARE)} if rng.random() < 0.3 else set()))
        keys = rng.choice(KEYS)
        pitch = rng.choice(PITCH)
        doc = {
            "v": "1",
            "core": {
                "name": f"Board {i}", "slug": f"board-{i}", "keys": keys,
                "electronics": {"kind": conn, "mcu": mcu},
                "chassis": {"kind": form},
                "switches": {"pitch": pitch, "mounts": foot},
                "firmware": fw,
            },
            "extras": {},
        }
        facets = {
            "mcu": [mcu], "connectivity": [conn], "form_factor": [form],
            "switch_footprint": foot, "firmware": fw, "pitch": [pitch],
            "key_bucket": ["<=36" if keys <= 36 else "37-48" if keys <= 48
                           else "49-64" if keys <= 64 else ">64"],
        }
        # Sparse extras, so `Unknown` really emits no facet (R3).
        for k, opts in (("lighting", LIGHTING), ("hotswap", ["true", "false"]),
                        ("smd_required", ["true", "false"]),
                        ("fab_files", ["true", "false"])):
            if rng.random() < 0.5:
                facets[k] = [rng.choice(opts)]
        rows_rev.append((i, 1, None, 1, "import", "seed", json.dumps(doc)))
        rows_design.append((i, f"board-{i}", i, "live", rng.randint(0, 80)))
        rows_search.append((
            i, i, f"Board {i}", f"board-{i}", f"Board {i} board-{i}",
            f"a {form} keyboard with {mcu}", f"{' '.join(fw)} {mcu} {conn}",
            json.dumps(facets), keys, rng.randint(0, 80),
        ))

    db.execute("BEGIN")
    db.executemany(
        "INSERT INTO revision(design_id,seq,parent_id,author_id,origin,summary,payload)"
        " VALUES(?,?,?,?,?,?,?)", rows_rev)
    db.executemany(
        "INSERT INTO design(id,slug,head_id,status,score) VALUES(?,?,?,?,?)", rows_design)
    db.executemany(
        "INSERT INTO design_search(design_id,revision_id,name,slug,hay_a,hay_b,hay_c,"
        "facets,keys,score) VALUES(?,?,?,?,?,?,?,?,?,?)", rows_search)
    db.execute(
        "INSERT INTO design_facet(design_id,key,value)"
        " SELECT s.design_id, k.key, v.value FROM design_search s,"
        " json_each(s.facets) k, json_each(k.value) v")
    db.execute("INSERT INTO design_fts(rowid,hay_a,hay_b,hay_c)"
               " SELECT design_id,hay_a,hay_b,hay_c FROM design_search")
    db.execute("INSERT INTO design_name_trgm(rowid,name)"
               " SELECT design_id,name FROM design_search")
    db.execute("COMMIT")
    db.execute("ANALYZE")


def facet_counts(db, selection: str, text: str | None = None):
    """§4.3. Returns (n_results, sorted rows). Temp tables per call, as in the store."""
    c = db.cursor()
    for ddl in DDL_TEMP:
        c.execute(ddl)
    c.execute("INSERT INTO sel(grp,val) SELECT value ->> '$.g', value ->> '$.v'"
              " FROM json_each(?)", (selection,))
    c.execute("INSERT INTO selgrp SELECT DISTINCT grp FROM sel")
    if text is None:
        c.execute(Q_HIT)
    else:
        c.execute(Q_HIT_FTS, (text,))
    c.execute("INSERT INTO cand(design_id,m) SELECT design_id, count(*) FROM hit"
              " GROUP BY design_id")
    n = c.execute("SELECT count(*) FROM selgrp").fetchone()[0]
    if n <= 1:
        # Excluding the only selected group leaves no filter, so zero-hit designs
        # are candidates too.
        if text is None:
            c.execute("INSERT INTO cand(design_id,m) SELECT design_id, 0"
                      " FROM design_search"
                      " WHERE design_id NOT IN (SELECT design_id FROM cand)")
        else:
            c.execute("INSERT INTO cand(design_id,m) SELECT rowid, 0 FROM design_fts"
                      " WHERE design_fts MATCH ? AND rowid NOT IN"
                      " (SELECT design_id FROM cand)", (text,))
    c.execute("CREATE INDEX temp.cand_m ON cand(m)")
    rows = c.execute(Q_COUNTS + " ORDER BY 1,3 DESC", {"n": n}).fetchall()
    total = c.execute("SELECT count(*) FROM cand WHERE m = ?", (n,)).fetchone()[0]
    for t in ("sel", "selgrp", "hit", "cand"):
        c.execute(f"DROP TABLE temp.{t}")
    return total, sorted(rows)


def reference(db, selection: str):
    """Brute force in Python. This is the definition of correct."""
    sel = collections.defaultdict(set)
    for e in json.loads(selection):
        sel[e["g"]].add(e["v"])
    dfac: dict[int, dict[str, set]] = collections.defaultdict(
        lambda: collections.defaultdict(set))
    for did, k, v in db.execute("SELECT design_id,key,value FROM design_facet"):
        dfac[did][k].add(v)
    all_keys = {k for d in dfac.values() for k in d}

    def matches(d, skip=None):
        return all(dfac[d].get(g, set()) & vs for g, vs in sel.items() if g != skip)

    out: collections.Counter = collections.Counter()
    for d in dfac:
        for k in all_keys:
            if matches(d, k if k in sel else None):
                for v in dfac[d].get(k, ()):
                    out[(k, v)] += 1
    return sum(1 for d in dfac if matches(d)), sorted(
        (k, v, n) for (k, v), n in out.items())


CASES = {
    "empty": "[]",
    "depth1": '[{"g":"mcu","v":"rp2040"}]',
    "depth1-multi": '[{"g":"mcu","v":"rp2040"},{"g":"mcu","v":"nrf52840"}]',
    "depth2": '[{"g":"mcu","v":"rp2040"},{"g":"form_factor","v":"split"}]',
    "depth3": '[{"g":"mcu","v":"rp2040"},{"g":"mcu","v":"nrf52840"},'
              '{"g":"switch_footprint","v":"choc_v1"},{"g":"connectivity","v":"wireless"}]',
    "depth5": '[{"g":"mcu","v":"nrf52840"},{"g":"connectivity","v":"wireless"},'
              '{"g":"form_factor","v":"split"},{"g":"switch_footprint","v":"choc_v1"},'
              '{"g":"key_bucket","v":"37-48"}]',
}


def timed(fn, reps=30):
    fn()
    ts = []
    for _ in range(reps):
        t = time.perf_counter()
        fn()
        ts.append((time.perf_counter() - t) * 1000)
    ts.sort()
    return statistics.median(ts), ts[int(0.95 * len(ts)) - 1]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--designs", type=int, default=4000,
                    help="corpus size (4000 ~ 3x the real corpus; try 25000)")
    ap.add_argument("--keep", action="store_true", help="keep the database file")
    args = ap.parse_args()

    print(f"sqlite3 library version: {sqlite3.sqlite_version}")
    path = os.path.join(tempfile.mkdtemp(prefix="kbmeasure-"), "kb.db")
    db = sqlite3.connect(path, isolation_level=None)
    for p in ("journal_mode=WAL", "synchronous=NORMAL", "temp_store=MEMORY",
              "cache_size=-40000", "busy_timeout=5000"):
        db.execute("PRAGMA " + p)

    t = time.perf_counter()
    build(db, args.designs)
    load_ms = (time.perf_counter() - t) * 1000
    n_facet = db.execute("SELECT count(*) FROM design_facet").fetchone()[0]
    size = os.path.getsize(path) + sum(
        os.path.getsize(path + s) for s in ("-wal", "-shm") if os.path.exists(path + s))
    print(f"corpus: {args.designs} designs, {n_facet} facet rows, "
          f"{size/1e6:.1f} MB on disk, built in {load_ms:.0f} ms\n")

    # §3.3 — the two filter formulations.
    print("§3.3  facet filter, two formulations (depth 3)")
    j = """SELECT count(*) FROM design_search s
      WHERE EXISTS (SELECT 1 FROM json_each(s.facets,'$.mcu')
                     WHERE value IN ('rp2040','nrf52840'))
        AND EXISTS (SELECT 1 FROM json_each(s.facets,'$.switch_footprint')
                     WHERE value IN ('choc_v1'))
        AND EXISTS (SELECT 1 FROM json_each(s.facets,'$.connectivity')
                     WHERE value IN ('wireless'))"""
    thin = """SELECT count(*) FROM (
        SELECT design_id FROM design_facet WHERE key='mcu' AND value IN ('rp2040','nrf52840')
        INTERSECT SELECT design_id FROM design_facet
          WHERE key='switch_footprint' AND value IN ('choc_v1')
        INTERSECT SELECT design_id FROM design_facet
          WHERE key='connectivity' AND value IN ('wireless'))"""
    nj = db.execute(j).fetchone()[0]
    nt = db.execute(thin).fetchone()[0]
    assert nj == nt, f"formulations disagree: {nj} vs {nt}"
    mj, _ = timed(lambda: db.execute(j).fetchone())
    mt, _ = timed(lambda: db.execute(thin).fetchone())
    print(f"  json_each  {mj:7.2f} ms   plan: "
          f"{db.execute('EXPLAIN QUERY PLAN ' + j).fetchall()[0][-1]}")
    print(f"  thin table {mt:7.2f} ms   ({mj/mt:.1f}x faster, same {nt} results)\n")

    # §4.3 — self-exclusion counts, verified.
    print("§4.3  self-exclusion facet counts (verified against brute force)")
    ok = True
    for name, sel in CASES.items():
        got, exp = facet_counts(db, sel), reference(db, sel)
        good = got == exp
        ok &= good
        med, p95 = timed(lambda s=sel: facet_counts(db, s))
        print(f"  {name:13} {'OK ' if good else 'MISMATCH'}  "
              f"results={got[0]:6}  median {med:7.2f} ms  p95 {p95:7.2f} ms")
    med, p95 = timed(lambda: facet_counts(db, CASES["depth3"], "keyboard"))
    print(f"  {'depth3+FTS5':13} --   {'':13}  median {med:7.2f} ms  p95 {p95:7.2f} ms\n")

    # §4.2 — FTS5 weighted rank, and rank composed with a facet predicate.
    print("§4.2  FTS5 bm25() weighted rank")
    q1 = """SELECT s.slug, bm25(design_fts, 10.0, 3.0, 1.0) AS rank FROM design_fts
            JOIN design_search s ON s.design_id = design_fts.rowid
            WHERE design_fts MATCH 'nrf52840 AND split' ORDER BY rank LIMIT 5"""
    q2 = """SELECT s.slug, bm25(design_fts, 10.0, 3.0, 1.0) AS rank FROM design_fts
            JOIN design_search s ON s.design_id = design_fts.rowid
            JOIN design_facet f ON f.design_id = s.design_id
              AND f.key='connectivity' AND f.value='wireless'
            WHERE design_fts MATCH 'split OR unibody' ORDER BY rank, s.score DESC LIMIT 5"""
    for label, q in (("weights only", q1), ("+ facet join", q2)):
        med, _ = timed(lambda q=q: db.execute(q).fetchall())
        print(f"  {label:14} {med:7.2f} ms   {len(db.execute(q).fetchall())} rows")

    # §3.1 — trigram tokenizer as the pg_trgm stand-in.
    tq = "SELECT count(*) FROM design_name_trgm WHERE name LIKE '%oard 12%'"
    med, _ = timed(lambda: db.execute(tq).fetchone())
    plan = db.execute("EXPLAIN QUERY PLAN " + tq).fetchall()[0][-1]
    print(f"\n§3.1  trigram substring LIKE  {med:7.2f} ms   plan: {plan}")
    print("      (the ':L' suffix means the LIKE is served by the index, not a scan)")
    fns = {r[0] for r in db.execute("SELECT name FROM pragma_function_list")}
    missing = sorted({"similarity", "editdist3", "spellfix1"} - fns)
    print(f"      absent, hence strsim in Rust: {', '.join(missing)}")

    # §3.6 — the write transaction, to size the single-writer pool.
    print("\n§3.6  full commit() transaction (single writer)")
    payload = json.dumps({"v": "1", "core": {"keys": 42}})
    facets = [("mcu", "nrf52840"), ("connectivity", "wireless"), ("form_factor", "split"),
              ("pitch", "choc_18x17"), ("switch_footprint", "choc_v1"), ("firmware", "zmk"),
              ("key_bucket", "37-48"), ("hotswap", "true"), ("fab_files", "true")]
    seqs: dict[int, int] = {}
    rng = random.Random(3)
    ts = []
    for _ in range(400):
        d = rng.randint(1, args.designs)
        seqs[d] = seqs.get(d, 1) + 1
        t = time.perf_counter()
        c = db.cursor()
        c.execute("BEGIN IMMEDIATE")
        rid = c.execute(
            "INSERT INTO revision(design_id,seq,parent_id,author_id,origin,summary,payload)"
            " VALUES(?,?,(SELECT head_id FROM design WHERE id=?),1,'merged_proposal','e',?)"
            " RETURNING id", (d, seqs[d], d, payload)).fetchone()[0]
        c.execute("UPDATE design SET head_id=? WHERE id=?", (rid, d))
        c.execute("DELETE FROM design_facet WHERE design_id=?", (d,))
        c.executemany("INSERT INTO design_facet(design_id,key,value) VALUES(?,?,?)",
                      [(d, k, v) for k, v in facets])
        c.execute("UPDATE design_search SET revision_id=?, facets=? WHERE design_id=?",
                  (rid, json.dumps({k: [v] for k, v in facets}), d))
        c.execute("COMMIT")
        ts.append((time.perf_counter() - t) * 1000)
    ts.sort()
    med = statistics.median(ts)
    print(f"  median {med:.2f} ms   p95 {ts[379]:.2f} ms   p99 {ts[395]:.2f} ms")
    print(f"  => one write connection sustains ~{1000/med:,.0f} commits/s")
    print(f"  => expected rate is a few per minute (~0.05/s): "
          f"{(1000/med)/0.05:,.0f}x headroom")

    if args.keep:
        print(f"\ndatabase kept at {path}")
    print("\nRESULT:", "all facet-count cases match the reference" if ok
          else "*** MISMATCH — the count query is wrong ***")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
