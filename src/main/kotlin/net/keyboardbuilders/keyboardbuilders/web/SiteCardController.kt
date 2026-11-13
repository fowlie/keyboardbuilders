package net.keyboardbuilders.keyboardbuilders.web

import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Duration
import javax.imageio.ImageIO

/**
 * The picture for every page that is NOT a design: the site's own mark and wordmark,
 * drawn once and served to all of them.
 *
 * ONE IMAGE FOR FOUR PAGES, and that is the difference from `OgImageController`. That one
 * draws a card PER SLUG because each design has a picture of its own -- its silhouette,
 * or a curated photo -- and a card showing the shape of the keyboard is the most
 * persuasive thing the catalogue owns. The home page, the result list and the 34 dev-board
 * pages have no such picture: the honest image for "234 designs, filterable" is not one of
 * the 234. So these four share a single static lockup rather than each computing something
 * per-page that would only ever be the same logo dressed differently.
 *
 * WHAT IT REPLACES IS NOTHING AT ALL. `Head.image` has been nullable since it was written
 * and only `designHead` ever set it, so pasting `keyboardbuilders.net` itself into Discord
 * previewed as a title and a line of grey text with no picture -- the site's own front
 * door being the one link that looked least like a site.
 *
 * THE MARK IS THE FAVICON'S, TO THE THIRD DECIMAL, not a fresh drawing of four squares.
 * [MARK] below is `static/favicon.svg`'s four `<rect>`s divided through by the 6.4-unit key
 * so they can be drawn at any size; the stagger, the 1.203-unit pitch and the 0.234 corner
 * are that file's numbers, carried over rather than re-invented. `MarkTest` holds the two
 * SVG copies together and cannot see this one -- it reads `<rect>` attributes out of markup
 * -- so the arithmetic is written down here instead, to be checked by eye against the
 * comment rather than found to have drifted later.
 *
 * DRAWN AT REQUEST TIME AND CACHED IN MEMORY, not checked into `static/`. A PNG in the
 * repository would be a fifth copy of the mark with no test and no way to notice it had
 * gone stale; drawing it from the same numbers means a logo change is one edit. It is
 * computed once per process because, unlike a design's card, it does not depend on
 * anything a request carries.
 */
@Controller
class SiteCardController {

    /**
     * A day's cache and not `immutable`, matching `OgImageController.card()`: this URL is
     * not content-hashed, so a tweak to the lockup has to be able to reach a crawler that
     * has already fetched it, and within a day rather than never.
     */
    @GetMapping("/og.png", produces = [MediaType.IMAGE_PNG_VALUE])
    fun card(): ResponseEntity<ByteArray> = ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
        .body(wide)

    // NO SQUARE THUMBNAIL, on reflection -- Google's own guidance names "a generic image,
    // for example your site logo" as what a `primaryImageOfPage` should avoid, and this
    // controller's only picture is exactly that logo. `OgImageController.thumbnail`
    // follows the same rule for a design with neither photo nor silhouette: no thumbnail
    // rather than a stand-in. These four pages keep `og:image` -- a share card is not the
    // same claim as a search engine's primary image of the page -- but set no
    // `Head.thumbnail`, so nothing here needs to draw a square.

    private val wide: ByteArray by lazy { drawWide() }

    /**
     * 1200x630: mark and wordmark SIDE BY SIDE, centred, with the site's own sentence
     * under them.
     *
     * The same arrangement the header uses, at forty times the size -- a reader who has
     * seen the site recognises the card and a reader who has not is looking at the thing
     * they will see at the top of the page they land on.
     *
     * THE SENTENCE IS THE ONE THE HOME PAGE ALREADY SERVES, not a slogan written for the
     * card. A share card that promises something the page does not say is a second set of
     * words to keep true, which is the reason `layout.kte` feeds `og:description` from the
     * same `Head.description` the search result gets.
     */
    private fun drawWide(): ByteArray {
        val image = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = canvas(image, W, H)

        val font = fitted(g, WORDMARK, LOCKUP_W - 4.609 * WIDE_KEY - WIDE_KEY * 0.55, 80)
        g.font = font
        val ink = wordmarkInk(g)
        val markW = 4.609 * WIDE_KEY
        val gap = WIDE_KEY * 0.55
        val left = (W - (markW + gap + ink.width)) / 2

        drawMark(g, WIDE_KEY, left, WIDE_MIDDLE - 2.75 * WIDE_KEY / 2)
        g.color = INK
        g.drawString(
            WORDMARK,
            (left + markW + gap - ink.x).toFloat(),
            (WIDE_MIDDLE - ink.y - ink.height / 2).toFloat(),
        )

        g.color = MUTED
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 32)
        val tagline = g.fontMetrics.stringWidth(TAGLINE)
        g.drawString(TAGLINE, ((W - tagline) / 2).toFloat(), TAGLINE_BASELINE)
        g.dispose()

