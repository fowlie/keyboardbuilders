package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Backlog #47: two layouts drawn over each other, so a reader can see which board is
 * bigger and where the keys differ.
 *
 * Every fixture split is the same two-by-one hand either side of a gap, so the ONLY
 * thing that differs between them is the fact under test -- the millimetres per unit,
 * or whether that is known at all.
 */
class SilhouetteOverlayTest {

    private val hands = """[[0,0,1,1],[1,0,1,1],[3,0,1,1],[4,0,1,1]]"""

    private val silhouettes = Silhouettes(
        jacksonObjectMapper(),
        ByteArrayResource(
            """
            {"mx-split":{"w":5,"h":1,"mm":19.05,"split":2.5,"keys":$hands},
             "choc-split":{"w":5,"h":1,"mm":17.0,"split":2.5,"keys":$hands},
             "unscaled-split":{"w":5,"h":1,"split":2.5,"keys":$hands},
             "unibody":{"w":3,"h":2,"mm":19.05,"keys":[[0,0,1,1],[1,0,1,1],[2,0,1,1],[1,1,1,1]]}}
            """.trimIndent().toByteArray()
        ),
        // A second, smaller layout of `mx-split`, the way KLOR's break-offs are recorded.
        ByteArrayResource(
            """{"mx-split":{"2":{"w":2.5,"h":1,"mm":19.05,"split":1.25,"keys":[[0,0,1,1],[1.5,0,1,1]]}}}"""
                .toByteArray()
        ),
    )

