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
 * Owner, 2026-10-02: no generic "Other" switch category. Kailh X (PG1425) was the only
 * board left under "Other low-profile" after Mini Choc moved out, and a commercial switch
 * gets its own name even at one board, because the corpus is still growing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KailhXTest {

    @Autowired lateinit var mockMvc: MockMvc


    private fun page(path: String) = mockMvc.get(path).andReturn().response.contentAsString

    @Test
    fun `the switch footprint group says Kailh X and has no Other`() {
        val group = Regex("<summary>Switch footprint</summary>.*?</details>", RegexOption.DOT_MATCHES_ALL)
            .find(page("/designs"))!!.value

        assertTrue(Regex(""">\s*Kailh X</a>""").containsMatchIn(group), "no Kailh X checkbox")
        assertTrue("""href="/designs?switch_footprint=kailh_x"""" in group)
        assertTrue("Other" !in group, "a generic Other is still offered")
    }

    @Test
    fun `filtering by Kailh X finds the Kailh X board`() {
        val found = Regex("""href="/designs/([a-z0-9-]+)"""")
            .findAll(page("/designs?switch_footprint=kailh_x").substringBefore("<footer")) // not /designs/families
            .map { it.groupValues[1] }.toSet()

        assertEquals(setOf("bad-wings-slim"), found)
    }

    /** `other_low_profile` was a live value; a rename must not leave it 200 with nothing. */
    @Test
    fun `the retired other low-profile value redirects permanently to Kailh X`() {
        val response = mockMvc.get("/designs?switch_footprint=other_low_profile&q=wings").andReturn().response

        assertEquals(301, response.status)
        assertEquals("/designs?switch_footprint=kailh_x&q=wings", response.getHeader("Location"))
    }
}
