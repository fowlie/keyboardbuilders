package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Duration
import javax.imageio.ImageIO

/**
 * The picture that appears when a design's link is pasted into Discord or Reddit.
 *
 * A REAL PHOTO WINS WHEN ONE EXISTS (backlog #101), cropped to the frame rather than
 * drawn. The page itself already prefers the photo as its own hero image over the drawn
 * silhouette when both exist (`designs/show.kte`), so the card matching that choice is
 * the consistent answer, not the exception to the rule below. Absent a photo, the card
 * falls back to being DRAWN, NOT PHOTOGRAPHED, from the same geometry the page uses: the
 * silhouette is the most recognisable thing this catalogue owns, and a card showing the
 * shape of the keyboard lands very differently from one showing a wordmark.
 *
 * JAVA2D AND NO NEW DEPENDENCY, because the silhouette is a few dozen rounded shapes.
 * `Silhouettes` holds each key as `[x, y, w, h]` in key units and renders them as SVG
 * `<rect>`s; the same four numbers fill a `RoundRectangle2D` just as well. A
 * headless-browser screenshotter or an SVG rasteriser would be a large amount of
 * machinery to draw squares.
 *
 * The one key that is not a rectangle -- an ISO enter, which carries a sixth number for
 * the width of its overhang -- is drawn here too, as a `Path2D`. Leaving it a slab on the
 * card while the page drew its reverse-L would be two pictures of one board that
 * disagree, which is exactly the failure the `og.png` 404 rule above is written against.
 *
 * PNG AND NOT SVG. Open Graph nominally allows SVG and the crawlers that matter do not
 * render it -- Discord, Slack and Twitter all want a raster, and an SVG card usually shows
 * as nothing at all rather than as an error.
 *
 * NOT TO THE ROW'S COMMON SCALE, and this is the one place that rule is deliberately
 * broken. Everywhere else a 34-key board must look smaller than a 42-key one, because the
 * comparison is the point (ticket 41 §1). A share card is seen alone, never beside another
 * card, so there is nothing to compare it to -- and a macropad rendered to common scale in
 * a 1200x630 frame is a speck. Each card fits its own board.
 */
