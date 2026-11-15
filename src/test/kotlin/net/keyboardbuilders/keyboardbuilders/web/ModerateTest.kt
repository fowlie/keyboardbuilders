package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.AfterEach
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Backlog #117: a moderator answers the bot's questions on the site. The bot writes the
 * questions to a file (here the test fixture), the app stores the answers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModerateTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var db: JdbcClient

    private val moderator = oauth2Login().attributes { it["login"] = "fowlie" }
    private val stranger = oauth2Login().attributes { it["login"] = "someone-else" }

    @AfterEach fun clear() { db.sql("DELETE FROM moderation_answer").update() }

    private fun page() = mockMvc.get("/moderate") { with(moderator) }
        .andExpect { status { isOk() } }.andReturn().response.contentAsString

    private fun answer(id: String, reply: String) = mockMvc.post("/moderate/answer") {
        with(moderator); with(csrf()); param("id", id); param("reply", reply)
    }

    @Test
    fun `a visitor who is not signed in is sent to GitHub`() {
        mockMvc.get("/moderate").andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/oauth2/authorization/github")
        }
    }

    @Test
    fun `a signed-in user who is not a moderator is refused`() {
        mockMvc.get("/moderate") { with(stranger) }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `a moderator sees the first unanswered question with one button per choice`() {
        val html = page()

        assertTrue("Found: is raeedcho/example a keyboard design?" in html, html)
        assertTrue("<strong>Is <a " in html, "body rendered as Markdown")
        assertTrue("href=\"https://github.com/raeedcho/example\"" in html, "links kept")
        assertTrue("value=\"y\"" in html && "value=\"n\"" in html, "a button per choice")
        assertTrue(">Yes<" in html && ">No<" in html)
    }

    @Test
    fun `html in a question body is shown as text, never run`() {
        val html = page()

        assertFalse("<script>alert(1)</script>" in html)
        assertTrue("&lt;script&gt;" in html)
    }

    @Test
    fun `answering stores the reply and moves on to the next question`() {
        answer("discovery:raeedcho/example:abc123", "y").andExpect { status { is3xxRedirection() } }

        val stored = db.sql("SELECT reply, by_login FROM moderation_answer WHERE question_id = ?")
            .param("discovery:raeedcho/example:abc123").query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
        assertEquals("y" to "fowlie", stored)
        assertTrue("Photo for Temper" in page())
    }

    @Test
    fun `a reply that is not one of the question's buttons is refused`() {
        answer("discovery:raeedcho/example:abc123", "maybe").andExpect { status { isBadRequest() } }
        answer("photo:temper:def456", "y").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `an answer to a question the file does not hold is refused`() {
        answer("discovery:nobody/nothing:000", "y").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a stranger cannot answer`() {
        mockMvc.post("/moderate/answer") {
            with(stranger); with(csrf()); param("id", "discovery:raeedcho/example:abc123"); param("reply", "y")
        }.andExpect { status { isForbidden() } }
    }

    // -- the menu link ---------------------------------------------------------------------

    private fun home(vararg login: org.springframework.test.web.servlet.request.RequestPostProcessor) =
        mockMvc.get("/") { login.forEach { with(it) } }.andReturn().response.contentAsString

    @Test
    fun `a moderator gets a Moderate link in the top menu`() {
        assertTrue("href=\"/moderate\"" in home(moderator))
    }

    @Test
    fun `nobody else does`() {
        assertFalse("href=\"/moderate\"" in home(stranger), "a signed-in non-moderator")
        assertFalse("href=\"/moderate\"" in home(), "a visitor")
    }

    // -- browsing ------------------------------------------------------------------------

    private fun page(id: String) = mockMvc.get("/moderate") { with(moderator); param("id", id) }
        .andExpect { status { isOk() } }.andReturn().response.contentAsString

    @Test
    fun `every open question is listed, linking to it`() {
        val html = page()

        assertTrue("href=\"/moderate?id=discovery:raeedcho/example:abc123\"" in html, html)
        assertTrue("href=\"/moderate?id=photo:temper:def456\"" in html, html)
    }

    @Test
    fun `any open question can be opened directly`() {
        val html = page("photo:temper:def456")

        assertTrue("<h2>Photo for Temper</h2>" in html, html)
        assertTrue("value=\"none\"" in html)
    }

    @Test
    fun `previous and next step through the queue`() {
        val first = page()
        val second = page("photo:temper:def456")

        assertTrue("rel=\"next\" href=\"/moderate?id=photo:temper:def456\"" in first, first)
        assertTrue("rel=\"prev\" href=\"/moderate?id=discovery:raeedcho/example:abc123\"" in second, second)
    }

    @Test
    fun `answering moves on to the question after it, not back to the top`() {
        answer("discovery:raeedcho/example:abc123", "y").andExpect {
            redirectedUrl("/moderate?id=photo:temper:def456")
        }
    }

    @Test
    fun `answering the last question goes back to the first one still open`() {
        answer("photo:temper:def456", "skip").andExpect {
            redirectedUrl("/moderate?id=discovery:raeedcho/example:abc123")
        }
    }

    @Test
    fun `when every question is answered the page says so`() {
        answer("discovery:raeedcho/example:abc123", "y")
        answer("photo:temper:def456", "skip")

        assertTrue("Nothing to answer" in page())
    }
}
