package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The site card is the mark, and the mark is four keys in a column stagger -- so this
 * asserts on the FOUR KEYS' OWN PIXELS rather than on the bytes' length or the frame's
 * dimensions. Nothing else can tell the intended lockup from a blank sheet of paper, or
 * from four squares in a straight line, which is the drift `MarkTest` exists to stop in
 * the two SVG copies and cannot see in this third, Java2D one.
 *
 * Not a `@SpringBootTest`: the controller takes no collaborators at all and its output
 * depends on nothing a request carries.
 */
class SiteCardTest {

    private val controller = SiteCardController()

    private val card: BufferedImage =
        ImageIO.read(ByteArrayInputStream(controller.card().body!!))

    private fun BufferedImage.isInk(x: Int, y: Int) = (getRGB(x, y) and 0xffffff) == 0x1b1b19

    private fun BufferedImage.isPaper(x: Int, y: Int) = (getRGB(x, y) and 0xffffff) == 0xfdfdfc

    /**
     * The centre of each of the four keys, found by scanning rather than by repeating the
     * controller's arithmetic: for each key's column of the mark, the topmost and
     * bottommost ink in the lockup's own band. Returned as (top, bottom) pairs in drawing
     * order, left to right.
     *
     * SCANNED AND NOT COMPUTED, because a test that re-derives the layout from the same
     * constants passes whether or not anything was ever drawn there.
     */
    private fun keyBandsIn(image: BufferedImage, band: IntRange): List<Pair<Int, Int>> {
        val keys = mutableListOf<Pair<Int, Int>>()
        var inRun = false
        (0 until image.width).forEach { x ->
            val inked = band.filter { y -> image.isInk(x, y) }
            if (inked.isEmpty()) {
                inRun = false
            } else if (!inRun) {
                inRun = true
                keys += inked.min() to inked.max()
            } else {
                // Widen the run in progress: a key's tallest column is its middle, not
                // the first rounded pixel of its corner.
                val last = keys.removeLast()
                keys += minOf(last.first, inked.min()) to maxOf(last.second, inked.max())
            }
        }
        return keys
    }

    @Test
    fun `the card is the size every crawler crops to`() {
        assertEquals(1200, card.width)
        assertEquals(630, card.height)
    }

    /**
     * THE STAGGER, which is the whole idea: pinky lowest, middle finger furthest, index
     * back down. Flattened into a row this is four squares and means nothing, and a later
     * hand "tidying" the geometry is exactly how that happens.
     */
    @Test
    fun `the wide card draws four keys stepped into a column stagger`() {
        // The lockup's own band, above the tagline and below the frame's top edge.
        val keys = keyBandsIn(card, 120..400).take(4)

        assertEquals(4, keys.size, "expected four keys in the mark, found ${keys.size}")
        val tops = keys.map { it.first }
        assertTrue(tops[0] > tops[1] && tops[1] > tops[2], "the first three should climb: $tops")
        assertTrue(tops[3] > tops[2], "the index column comes back down: $tops")
        // Each key is about as tall as it is wide, i.e. these are keys and not letters.
        keys.forEach { (top, bottom) ->
            assertTrue(bottom - top in 46..62, "a key is ${bottom - top}px tall, expected ~54")
        }
    }

    /** The wordmark sits right of the mark, not below it -- there is no square layout to confuse it with. */
    @Test
    fun `the wordmark sits beside the mark`() {
        val inkedRight = (0 until card.width).last { x -> (120..400).any { card.isInk(x, it) } }
        assertTrue(inkedRight > 800, "nothing is drawn right of the mark")
    }

    /** Paper to the edges: the lockup is centred with air around it, not bled off-frame. */
    @Test
    fun `nothing is drawn against the frame's edge`() {
        listOf(0, 1, 2).forEach { edge ->
            assertTrue(card.isPaper(edge, edge), "the card is inked at its corner")
            assertTrue(card.isPaper(card.width - 1 - edge, card.height - 1 - edge))
        }
        // And the wordmark is not clipped by either side: the frame keeps a clear column.
        (0 until card.height).forEach { y ->
            assertTrue(card.isPaper(0, y) && card.isPaper(card.width - 1, y), "inked at row $y")
        }
    }
}
