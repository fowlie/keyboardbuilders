package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.core.io.ClassPathResource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Forgejo #13. `content-visibility: auto` on the result rows is a RENDERING hint: it
 * lets the browser skip layout and paint for offscreen rows, taking the facet swap from
 * 471 ms to 164 ms on a 4x-throttled profile at 300 designs.
 *
 * WHAT THIS FILE CAN AND CANNOT GUARD. It can assert the rule ships and that nothing
 * depends on it. It cannot assert the property *works*, and — more importantly — it
 * cannot catch a wrong `contain-intrinsic-size`, which is the failure this rule
 * actually shipped with: the first version fed it the 107px border-box height, but
 * `contain-intrinsic-size` sizes the CONTENT box, so every row was over-estimated by
 * its 29px of padding and border and the page lost 14.5% of its height as rows were
 * laid out for real. Nothing here would have failed. What caught it was
 * `scripts/measure_scroll_drift.mjs`, and that is where that guard lives.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OffscreenRowsTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var catalog: DesignCatalog

    private val css = ClassPathResource("static/app.css").inputStream
        .use { it.readBytes().decodeToString() }

    @Test
    fun `the stylesheet skips layout for offscreen result rows`() {
        assertTrue(
            Regex("""ul\.results li \{[^}]*content-visibility:\s*auto""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(css),
            "the result rows must carry content-visibility: auto",
        )
    }

    /**
     * A skipped row still needs a height, or the scrollbar is a lie. Both breakpoints
     * set one, because the row restacks below 40rem and its content box grows from a
     * uniform 78px to a 102-164px spread.
     */
    @Test
    fun `every rule that skips layout also states a placeholder height`() {
        val skipping = Regex("""ul\.results li \{[^}]*content-visibility:\s*auto[^}]*\}""",
            RegexOption.DOT_MATCHES_ALL).findAll(css).count()
        val sized = Regex("""contain-intrinsic-size:\s*auto\s+\d+px""").findAll(css).count()

        assertTrue(skipping > 0, "no rule skips layout, so this test is guarding nothing")
        assertTrue(
            sized >= skipping,
            "$skipping rule(s) skip layout but only $sized state a placeholder height",
        )
    }

    /**
     * The property must never become a correctness dependency. A browser without it —
     * or a reader that ignores CSS entirely — has to see every row the page claims to
     * hold, because they are all in the document either way. Asserted against the
     * rendered page rather than the stylesheet: this is why #13 is a stylesheet change
     * and not a template one, and it is what stops it quietly becoming one.
     *
     * Counted against the PAGE, not the catalogue, since paging landed — the rows a page
     * omits are omitted by the server and have nothing to do with the rendering hint.
     */
    @Test
    fun `the document holds every row the page claims, so a browser without the property loses nothing`() {
        val html = mockMvc.get("/designs").andReturn().response.contentAsString
        val list = html.substringAfter("<ul class=\"results\">").substringBefore("</ul>")

        assertEquals(
            minOf(catalog.all().size, Page.DEFAULT_SIZE),
            Regex("<li[\\s>]").findAll(list).count(),
            "every row on this page must be in the document, hint or no hint",
        )
    }
}
