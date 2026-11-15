package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The publisher credit, read from the repo a design cites rather than stored.
 *
 * IT SAYS "PUBLISHED BY" AND NOT "DESIGNED BY", and the distinction is the whole test.
 * What is known is who owns the repository. For 229 of the corpus's 234 that is also the
 * designer; for the 5 whose repo is a fork it is the author of THAT variant, which is
 * still the right credit for this record. The ancestor credit that is genuinely missing
 * is `based_on`, Forgejo #24.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CreditTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `the handle is the repo owner, and it links to them`() {
        val credit = setOf(SourceRef("https://github.com/weteor/3w6")).credit()!!

        assertEquals("weteor", credit.handle)
        assertEquals("https://github.com/weteor", credit.url)
    }

    /**
     * Absent rather than guessed. Every design in the corpus cites GitHub today, so this
     * path is unreachable from the seed -- which is exactly why it is worth pinning: the
     * day a design cites a GitLab or a personal site, the page must show no credit rather
     * than a handle scraped out of the wrong part of a URL.
     */
    @Test
    fun `a source that is not github credits nobody`() {
        assertNull(setOf(SourceRef("https://gitlab.com/someone/board")).credit())
        assertNull(setOf(SourceRef("https://example.com/my-keyboard")).credit())
    }

    @Test
    fun `a design page shows who published it`() {
        val html = mockMvc.get("/designs/3w6").andReturn().response.contentAsString
        val byline = html.substringAfter("class=\"byline\"").substringBefore("</p>")

        // "by", the way the hobby credits a board -- "the Sofle, by Josef Adamcik".
        assertTrue(byline.trimStart('>', ' ', '\n').startsWith("by"), byline)
        assertTrue("https://github.com/weteor" in byline, byline)
        assertTrue("weteor" in byline, byline)
    }

    /**
     * The claim is bounded on purpose. "Designed by" would be a stronger statement than
     * the data supports, and it is the obvious later "improvement" -- so it is pinned.
     */
    @Test
    fun `the page does not claim to know who designed it`() {
        val html = mockMvc.get("/designs/3w6").andReturn().response.contentAsString

        assertTrue("Designed by" !in html, "the credit overclaims")
        assertTrue("Author" !in html, "the credit overclaims")
    }

    /** Backlog #110 step 4, against the real seed: Temper credits Chocofi. */
    @Test
    fun `an ancestor's page links the designs derived from it`() {
        val html = mockMvc.get("/designs/chocofi").andReturn().response.contentAsString
        val section = html.substringAfter("derived-head", "").substringBefore("related-head")

        assertTrue("Derived from Chocofi" in section, section.take(300))
        assertTrue("href=\"/designs/temper\"" in section, section.take(300))
        assertTrue(html.indexOf("derived-head") < html.indexOf("related-head"), "above Similar designs")
    }

    @Test
    fun `a design nothing credits shows no such line`() {
        val html = mockMvc.get("/designs/3w6").andReturn().response.contentAsString

        assertTrue("derived-head" !in html)
    }

    /** A design already shown under "Derived from X" is not shown again as similar. */
    @Test
    fun `a derived design is not repeated under similar designs`() {
        val html = mockMvc.get("/designs/cantor").andReturn().response.contentAsString
        val derived = html.substringAfter("derived-head", "").substringBefore("related-head")
        val similar = html.substringAfter("related-head", "").substringBefore("</ul>")

        assertTrue("href=\"/designs/cantor-remix\"" in derived, derived.take(300))
        assertTrue("href=\"/designs/cantor-remix\"" !in similar, similar.take(600))
    }
}
