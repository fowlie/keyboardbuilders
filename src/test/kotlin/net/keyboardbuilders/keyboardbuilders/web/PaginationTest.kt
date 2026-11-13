package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Forgejo #12's remaining lever, over a SYNTHETIC catalog of 236 designs.
 *
 * Synthetic on purpose, and for the reason `DesignsControllerTest` already gives: the
 * real seed is regenerated whenever a source improves, so a paging test sized against
 * it would go red on a good day's data work. 236 is chosen to be an awkward number —
 * two full pages and a short third — because every paging bug is a boundary.
 */
// Parts off: the parts seed is checked against the real design and controller
// catalogues at startup, and this class swaps one of them for a fixture.
@SpringBootTest(properties = ["keyboardbuilders.parts.enabled=false"])
@AutoConfigureMockMvc
@Import(ManyDesignsFixture::class)
class PaginationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun page(query: String = "") =
        mockMvc.get("/designs$query").andReturn().response

    private fun rowsIn(html: String) =
        Regex("""href="/designs/(board-\d+)"""").findAll(html).map { it.groupValues[1] }.toList()

    @Test
    fun `the index without a page parameter is the first page`() {
        val html = page().contentAsString
        val rows = rowsIn(html)

        assertEquals(Page.DEFAULT_SIZE, rows.size)
        assertEquals("board-1", rows.first())
        assertEquals("board-50", rows.last())
    }

    @Test
    fun `a later page holds the rows that follow`() {
        assertEquals(listOf("board-51", "board-100"), rowsIn(page("?page=2").contentAsString).let {
            listOf(it.first(), it.last())
        })
    }

    /** 236 over 50 is four full pages and a short fifth, which is where off-by-ones surface. */
    @Test
    fun `the last page is short and does not run past the end`() {
        val rows = rowsIn(page("?page=5").contentAsString)

        assertEquals(36, rows.size)
        assertEquals("board-236", rows.last())
    }

    /**
     * `ux.md` §2.5 makes the count the first thing faceted search has to get right, and
     * it is the count of the RESULT SET, not of the page. A reader filtering to 236
     * designs has 236 designs; which hundred they are looking at is a separate fact.
     */
    @Test
    fun `the count states the whole result set, not the page`() {
        assertTrue("236 designs" in page().contentAsString)
        assertTrue("236 designs" in page("?page=5").contentAsString)
    }

    /**
     * `ux.md` §2.7's degradation table: "Sort / paginate — boosted links / plain
     * `<a href>`". Plain anchors are the no-JS path, so they must be real hrefs and not
     * buttons waiting on a script that v0.5 does not yet load.
     */
    @Test
    fun `paging is plain anchors, so it works with no javascript`() {
        val nav = page("?page=2").contentAsString
            .substringAfter("<nav class=\"pages\"").substringBefore("</nav>")

        assertTrue("""href="/designs?page=1"""" in nav, "no link back: $nav")
        assertTrue("""href="/designs?page=3"""" in nav, "no link forward: $nav")
        assertFalse("<button" in nav, "paging must not need a script")
    }

    @Test
    fun `the first page offers no previous and the last offers no next`() {
        assertFalse("rel=\"prev\"" in page().contentAsString)
        assertTrue("rel=\"next\"" in page().contentAsString)
        assertTrue("rel=\"prev\"" in page("?page=5").contentAsString)
        assertFalse("rel=\"next\"" in page("?page=5").contentAsString)
    }

/**
     * The URL is truth (`ux.md` §2.7). A page that does not exist is a 404, not page 3
     * served quietly under a `?page=99` address that would then be shared and bookmarked.
     */
    @Test
    fun `a page past the end is not found`() {
        assertEquals(404, page("?page=6").status)
        assertEquals(404, page("?page=0").status)
        assertEquals(404, page("?page=-1").status)
    }

    /** Consistent with #4's rule for query parameters: an unparseable token is an error. */
    @Test
    fun `an unparseable page is a bad request`() {
        assertEquals(400, page("?page=abc").status)
    }
}
