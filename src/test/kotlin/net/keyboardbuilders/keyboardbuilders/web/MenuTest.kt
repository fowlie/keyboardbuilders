package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertTrue

/**
 * The phone menu closes when you tap outside it or press Escape -- reader-reported on
 * Reddit, 2026-10-04: "close when an event happens outside the menu". The button already
 * toggles it, because it is a `details` element. The behaviour itself is checked in a
 * real browser; this holds the script on every page so it cannot quietly go missing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MenuTest {

    @Autowired lateinit var mockMvc: MockMvc

    private fun header(path: String) = mockMvc.get(path).andReturn().response.contentAsString
        .substringAfter("<header").substringBefore("</header>")

    @Test
    fun `every page closes the phone menu on a tap outside it and on Escape`() {
        for (path in listOf("/", "/designs", "/about")) {
            val header = header(path)
            assertTrue("<details class=\"menu\"" in header, "$path: no menu")
            assertTrue("pointerdown" in header, "$path: a tap outside the menu does not close it")
            assertTrue("Escape" in header, "$path: Escape does not close the menu")
        }
    }
}
