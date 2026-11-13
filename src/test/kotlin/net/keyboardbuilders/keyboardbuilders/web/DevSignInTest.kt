package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertTrue

/**
 * Backlog #116: on the dev machine, sign in as any GitHub login without GitHub, so the
 * author's screens can be tried in a real browser as a designer who owns designs.
 */
@SpringBootTest(properties = ["keyboardbuilders.dev-sign-in=true"])
@AutoConfigureMockMvc
class DevSignInTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `signing in as a login shows that designer's account page`() {
        val session = MockHttpSession()
        mockMvc.get("/dev/sign-in-as") { param("login", "davidphilipbarr"); this.session = session }
            .andExpect { status { is3xxRedirection() }; redirectedUrl("/account") }

        val body = mockMvc.get("/account") { this.session = session }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString

        assertTrue("Signed in with GitHub as <strong>davidphilipbarr</strong>" in body)
        assertTrue("/designs/sweep-2\"" in body)
    }

    /** As a browser does it: the page's own Sign out form, with the token it rendered. */
    @Test
    fun `the sign-out button signs the designer out`() {
        val session = MockHttpSession()
        mockMvc.get("/dev/sign-in-as") { param("login", "davidphilipbarr"); this.session = session }
        val body = mockMvc.get("/account") { this.session = session }.andReturn().response.contentAsString
        val form = Regex("""<form method="post" action="/logout">\s*<input type="hidden" name="([^"]+)" value="([^"]+)">""")
            .find(body) ?: error("no sign-out form with a token on the account page")

        mockMvc.post("/logout") { this.session = session; param(form.groupValues[1], form.groupValues[2]) }
            .andExpect { status { is3xxRedirection() }; redirectedUrl("/") }

        mockMvc.get("/account") { this.session = session }
            .andExpect { redirectedUrl("/oauth2/authorization/github") }
    }
}

/** The default configuration, which is production's: the URL does not exist. */
@SpringBootTest
@AutoConfigureMockMvc
class DevSignInOffTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `without the dev switch there is no sign-in-as`() {
        mockMvc.get("/dev/sign-in-as") { param("login", "davidphilipbarr") }
            .andExpect { status { isNotFound() } }
    }
}