@Controller
class OgImageController(
    private val catalog: DesignCatalog,
    private val silhouettes: Silhouettes,
    private val renames: SlugRenames,
    private val photos: Photos,
    private val photoStore: PhotoStore,
) {

    /**
     * THE SAME THREE ANSWERS AS THE PAGE, and for the same reason: two endpoints keyed
     * by one slug that disagree about whether it exists is an inconsistency nobody
     * would predict from either one's code. Forgejo #38.
     *
     * A crawler reaching a renamed design's page is redirected there first and reads
     * the CURRENT page's `og:image`, so in the ordinary flow this redirect never fires
     * -- it is for a cached copy of the old html, which is exactly the case a permanent
     * redirect exists to serve.
     *
     * REDIRECTED RATHER THAN SERVED UNDER THE OLD SLUG, and that is the half worth
     * stating. `silhouettes.keysFor` below takes the REQUESTED slug, so resolving the
     * design and drawing it here would produce a card with no silhouette -- silently,
     * because a missing shape is a legitimate state for a design nobody has drawn. A
     * quietly wrong picture is worse than either alternative.
     */
    @GetMapping("/designs/{slug}/og.png", produces = [MediaType.IMAGE_PNG_VALUE])
    fun card(@PathVariable slug: String): ResponseEntity<ByteArray> {
        val design = catalog.bySlug(slug) ?: renames.currentFor(slug)?.let { current ->
            return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(URI.create("/designs/$current/og.png"))
                .build()
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no design with slug '$slug'")
        val png = fromPhoto(slug, W, H) ?: run {
            val row = design.toRow(silhouettes)
            draw(row.name, row.shape + row.electronics, silhouettes.keysFor(slug), silhouettes.knobsFor(slug))
        }

        return ResponseEntity.ok()
            // A day: the corpus is rebuilt on a deploy, and a stale card for a few hours
            // is a far smaller problem than re-rendering this for every crawler that
            // re-checks a link.
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
            .body(png)
    }

    /**
     * The image a SEARCH RESULT wants, and it is not the share card -- Forgejo #42.
     *
     * `og.png` is 1200x630, carries the wordmark and is designed to be read whole in a
     * chat embed; a search thumbnail is square, cropped tight, and seen at a glance.
     * Google's own guidance says to avoid a generic image such as a site logo, so this
     * one draws nothing but the silhouette -- no name, no facts, no mark.
     *
     * Square rather than resized from the card: the card's centred artwork already
     * leaves air above and below on most boards, and a square crop of it would show
     * mostly paper. This re-lays out the same geometry to fill a square frame instead.
     */
    @GetMapping("/designs/{slug}/thumbnail.png", produces = [MediaType.IMAGE_PNG_VALUE])
    fun thumbnail(@PathVariable slug: String): ResponseEntity<ByteArray> {
        catalog.bySlug(slug) ?: renames.currentFor(slug)?.let { current ->
            return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(URI.create("/designs/$current/thumbnail.png"))
                .build()
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no design with slug '$slug'")
        // NO GENERIC IMAGE. A design with neither a photo nor a drawn silhouette gets no
        // thumbnail at all rather than a blank square -- the one thing Google's guidance
        // names as worse than no image is a picture that says nothing about this specific
        // page. A photo is the opposite of generic, so it takes this square ahead of the
        // silhouette rather than behind it.
        val png = fromPhoto(slug, THUMBNAIL, THUMBNAIL)
            ?: silhouettes.keysFor(slug)?.takeIf { it.isNotEmpty() }
                ?.let { drawThumbnail(it, silhouettes.knobsFor(slug)) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no silhouette for '$slug'")

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
            .body(png)
    }

    private fun draw(
        name: String,
        facts: List<String>,
        keys: List<List<Double>>?,
        knobs: Silhouettes.Knobs = Silhouettes.Knobs(emptyList(), emptyList()),
    ): ByteArray {
        val image = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        g.color = PAPER
        g.fillRect(0, 0, W, H)

        // The site's own mark, four keys stepped into a column stagger, bottom right.
        g.color = RULE
        listOf(0.0 to 18.4, 7.7 to 13.0, 15.4 to 7.2, 23.1 to 11.2).forEach { (x, y) ->
            g.fill(RoundRectangle2D.Double(W - 150 + x * 3.4, H - 90 + y * 3.4, 21.8, 21.8, 5.0, 5.0))
        }

        if (keys != null && keys.isNotEmpty()) {
            val boardW = maxOf(keys.maxOf { it[0] + it[2] }, knobs.encoders.maxOfOrNull { it[0] + 0.5 } ?: 0.0)
            val boardH = maxOf(keys.maxOf { it[1] + it[3] }, knobs.encoders.maxOfOrNull { it[1] + 0.5 } ?: 0.0)
            // Fit the frame's drawing area, never enlarging past a sane key size: a 6-key
            // macropad scaled to fill 1200px would be six enormous slabs.
            val scale = minOf(ART_W / boardW, ART_H / boardH, 84.0)
            val ox = (W - boardW * scale) / 2
            val oy = ART_TOP + (ART_H - boardH * scale) / 2
            val gap = maxOf(2.0, scale * 0.09)
            g.color = INK
            drawKeys(g, keys, knobs, ox, oy, scale, gap)
        }

        g.color = INK
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 56)
        g.drawString(ellipsis(g, name, W - 2 * PAD), PAD.toFloat(), 96f)

        g.color = MUTED
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 30)
        g.drawString(ellipsis(g, facts.joinToString(" · "), W - 2 * PAD), PAD.toFloat(), 142f)

        g.color = RULE
        g.stroke = BasicStroke(2f)
        g.drawLine(PAD, 172, W - PAD, 172)

        g.color = MUTED
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 26)
        g.drawString("keyboardbuilders.net", PAD.toFloat(), (H - 44).toFloat())
        g.dispose()

        return encodePng(image)
    }

    /** Silhouette only, filling a square frame. Null/empty keys is the caller's job to refuse. */
    private fun drawThumbnail(
        keys: List<List<Double>>?,
        knobs: Silhouettes.Knobs = Silhouettes.Knobs(emptyList(), emptyList()),
    ): ByteArray {
        val image = BufferedImage(THUMBNAIL, THUMBNAIL, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        g.color = PAPER
        g.fillRect(0, 0, THUMBNAIL, THUMBNAIL)

        if (keys != null && keys.isNotEmpty()) {
            val boardW = maxOf(keys.maxOf { it[0] + it[2] }, knobs.encoders.maxOfOrNull { it[0] + 0.5 } ?: 0.0)
            val boardH = maxOf(keys.maxOf { it[1] + it[3] }, knobs.encoders.maxOfOrNull { it[1] + 0.5 } ?: 0.0)
            val drawable = THUMBNAIL - 2 * THUMBNAIL_PAD
            val scale = minOf(drawable / boardW, drawable / boardH)
            val ox = (THUMBNAIL - boardW * scale) / 2
            val oy = (THUMBNAIL - boardH * scale) / 2
            val gap = maxOf(2.0, scale * 0.09)
            g.color = INK
            drawKeys(g, keys, knobs, ox, oy, scale, gap)
        }
        g.dispose()
        return encodePng(image)
    }

    /**
     * The photo-based card or thumbnail for a design that has one, cropped to fill
     * `w`x`h`, or null for every case the caller should fall back on: no approved photo,
     * or a fetch that failed (the bucket unreachable, a corrupt cache entry). A fetch
     * failure here must read as "draw the silhouette instead", never as a 500 -- the
     * silhouette card already exists and is correct, it is just the second choice now.
     */
    private fun fromPhoto(slug: String, w: Int, h: Int): ByteArray? {
        val photo = photos.of(slug) ?: return null
        val bytes = photoStore.bytes(photo.jpg) ?: return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        return encodePng(frame(image, w, h))
    }

    /**
     * The photo framed to fill `w`x`h` -- cropped if its aspect ratio is close enough to
     * the frame's, letterboxed onto [PAPER] otherwise.
     *
     * BOTH STRATEGIES EXIST because photos are not curated to one aspect ratio (backlog
     * #101 sampled 0.93 to 3.1) and no single rule serves both ends of that range. A hard
     * crop of `mini-ten-key-plus`'s near-square photo against this card's 1.9:1 frame
     * keeps only 49% of the photo's height -- the split halves and the cable joining
     * them, the whole reason the photo was curated, are the first thing that crop throws
     * away. Padding with [PAPER] instead costs empty frame, which is the smaller loss.
     *
     * `MIN_VISIBLE` is measured, not guessed: sorting the current 111 photos by how much
     * of the shorter axis a crop would keep shows a clean gap between six near-square
     * photos (0.49-0.53) and everything else (0.60 and up, most well above it) -- so the
     * threshold sits in that gap rather than at a round number that might not stay clear
     * of either cluster as more photos are added.
     */
    private fun frame(source: BufferedImage, w: Int, h: Int): BufferedImage {
        val sourceAspect = source.width.toDouble() / source.height
        val targetAspect = w.toDouble() / h
        val visible = if (sourceAspect > targetAspect) targetAspect / sourceAspect else sourceAspect / targetAspect
        return if (visible < MIN_VISIBLE) letterbox(source, w, h) else crop(source, w, h)
    }

    /** Scaled up to cover the frame and centre-cropped to it -- `object-fit: cover`. */
    private fun crop(source: BufferedImage, w: Int, h: Int): BufferedImage {
        val scale = maxOf(w.toDouble() / source.width, h.toDouble() / source.height)
        val scaledW = maxOf(w, Math.round(source.width * scale).toInt())
        val scaledH = maxOf(h, Math.round(source.height * scale).toInt())
        val scaled = resize(source, scaledW, scaledH)
        return scaled.getSubimage((scaledW - w) / 2, (scaledH - h) / 2, w, h)
    }

    /** Scaled down to fit wholly inside the frame and centred on [PAPER] -- `object-fit: contain`. */
    private fun letterbox(source: BufferedImage, w: Int, h: Int): BufferedImage {
        val scale = minOf(w.toDouble() / source.width, h.toDouble() / source.height)
        val scaledW = Math.round(source.width * scale).toInt()
        val scaledH = Math.round(source.height * scale).toInt()
        val scaled = resize(source, scaledW, scaledH)
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = PAPER
        g.fillRect(0, 0, w, h)
        g.drawImage(scaled, (w - scaledW) / 2, (h - scaledH) / 2, null)
        g.dispose()
        return image
    }

    private fun resize(source: BufferedImage, w: Int, h: Int): BufferedImage {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(source, 0, 0, w, h, null)
        g.dispose()
        return image
    }

    private fun encodePng(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().use { out -> ImageIO.write(image, "png", out); out.toByteArray() }

    /**
     * Every key, and every encoder the board names, as `Silhouettes.render` draws them
     * (Forgejo #49): a key the board lets you swap for an encoder is its own outline with
     * a knob inside, and an encoder on no key is a knob alone.
     */
    private fun drawKeys(
        g: java.awt.Graphics2D, keys: List<List<Double>>, knobs: Silhouettes.Knobs,
        ox: Double, oy: Double, scale: Double, gap: Double,
    ) {
        val stroke = maxOf(2.0, scale * 0.08)
        keys.forEach { key ->
            if (!Silhouettes.isKeyOrEncoder(key, knobs.keyOrEncoder)) {
                g.fill(keyShape(key, ox, oy, scale, gap))
                return@forEach
            }
            // The outline inset by half its stroke, exactly as the SVG draws it.
            val inset = keyShape(key, ox, oy, scale, gap + stroke)
            val old = g.stroke
            g.stroke = BasicStroke(stroke.toFloat())
            g.draw(inset)
            g.stroke = old
            val (x, y, w, h) = key
            knob(g, ox + (x + w / 2) * scale, oy + (y + h / 2) * scale, (w * scale - gap) * Silhouettes.KNOB_IN_KEY)
        }
        knobs.encoders.forEach { (cx, cy) ->
            knob(g, ox + cx * scale, oy + cy * scale, (scale - gap) * Silhouettes.KNOB)
        }
    }

    private fun knob(g: java.awt.Graphics2D, cx: Double, cy: Double, r: Double) =
        g.fill(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))

    /**
     * One key: a rounded rect, or an ISO enter's reverse-L where the geometry carries a
     * sixth number, rotated about ITS OWN CENTRE where it carries a fifth.
     *
     * `Silhouettes.kt`'s own rules, repeated here rather than shared because one draws
     * SVG and the other Java2D and there is no common type between them. A rotation
     * absent means zero, which is every firmware-drawn design; `reviung41`'s own board
     * reads real column splay and drew square until that existed -- owner-reported, the
     * thumbnail matching the page's SVG for the first time. The overhang is the same
     * bargain in the other direction: it appears only on firmware-drawn geometry, since
     * a `.kicad_pcb` never states a keycap's width at all.
     */
    private fun keyShape(
        key: List<Double>, ox: Double, oy: Double, scale: Double, gap: Double,
    ): Shape {
        val (x, y, w, h) = key
        val left = ox + x * scale + gap / 2
        val top = oy + y * scale + gap / 2
        val width = w * scale - gap
        val height = h * scale - gap
        val notch = key.getOrNull(5) ?: 0.0
        val tab = ox + (x - notch) * scale + gap / 2
        val step = oy + (y + 1) * scale - gap / 2
        val shape: Shape = if (notch > 0.0) roundedPath(
            // Clockwise from the top-left of the overhanging tab, with the step one key
            // unit down. Every edge insets inward by half a gap, so at the notch the
            // tab's underside rises while the lower leg's left edge moves right.
            listOf(
                tab to top,
                left + width to top,
                left + width to top + height,
                left to top + height,
                left to step,
                tab to step,
            ),
            scale * 0.13,
        ) else
            // `RoundRectangle2D` takes arc WIDTH, which is twice the radius the path
            // helper and the SVG both take. Same corner, stated in the other unit.
            RoundRectangle2D.Double(left, top, width, height, scale * 0.26, scale * 0.26)

        val turn = key.getOrNull(4) ?: 0.0
        if (turn == 0.0) return shape
        return AffineTransform
            .getRotateInstance(Math.toRadians(turn), left + width / 2, top + height / 2)
            .createTransformedShape(shape)
    }

    /**
     * A closed polygon with its corners rounded, as a `Path2D`.
     *
     * CUBICS, NOT `Arc2D`, and the reason is the ISO enter's one REFLEX corner. Five of
     * its six corners are convex and that one is not, and a reflex corner has to curve
     * the opposite way -- bulging into the notch, tangent to both edges -- or the notch
     * fills in. Putting each corner's control points a fixed fraction along the travel
     * directions gets both cases from one expression, because those directions already
     * carry which way the outline turns; `k = 4/3 * tan(theta/4)` is the standard
     * Bezier approximation of a circular arc and is exact to a rounding error at 90
     * degrees. Verified against the tangent fillet, and against the arcs
     * `Silhouettes.render` emits for the same six corners, before either was trusted.
     *
     * The radius is clamped to half of each adjoining edge so a corner cannot eat past
     * the middle of a short one -- an overhang is a quarter unit wide, which is the
     * shortest edge either renderer draws.
     */
    private fun roundedPath(corners: List<Pair<Double, Double>>, radius: Double): Shape {
        val path = Path2D.Double()
        corners.indices.forEach { i ->
            val (px, py) = corners[(i + corners.size - 1) % corners.size]
            val (cx, cy) = corners[i]
            val (nx, ny) = corners[(i + 1) % corners.size]
            val incoming = Math.hypot(cx - px, cy - py)
            val outgoing = Math.hypot(nx - cx, ny - cy)
            if (incoming == 0.0 || outgoing == 0.0) return@forEach
            val r = minOf(radius, incoming / 2, outgoing / 2)
            val ux = (cx - px) / incoming
            val uy = (cy - py) / incoming
            val vx = (nx - cx) / outgoing
            val vy = (ny - cy) / outgoing
            val startX = cx - ux * r
            val startY = cy - uy * r
            val endX = cx + vx * r
            val endY = cy + vy * r
            if (i == 0) path.moveTo(startX, startY) else path.lineTo(startX, startY)
            val turn = Math.atan2(Math.abs(ux * vy - uy * vx), ux * vx + uy * vy)
            val k = 4.0 / 3.0 * Math.tan(turn / 4)
            path.curveTo(
                startX + ux * k * r, startY + uy * k * r,
                endX - vx * k * r, endY - vy * k * r,
                endX, endY,
            )
        }
        path.closePath()
        return path
    }

    /** Trim to fit rather than overrun the frame; a card has no wrapping. */
    private fun ellipsis(g: java.awt.Graphics2D, text: String, max: Int): String {
        if (g.fontMetrics.stringWidth(text) <= max) return text
        var cut = text
        while (cut.isNotEmpty() && g.fontMetrics.stringWidth("$cut…") > max) cut = cut.dropLast(1)
        return "$cut…"
    }

    private companion object {
        /** 1200x630 is what every crawler that matters crops to. */
        const val W = 1200
        const val H = 630
        const val PAD = 64
        const val ART_TOP = 200.0
        const val ART_W = 1000.0
        const val ART_H = 300.0

        /** The search thumbnail's frame: square, and large enough that Google will keep it. */
        const val THUMBNAIL = 630
        const val THUMBNAIL_PAD = 48.0

        /** [frame]'s crop-vs-letterbox threshold. See that function's own doc for where this number comes from. */
        const val MIN_VISIBLE = 0.55

        // app.css's own light-theme tokens. A share card has no reader theme to follow --
        // it is an image, and it is composited on whatever the chat client's background is,
        // so it commits to the light palette rather than inheriting anything.
        val PAPER: Color = Color.decode("#fdfdfc")
        val INK: Color = Color.decode("#1b1b19")
        val MUTED: Color = Color.decode("#6b6b66")
        val RULE: Color = Color.decode("#e2e2dd")
    }
}
