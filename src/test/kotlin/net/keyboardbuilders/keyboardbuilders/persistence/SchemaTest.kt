package net.keyboardbuilders.keyboardbuilders.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Forgejo #2: the schema applies to an empty database and accepts a v0.5 import row.
 *
 * THE PIN, AND WHY IT IS NOT OPTIONAL. Every new connection to `:memory:` gets its own
 * DATABASE. With an ordinary pool the migration lands on one connection and the queries
 * hit an empty one, intermittently — `implementation.md` §3 calls that "a two-hour
 * debugging session you now do not have to have". `SingleConnectionDataSource` with
 * `suppressClose` is the pin: one connection, reused, never actually closed under the
 * caller's feet.
 *
 * `foreign_keys` is armed on the URL rather than by a `PRAGMA` statement, because the
 * pragma is per-connection and the production wiring arms it the same way.
 */
class SchemaTest {

    private lateinit var source: SingleConnectionDataSource
    private lateinit var db: JdbcClient

    @BeforeEach
    fun applySchema() {
        source = SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=on", true)
        Flyway.configure()
            .dataSource(source)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        db = JdbcClient.create(source)
    }

    @AfterEach
    fun close() = source.destroy()

    private fun tables(): Set<String> = db
        .sql("SELECT name FROM sqlite_master WHERE type IN ('table','view')")
        .query(String::class.java).list().filterNotNull().toSet()

    /** Assertion 1: it applies at all, and lands the tables v0.5 is scoped to. */
    @Test
    fun `the migration applies to an empty database`() {
        val landed = tables()

        listOf(
            "user", "design", "revision", "revision_field", "field_confirmation",
            "field_verification", "controller_board", "design_facet", "design_search",
            "design_fts", "design_name_trgm",
        ).forEach { assertTrue(it in landed, "missing table $it; got ${landed.sorted()}") }
    }

    /** The community and auth layers are out of scope and must not arrive by accident. */
    @Test
    fun `the community layer is not in the v0-5 schema`() {
        val landed = tables()

        listOf("proposal", "proposal_field", "proposal_approval", "vote",
            "reputation_event", "github_identity")
            .forEach { assertTrue(it !in landed, "$it is out of scope for v0.5") }
    }

    /**
     * Assertion 2, and the shape of every row the importer will write: a design with no
     * author, and its genesis revision with no author and no parent.
     */
    @Test
    fun `an import revision inserts with no author`() {
        val designId = insertDesign("sofle-v1")
        val revisionId = insertRevision(designId, seq = 1, parentId = null, origin = "import")

        db.sql("UPDATE design SET head_id = ? WHERE id = ?").params(revisionId, designId).update()

        val row = db.sql(
            "SELECT author_id, parent_id, origin, doc_version FROM revision WHERE id = ?"
        ).param(revisionId).query().singleRow()

        assertEquals(null, row["author_id"], "an import has no author to name")
        assertEquals(null, row["parent_id"], "the genesis revision has no parent")
        assertEquals("import", row["origin"])
        // The generated column reads the document version straight out of the payload,
        // so it cannot drift from the JSON it describes. "1" is the DOCUMENT shape's
        // version, not the product's -- an earlier draft of this test wrote "0.5" and
        // conflated the two.
        assertEquals("1", row["doc_version"])
    }

    /**
     * Assertion 3, and the one worth having: it proves the append-only bookkeeping is
     * real rather than decorative. `seq` and `parent_id` are written honestly from the
     * first import, so corrections later cannot clobber them.
     */
    @Test
    fun `a non-first revision must have a parent`() {
        val designId = insertDesign("cradio")
        insertRevision(designId, seq = 1, parentId = null, origin = "import")

        val refused = assertThrowsSql {
            insertRevision(designId, seq = 2, parentId = null, origin = "import")
        }
        assertTrue(
            "CHECK" in refused.uppercase(),
            "expected the seq/parent CHECK to refuse it, got: $refused",
        )
    }

    /**
     * The other half of the same CHECK: a first revision may not claim a parent.
     *
     * The second revision is written as a human `direct_edit` WITH an author, which is
     * what a correction actually looks like — the first draft of this test left the
     * author out and the schema refused it, which is the author CHECK doing its job on
     * the test rather than on the data.
     */
    @Test
    fun `a first revision may not have a parent`() {
        val editor = insertUser("editor")
        val designId = insertDesign("sweep")
        val genesis = insertRevision(designId, seq = 1, parentId = null, origin = "import")
        val second = insertRevision(
            designId, seq = 2, parentId = genesis, origin = "direct_edit", authorId = editor,
        )

        assertNotNull(second)
        val refused = assertThrowsSql("a genesis revision may not claim a parent") {
            insertRevision(insertDesign("other"), seq = 1, parentId = genesis, origin = "import")
        }
        assertTrue("CHECK" in refused.uppercase(), "expected the CHECK, got: $refused")
    }

