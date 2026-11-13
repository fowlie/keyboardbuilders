package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.persistence.DesignQueries
import net.keyboardbuilders.keyboardbuilders.persistence.SeedImporter
import net.keyboardbuilders.keyboardbuilders.persistence.Unpublished
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Backlog #116: a designer signs in with GitHub and unpublishes, or publishes again, the
 * designs whose source repo is under their own account.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var unpublished: Unpublished
    @Autowired lateinit var importer: SeedImporter
    @Autowired lateinit var queries: DesignQueries
    @Autowired lateinit var db: JdbcClient

    /** sweep-2's source is github.com/davidphilipbarr/sweep. */
    private val owner = oauth2Login().attributes { it["login"] = "davidphilipbarr" }
    private val stranger = oauth2Login().attributes { it["login"] = "someone-else" }

    @BeforeEach fun clear() { db.sql("DELETE FROM unpublished").update() }

    /** The context is shared (#114): every design back in the database for other classes. */
    @AfterEach fun restore() { clear(); importer.importAll() }

    private fun page(login: org.springframework.test.web.servlet.request.RequestPostProcessor) =
        mockMvc.get("/account") { with(login) }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

    @Test
    fun `the account page sends a visitor who is not signed in to GitHub`() {
        mockMvc.get("/account").andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/oauth2/authorization/github")
        }
    }

    /** After GitHub, the designer returns to the saved URL. Spring's default appends
     *  `?continue` to it, which then sits in the address bar. */
    @Test
    fun `after signing in the designer comes back to the plain account URL`() {
        val session = org.springframework.mock.web.MockHttpSession()
        mockMvc.get("/account") { this.session = session }

        val saved = org.springframework.security.web.savedrequest.HttpSessionRequestCache()
            .getRequest(org.springframework.mock.web.MockHttpServletRequest().apply { setSession(session) },
                org.springframework.mock.web.MockHttpServletResponse())

        assertTrue(saved!!.redirectUrl.endsWith("/account"), saved.redirectUrl)
    }

    @Test
    fun `a designer sees the designs under their own GitHub account, and no others`() {
        val body = page(owner)
        assertTrue("/designs/sweep-2\"" in body)
        assertFalse("/designs/lily58\"" in body)
    }

    /** GitHub logins are case-insensitive; a repo URL may spell the account either way. */
    @Test
    fun `the account match ignores case`() {
        assertTrue("/designs/sweep-2\"" in page(oauth2Login().attributes { it["login"] = "DavidPhilipBarr" }))
    }

    @Test
    fun `a signed-in visitor with no designs is told so`() {
        assertTrue("No designs on this site come from a GitHub account named someone-else" in page(stranger))
    }

    @Test
    fun `the designer unpublishes their design and it leaves the site at once`() {
        mockMvc.post("/account/designs/sweep-2/unpublish") { with(owner); with(csrf()) }
            .andExpect { status { is3xxRedirection() }; redirectedUrl("/account") }

        assertTrue(unpublished.contains("sweep-2"))
        assertFalse("sweep-2" in queries.allRanked(), "still counted on the rail and in search")
        mockMvc.get("/designs/sweep-2").andExpect { status { isGone() } }
    }

    @Test
    fun `an unpublished design stays on the account page, to be published again`() {
        mockMvc.post("/account/designs/sweep-2/unpublish") { with(owner); with(csrf()) }

        assertTrue("/account/designs/sweep-2/publish\"" in page(owner))

        mockMvc.post("/account/designs/sweep-2/publish") { with(owner); with(csrf()) }
            .andExpect { status { is3xxRedirection() } }

        assertFalse(unpublished.contains("sweep-2"))
        assertTrue("sweep-2" in queries.allRanked())
        mockMvc.get("/designs/sweep-2").andExpect { status { isOk() } }
    }

    @Test
    fun `someone else cannot unpublish a design`() {
        mockMvc.post("/account/designs/sweep-2/unpublish") { with(stranger); with(csrf()) }
            .andExpect { status { isForbidden() } }

        assertFalse(unpublished.contains("sweep-2"))
    }

    @Test
    fun `nobody signed in can unpublish a design`() {
        mockMvc.post("/account/designs/sweep-2/unpublish") { with(csrf()) }
            .andExpect { status { is3xxRedirection() } }

        assertFalse(unpublished.contains("sweep-2"))
    }

    /** Another site's form, posting with the designer's cookie, carries no token. */
    @Test
    fun `an unpublish without the form's token is refused`() {
        mockMvc.post("/account/designs/sweep-2/unpublish") { with(owner) }
            .andExpect { status { isForbidden() } }

        assertFalse(unpublished.contains("sweep-2"))
    }

    /** Every deploy restarts the app and drops its sessions, so a page left open across one
     *  posts a token whose session is gone. That is an expired sign-in, not an attack. */
    private val expiredSession = org.springframework.test.web.servlet.request.RequestPostProcessor {
        it.requestedSessionId = "from-before-the-deploy"; it.isRequestedSessionIdValid = false; it
    }

    @Test
    fun `a sign-out from a page older than the last deploy goes to the front page`() {
        mockMvc.post("/logout") { with(expiredSession); param("_csrf", "stale") }
            .andExpect { status { is3xxRedirection() }; redirectedUrl("/") }
    }

    @Test
    fun `an unpublish from a page older than the last deploy asks for sign-in again, and changes nothing`() {
        mockMvc.post("/account/designs/sweep-2/unpublish") { with(expiredSession); param("_csrf", "stale") }
            .andExpect { status { is3xxRedirection() }; redirectedUrl("/account") }

        assertFalse(unpublished.contains("sweep-2"))
    }

    @Test
    fun `a design that does not exist cannot be unpublished`() {
        mockMvc.post("/account/designs/no-such-board/unpublish") { with(owner); with(csrf()) }
            .andExpect { status { isNotFound() } }
    }

    /** Spring Security's default `no-store` would stop browsers keeping the stylesheet,
     *  which is served without Cache-Control on purpose (see Head.kt). */
    @Test
    fun `signing in did not add no-store to every response`() {
        mockMvc.get("/app.css").andExpect {
            status { isOk() }
            header { doesNotExist("Cache-Control") }
        }
    }
}
