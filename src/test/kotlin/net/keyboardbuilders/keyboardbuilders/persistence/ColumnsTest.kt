package net.keyboardbuilders.keyboardbuilders.persistence

import net.keyboardbuilders.keyboardbuilders.domain.Mcu
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.ResultSet
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Forgejo #3: the row-to-domain boundary. Read against the real schema, not a fixture
 * table, because the hazard being pinned is JDBC's behaviour and a hand-rolled table
 * would let us pick a friendlier one.
 *
 * A NOTE ON THE ISSUE'S EXAMPLE. It is written around "a NULL `mcu` column", and the
 * schema has none: `controller_board.mcu` is NOT NULL, and a design's facts live inside
 * `revision.payload` as JSON. The behaviour under test is the boundary's, not that one
 * column's, so it is pinned on columns that do exist — `design_search.last_commit` for a
 * nullable TEXT, `design_search.stars` for a nullable INTEGER, and `design_facet.value`
 * for the enum tag the issue names. If a real `mcu` column ever appears, nothing here
 * changes.
 */
class ColumnsTest {

    private lateinit var source: SingleConnectionDataSource
    private lateinit var db: JdbcClient

    @BeforeEach
    fun applySchema() {
        source = SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=on", true)
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        db = JdbcClient.create(source)
        db.sql("INSERT INTO design (id, slug) VALUES (1, 'sofle-v1')").update()
        db.sql(
            """INSERT INTO revision (id, design_id, seq, parent_id, origin, summary, payload)
               VALUES (1, 1, 1, NULL, 'import', 'imported', '{"v":"1"}')"""
        ).update()
    }

    @AfterEach
    fun close() = source.destroy()

    /** Reads one row through [block], so each test states only what it is pinning. */
    private fun <T> readSearchRow(block: (ResultSet) -> T): T = db
        .sql("SELECT * FROM design_search WHERE design_id = 1")
        .query(RowMapper { rs, _ -> block(rs) })
        .single()

    private fun insertSearchRow(lastCommit: String?, stars: Int?) = db.sql(
        """INSERT INTO design_search
             (design_id, revision_id, name, slug, hay_a, hay_b, hay_c, facets, keys,
              stars, last_commit)
           VALUES (1, 1, 'Sofle v1', 'sofle-v1', 'a', 'b', 'c', '{}', 60, ?, ?)"""
    ).params(listOf(stars, lastCommit)).update()

    // -- 1. NULL is an absence, and reads as one ----------------------------------

    @Test
    fun `a NULL text column reads as Unknown`() {
        insertSearchRow(lastCommit = null, stars = null)

        val read = readSearchRow { it.recorded("last_commit") { s -> Instant.parse(s) } }

        assertEquals(Recorded.Unknown, read)
    }

    /**
     * `getInt` answers 0 for SQL NULL, so a nullable numeric column silently becomes a
     * real value unless something stops it. The schema's own comment on this column says
     * NULL means "we have no star count", NOT zero stars — so 0 here would be the
     * default-fill this project refuses, arriving through JDBC rather than through code.
     */
    @Test
    fun `a NULL integer column reads as Unknown and not as zero`() {
        insertSearchRow(lastCommit = null, stars = null)

        val read = readSearchRow { it.recordedInt("stars") }

        assertEquals(Recorded.Unknown, read)
        assertTrue(read != Recorded.Known(0), "NULL stars must not read as zero stars")
    }

    // -- 2. A value that parses reads as that value -------------------------------

    @Test
    fun `a present parseable value reads as Known`() {
        insertSearchRow(lastCommit = "2026-08-28T09:15:00Z", stars = 412)

        assertEquals(
            Recorded.Known(Instant.parse("2026-08-28T09:15:00Z")),
            readSearchRow { it.recorded("last_commit") { s -> Instant.parse(s) } },
        )
        assertEquals(Recorded.Known(412), readSearchRow { it.recordedInt("stars") })
    }

    @Test
    fun `an enum tag that names a variant reads as that variant`() {
        db.sql("INSERT INTO design_facet (design_id, key, value) VALUES (1, 'mcu', 'Rp2040')")
            .update()

        val read = db.sql("SELECT value FROM design_facet WHERE key = 'mcu'")
            .query(RowMapper { rs, _ -> rs.recorded("value") { s -> enumValueOf<Mcu>(s) } })
            .single()

        assertEquals(Recorded.Known(Mcu.Rp2040), read)
    }

    // -- 3. A value that does not parse fails loudly. The judgement. ---------------

    /**
     * The behaviour this issue exists to decide. An unreadable value is a bug in whatever
     * wrote it, so it stops the request rather than degrading the page.
     */
    @Test
    fun `a present unparseable value throws rather than becoming Unknown`() {
        insertSearchRow(lastCommit = "yesterday", stars = null)

        val thrown = assertFailsWith<UnreadableColumn> {
            readSearchRow { it.recorded("last_commit", row = "sofle-v1") { s -> Instant.parse(s) } }
        }

        assertEquals("last_commit", thrown.column)
        assertEquals("yesterday", thrown.value)
        assertEquals("sofle-v1", thrown.row)
    }

    @Test
    fun `an enum tag naming no variant throws rather than becoming Unknown`() {
        db.sql("INSERT INTO design_facet (design_id, key, value) VALUES (1, 'mcu', 'Rp2040X')")
            .update()

        val thrown = assertFailsWith<UnreadableColumn> {
            db.sql("SELECT value FROM design_facet WHERE key = 'mcu'")
                .query(RowMapper { rs, _ ->
                    rs.recorded("value", row = "sofle-v1") { s -> enumValueOf<Mcu>(s) }
                }).single()
        }

        assertEquals("Rp2040X", thrown.value)
    }

    /**
     * The message is the whole remedy: it has to say which column, which value, and
     * which row, because a bare `No enum constant Mcu.Rp2040X` tells you what was wrong
     * and not where, and finding the row is the expensive half.
     */
    @Test
    fun `the failure names the column, the value and the row`() {
        insertSearchRow(lastCommit = "not-a-timestamp", stars = null)

        val message = assertFailsWith<UnreadableColumn> {
            readSearchRow { it.recorded("last_commit", row = "sofle-v1") { s -> Instant.parse(s) } }
        }.message ?: ""

        listOf("last_commit", "not-a-timestamp", "sofle-v1").forEach {
            assertTrue(it in message, "the message must name $it; got: $message")
        }
    }

    /**
     * Guards the shape of the fix, not just its behaviour. The tempting repair for a
     * throwing boundary is a `runCatching { ... } ?: Recorded.Unknown` at the call site,
     * and that single line reintroduces exactly the failure this issue names — a wrong
     * value wearing emptiness's clothes. Stating it as a test means the next person to
     * reach for it has to delete an assertion that says why not.
     */
    @Test
    fun `an unreadable value is never silently equal to an absent one`() {
        insertSearchRow(lastCommit = "nonsense", stars = null)

        val absent = readSearchRow { it.recordedInt("stars") }
        val unreadable = runCatching {
            readSearchRow { it.recorded("last_commit") { s -> Instant.parse(s) } }
        }

        assertEquals(Recorded.Unknown, absent)
        assertTrue(unreadable.isFailure, "an unreadable value must not resolve to a value at all")
        assertTrue(unreadable.exceptionOrNull() is UnreadableColumn)
    }
}