    /**
     * The author CHECK, which is what stops the nullable column becoming a hole: a
     * machine origin has no author, and a human origin must have one. Neither direction
     * is enforced by the nullability alone.
     */
    @Test
    fun `a machine origin may not name an author and a human origin must`() {
        val designId = insertDesign("kaly")
        val author = insertUser("mats")

        assertThrowsSql("an import must not name an author") {
            insertRevision(designId, seq = 1, parentId = null, origin = "import", authorId = author)
        }
        assertThrowsSql("a human edit must name one") {
            insertRevision(designId, seq = 1, parentId = null, origin = "creation")
        }
        assertNotNull(
            insertRevision(designId, seq = 1, parentId = null, origin = "creation", authorId = author)
        )
    }

    /** `ai_verification` is a fifth origin, and it is a machine one. */
    @Test
    fun `ai verification is an origin the schema accepts`() {
        val designId = insertDesign("chouchou")

        assertNotNull(
            insertRevision(designId, seq = 1, parentId = null, origin = "ai_verification")
        )
        assertThrowsSql("an unknown origin is refused") {
            insertRevision(insertDesign("nope"), seq = 1, parentId = null, origin = "vibes")
        }
    }

    // -- V7: offer_observation (#112) ----------------------------------------------

    /** One look at one shop listing; the rows are the price history, so none is ever
     *  replaced, and nothing references `design`, which every boot's import empties. */
    @Test
    fun `an offer observation inserts, keyed by shop, ref and time`() {
        assertTrue("offer_observation" in tables())
        insertObservation()
        assertThrowsSql("the same look twice is one row") { insertObservation() }
        insertObservation(observedAt = "2026-10-05T09:00:00Z")
    }

    @Test
    fun `an offer observation refuses a currency the module cannot add up`() {
        assertRefusedByCheck { insertObservation(currency = "NOK") }
    }

    @Test
    fun `an offer observation refuses a stock state nobody defined`() {
        assertRefusedByCheck { insertObservation(stock = "coming_soon") }
        assertRefusedByCheck { insertObservation(net = -1) }
    }

    /**
     * The newest row is picked with MAX(observed_at), a text comparison. That is only
     * right if every time is written one way: "12:00:00.5Z" sorts BEFORE "12:00:00Z".
     */
    @Test
    fun `an offer observation's time is whole seconds in UTC, one spelling`() {
        assertRefusedByCheck { insertObservation(observedAt = "2026-10-04T12:00:00.500Z") }
        assertRefusedByCheck { insertObservation(observedAt = "2026-10-04T14:00:00+02:00") }
        assertRefusedByCheck { insertObservation(observedAt = "2026-10-04 12:00:00") }
    }

    // -- helpers ------------------------------------------------------------------

    private fun insertUser(handle: String): Long = db
        .sql("INSERT INTO user (handle, display_name) VALUES (?, ?) RETURNING id")
        .params(handle, handle.replaceFirstChar { it.uppercase() })
        .query(Long::class.java).single()

    private fun insertDesign(slug: String): Long = db
        .sql("INSERT INTO design (slug) VALUES (?) RETURNING id")
        .param(slug).query(Long::class.java).single()

    private fun insertRevision(
        designId: Long,
        seq: Int,
        parentId: Long?,
        origin: String,
        authorId: Long? = null,
    ): Long = db.sql(
        """INSERT INTO revision (design_id, seq, parent_id, author_id, origin, summary, payload)
           VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id"""
    ).params(listOf(designId, seq, parentId, authorId, origin, "imported", """{"v":"1"}"""))
        .query(Long::class.java).single()

    private fun insertObservation(
        observedAt: String = "2026-10-04T12:00:00Z",
        net: Long = 2070,
        currency: String = "EUR",
        stock: String = "in_stock",
    ) = db.sql(
        """INSERT INTO offer_observation (shop, shop_ref, observed_at, net_minor, currency, stock)
           VALUES (?, ?, ?, ?, ?, ?)"""
    ).params("42keebs", "4808", observedAt, net, currency, stock).update()

    /** A refusal by a CHECK, not by a missing table or column: the second would pass too. */
    private fun assertRefusedByCheck(block: () -> Unit) {
        val refused = assertThrowsSql(block = block)
        assertTrue("CHECK" in refused.uppercase(), "expected a CHECK to refuse it, got: $refused")
    }

    private fun assertThrowsSql(why: String = "", block: () -> Unit): String {
        val thrown = runCatching(block).exceptionOrNull()
            ?: error("expected the database to refuse this${if (why.isEmpty()) "" else ": $why"}")
        val sql = generateSequence(thrown) { it.cause }.firstOrNull { it is SQLException }
            ?: error("expected a SQLException${if (why.isEmpty()) "" else " ($why)"}, got $thrown")
        return sql.message ?: ""
    }
}
