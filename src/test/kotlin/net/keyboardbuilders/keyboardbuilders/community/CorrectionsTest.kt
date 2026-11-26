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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A designer corrects measured facts on their own design (owner, 2026-11-26): splay,
 * stagger and switch mounts, picked from a list, never moderated, shown at once. One link
 * under Spec and one form for every fact, not a link per line (owner, 2026-11-27).
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
    private val form = "/designs/$slug/correct"

    @BeforeEach @AfterEach fun clear() {
        db.sql("DELETE FROM correction").update()
        importer.importAll()
    }

    private fun save(who: RequestPostProcessor, vararg fields: Pair<String, String>) =
        mockMvc.post(form) { with(who); with(csrf()); fields.forEach { (k, v) -> param(k, v) } }

    private fun html(url: String, who: RequestPostProcessor? = null) =
        mockMvc.get(url) { who?.let { with(it) } }.andReturn().response.contentAsString

    private fun listed(query: String) = "href=\"/designs/$slug\"" in html("/designs?$query")

    private fun rows() = db.sql("SELECT count(*) FROM correction").query(Int::class.java).single()

    @Test
    fun `the seed's values, before anyone corrects them`() {
        assertTrue("column-staggered" in html("/designs/$slug"))
        assertTrue(listed("stagger=column"))
        assertFalse(listed("stagger=row"))
    }

    @Test
    fun `the designer's correction shows on the page and in the filters at once`() {
        save(designer, "stagger" to "row").andExpect { status { is3xxRedirection() }; redirectedUrl("/designs/$slug") }

        val page = html("/designs/$slug")
        assertTrue("row-staggered" in page, page)
        assertFalse("column-staggered" in page)
        assertTrue(listed("stagger=row"))
        assertFalse(listed("stagger=column"))
    }

    @Test
    fun `one save corrects several facts, and stores only the ones that changed`() {
        save(designer, "splay" to "yes", "stagger" to "column", "switch_mounts" to "mx_socket", "switch_mounts" to "solder")

        assertEquals(2, rows())
        assertTrue(listed("splay=yes"))
        assertTrue(listed("hotswap=kailh"))
        assertFalse(listed("hotswap=millmax"))
        val page = html("/designs/$slug")
        assertTrue("Gateron socket" in page && "or solder the switches in" in page, page)
    }

    @Test
    fun `saving the form unchanged stores nothing`() {
        save(designer, "splay" to "no", "stagger" to "column", "switch_mounts" to "solder", "switch_mounts" to "millmax")
        assertEquals(0, rows())
    }

    @Test
    fun `a correction outlives the next import, which every boot runs`() {
        save(designer, "splay" to "yes")
        importer.importAll()

        assertTrue(listed("splay=yes"))
        assertFalse(listed("splay=no"))
    }

    @Test
    fun `the latest correction of a fact wins`() {
        save(designer, "stagger" to "row")
        save(designer, "stagger" to "ortho")
        assertTrue(listed("stagger=ortho"))
        assertFalse(listed("stagger=row"))
    }

    @Test
    fun `an answer that is not one of the fact's choices is refused, and nothing is stored`() {
        save(designer, "splay" to "yes", "stagger" to "diagonal").andExpect { status { isBadRequest() } }
        save(designer, "splay" to "yes", "splay" to "no").andExpect { status { isBadRequest() } }
        assertEquals(0, rows())
    }

    @Test
    fun `someone who is not the designer does not change the page`() {
        save(stranger, "stagger" to "row").andExpect { status { isForbidden() } }
        assertEquals(0, rows())
        assertTrue(listed("stagger=column"))
    }

    @Test
    fun `correcting needs a GitHub sign-in`() {
        mockMvc.get(form).andExpect { status { is3xxRedirection() } }
    }

    @Test
    fun `the form holds every fact, each with its current answer picked`() {
        val page = html(form, designer)
        listOf("splay", "stagger", "switch_mounts").forEach { assertTrue("name=\"$it\"" in page, it) }
        assertTrue(Regex("""name="stagger" value="column"\s+checked""").containsMatchIn(page), page)
        assertTrue(Regex("""name="splay" value="no"\s+checked""").containsMatchIn(page))
        assertTrue(Regex("""name="switch_mounts" value="millmax"\s+checked""").containsMatchIn(page))
        assertFalse(Regex("""name="switch_mounts" value="mx_socket"\s+checked""").containsMatchIn(page))
    }

    @Test
    fun `someone else sees the form's answers but cannot save them`() {
        val page = html(form, stranger)
        assertTrue("Only the designer" in page, page)
        assertFalse("type=\"submit\"" in page.substringAfter("<article"))
    }

    @Test
    fun `the design page links to the form once, under Spec`() {
        val page = html("/designs/$slug")
        assertEquals(1, Regex(Regex.escape("href=\"$form\"")).findAll(page).count(), page)
    }

    @Test
    fun `the designer's account lists an edit link for each of their designs`() {
        assertTrue("href=\"$form\"" in html("/account", designer))
    }
}
