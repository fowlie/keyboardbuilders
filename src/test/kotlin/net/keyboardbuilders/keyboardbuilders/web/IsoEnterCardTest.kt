package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.DesignCore
import net.keyboardbuilders.keyboardbuilders.domain.DesignName
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.KeyCount
import net.keyboardbuilders.keyboardbuilders.domain.Layout
import net.keyboardbuilders.keyboardbuilders.domain.License
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Licensing
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.ComponentKind
import net.keyboardbuilders.keyboardbuilders.domain.HardestComponent
import net.keyboardbuilders.keyboardbuilders.domain.SolderingDifficulty
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The share card and the search thumbnail draw the SAME geometry as the page, in Java2D
 * instead of SVG, and the ISO enter is the one key where the two could quietly disagree:
 * a renderer that reads only the first four numbers draws QMK's own rectangle and nothing
 * errors. So this asserts on PIXELS rather than on shapes -- it is the only way to tell a
 * reverse-L from a slab from outside the drawing code.
 *
 * Not a `@SpringBootTest`: the controller is a plain class over three collaborators, and
 * a fixture board with round numbers makes the expected pixels legible where the real
 * seed's 16-unit fullsize boards would not.
 */
class IsoEnterCardTest {

    /**
     * Five units by two, with the ISO enter at the right: one key in the top band
     * stopping a quarter unit short of it (2.5-3.5), one in the bottom band stopping
     * exactly at it (2.75-3.75), and the enter itself carrying the overhang as its sixth
     * number -- the shape `build_silhouettes.py` measures on all 16 of the real ones.
     */
    private val silhouettes = Silhouettes(
        jacksonObjectMapper(),
        ByteArrayResource(
            """
            {"iso-board":{"w":5.0,"h":2.0,"keys":[
                [2.5,0.0,1.0,1.0],[2.75,1.0,1.0,1.0],[3.75,0.0,1.25,2.0,0.0,0.25]]}}
            """.trimIndent().toByteArray()
        ),
        ByteArrayResource("{}".toByteArray()),
    )

    private val controller = OgImageController(
        catalog = object : DesignCatalog {
            override fun all() = listOf(board)
            override fun bySlug(slug: String) = board.takeIf { it.core.slug.value == slug }
        },
        silhouettes = silhouettes,
        renames = SlugRenames(jacksonObjectMapper(), ByteArrayResource("{}".toByteArray())),
        // No photo curated for this fixture, so every card and thumbnail here still
        // exercises the drawn path -- which is the one this file is testing.
        photos = Photos(jacksonObjectMapper(), ByteArrayResource("{}".toByteArray())),
        photoStore = PhotoStore(dir = "build/photos", baseUrl = "https://example.invalid"),
    )

    private val thumbnail: BufferedImage =
        ImageIO.read(ByteArrayInputStream(controller.thumbnail("iso-board").body!!))

    // The thumbnail's own fit, restated: a 630px square with 48px of air, the board
    // scaled to fill what is left and centred, and a gap of a ninth of a key floored at
    // 2px. Stated again here rather than reached into, so the expected pixels are
    // legible -- and if the frame ever changes, this test is meant to notice.
    private val scale = (630.0 - 2 * 48.0) / 5.0
    private val ox = (630.0 - 5 * scale) / 2
    private val oy = (630.0 - 2 * scale) / 2
    private val half = maxOf(2.0, scale * 0.09) / 2

    /** The enter's three vertical edges and the row line its two legs differ across. */
    private val tabLeft = ox + 3.5 * scale + half
    private val legLeft = ox + 3.75 * scale + half
    private val right = ox + 5.0 * scale - half
    private val top = oy + half
    private val step = oy + scale - half
    private val bottom = oy + 2 * scale - half

    private fun isInk(x: Double, y: Double): Boolean {
        val rgb = thumbnail.getRGB(x.toInt(), y.toInt()) and 0xffffff
        return rgb == 0x1b1b19
    }

