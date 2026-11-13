package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The footer carries every link the top bar does, plus the site's own pages (owner,
 * 2026-10-02: "we can add every button we have in the top bar also in the footer"), so a
 * reader at the bottom of a long page need not scroll back up.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FooterTest {

    @Autowired lateinit var mockMvc: MockMvc

    private fun footer(path: String) = mockMvc.get(path).andReturn().response.contentAsString
        .substringAfter("<footer").substringBefore("</footer>")

    @Test
    fun `the footer links to every page the top bar does`() {
        for (path in listOf("/", "/designs", "/about")) {
            val footer = footer(path)
            for (href in listOf("/designs", "/favorites", "/settings")) {
                assertTrue("""href="$href"""" in footer, "$path: no $href in the footer")
            }
        }
    }

    /** Owner, 2026-10-04: only Favorites carried an icon (a heart), which read as odd
     *  beside the plain Designs and Settings. The bar's keys and the footer's are words. */
    @Test
    fun `the bar and the footer name Favorites in a plain word, like the others`() {
        val page = mockMvc.get("/").andReturn().response.contentAsString
        assertEquals(3, Regex("""<a href="/favorites">Favorites</a>""").findAll(page).count(), "bar, menu and footer")
        assertFalse("&#9825; Favorites" in page || "\u2661 Favorites" in page)
    }

    /** Owner, 2026-11-13: no separate Privacy link. It led to the same page as "About this
     *  site" right above it, and the column was cramped once Source code joined it. */
    @Test
    fun `the footer links to about and the contact address, not privacy on its own`() {
        val footer = footer("/")

        assertTrue("""href="/about"""" in footer, footer)
        assertFalse("""href="/about#privacy"""" in footer, footer)
        assertTrue("""href="mailto:hello@keyboardbuilders.net""" in footer, footer)
    }

    @Test
    fun `the about page keeps its privacy section`() {
        val about = mockMvc.get("/about").andReturn().response.contentAsString

        assertTrue("""<h2 id="privacy">""" in about, "no #privacy anchor on /about")
    }
}
