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
 * Backlog #109, against the real seed. Kailh's PG1232 Mini Choc was recorded under a
 * catch-all low-profile name, which put it in one rail checkbox with Kailh X. It has its
 * own footprint and four published boards, so it gets its own value.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MiniChocTest {

    @Autowired lateinit var mockMvc: MockMvc


    private fun page(path: String) = mockMvc.get(path).andReturn().response.contentAsString

    private val pg1232Boards = setOf("cradio-pg1232", "cradio-z-lp", "ferris-0-1-low", "ferris-0-2-mini")

    @Test
    fun `the switch footprint group offers Mini Choc as its own checkbox`() {
        val group = Regex("<summary>Switch footprint</summary>.*?</details>", RegexOption.DOT_MATCHES_ALL)
            .find(page("/designs"))!!.value

        assertTrue(Regex(""">\s*Mini Choc</a>""").containsMatchIn(group), "no Mini Choc checkbox")
        assertTrue("""href="/designs?switch_footprint=choc_mini"""" in group)
    }

    @Test
    fun `filtering by Mini Choc finds exactly the PG1232 boards`() {
        val found = Regex("""href="/designs/([a-z0-9-]+)"""")
            .findAll(page("/designs?switch_footprint=choc_mini").substringBefore("<footer")) // not /designs/families
            .map { it.groupValues[1] }.toSet()

        assertEquals(pg1232Boards, found)
    }

    @Test
    fun `a Mini Choc board names Kailh's switch on its page`() {
        assertTrue("Kailh Mini Choc" in page("/designs/ferris-0-1-low"))
    }
}