    private fun isPaper(x: Double, y: Double): Boolean {
        val rgb = thumbnail.getRGB(x.toInt(), y.toInt()) and 0xffffff
        return rgb == 0xfdfdfc
    }

    /**
     * THE REGRESSION THIS EXISTS FOR, and the top band is the only place it shows. An ISO
     * enter's notch is not empty board -- the key below and left of it reaches into that
     * space, which is the whole reason the two legs are different widths -- so the strip
     * that tells a reverse-L from a slab is the one ABOVE the step, between where the top
     * band's last key stops and where the lower leg begins. Drawn as the rectangle QMK
     * states, all of that is paper.
     */
    @Test
    fun `the card draws the ISO enter's overhanging tab`() {
        listOf(0.25, 0.5, 0.75).forEach { across ->
            val x = tabLeft + (legLeft - tabLeft) * across
            listOf(0.35, 0.65).forEach { down ->
                val y = top + (step - top) * down
                assertTrue(isInk(x, y), "no tab at ${x.toInt()}, ${y.toInt()}")
            }
        }
    }

    /** And the lower leg is solid, so the tab was not drawn instead of the key. */
    @Test
    fun `the card still draws the lower leg`() {
        assertTrue(isInk((legLeft + right) / 2, (step + bottom) / 2), "the lower leg is missing")
    }

    /**
     * The step is real: below it the key starts a quarter unit further right, and the
     * strip its neighbour occupies must stay that neighbour's. A tab drawn full depth --
     * a 1.5x2 rectangle rather than a reverse-L -- would run into it, and only the gap
     * between the two would be left to notice.
     */
    @Test
    fun `the tab does not reach below the step into its neighbour's key`() {
        // The one gap of paper between the key below-left and the enter's lower leg.
        val x = legLeft - half
        listOf(0.35, 0.5, 0.65, 0.8).forEach { down ->
            val y = step + (bottom - step) * down
            assertTrue(isPaper(x, y), "the gap below the step is filled at ${x.toInt()}, ${y.toInt()}")
        }
        // Which is a gap and not a void: the neighbour is right there, still drawn.
        assertTrue(isInk(legLeft - 3 * half, (step + bottom) / 2), "the key below-left moved")
    }

    /**
     * The tab reaches into space the board left empty, so it must not touch the key
     * beside it -- and the board's own bounding box must not grow to make room for it.
     */
    @Test
    fun `the overhang stays clear of its neighbour and of the frame`() {
        // Half a gap to the left of the tab is the gap itself, and it is paper.
        assertTrue(isPaper(tabLeft - half, (top + step) / 2))
        // The neighbour that stops short is one full gap further left again.
        assertTrue(isInk(tabLeft - 3 * half, (top + step) / 2), "the key left of the tab moved")
        // Nothing is drawn in the frame's padding, which a widened board would reach into.
        assertFalse(isInk(ox / 2, (top + step) / 2), "the board grew past its own bounding box")
    }

    private companion object {
        /**
         * The catalogue row the endpoints look up before they draw anything. Its facts do
         * not reach the thumbnail -- that image is silhouette and nothing else -- so this
         * is the smallest design the domain will accept.
         */
        private val board = Design(
            core = DesignCore(
                name = DesignName("ISO Board"),
                slug = Slug("iso-board"),
                sources = setOf(SourceRef("https://example.invalid/iso-board")),
                firmware = Recorded.Known(setOf(FirmwareRef.Qmk("iso-board"))),
                layouts = setOf(Layout(KeyCount(3))),
                switches = setOf(SwitchFootprint.Mx),
                buildMethod = BuildMethod.Pcb(
                    Recorded.Known(HardestComponent(SolderingDifficulty.ThroughHole, setOf(ComponentKind.Diode)))
                ),
                controller = Controller.Socket(ControllerFootprint.ProMicro),
                body = Body.Unibody,
                licensing = Licensing(
                    hardware = LicenseState.Stated(setOf(License.Mit)),
                    firmware = LicenseState.Stated(setOf(License.Gpl2Only)),
                    documentation = LicenseState.Unstated,
                ),
            ),
        )
    }
}
