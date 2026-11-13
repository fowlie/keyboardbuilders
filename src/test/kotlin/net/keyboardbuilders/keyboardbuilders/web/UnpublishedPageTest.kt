package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.persistence.SeedImporter
import net.keyboardbuilders.keyboardbuilders.persistence.Unpublished
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Backlog #116: a design its author unpublished is gone from every page that lists
 * designs, and its own URL says why instead of reading as a broken link.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnpublishedPageTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var unpublished: Unpublished
    @Autowired lateinit var importer: SeedImporter
    @Autowired lateinit var db: JdbcClient
    @Autowired lateinit var catalog: DesignCatalog
    @Autowired lateinit var related: RelatedDesigns

    @BeforeEach fun unpublishSweep() {
        unpublished.add("sweep-2", by = "davidphilipbarr")
        importer.importAll()
    }

    /** The context is shared (#114): put the design back for every other class. */
    @AfterEach fun restore() {
        db.sql("DELETE FROM unpublished").update()
        importer.importAll()
    }

    private fun body(url: String) =
        mockMvc.get(url).andReturn().response.contentAsString

    @Test
    fun `its page answers 410 and says the designer took it down`() {
        mockMvc.get("/designs/sweep-2").andExpect {
            status { isGone() }
            // The reason, not the body: MockMvc does not follow the servlet error forward,
            // and ErrorPageController shows a 4xx's reason as the page's one line.
            status { reason("The designer took this page down.") }
        }
    }

    /** Each check also publishes the design again and expects it back, so none of them
     *  can pass on a page that never showed the design in the first place. */
    private fun goneThenBack(url: String, link: String) {
        assertFalse(link in body(url), "$url still shows the unpublished design")
        unpublished.remove("sweep-2")
        importer.importAll()
        assertTrue(link in body(url), "$url never showed the design, so its absence proves nothing")
    }

    @Test
    fun `it is not in the design list`() = goneThenBack("/designs?q=sweep", "/designs/sweep-2\"")

    @Test
    fun `it is not in the sitemap`() = goneThenBack("/sitemap.xml", "/designs/sweep-2<")

    @Test
    fun `it is not on its family's page`() = goneThenBack("/designs/families/sweep", "/designs/sweep-2\"")

    /** Asked of the ranking itself: no page is known to list sweep-2 as similar, so this
     *  unpublishes whatever sweep-high's first match is. */
    @Test
    fun `it is not offered as a similar design`() {
        val sweepHigh = catalog.bySlug("sweep-high")!!
        val first = related.similarTo(sweepHigh).first().design.core.slug.value

        unpublished.add(first, by = "someone")

        assertFalse(related.similarTo(sweepHigh).any { it.design.core.slug.value == first })
    }
}
