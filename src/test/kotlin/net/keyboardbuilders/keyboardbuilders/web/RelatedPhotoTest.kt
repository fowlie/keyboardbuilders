package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertTrue

/**
 * Reported 2026-09-28: "the 'boards like this' doesn't show its photo even if it has
 * one". The strip predated the photo cards (77c5904) and kept drawing only the
 * silhouette. Found in the live corpus rather than a fixture, so the test is about the
 * page a reader actually gets: some design whose strip holds a photographed board.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RelatedPhotoTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var catalog: DesignCatalog
    @Autowired lateinit var related: RelatedDesigns
    @Autowired lateinit var photos: Photos

    private fun strip(html: String) =
        html.substringAfter("Boards like this</h2>").substringBefore("</ul>")

    @Test
    fun `a photographed board in the strip shows its photo`() {
        val (page, neighbour) = catalog.all().firstNotNullOf { design ->
            related.similarTo(design).firstOrNull { photos.of(it.design.core.slug.value) != null }
                ?.let { design.core.slug.value to it.design.core.slug.value }
        }
        val strip = strip(mockMvc.get("/designs/$page").andReturn().response.contentAsString)
        val url = Photos.cardUrl(photos.of(neighbour)!!)

        assertTrue(url in strip, "/designs/$page shows $neighbour without its photo $url:\n$strip")
    }

    /** The compare chooser's own "Boards like this" is the same card, leading to the pair. */
    @Test
    fun `the compare chooser shows the photo too, and still leads to the pair`() {
        val (page, neighbour) = catalog.all().firstNotNullOf { design ->
            related.similarTo(design, limit = 6).firstOrNull { photos.of(it.design.core.slug.value) != null }
                ?.let { design.core.slug.value to it.design.core.slug.value }
        }
        val strip = strip(mockMvc.get("/designs/compare?a=$page").andReturn().response.contentAsString)

        assertTrue(Photos.cardUrl(photos.of(neighbour)!!) in strip, strip)
        assertTrue("b=$neighbour" in strip, "the card no longer leads to the comparison:\n$strip")
    }

    @Test
    fun `the strip still says why each board is there`() {
        val page = catalog.all().first { related.similarTo(it).isNotEmpty() }.core.slug.value
        val strip = strip(mockMvc.get("/designs/$page").andReturn().response.contentAsString)

        assertTrue("related-reason" in strip, strip)
    }
}
