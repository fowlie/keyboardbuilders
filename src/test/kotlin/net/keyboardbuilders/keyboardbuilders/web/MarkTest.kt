package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.core.io.ClassPathResource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mark is drawn twice and the two copies must agree.
 *
 * WHY IT IS DRAWN TWICE AT ALL. The header needs the four keys INLINE so they inherit
 * `currentColor` and follow the theme with no second asset; an `<img>` cannot do that.
 * The tab icon needs to be a standalone file, because that is what `<link rel="icon">`
 * fetches. Neither can be derived from the other at request time without a template
 * render per favicon hit, so the geometry is spelled in two places on purpose.
 *
 * Which makes this the test that stops them drifting. The failure it exists to catch is
 * silent and slow: someone nudges the stagger in the header, the tab keeps the old shape,
 * and nothing anywhere goes red — the site just quietly has two logos.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MarkTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    /** Every `<rect>`'s geometry, in document order, ignoring whitespace and attribute noise. */
    private fun keysIn(svg: String): List<String> =
        Regex("""<rect\b([^>]*)/?>""").findAll(svg)
            .map { rect ->
                listOf("x", "y", "width", "height", "rx").joinToString(" ") { attr ->
                    val found = Regex("""\b$attr\s*=\s*"([^"]*)"""").find(rect.groupValues[1])
                    "$attr=${found?.groupValues?.get(1) ?: "-"}"
                }
            }
            .toList()

    private fun favicon() = ClassPathResource("static/favicon.svg").inputStream
        .use { it.readBytes().decodeToString() }

    private fun header() = mockMvc.get("/").andReturn().response.contentAsString
        .substringAfter("<svg class=\"mark\"").substringBefore("</svg>")

    @Test
    fun `the header mark and the favicon draw the same four keys`() {
        val inFile = keysIn(favicon())
        val inPage = keysIn(header())

        assertEquals(4, inFile.size, "favicon.svg should hold four keys, found $inFile")
        assertEquals(inFile, inPage, "the tab icon and the header have drifted apart")
    }

    /**
     * The stagger is the whole idea — pinky lowest, middle finger furthest, index back
     * down. Flattened, this is four squares in a row and means nothing, and that is a
     * plausible thing for a later hand to "tidy".
     */
    @Test
    fun `the keys are actually staggered, and not in a straight line`() {
        val ys = Regex("""<rect[^>]*\by\s*=\s*"([^"]*)"""").findAll(favicon())
            .map { it.groupValues[1].toDouble() }.toList()

        assertEquals(4, ys.size)
        assertTrue(ys.distinct().size == 4, "every key sits at its own height: $ys")
        assertTrue(ys[0] > ys[1] && ys[1] > ys[2], "the first three should climb: $ys")
        assertTrue(ys[3] > ys[2], "the index column comes back down: $ys")
    }

    // -- the files the browser actually asks for ----------------------------------

    @Test
    fun `the three icon files are served`() {
        listOf(
            "/favicon.svg" to "image/svg+xml",
            "/favicon.ico" to null,
            "/apple-touch-icon.png" to "image/png",
        ).forEach { (path, type) ->
            val response = mockMvc.get(path).andReturn().response
            assertEquals(200, response.status, "$path is not served")
            assertTrue(response.contentAsByteArray.isNotEmpty(), "$path is empty")
            if (type != null) {
                assertTrue(response.contentType!!.startsWith(type), "$path is ${response.contentType}")
            }
        }
    }

    /**
     * `/favicon.ico` used to 404, and a 404 here is not free: browsers request it whether
     * or not a page links to one, so every visit was logging an error and the tab showed
     * a blank page glyph next to a site that is about drawings of keyboards.
     */
    @Test
    fun `the page points a browser at all three`() {
        val head = mockMvc.get("/").andReturn().response.contentAsString
            .substringAfter("<head>").substringBefore("</head>")

        assertTrue("""href="/favicon.svg"""" in head, head)
        assertTrue("""href="/favicon.ico"""" in head, head)
        assertTrue("""href="/apple-touch-icon.png"""" in head, head)
    }

    /**
     * The SVG carries its own `prefers-color-scheme` rule, which is the reason it is the
     * primary icon rather than the .ico: it is the only one of the three that can follow
     * the reader's theme.
     */
    @Test
    fun `the svg icon answers to the reader's theme`() {
        assertTrue("prefers-color-scheme: dark" in favicon(), "the tab icon is stuck in one theme")
    }

    /**
     * THE MARK IS TWO COLOURS, and the second is part of the logo, not decoration: grey
     * keys and a terracotta top key. The header draws them from app.css and the tab icon
     * from its own file, so -- exactly like the geometry above -- nudging one and
     * forgetting the other would ship two logos with nothing going red.
     *
     * Compared in the DARK scheme only, because that is the one where the two surfaces
     * share a ground: the header bar is dark in both schemes, while a light browser tab
     * needs darker shades than the bar does to stay visible.
     */
    @Test
    fun `the dark-scheme tab icon wears the header mark's two colors`() {
        val css = ClassPathResource("static/app.css").inputStream.use { it.readBytes().decodeToString() }
        val keys = Regex("""\.wordmark \.mark \{[^}]*?\bcolor:\s*(#[0-9a-fA-F]{6})""").find(css)!!.groupValues[1]
        val top = Regex("""\.wordmark \.mark rect:nth-child\(3\) \{\s*fill:\s*(#[0-9a-fA-F]{6})""").find(css)!!.groupValues[1]

        val dark = favicon().substringAfter("prefers-color-scheme: dark").substringBefore("</style>")
        assertTrue(keys.lowercase() in dark.lowercase(), "dark tab icon lacks the header's key grey $keys: $dark")
        assertTrue(top.lowercase() in dark.lowercase(), "dark tab icon lacks the header's top key $top: $dark")
        assertTrue(keys.lowercase() != top.lowercase(), "the mark is meant to be two colors")
    }
}
