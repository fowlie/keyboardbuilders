package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The about page, and the two footer links every page carries: to it, and to the
 * support page. The support link is configuration and UNSET SHOWS NOTHING, the same rule
 * the analytics origin follows -- a copy of this run from source must not ask for money
 * on the owner's behalf.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AboutTest {

    @Autowired lateinit var mockMvc: MockMvc

    private fun page(path: String) = mockMvc.get(path).andReturn().response

    @Test
    fun `the about page renders with the cartoon of its author`() {
        val response = page("/about")

        assertEquals(200, response.status)
        val html = response.contentAsString
        assertTrue("<h1>" in html, "no heading on /about")
        assertTrue(Regex("""<img[^>]+src="/fowlie\.webp"[^>]+alt="[^"]+"""").containsMatchIn(html),
            "no cartoon with alt text on /about")
    }

    @Test
    fun `the footer links to the about page`() {
        assertTrue("""href="/about"""" in page("/").contentAsString)
        assertTrue("""href="/about"""" in page("/designs").contentAsString)
    }

    /** AGPL §13: a site running AGPL code offers its users the source. Every page, so the
     *  offer is wherever a reader happens to be. */
    @Test
    fun `the footer links to the source code on GitHub`() {
        val link = Regex("""<a class="source-link external" href="https://github\.com/fowlie/keyboardbuilders"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL)
        for (path in listOf("/", "/designs", "/about")) {
            val a = link.find(page(path).contentAsString)?.groupValues?.get(1)
            assertTrue(a != null, "no source link in the footer of $path")
            assertTrue("Source code" in a, "the link says what it is: $a")
            assertTrue(Regex("""<svg class="gh"[^>]*aria-hidden="true"""").containsMatchIn(a), "GitHub mark, hidden from screen readers")
        }
    }

    @Test
    fun `the about page is in the sitemap`() {
        assertTrue("<loc>https://keyboardbuilders.net/about</loc>" in page("/sitemap.xml").contentAsString)
    }
}

/** The footer and the About page link to the owner's Ko-fi page in every environment,
 *  the dev server included (owner, 2026-10-02: "no need to hide it ... KISS"). */
@SpringBootTest
@AutoConfigureMockMvc
class SupportLinkTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `the footer and the about page link to Ko-fi`() {
        val footer = mockMvc.get("/").andReturn().response.contentAsString
        val about = mockMvc.get("/about").andReturn().response.contentAsString

        assertTrue("""class="support-link external" href="https://ko-fi.com/keyboardbuilders"""" in footer)
        assertTrue("""<a href="https://ko-fi.com/keyboardbuilders" rel="noopener" target="_blank">buy me a beer</a>""" in about)
    }
}
