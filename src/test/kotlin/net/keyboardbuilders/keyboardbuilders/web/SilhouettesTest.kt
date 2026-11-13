package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SilhouettesTest {

    /** A narrow board, a wide one, and a very wide one to exercise the cap. */
    private val silhouettes = Silhouettes(
        jacksonObjectMapper(),
        ByteArrayResource(
            """
            {"small":{"w":4.0,"h":4.0,"keys":[[0.0,0.0,1.0,1.0]]},
             "wide":{"w":12.0,"h":4.0,"keys":[[0.0,0.0,1.0,1.0]]},
             "huge":{"w":60.0,"h":5.0,"keys":[[0.0,0.0,1.0,1.0]]},
             "iso":{"w":15.0,"h":2.0,"keys":[
                [12.5,0.0,1.0,1.0],[12.75,1.0,1.0,1.0],[13.75,0.0,1.25,2.0,0.0,0.25]]},
             "knobs":{"w":4.0,"h":1.0,"keys":[[0.0,0.0,1.0,1.0],[1.0,0.0,1.0,1.0],[3.0,0.0,1.0,1.0]],
                "encoders":[[2.5,0.5]],"key_or_encoder":[[3.5,0.5]]}}
            """.trimIndent().toByteArray()
        ),
        ByteArrayResource(
            """
            {"multi":{"34":{"w":4.0,"h":4.0,"keys":[[0.0,0.0,1.0,1.0]]},
                      "42":{"w":12.0,"h":4.0,"keys":[[0.0,0.0,1.0,1.0]]}}}
            """.trimIndent().toByteArray()
        ),
    )

    private fun widthOf(svg: String) =
        Regex("""width="([0-9.]+)"""").find(svg)!!.groupValues[1].toDouble()

    /** Every `<rect>` in the markup, as `[x, y, width, height]`. */
    private fun rectsOf(svg: String) =
        Regex("""<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)"""")
            .findAll(svg)
            .map { m -> m.groupValues.drop(1).map { it.toDouble() } }
            .toList()

    /** Where the path's pen starts, which for an ISO enter is on its overhanging tab. */
    private fun startOf(svg: String) =
        Regex("""<path d="M([-0-9.]+) ([-0-9.]+)""").find(svg)!!.let {
            it.groupValues[1].toDouble() to it.groupValues[2].toDouble()
        }

    /**
     * The whole point of the component, and ux.md §3.5 calls it the easiest thing to
     * get wrong: a smaller board must RENDER smaller. Fitting each silhouette to its
     * box would make a macropad and a fullsize the same width.
     */
    @Test
    fun `a board with fewer key units is drawn narrower, not scaled up to match`() {
        val small = widthOf(silhouettes.render("small", 4, "small", 8.0, 400.0))
        val wide = widthOf(silhouettes.render("wide", 12, "wide", 8.0, 400.0))

        assertTrue(wide > small * 2.5, "expected common scale, got $small and $wide")
    }

    /** Past the cap a board shrinks as a whole, so scale stays ordinal. */
    @Test
    fun `an oversized board is capped rather than allowed to run off the row`() {
        val huge = widthOf(silhouettes.render("huge", 100, "huge", 8.0, 208.0))
        val wide = widthOf(silhouettes.render("wide", 12, "wide", 8.0, 208.0))

        assertTrue(huge <= 208.0, "cap not applied, got $huge")
        assertTrue(huge > wide, "capping must not make the biggest board smaller than a mid one")
    }

    /**
     * ux.md §3.5's degradation rule. Every ZMK design lands here, because ticket 41
     * chose QMK as the single geometry source.
     */
    @Test
    fun `a design with no geometry gets a dashed outline sized to its key count`() {
        val svg = silhouettes.render("not-in-the-file", 42, "somebody, 42 keys", 8.0, 208.0)

        assertContains(svg, "stroke-dasharray")
        assertTrue(widthOf(svg) > 0.0)
    }

    @Test
    fun `the placeholder grows with the key count, so it still says something`() {
        val few = widthOf(silhouettes.render("missing", 12, "x", 8.0, 400.0))
        val many = widthOf(silhouettes.render("missing", 96, "x", 8.0, 400.0))

        assertTrue(many > few, "a 96-key unknown should not look like a 12-key unknown")
    }

    /** The picture is decorative; the information in it is not (ux.md §3.5). */
    @Test
    fun `the label states the facts and the markup inherits the page color`() {
        val svg = silhouettes.render("wide", 42, "Corne, 42 keys, split", 8.0, 208.0)

        assertContains(svg, """role="img"""")
        assertContains(svg, """aria-label="Corne, 42 keys, split"""")
        assertContains(svg, "currentColor")
    }

    /**
     * ux.md §3.5: "Geometry only. No color coding, no legends, no annotations at
     * 96px and below." A blueprint treatment was built, tried on both tiers and
     * withdrawn on 2026-08-27 -- "a bit hard on the eyes" -- so the silhouette is
     * back to geometry in `currentColor`, and dark mode is free again.
     */
    @Test
    fun `a silhouette is plain geometry that inherits the page color`() {
        val row = silhouettes.render("wide", 12, "wide", 8.0, 208.0)

        assertContains(row, "currentColor")
        assertTrue("blueprint" !in row && "<line" !in row, "no panel, no grid, no palette")
    }

    /**
     * Splitness is the one shape fact held independently of geometry, so an
     * undrawable split should still look like a split.
     */
    @Test
    fun `a split with no geometry gets one dashed box per half`() {
        val unibody = silhouettes.render("missing", 40, "x", 20.0, 620.0, halves = 1)
        val split = silhouettes.render("missing", 40, "x", 20.0, 620.0, halves = 2)

        assertTrue(Regex("<rect[^>]*stroke-dasharray").findAll(unibody).count() == 1)
        assertTrue(Regex("<rect[^>]*stroke-dasharray").findAll(split).count() == 2,
            "a two-half split should draw two boxes")
    }

    /**
     * Forgejo #49, owner-reported on Junco: its encoders were drawn as plain switches. A
     * knob on no key is a circle and NOT a key -- the rect count stays the switch count
     * the page prints -- and a key the board lets you swap for a knob is still a key,
     * drawn hollow with a knob inside.
     */
    @Test
    fun `an encoder is drawn as a knob, and a key-or-encoder as a hollow key around one`() {
        val svg = silhouettes.render("knobs", 3, "knobs", 20.0, 620.0)

        assertEquals(3, Regex("<rect").findAll(svg).count(), "the knob on no key is not a key")
        assertEquals(2, Regex("<circle").findAll(svg).count(), "one knob alone, one inside a key")
        val hollow = Regex("""<rect[^>]*fill="none"[^>]*/><circle cx="([0-9.]+)" cy="([0-9.]+)"""")
            .findAll(svg).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
        assertEquals(listOf(70.0 to 10.0), hollow, "the key at x 3 is the hollow one, its knob at its center")
        assertContains(svg, """<circle cx="50" cy="10"""")
    }

    @Test
    fun `a board with no encoder named draws no knob`() {
        val svg = silhouettes.render("wide", 12, "wide", 20.0, 620.0)

        assertTrue("<circle" !in svg && "fill=\"none\"" !in svg)
    }

    /**
     * Owner-reported: Discipline's ISO enter drew as a plain slab. QMK states that key as
     * one `x/y/w/h` and cannot say it is a reverse-L, so the shape is reconstructed from
     * the overhang `build_silhouettes.py` measures off the board's own empty space.
     */
    @Test
    fun `an ISO enter is drawn as a reverse-L rather than a rectangle`() {
        val svg = silhouettes.render("iso", 65, "iso", 20.0, 620.0)

        // Its two ordinary keys stay rects; only the enter becomes a path.
        assertEquals(2, rectsOf(svg).size, "only the ISO enter should stop being a rect")
        assertEquals(1, Regex("<path").findAll(svg).count())

        val arcs = Regex("""A[0-9.]+ [0-9.]+ 0 0 ([01])""").findAll(svg)
            .map { it.groupValues[1] }.toList()
        assertEquals(6, arcs.size, "a reverse-L has six corners, all of them rounded")
        // THE ONE THAT MATTERS. Five corners are convex and the sixth -- where the tab
        // meets the lower leg -- is reflex, and it has to curve the other way or the
        // notch fills in and the key is a slab again with rounder edges.
        assertEquals(1, arcs.count { it == "0" }, "exactly one corner turns the other way")
    }

    /**
     * The overhang is a real quarter unit of keycap, and it has to stay clear of the key
     * beside it: a reconstruction that overlaps its neighbour would be a worse picture
     * than the rectangle it replaced.
     */
    @Test
    fun `the overhang reaches a quarter unit left and still leaves the ordinary gap`() {
        val svg = silhouettes.render("iso", 65, "iso", 20.0, 620.0)
        val (tabLeft, _) = startOf(svg)

        // scale 20, so the gap floors at 1.8 and each edge insets by 0.9.
        val leg = 13.75 * 20 + 0.9
        assertEquals(leg - 0.25 * 20, tabLeft, 0.01,
            "the tab should start a quarter unit left of the declared leg")

        // The neighbour in the enter's TOP band -- the row whose keys stop short.
        val above = rectsOf(svg).filter { it[1] < 20.0 }.maxOf { it[0] + it[2] }
        assertEquals(1.8, tabLeft - above, 0.01, "the tab should be one gap clear, no more")
    }

    /** The overhang reaches left into space the board already left empty, so nothing moves. */
    @Test
    fun `an ISO enter does not change the size of the board it is on`() {
        assertEquals(15.0 * 20, widthOf(silhouettes.render("iso", 65, "iso", 20.0, 620.0)), 0.01)
    }

    @Test
    fun `a name with a quote in it cannot break out of the label`() {
        val svg = silhouettes.render("wide", 42, """the "quoted" board""", 8.0, 208.0)

        assertContains(svg, "&quot;quoted&quot;")
    }

    // -- renderLayout, backlog #23 -------------------------------------------------

    /** The whole point: two counts, two different pictures, from one slug. */
    @Test
    fun `renderLayout draws the geometry for the count asked, not the design's other one`() {
        val narrow = widthOf(silhouettes.renderLayout("multi", 34, "x", 8.0, 400.0)!!)
        val wide = widthOf(silhouettes.renderLayout("multi", 42, "x", 8.0, 400.0)!!)

        assertEquals(4.0 * 8, narrow, 0.01)
        assertEquals(12.0 * 8, wide, 0.01)
    }

    @Test
    fun `renderLayout is null for a count the design does not have drawn, never a placeholder`() {
        assertEquals(null, silhouettes.renderLayout("multi", 99, "x", 8.0, 400.0))
    }

    @Test
    fun `renderLayout is null for a slug with no per-layout entry at all`() {
        assertEquals(null, silhouettes.renderLayout("wide", 12, "x", 8.0, 400.0))
    }
}
