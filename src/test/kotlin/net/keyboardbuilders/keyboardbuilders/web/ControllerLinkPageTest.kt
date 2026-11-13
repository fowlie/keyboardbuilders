package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

/**
 * The Controller spec line as rendered, against the real seed and the real controller
 * catalogue: the link is only as good as the slug it points at, and a fixture could hold
 * a board the live catalogue does not.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ControllerLinkPageTest {

    @Autowired lateinit var mockMvc: MockMvc


    private fun controllerLine(slug: String) =
        Regex("<dt>Controller</dt>\\s*<dd[^>]*>(.*?)</dd>", RegexOption.DOT_MATCHES_ALL)
            .find(mockMvc.get("/designs/$slug").andReturn().response.contentAsString)!!
            .groupValues[1].replace(Regex("\\s+"), " ").trim()

    @Test
    fun `a Pro Micro socket links to the Pro Micro's own page`() {
        assertEquals("""<a href="/parts/dev-board/pro-micro">Pro Micro socket</a>""", controllerLine("sofle-v1"))
        assertEquals(200, mockMvc.get("/parts/dev-board/pro-micro").andReturn().response.status)
    }

    /**
     * Every namesake slug is written by hand in `Controller.namesake`, so each is checked
     * against a live page here: a typo would otherwise just drop the link, silently.
     */
    @Test
    fun `every other namesake links to a page that exists`() {
        mapOf(
            "sofle-pico" to "raspberry-pi-pico",
            "cheapino" to "rp2040-zero",
            "cantor" to "weact-blackpill",
            "pill60" to "bluepill",
        ).forEach { (design, board) ->
            assertEquals(
                """<a href="/parts/dev-board/$board">""",
                controllerLine(design).substringBefore(">") + ">",
                design,
            )
            assertEquals(200, mockMvc.get("/parts/dev-board/$board").andReturn().response.status, board)
        }
    }

    /** No one board is "the" Seeed XIAO: four in the catalogue share the footprint. */
    @Test
    fun `a socket no single board is named for stays plain text`() {
        assertEquals("Seeed XIAO socket", controllerLine("anavi-arrows"))
    }

    @Test
    fun `a soldered chip is not a dev board and is not linked`() {
        assertEquals("soldered RP2040", controllerLine("corne-cherry-v4"))
    }
}
