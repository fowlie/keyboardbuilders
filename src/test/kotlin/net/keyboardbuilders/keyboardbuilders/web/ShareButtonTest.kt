package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Forgejo #49, the share control on a design page, in YouTube's shape: a Share pill that
 * opens a panel of round targets and a link box with Copy. What the server can promise is
 * the markup: the pill opens the panel without JavaScript (a popover), every target shares
 * the design's canonical address, and Copy starts hidden because only the script can make
 * it work. The clipboard and the phone's share sheet are browser behaviour, checked there.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShareButtonTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var catalog: DesignCatalog

    private val slug by lazy { catalog.all().first().core.slug.value }
    private val html by lazy { mockMvc.get("/designs/$slug").andReturn().response.contentAsString }
    private val canonical by lazy { html.substringAfter("<link rel=\"canonical\" href=\"", "").substringBefore("\"") }
    private val panel by lazy { html.substringAfter("<div id=\"share-panel\"", "").substringBefore("<script>") }

    @Test
    fun `Share sits in the row under the picture and opens the panel without a script`() {
        val row = html.substringAfter("<p class=\"what-fits\">", "").substringBefore("</p>")
        val byline = html.substringAfter("<p class=\"byline\">", "").substringBefore("</p>")

        assertTrue("popovertarget=\"share-panel\"" in row, "no Share key beside What fits / Compare: $row")
        assertTrue("share" !in byline, "Share is still in the byline: $byline")
        assertTrue(html.contains("<div id=\"share-panel\" class=\"share-panel\" popover"), "the panel is not a popover")
    }

    @Test
    fun `every target shares the canonical address, encoded`() {
        val encoded = java.net.URLEncoder.encode(canonical, Charsets.UTF_8)
        // Discord is excluded: it has no share intent, so its link opens Discord and the
        // script copies the address for pasting (checked in the browser, not here).
        val targets = Regex("<a class=\"share-target(?! share-discord)[^\"]*\"[^>]*href=\"([^\"]+)\"").findAll(panel).map { it.groupValues[1] }.toList()

        assertEquals(7, targets.size, "targets: $targets")
        assertTrue("share-discord" in panel, "no Discord target")
        targets.forEach { assertTrue(encoded in it, "`$it` does not carry $encoded") }
        assertTrue("value=\"$canonical\"" in panel, "the link box does not hold the canonical address")
    }

    @Test
    fun `Copy starts hidden, since only the script can make it work`() {
        assertTrue("<button type=\"button\" class=\"share-copy\" hidden>" in panel)
        assertTrue("role=\"status\"" in panel, "the copy confirmation has no live region")
    }

    @Test
    fun `sharing previews as the design, not the site`() {
        assertTrue("<meta property=\"og:image\" content=\"" in html && "/designs/$slug/og.png\"" in html)
        assertTrue("<meta property=\"og:title\"" in html)
    }
}
