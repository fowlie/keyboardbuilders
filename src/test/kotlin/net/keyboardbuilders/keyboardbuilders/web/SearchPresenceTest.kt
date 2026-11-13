package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The SEO pass of 2026-10-02 (owner: "If I google 'build open source keyboard' I don't see
 * it"). The front page was titled "Home · Keyboard Builders" and headed with a question,
 * so nothing a search engine weighs said what the site is; an Android install also took
 * that title as the app's name.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SearchPresenceTest {

    @Autowired lateinit var mockMvc: MockMvc

    private fun html(path: String) = mockMvc.get(path).andReturn().response.contentAsString
    private fun title(page: String) = Regex("<title>(.*?)</title>").find(page)?.groupValues?.get(1)

    @Test
    fun `the front page says what the site is in its title and heading`() {
        val page = html("/")

        assertEquals("Open-source keyboard designs you can build · Keyboard Builders", title(page))
        val h1 = assertNotNull(Regex("<h1[^>]*>(.*?)</h1>", RegexOption.DOT_MATCHES_ALL).find(page)).groupValues[1]
        assertTrue("open-source keyboard" in h1.lowercase(), h1)
        assertTrue("What kind of keyboard are you after?" in page, "the owner's question is gone")
    }

    @Test
    fun `the front page names the site to search engines`() {
        val page = html("/")
        val ld = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(page).map { it.groupValues[1] }.firstOrNull { "\"WebSite\"" in it }

        // Parsed, not matched: jte escapes "/" as "\/" inside a script, which is valid JSON.
        val json = jacksonObjectMapper().readTree(assertNotNull(ld, "no WebSite block"))
        assertEquals("Keyboard Builders", json["name"].asString())
        assertEquals("https://keyboardbuilders.net/", json["url"].asString())
    }

    /**
     * EVERY JSON-LD BLOCK IS STRICT JSON. jte escapes text inside a script for JavaScript,
     * where "\-" is fine and JSON rejects it, so every design whose slug has a hyphen
     * (156 of 379 sitemap URLs on 2026-10-03) carried a block Google could not parse:
     * Search Console's "invalid escape sequence". Jackson rejects any escape JSON lacks.
     */
    @Test
    fun `structured data parses as JSON on a hyphenated design, the front page and every learn topic and a family page`() {
        // The learn slugs are hyphenated too (#108), and each page carries two blocks.
        for (path in listOf("/designs/corne-cherry-v4", "/", "/designs/families/bad-wings") + LearnTopic.entries.map { "/learn/${it.slug}" }) {
            val blocks = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(html(path)).map { it.groupValues[1] }.toList()
            assertTrue(blocks.isNotEmpty(), "$path: no structured data")
            for (block in blocks) jacksonObjectMapper().readTree(block)
        }
        val page = jacksonObjectMapper().readTree(
            Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html("/designs/corne-cherry-v4"))!!.groupValues[1])
        assertEquals("https://keyboardbuilders.net/designs/corne-cherry-v4", page["url"].asString())
    }

    /** A family page (#107) is a list of its variants, and says so to a search engine:
     *  an ItemList whose items are the variants' own absolute URLs, in the page's order. */
    @Test
    fun `a family page lists its variants as an ItemList`() {
        val block = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html("/designs/families/corne")).map { it.groupValues[1] }.firstOrNull { "\"ItemList\"" in it }
        val json = jacksonObjectMapper().readTree(assertNotNull(block, "no ItemList block"))
        val items = json["itemListElement"].let { list -> (0 until list.size()).map { list[it] } }

        assertEquals("https://keyboardbuilders.net/designs/families/corne", json["url"].asString())
        assertEquals(
            listOf(
                "https://keyboardbuilders.net/designs/corne-cherry-v4",
                "https://keyboardbuilders.net/designs/corne-chocolate-v4",
            ),
            items.map { it["url"].asString() },
        )
        assertEquals(listOf(1, 2), items.map { it["position"].asInt() })
    }

    @Test
    fun `the catalogue's own title names what it lists`() {
        assertEquals("Open-source keyboard designs · Keyboard Builders", title(html("/designs")))
    }

    /** Chrome installs a site as an app, under the manifest's name, only with a 192 and a
     *  512 icon and a display other than `browser`; otherwise it makes a shortcut named
     *  after the page title. */
    @Test
    fun `the manifest is installable, so the app takes the manifest's name`() {
        val manifest = html("/site.webmanifest").replace(" ", "")

        assertTrue(""""name":"KeyboardBuilders"""" in manifest, manifest)
        assertTrue(""""display":"standalone"""" in manifest, manifest)
        for (size in listOf(192, 512)) {
            assertTrue(""""src":"/icon-$size.png","sizes":"${size}x$size"""" in manifest, manifest)
            val icon = mockMvc.get("/icon-$size.png").andReturn().response
            assertEquals(200, icon.status, "/icon-$size.png")
            assertEquals("image/png", icon.contentType)
        }
    }

    @Test
    fun `meta-webindexer is kept off the filtered lists like the other facet crawlers`() {
        val robots = html("/robots.txt")

        assertTrue("User-agent: meta-webindexer\nDisallow: /designs?" in robots, robots)
    }

    /** No Cache-Control on /app.css let browsers keep a stale copy after a deploy, so a
     *  shipped style change did not show (owner, the wordmark hover). The URL now carries
     *  the file's own content hash. */
    @Test
    fun `the stylesheet URL changes with its content`() {
        val href = Regex("""<link rel="stylesheet" href="([^"]+)">""").find(html("/"))?.groupValues?.get(1)

        assertTrue(Regex("""^/app\.css\?v=[0-9a-f]{10}$""").matches(assertNotNull(href)), href)
        assertEquals(200, mockMvc.get(href).andReturn().response.status)
    }
}