    /** The keys of one layer, as `[x, y, width, height]` in the SVG's own pixels. */
    private fun layer(svg: String, name: String): List<List<Double>> {
        val body = svg.substringAfter("""<g class="overlay-$name"""").substringBefore("</g>")
        return Regex("""<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)"""")
            .findAll(body)
            .map { m -> m.groupValues.drop(1).map { it.toDouble() } }
            .toList()
    }

    /** Center-to-center distance of the first two keys: one key unit, in pixels. */
    private fun pitchOf(keys: List<List<Double>>) = keys[1][0] - keys[0][0]

    private fun right(keys: List<List<Double>>) = keys.maxOf { it[0] + it[2] }
    private fun left(keys: List<List<Double>>) = keys.minOf { it[0] }
    private fun top(keys: List<List<Double>>) = keys.minOf { it[1] }
    private fun bottom(keys: List<List<Double>>) = keys.maxOf { it[1] + it[3] }

    /**
     * THE REASON THE BUILD NOW EMITS `mm`. Each silhouette is in its own board's key
     * units, so without it a 17 mm Choc board and a 19.05 mm MX board draw the same size
     * per key -- the Choc one 12% too big, in the one view that exists to show size.
     */
    @Test
    fun `a choc-spaced board is drawn smaller than an MX one, by the ratio of their spacing`() {
        val overlay = silhouettes.overlay("choc-split", "mx-split", "label")!!

        assertTrue(overlay.toScale)
        val ratio = pitchOf(layer(overlay.svg, "a")) / pitchOf(layer(overlay.svg, "b"))
        assertEquals(17.0 / 19.05, ratio, 0.01)
    }

    /** Never 19.05 filled in for an unknown: both are drawn in key units, and say so. */
    @Test
    fun `without both scales, both are drawn at the same key size and it is not to scale`() {
        val overlay = silhouettes.overlay("unscaled-split", "choc-split", "label")!!

        assertFalse(overlay.toScale)
        assertEquals(pitchOf(layer(overlay.svg, "a")), pitchOf(layer(overlay.svg, "b")), 0.01)
    }

    /** Two whole splits overlaid mostly compare the gap between halves, which is the
     *  drawing's choice rather than the board's. So two splits show one hand each. */
    @Test
    fun `two splits are compared hand to hand, left hands only`() {
        val overlay = silhouettes.overlay("choc-split", "mx-split", "label")!!

        assertTrue(overlay.hands)
        assertEquals(2, layer(overlay.svg, "a").size)
        assertEquals(2, layer(overlay.svg, "b").size)
    }

    /**
     * Half a pixel of tolerance, and it is the gap: every key is inset by a gap that
     * scales with its own board, so the KEY BOXES meet exactly while the drawn edges of a
     * 17 mm board and a 19.05 mm one sit 0.1 px apart. A misaligned anchor is off by keys.
     */
    @Test
    fun `left hands are lined up at their top inner corner`() {
        val overlay = silhouettes.overlay("choc-split", "mx-split", "label")!!
        val a = layer(overlay.svg, "a")
        val b = layer(overlay.svg, "b")

        assertEquals(right(a), right(b), 0.5)
        assertEquals(top(a), top(b), 0.5)
    }

    /** A split against a unibody has no hand to compare with, so both are whole. */
    @Test
    fun `a split against a unibody shows both whole boards, centered on each other`() {
        val overlay = silhouettes.overlay("mx-split", "unibody", "label")!!
        val a = layer(overlay.svg, "a")
        val b = layer(overlay.svg, "b")

        assertFalse(overlay.hands)
        assertEquals(4, a.size)
        assertEquals(4, b.size)
        assertEquals((left(a) + right(a)) / 2, (left(b) + right(b)) / 2, 0.01)
        assertEquals((top(a) + bottom(a)) / 2, (top(b) + bottom(b)) / 2, 0.01)
    }

    /**
     * Color alone cannot tell the two apart (`ux.md` §7.3), so the first is filled and
     * the second an outline. A SOLID one: dashed is this site's sign for a board whose
     * geometry is unknown (§3.5's placeholder), and would say the second has no drawing.
     */
    @Test
    fun `the first board is filled and the second is a solid outline`() {
        val svg = silhouettes.overlay("choc-split", "mx-split", "label")!!.svg

        val first = svg.substringAfter("""<g class="overlay-a"""").substringBefore(">")
        val second = svg.substringAfter("""<g class="overlay-b"""").substringBefore(">")
        assertTrue("""fill="currentColor"""" in first, first)
        assertTrue("""fill="none"""" in second && "stroke=" in second, second)
        assertFalse("dasharray" in svg, "a dashed outline reads as unknown geometry")
    }

    /** A placeholder is an invented shape, and laying a real board over it would read as
     *  a comparison. No geometry on either side means no overlay at all. */
    @Test
    fun `no overlay when either board has no drawing`() {
        assertNull(silhouettes.overlay("mx-split", "never-drawn", "label"))
        assertNull(silhouettes.overlay("never-drawn", "mx-split", "label"))
    }

    /** Owner-reported 2026-09-25: comparing against KLOR drew only its largest layout. */
    @Test
    fun `a chosen layout is drawn instead of the design's largest`() {
        val overlay = silhouettes.overlay("mx-split", "choc-split", "label", firstKeys = 2)!!

        assertEquals(1, layer(overlay.svg, "a").size, "the 2-key layout's left hand is one key")
    }

    @Test
    fun `the drawn layouts are the main picture's count and every other recorded one`() {
        assertEquals(listOf(4, 2), silhouettes.drawnLayouts("mx-split"))
        assertEquals(listOf(4), silhouettes.drawnLayouts("choc-split"))
        assertEquals(emptyList(), silhouettes.drawnLayouts("never-drawn"))
    }

    /** A layout with no drawing of its own is not drawn as the largest one instead:
     *  that would be a picture of a different arrangement under this one's name. */
    @Test
    fun `no overlay for a layout that has no drawing of its own`() {
        assertNull(silhouettes.overlay("mx-split", "choc-split", "label", firstKeys = 3))
    }

    /** The existing single-board picture must not move: the overlay shares its key
     *  drawing, and a refactor that shifted every silhouette would pass every test here. */
    @Test
    fun `the single-board silhouette is unchanged by the shared drawing`() {
        val svg = silhouettes.render("mx-split", 4, "label", 20.0, 620.0)

        assertEquals(listOf(0.9, 0.9, 18.2, 18.2), Regex("""<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)"""")
            .find(svg)!!.groupValues.drop(1).map { it.toDouble() })
    }
}
