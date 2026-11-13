package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class HomeScreenTest {

    @Autowired lateinit var mockMvc: MockMvc

    private fun page(path: String) = mockMvc.get(path).andReturn().response

    /** The installed-app name: without these a phone offers the page title, "Home · Keyboard Builders". */
    @Test
    fun `the site names itself for a home screen install`() {
        val html = page("/").contentAsString

        assertTrue("""<meta name="application-name" content="Keyboard Builders">""" in html)
        assertTrue("""<meta name="apple-mobile-web-app-title" content="Keyboard Builders">""" in html)
        assertTrue("""<link rel="manifest" href="/site.webmanifest">""" in html)
        val response = page("/site.webmanifest")
        assertEquals("application/manifest+json", response.contentType)
        val manifest = response.contentAsString
        assertTrue(""""short_name": "Keyboard Builders"""" in manifest, manifest)
    }
}
