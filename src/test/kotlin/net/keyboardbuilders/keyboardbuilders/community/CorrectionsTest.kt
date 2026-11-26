package net.keyboardbuilders.keyboardbuilders.community

import net.keyboardbuilders.keyboardbuilders.persistence.SeedImporter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A designer corrects a measured fact on their own design's page (owner, 2026-11-26):
 * splay, stagger and switch mounts, picked from a list, never moderated, shown at once.
 *
 * sweep-2 is davidphilipbarr's (github.com/davidphilipbarr/sweep). Its seed says: not
 * splayed, column-staggered, Mill-Max or solder.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorrectionsTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var db: JdbcClient
    @Autowired lateinit var importer: SeedImporter

    private fun as_(login: String) = oauth2Login().attributes { it["login"] = login }
    private val designer = as_("davidphilipbarr")
    private val stranger = as_("someone-else")
    private val slug = "sweep-2"

    @BeforeEach @AfterEach fun clear() {
        db.sql("DELETE FROM correction").update()
        importer.importAll()
    }

    private fun correct(fact: String, who: RequestPostProcessor, vararg values: String) =
        mockMvc.post("/designs/$slug/correct/$fact") {
            with(who); with(csrf()); values.forEach { param("value", it) }
        }

    private fun html(url: String, who: RequestPostProcessor? = null) =
        mockMvc.get(url) { who?.let { with(it) } }.andReturn().response.contentAsString

    private fun listed(query: String) = "href=\"/designs/$slug\"" in html("/designs?$query")

    @Test
    fun `the seed's values, before anyone corrects them`() {
        assertTrue("column-staggered" in html("/designs/$slug"))
        assertTrue(listed("stagger=column"))
        assertFalse(listed("stagger=row"))
    }

    @Test
    fun `the designer's correction shows on the page and in the filters at once`() {
        correct("stagger", designer, "row").andExpect { status { is3xxRedirection() }; redirectedUrl("/designs/$slug") }

        val page = html("/designs/$slug")
        assertTrue("row-staggered" in page, page)
        assertFalse("column-staggered" in page)
        assertTrue(listed("stagger=row"))
        assertFalse(listed("stagger=column"))
    }

    @Test
    fun `a correction outlives the next import, which every boot runs`() {
        correct("splay", designer, "yes")
        importer.importAll()

        assertTrue(listed("splay=yes"))
        assertFalse(listed("splay=no"))
    }

    @Test
    fun `switch mounts take several values at once`() {
        correct("switch_mounts", designer, "mx_socket", "solder")
        val page = html("/designs/$slug")
        assertTrue("Gateron socket" in page && "or solder the switches in" in page, page)
        assertFalse("Mill-Max" in page.substringAfter(">Hotswap<").substringBefore("</dd>"))
        assertTrue(listed("hotswap=kailh"))
        assertFalse(listed("hotswap=millmax"))
    }

    @Test
    fun `the latest correction of a fact wins`() {
        correct("stagger", designer, "row")
        correct("stagger", designer, "ortho")
        assertTrue(listed("stagger=ortho"))
        assertFalse(listed("stagger=row"))
    }

    @Test
    fun `a value that is not one of the fact's choices is refused`() {
        correct("stagger", designer, "diagonal").andExpect { status { isBadRequest() } }
        correct("switch_mounts", designer).andExpect { status { isBadRequest() } }
        correct("colour", designer, "red").andExpect { status { isNotFound() } }
        assertTrue(listed("stagger=column"))
    }

    @Test
    fun `someone who is not the designer does not change the page`() {
        correct("stagger", stranger, "row")
        assertTrue(listed("stagger=column"))
        assertFalse(listed("stagger=row"))
    }

    @Test
    fun `correcting needs a GitHub sign-in`() {
        mockMvc.get("/designs/$slug/correct/stagger").andExpect { status { is3xxRedirection() } }
    }

    @Test
    fun `the form offers the fact's choices with the current one marked`() {
        val form = html("/designs/$slug/correct/stagger", designer)
        listOf("ortho", "row", "column").forEach { assertTrue("value=\"$it\"" in form, form) }
        assertTrue(Regex("""value="column"\s+checked""").containsMatchIn(form), form)
    }

    @Test
    fun `the design page links each correctable fact to its form`() {
        val page = html("/designs/$slug")
        listOf("splay", "stagger", "switch_mounts").forEach {
            assertTrue("/designs/$slug/correct/$it" in page, it)
        }
    }
}
