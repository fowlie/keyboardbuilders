package net.keyboardbuilders.keyboardbuilders.persistence

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Backlog #116: an author takes their design off the site. The record is a slug in a table
 * the seed import never touches, like `heart`.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnpublishedTest {

    @Autowired lateinit var unpublished: Unpublished
    @Autowired lateinit var importer: SeedImporter
    @Autowired lateinit var queries: DesignQueries
    @Autowired lateinit var db: JdbcClient

    /** Before and after, and re-imported after: the context is shared (#114), and a design
     *  left out of the database would vanish from another class's pages. */
    @BeforeEach fun clear() { db.sql("DELETE FROM unpublished").update() }
    @AfterEach fun restore() { clear(); importer.importAll() }

    @Test
    fun `a design is published until its author unpublishes it`() {
        assertFalse(unpublished.contains("sweep-2"))

        unpublished.add("sweep-2", by = "davidphilipbarr")

        assertTrue(unpublished.contains("sweep-2"))
        assertEquals(setOf("sweep-2"), unpublished.all())
    }

    @Test
    fun `publishing again removes the record`() {
        unpublished.add("sweep-2", by = "davidphilipbarr")

        unpublished.remove("sweep-2")

        assertFalse(unpublished.contains("sweep-2"))
    }

    @Test
    fun `unpublishing twice is one record, not an error`() {
        unpublished.add("sweep-2", by = "davidphilipbarr")
        unpublished.add("sweep-2", by = "davidphilipbarr")

        assertEquals(setOf("sweep-2"), unpublished.all())
    }

    /** Every boot runs the import, which opens with `DELETE FROM design`. */
    @Test
    fun `the record survives the seed import that runs on every boot`() {
        unpublished.add("sweep-2", by = "davidphilipbarr")

        importer.importAll()

        assertTrue(unpublished.contains("sweep-2"))
    }

    /** The facet rail, search and every ranked list read the `design` tables, so leaving
     *  the design out of the import hides it from all of them at once. */
    @Test
    fun `the import leaves an unpublished design out of the database`() {
        val before = queries.total()
        assertTrue("sweep-2" in queries.allRanked())

        unpublished.add("sweep-2", by = "davidphilipbarr")
        importer.importAll()

        assertFalse("sweep-2" in queries.allRanked())
        assertEquals(before - 1, queries.total())
    }
}
