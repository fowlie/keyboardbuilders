package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertTrue

/** The Hotswap spec line as rendered, against the real seed (#107 step 9). */
@SpringBootTest
@AutoConfigureMockMvc
class HotswapPageTest {

    @Autowired lateinit var mockMvc: MockMvc


    private fun hotswapLine(slug: String) =
        // The label links to its learn page (#108), so the regex takes either form.
        Regex("<dt>(?:<a [^>]+>)?Hotswap(?:</a>)?</dt>\\s*<dd>(.*?)</dd>", RegexOption.DOT_MATCHES_ALL)
            .find(mockMvc.get("/designs/$slug").andReturn().response.contentAsString)!!
            .groupValues[1].replace(Regex("\\s+"), " ").trim()

    /** Two parts read as a list, not as one run-together phrase. */
    @Test
    fun `parts are separated by commas`() {
        assertTrue(
            Regex("""Kailh socket</a>, <a href="/designs\?hotswap=gateron">Gateron socket</a>""")
                .containsMatchIn(hotswapLine("kuro65")),
            hotswapLine("kuro65"),
        )
    }

    /** Hillside 46 takes Mill-Max or a soldered switch, and the page says both. */
    @Test
    fun `a board that also solders says so after its parts`() {
        assertTrue(hotswapLine("hillside-46").endsWith("Mill-Max</a>, or solder the switches in"),
                   hotswapLine("hillside-46"))
    }
}
