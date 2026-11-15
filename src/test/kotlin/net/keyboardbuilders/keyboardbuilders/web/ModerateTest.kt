package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
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
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
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

    @Value("\${keyboardbuilders.moderation.checked}") lateinit var checkedPath: String

    @AfterEach fun clear() {
        db.sql("DELETE FROM moderation_answer").update()
        File(checkedPath).delete()
    }

    private fun page() = mockMvc.get("/moderation") { with(moderator) }
        .andExpect { status { isOk() } }.andReturn().response.contentAsString

    private fun answer(id: String, reply: String) = mockMvc.post("/moderation/answer") {
        with(moderator); with(csrf()); param("id", id); param("reply", reply)
    }

    @Test
    fun `a visitor who is not signed in is sent to GitHub`() {
        mockMvc.get("/moderation").andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/oauth2/authorization/github")
        }
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

    // -- a photo by URL --------------------------------------------------------------------

    private fun photoByUrl(id: String, image: String, page: String? = null) = mockMvc.post("/moderation/answer") {
        with(moderator); with(csrf()); param("id", id); param("image", image)
        if (page != null) param("page", page)
    }

    private fun stored(id: String) = db.sql("SELECT reply FROM moderation_answer WHERE question_id = ?")
        .param(id).query(String::class.java).optional().orElse(null)

    @Test
    fun `a photo question offers a URL field, other questions do not`() {
        assertTrue("name=\"image\"" in page("photo:temper:def456"))
        assertFalse("name=\"image\"" in page("discovery:raeedcho/example:abc123"))
    }

    @Test
    fun `a photo URL is stored as the reply the bot reads, with its credit page if given`() {
        photoByUrl("photo:temper:def456", "https://example.com/temper.jpg").andExpect { status { is3xxRedirection() } }
        assertEquals("https://example.com/temper.jpg", stored("photo:temper:def456"))

        photoByUrl("photo:temper:def456", "https://example.com/b.jpg", "https://reddit.com/r/x/1").andExpect { status { is3xxRedirection() } }
        assertEquals("https://example.com/b.jpg https://reddit.com/r/x/1", stored("photo:temper:def456"))
    }

    @Test
    fun `a photo URL must be https`() {
        photoByUrl("photo:temper:def456", "not a url").andExpect { status { isBadRequest() } }
        photoByUrl("photo:temper:def456", "http://example.com/a.jpg").andExpect { status { isBadRequest() } }
        photoByUrl("photo:temper:def456", "https://example.com/a.jpg", "javascript:alert(1)").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a URL is not an answer to anything but a photo question`() {
        photoByUrl("discovery:raeedcho/example:abc123", "https://example.com/a.jpg").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `an answer to a question the file does not hold is refused`() {
        answer("discovery:nobody/nothing:000", "y").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a stranger cannot answer`() {
        mockMvc.post("/moderation/answer") {
            with(stranger); with(csrf()); param("id", "discovery:raeedcho/example:abc123"); param("reply", "y")
        }.andExpect { status { isForbidden() } }
    }

    // -- the footer link and the invitation ---------------------------------------------

    private fun home(vararg login: org.springframework.test.web.servlet.request.RequestPostProcessor) =
        mockMvc.get("/") { login.forEach { with(it) } }.andReturn().response.contentAsString

    @Test
    fun `every visitor gets a Moderation link in the footer, and no top-menu link`() {
        for (html in listOf(home(), home(stranger), home(moderator))) {
            val footer = html.substringAfter("<footer")
            val top = html.substringBefore("<footer")
            assertTrue("href=\"/moderation\"" in footer, "footer link")
            assertFalse("href=\"/moderation\"" in top, "no top-menu link")
        }
    }

    @Test
    fun `a signed-in user who is not a moderator is invited, not shown questions`() {
        val html = mockMvc.get("/moderation") { with(stranger) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString

        assertTrue("invite-only" in html, html)
        assertTrue("hello@keyboardbuilders.net" in html)
        assertFalse("raeedcho/example" in html, "no question leaks to a non-moderator")
    }

    // -- browsing ------------------------------------------------------------------------

    private fun page(id: String) = mockMvc.get("/moderation") { with(moderator); param("id", id) }
        .andExpect { status { isOk() } }.andReturn().response.contentAsString

    @Test
    fun `every open question is listed, linking to it`() {
        val html = page()

        assertTrue("href=\"/moderation?id=discovery:raeedcho/example:abc123\"" in html, html)
        assertTrue("href=\"/moderation?id=photo:temper:def456\"" in html, html)
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

        assertTrue("rel=\"next\" href=\"/moderation?id=photo:temper:def456\"" in first, first)
        assertTrue("rel=\"prev\" href=\"/moderation?id=discovery:raeedcho/example:abc123\"" in second, second)
    }

    @Test
    fun `answering moves on to the question after it, not back to the top`() {
        answer("discovery:raeedcho/example:abc123", "y").andExpect {
            redirectedUrl("/moderation?id=photo:temper:def456")
        }
    }

    @Test
    fun `answering the last question goes back to the first one still open`() {
        answer("photo:temper:def456", "skip").andExpect {
            redirectedUrl("/moderation?id=discovery:raeedcho/example:abc123")
        }
    }

    @Test
    fun `when every question is answered the page says so`() {
        answer("discovery:raeedcho/example:abc123", "y")
        answer("photo:temper:def456", "skip")

        assertTrue("Nothing to answer" in page())
    }

    // -- undo, and when the bot next looks ------------------------------------------------

    private fun stamp(t: Instant) = t.truncatedTo(ChronoUnit.SECONDS).toString()

    /** The bot's stamp: when its last answer check read the answers. */
    private fun checked(minutesAgo: Long) {
        File(checkedPath).apply { parentFile.mkdirs() }
            .writeText("""{"at": "${stamp(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES))}"}""")
    }

    private fun answered(id: String, reply: String, minutesAgo: Long) {
        db.sql("INSERT INTO moderation_answer (question_id, reply, by_login, at) VALUES (?, ?, 'fowlie', ?)")
            .params(id, reply, stamp(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES))).update()
    }

    private fun undo(id: String) = mockMvc.post("/moderation/undo") {
        with(moderator); with(csrf()); param("id", id)
    }

    @Test
    fun `an answer the bot has not read yet is listed with an undo button`() {
        checked(minutesAgo = 5)
        answered("discovery:raeedcho/example:abc123", "y", minutesAgo = 1)

        val html = page()
        val waiting = html.substringAfter("Waiting for the bot")
        assertTrue("Found: is raeedcho/example a keyboard design?" in waiting, html)
        assertTrue("Yes" in waiting, "the answer's label")
        assertTrue("action=\"/moderation/undo\"" in waiting, html)
    }

    @Test
    fun `undo forgets the answer and opens the question again`() {
        checked(minutesAgo = 5)
        answered("discovery:raeedcho/example:abc123", "y", minutesAgo = 1)

        undo("discovery:raeedcho/example:abc123").andExpect {
            redirectedUrl("/moderation?id=discovery:raeedcho/example:abc123")
        }
        assertEquals(null, stored("discovery:raeedcho/example:abc123"))
        assertTrue("<h2>Found: is raeedcho/example a keyboard design?</h2>" in page())
    }

    @Test
    fun `an answer the bot has already read cannot be undone`() {
        answered("discovery:raeedcho/example:abc123", "y", minutesAgo = 6)
        checked(minutesAgo = 5)

        assertFalse("action=\"/moderation/undo\"" in page())
        undo("discovery:raeedcho/example:abc123").andExpect { status { isConflict() } }
        assertEquals("y", stored("discovery:raeedcho/example:abc123"))
    }

    @Test
    fun `before the bot's first check every answer can be undone`() {
        answered("discovery:raeedcho/example:abc123", "y", minutesAgo = 60)

        undo("discovery:raeedcho/example:abc123").andExpect { status { is3xxRedirection() } }
    }

    @Test
    fun `a stranger cannot undo`() {
        answered("discovery:raeedcho/example:abc123", "y", minutesAgo = 1)

        mockMvc.post("/moderation/undo") {
            with(stranger); with(csrf()); param("id", "discovery:raeedcho/example:abc123")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `the page says when the bot next checks`() {
        checked(minutesAgo = 3)

        assertTrue("Next check in about 7 min" in page(), page())
    }

    @Test
    fun `a late check is shown as late, not as a negative wait`() {
        checked(minutesAgo = 25)

        val html = page()
        assertTrue("last checked 25 min ago" in html, html)
        assertFalse("Next check in" in html)
    }
}
