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
 * Compare from the card (owner, 2026-11-27): two presses on /designs open the pair,
 * through static/compare.js. Without the script the button is a link to the existing
 * "pick a second design" page, and that link must resolve.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CardCompareTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `every card has a compare link that starts a comparison with its design`() {
        val html = mockMvc.get("/designs?q=sweep").andReturn().response.contentAsString
        val link = Regex("""<a class="card-compare"[^>]*>""").findAll(html).map { it.value }
            .firstOrNull { "data-slug=\"sweep-2\"" in it }
        assertTrue(link != null, "the Sweep 2 card has no compare link")

        val href = Regex("""href="([^"]+)"""").find(link)!!.groupValues[1]
        assertEquals("/designs/compare?a=sweep-2", href)
        assertEquals(200, mockMvc.get(href).andReturn().response.status)
    }
}