        return encodePng(image)
    }

    /** The four keys, at `key` pixels to a key unit, with the lockup's top left at `ox`, `oy`. */
    private fun drawMark(g: Graphics2D, key: Double, ox: Double, oy: Double) {
        g.color = INK
        MARK.forEach { (x, y) ->
            g.fill(
                RoundRectangle2D.Double(
                    ox + x * key, oy + y * key, key, key,
                    // `RoundRectangle2D` takes arc WIDTH, which is twice the `rx` the SVG
                    // states -- the same corner, in the other unit.
                    2 * 0.234 * key, 2 * 0.234 * key,
                )
            )
        }
    }

    /**
     * The wordmark's INK bounds rather than its font metrics, which is what lets the mark
     * and the letters line up at all.
     *
     * "keyboardbuilders" is all lowercase, and a font's ascent and descent describe the
     * space the font reserves, not the space these sixteen glyphs occupy: measured in the
     * bold sans this draws in, the reserved box is about a third taller than the ink. Centre
     * the mark on the reserved box and it sits visibly high; centre it on the ink and it
     * sits where the header's `align-items: center` puts it.
     */
    private fun wordmarkInk(g: Graphics2D): Rectangle2D =
        g.font.createGlyphVector(g.fontRenderContext, WORDMARK).visualBounds

    /**
     * The largest size at or below `from` at which the wordmark still fits `max`.
     *
     * NOT A FIXED SIZE, because `Font.SANS_SERIF` is whatever the host resolves it to. The
     * numbers here were chosen against this machine's, where the wordmark measures 714px
     * bold at 80pt; a host whose sans is wider would push the lockup past the frame, and a
     * card with a clipped letter is worse than one set a few points smaller.
     */
    private fun fitted(g: Graphics2D, text: String, max: Double, from: Int): Font {
        var size = from
        while (size > 12 && g.getFontMetrics(bold(size)).stringWidth(text) > max) size--
        return bold(size)
    }

    /**
     * BOLD, where the header's own CSS asks for 600. Java2D offers two weights and not a
     * variable axis, so the choice is bold or regular; bold is the one that reads as the
     * same mark at a glance.
     */
    private fun bold(size: Int) = Font(Font.SANS_SERIF, Font.BOLD, size)

    private fun canvas(image: BufferedImage, w: Int, h: Int): Graphics2D {
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = PAPER
        g.fillRect(0, 0, w, h)
        return g
    }

    private fun encodePng(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().use { out -> ImageIO.write(image, "png", out); out.toByteArray() }

    private companion object {
        const val W = 1200
        const val H = 630

        /**
         * `static/favicon.svg`'s four keys in key units: its `x` and `y` less the mark's own
         * top left (1.4, 7.2), divided by the 6.4-unit key. Pinky lowest, middle finger
         * furthest, index back down -- the column stagger the whole catalogue is about.
         * Overall the mark is 4.609 by 2.75 keys.
         */
        val MARK = listOf(
            0.0 to 1.75,
            1.203 to 0.906,
            2.406 to 0.0,
            3.609 to 0.625,
        )

        const val WORDMARK = "keyboardbuilders"

        /** The home page's own description, shortened to one line that fits the frame. */
        const val TAGLINE = "open-source keyboard designs, read from their own board files"

        /** Wide card: key size, the width the lockup may fill, and where it centres. */
        const val WIDE_KEY = 62.0
        const val LOCKUP_W = 1056.0
        const val WIDE_MIDDLE = 272.0
        const val TAGLINE_BASELINE = 452f

        // app.css's light-theme tokens, the same four `OgImageController` commits to and
        // for the same reason: a share card is composited on whatever the chat client's
        // background happens to be, so it follows no reader theme.
        val PAPER: Color = Color.decode("#fdfdfc")
        val INK: Color = Color.decode("#1b1b19")
        val MUTED: Color = Color.decode("#6b6b66")
    }
}
