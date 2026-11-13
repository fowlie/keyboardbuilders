package net.keyboardbuilders.keyboardbuilders.web

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * Draws a design's layout as inline SVG. Ticket 41, ux.md §3.5.
 *
 * Third-party photos are out of v1 for legal exposure, so this is not a fallback --
 * it is the only image the product has. Inline rather than `<img>` because the
 * silhouette inherits `currentColor`, which is what makes dark mode free; an `<img>`
 * cannot see the page's text colour.
 *
 * The geometry comes from `scripts/build_silhouettes.py` in KEY UNITS, because the
 * rendered size is a decision the page makes rather than the build.
 */
@Component
class Silhouettes(
    mapper: ObjectMapper,
    @Value("classpath:seed/silhouettes.json") resource: Resource,
    @Value("classpath:seed/layout_silhouettes.json") layoutResource: Resource,
) {
    private val geometry: Map<String, Geometry> =
        resource.inputStream.use { mapper.readValue<Map<String, Geometry>>(it) }

    /** `slug -> {key count -> Geometry}`, one entry only for a design whose OWN
     *  firmware states more than one of its declared layouts as a complete shape --
     *  see [renderLayout]. Most designs have no entry here at all, which is the
     *  common and correct case, not a gap. */
    private val perLayout: Map<String, Map<String, Geometry>> =
        layoutResource.inputStream.use { mapper.readValue<Map<String, Map<String, Geometry>>>(it) }

    /**
     * `from` names the QMK record a non-QMK design borrowed its geometry from, so
     * the borrow is auditable rather than invisible. Nothing renders it yet; it
     * exists because a picture of a board should be able to say where it came from.
     */
    internal data class Geometry(
        val w: Double,
        val h: Double,
        val keys: List<List<Double>>,
        val from: String? = null,
        /** Millimetres per key unit, or null where the build could not say -- see
         *  `build_silhouettes.py`. Only [overlay] reads it: one board alone is drawn at
         *  common key scale, and that choice is ticket 41's, not this field's. */
        val mm: Double? = null,
        /** On a split, the x (in key units) between its two hands; null on anything
         *  else, and on a split whose drawing has no clear gap between them. */
        val split: Double? = null,
        /** Centres, in key units, of the board's rotary encoders that sit on no key -- a
         *  knob and nothing else, so NOT in [keys], which stays the switch count. Forgejo
         *  #49. Written only where the board's own footprints name one: empty is Unknown,
         *  never "this board has no encoder". */
        val encoders: List<List<Double>> = emptyList(),
        /** Centres of keys IN [keys] where the board also lets you fit an encoder instead
         *  of the switch -- Junco's outer bottom corners. Same Unknown rule. */
        @param:JsonProperty("key_or_encoder")
        val keyOrEncoder: List<List<Double>> = emptyList(),
    )

    /** [Geometry.encoders] and [Geometry.keyOrEncoder], for a renderer that draws [keysFor]. */
    data class Knobs(val encoders: List<List<Double>>, val keyOrEncoder: List<List<Double>>)

    /**
     * COMMON SCALE, which is the whole point (ticket 41 §1). One key unit is the same
     * number of pixels for every design on the page, so a 34-key board *looks*
     * smaller than a 42-key one. Fitting each silhouette to its box would destroy
     * the single most useful comparison this component offers, and ux.md §3.5 calls
     * that the easiest thing to get wrong.
     *
     * `cap` keeps a fullsize board from running off a result row. Past it the board
     * shrinks AS A WHOLE rather than being re-fitted, so scale stays ordinal --
     * bigger still reads as bigger, it just stops being exactly to scale.
     */
    /**
     * A design's key rectangles in KEY UNITS, or null where no geometry was measured.
     *
     * Exposed for the share-card renderer, which draws the same shapes with Java2D
     * instead of as SVG. Deliberately raw and unscaled: scaling is the caller's decision
     * here as it is in `render`, and the card breaks the common-scale rule on purpose.
     *
     * A key is four numbers, sometimes five (rotation) and rarely six (an ISO enter's
     * overhang). A caller that reads only the first four draws QMK's own rectangles,
     * which is a coarser picture rather than a wrong one -- but both current callers read
     * all six, because two pictures of one board that disagree is worse than either.
     */
    fun keysFor(slug: String): List<List<Double>>? = geometry[slug]?.keys

    /** Where [slug]'s board places an encoder, for the share-card renderer -- the same
     *  marks the page draws, so the card and the page never disagree. Empty lists where
     *  the build found none, which is Unknown and not "none". */
    fun knobsFor(slug: String): Knobs =
        geometry[slug].let { Knobs(it?.encoders.orEmpty(), it?.keyOrEncoder.orEmpty()) }

    /** Whether [slug]'s drawing (of the layout with [keys] keys, or its main one) knows its
     *  own millimetres -- what [overlay] needs on BOTH sides to draw to scale. Asked
     *  separately so a page can name the board that lacks it. */
    fun hasScale(slug: String, keys: Int? = null): Boolean = geometryFor(slug, keys)?.mm != null

    /** Whether the layout with [keys] keys (or the main one) has a drawing of its own. */
    fun isDrawn(slug: String, keys: Int? = null): Boolean = geometryFor(slug, keys) != null

    /**
     * Every layout of [slug] that has a drawing, as key counts, largest first: the main
     * picture's own count, then each one [renderLayout] holds. Empty for a design with no
     * drawing at all.
     */
    fun drawnLayouts(slug: String): List<Int> =
        (listOfNotNull(geometry[slug]?.keys?.size) + perLayout[slug]?.keys?.map { it.toInt() }.orEmpty())
            .distinct().sortedDescending()

    /** The main picture's key count: the layout an overlay draws when none is named. */
    fun defaultLayout(slug: String): Int? = geometry[slug]?.keys?.size

    /**
     * The drawing of [slug]'s [keys]-key layout, or its main picture when [keys] is null.
     *
     * A count with no drawing of its OWN is null, never the main picture standing in for
     * it: under this layout's name that would be a picture of a different arrangement.
     * The main picture does answer to its own count, since it is that layout's drawing.
     */
    private fun geometryFor(slug: String, keys: Int?): Geometry? =
        if (keys == null) geometry[slug]
        else perLayout[slug]?.get(keys.toString()) ?: geometry[slug]?.takeIf { it.keys.size == keys }

    fun render(
        slug: String,
        keyCount: Int,
        label: String,
        unit: Double,
        cap: Double,
        /** Only used when there is no geometry, to shape the placeholder. */
        halves: Int = 1,
    ): String {
        val found = geometry[slug]
            ?: return placeholder(keyCount, label, unit, cap, halves)
        return drawGeometry(found, label, unit, cap)
    }

    /**
     * Backlog #23: a design that declares SEVERAL layouts (a break-off variant, a
     * keycap option) may have more than one of them drawn -- KLOR's snap-off outcomes,
     * `discipline`'s four keycap widths -- each one a real, separate geometry
     * `scripts/build_silhouettes.py` found in the design's own firmware, not inferred
     * from another. `null` where this particular count was not one of them, same
     * meaning [render]'s placeholder gives a slug with no geometry at all: draw nothing
     * rather than a picture that might not be this layout's own shape.
     *
     * Nothing calls this yet -- the design page shows one picture today ([render],
     * always the design's largest layout). It exists so that stays a template decision
     * rather than a data one.
     */
    fun renderLayout(slug: String, keys: Int, label: String, unit: Double, cap: Double): String? =
        perLayout[slug]?.get(keys.toString())?.let { drawGeometry(it, label, unit, cap) }

    /** The drawing itself, shared by [render] and [renderLayout] -- both already have a
     *  [Geometry] in hand by the time they call this; only how they got one differs. */
    private fun drawGeometry(found: Geometry, label: String, unit: Double, cap: Double): String {
        val scale = minOf(unit, cap / found.w)
        val width = found.w * scale
        val height = found.h * scale
        return svg(
            width, height, label,
            """<g fill="currentColor">${keyShapes(found.keys, scale, keyOrEncoder = found.keyOrEncoder)}""" +
                "${knobShapes(found.encoders, scale)}</g>",
        )
    }

    /**
     * Two boards over each other -- backlog #47, the comparison ticket 41 made the
     * silhouettes for. Null when either has no drawing: a placeholder is an invented
     * shape, and a real board laid over one would read as a comparison.
     *
     * [first] is FILLED and [second] is a SOLID OUTLINE, so the two stay apart in
     * greyscale and to a colour-blind eye (`ux.md` §7.3: colour is never the only
     * carrier); colour is added by `app.css` on top. NOT DASHED, though dashed was the
     * first draft: a dashed outline is this site's sign for "geometry unknown" -- §3.5's
     * placeholder and §7.3's `--unknown` -- so it would say this board has no drawing.
     *
     * THREE DECISIONS, each reported back on [Overlay] so the page can state it rather
     * than leave a reader to assume the opposite:
     *
     *  * **To scale only when both boards have one.** Each silhouette is in its own
     *    board's key units, so a 17 mm Choc board drawn in units is 12% too big next to
     *    an MX one. Where either `mm` is unknown both are drawn in key units -- never
     *    19.05 filled in for the missing one, which would be a scale nobody measured.
     *  * **Two splits compare their left hands.** The gap between a split's halves is
     *    the drawing's choice, so two whole splits overlaid mostly compare that. Anything
     *    else -- a unibody, or a split with no clear gap -- is drawn whole.
     *  * **Hands line up at the top inner corner**, where the index finger sits, so an
     *    extra outer column shows as a pinky column sticking out rather than as every key
     *    shifted by half of one. Whole boards line up at their centres.
     */
    fun overlay(
        first: String,
        second: String,
        label: String,
        /** A layout of [first] by key count, as KLOR's Konrad is 40; null for its main picture. */
        firstKeys: Int? = null,
        secondKeys: Int? = null,
    ): Overlay? {
        val a = geometryFor(first, firstKeys) ?: return null
        val b = geometryFor(second, secondKeys) ?: return null
        val aMm = a.mm
        val bMm = b.mm
        val toScale = aMm != null && bMm != null
        val hands = a.split != null && b.split != null
        val aKeys = if (hands) a.leftHand() else a.keys
        val bKeys = if (hands) b.leftHand() else b.keys

        // Pixels per key unit. To scale, an MX key gets exactly [OVERLAY_UNIT].
        var aScale = if (toScale) aMm * OVERLAY_UNIT / MX_MM else OVERLAY_UNIT
        var bScale = if (toScale) bMm * OVERLAY_UNIT / MX_MM else OVERLAY_UNIT
        val aBox = extent(aKeys)
        val bBox = extent(bKeys)
        // The cap shrinks both boards by the SAME factor, so their ratio -- the thing
        // this picture is for -- survives it.
        val fit = minOf(1.0, DETAIL_CAP / maxOf(aBox.width * aScale, bBox.width * bScale))
        aScale *= fit
        bScale *= fit

        val width = maxOf(aBox.width * aScale, bBox.width * bScale)
        val height = maxOf(aBox.height * aScale, bBox.height * bScale)
        fun shift(box: Extent, scale: Double): Pair<Double, Double> =
            if (hands) (width - box.right * scale) to (-box.top * scale)
            else (width - (box.left + box.right) * scale) / 2 to (height - (box.top + box.bottom) * scale) / 2
        val (aX, aY) = shift(aBox, aScale)
        val (bX, bY) = shift(bBox, bScale)

        // Room for the outline's stroke, which is centred on the edge of the canvas.
        val pad = 2.0
        val stroke = fmt(maxOf(1.5, OVERLAY_UNIT * fit * 0.07))
        val body = """<g class="overlay-a" fill="currentColor" fill-opacity="0.3">""" +
            keyShapes(aKeys, aScale, aX + pad, aY + pad) + "</g>" +
            """<g class="overlay-b" fill="none" stroke="currentColor" stroke-width="$stroke">""" +
            keyShapes(bKeys, bScale, bX + pad, bY + pad) + "</g>"
        // `ux.md` §3.5: the label states the facts. Here that includes the two decisions,
        // because a screen reader has no caption beside the picture to read them from.
        val facts = "$label, ${if (hands) "left halves" else "whole boards"}, " +
            if (toScale) "to scale" else "not to scale"
        return Overlay(
            svg = svg(width + 2 * pad, height + 2 * pad, facts, body)
                .replaceFirst("""class="silhouette"""", """class="silhouette overlay""""),
            toScale = toScale,
            hands = hands,
        )
    }

    /** What [overlay] drew, and the two decisions the page has to state beside it. */
    data class Overlay(val svg: String, val toScale: Boolean, val hands: Boolean)

    private fun Geometry.leftHand() = keys.filter { it[0] + it[2] / 2 < split!! }

    /** A set of keys' bounding box in key units, a turned key by what it covers at its
     *  angle -- the same projection `build_silhouettes.left_hand_edge` makes. */
    private data class Extent(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width get() = right - left
        val height get() = bottom - top
    }

    private fun extent(keys: List<List<Double>>): Extent {
        val boxes = keys.map { key ->
            val (x, y, w, h) = key
            val turn = Math.toRadians(key.getOrNull(4) ?: 0.0)
            val cos = Math.abs(Math.cos(turn))
            val sin = Math.abs(Math.sin(turn))
            val halfW = (w * cos + h * sin) / 2
            val halfH = (w * sin + h * cos) / 2
            val cx = x + w / 2
            val cy = y + h / 2
            Extent(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        }
        return Extent(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }

    /**
     * Every key as an SVG shape at [scale] pixels per unit, moved by ([dx], [dy]) pixels.
     * Shared by the single picture and the overlay, so the two draw a key identically.
     */
    private fun keyShapes(
        keys: List<List<Double>>,
        scale: Double,
        dx: Double = 0.0,
        dy: Double = 0.0,
        /** Centers of keys the board lets you swap for an encoder; see [Geometry.keyOrEncoder]. */
        keyOrEncoder: List<List<Double>> = emptyList(),
    ): String {
        // ux.md §3.5 says "no gaps rendered below 2px", and taking that literally
        // was wrong: at the row tier the proportional gap works out to 0.72px, so it
        // dropped to zero, adjacent keys fused and the board read as mud rather than
        // as a keyboard. A GAP IS THE THING THAT MAKES KEYS LOOK LIKE KEYS, so it
        // gets a floor instead of a cutoff -- one pixel, always, even where that is
        // more than the proportional share.
        //
        // Capped at a fifth of a key so the floor cannot eat a very small silhouette:
        // a board wide enough to hit the row cap renders under 8px/unit, and there a
        // 1px gap is already an eighth of the key.
        val gap = minOf(maxOf(1.0, scale * 0.09), scale * 0.2)
        val radius = scale * 0.13

        return keys.joinToString("") { key ->
            val (x, y, w, h) = key
            // A FIFTH NUMBER, WHEN THE BOARD SUPPLIED ONE: the switch's rotation in
            // degrees. A splayed keyboard turns whole columns -- KLOR's 21 switches sit
            // at six angles -- and a firmware matrix cannot say so, so this is only ever
            // present on geometry read from a `.kicad_pcb`. Absent means zero, which is
            // what every firmware-drawn design has always meant.
            val turn = key.getOrNull(4) ?: 0.0
            // A SIXTH NUMBER, ON AN ISO ENTER AND NOTHING ELSE: how far the key's TOP
            // overhangs its bottom leg to the left, in key units. `build_silhouettes.py`
            // measures it from the board's own empty space rather than assuming a keycap
            // convention; absent, or zero, is a plain rectangle, which is every other key
            // in the corpus.
            val notch = key.getOrNull(5) ?: 0.0
            val left = x * scale + gap / 2 + dx
            val top = y * scale + gap / 2 + dy
            val width = w * scale - gap
            val height = h * scale - gap
            // Rotated about the key's OWN center, so a turned cap stays where the board
            // puts it instead of swinging away from its column.
            val spin =
                if (turn == 0.0) ""
                else """ transform="rotate(${fmt(turn)} ${fmt(left + width / 2)} ${fmt(top + height / 2)})""""
            if (notch > 0.0) {
                // THE REVERSE-L, clockwise from the top-left of the overhanging tab. The
                // step sits ONE KEY UNIT below the key's top, because that is the row
                // boundary the two legs differ across.
                //
                // Every edge insets INWARD by half a gap exactly as the rect branch does,
                // which at the notch means the tab's underside rises while the lower leg's
                // left edge moves right -- the two of them opening the notch rather than
                // both shifting one way. `left` is already the lower leg's inset edge.
                val tab = (x - notch) * scale + gap / 2 + dx
                val step = (y + 1) * scale - gap / 2 + dy
                val outline = roundedPath(
                    listOf(
                        tab to top,
                        left + width to top,
                        left + width to top + height,
                        left to top + height,
                        left to step,
                        tab to step,
                    ),
                    radius,
                )
                """<path d="$outline"$spin/>"""
            } else if (isKeyOrEncoder(key, keyOrEncoder)) {
                // A KEY OR A KNOB, Forgejo #49: the key's own outline, hollow, with a knob
                // inside it. Neither a plain key (it may not be one) nor a plain knob (it
                // holds a switch unless the builder chooses otherwise). The stroke is
                // inset by half its width so the outline covers exactly the key's area.
                val stroke = maxOf(1.0, scale * 0.08)
                """<rect x="${fmt(left + stroke / 2)}" y="${fmt(top + stroke / 2)}" """ +
                    """width="${fmt(width - stroke)}" height="${fmt(height - stroke)}" """ +
                    """rx="${fmt(radius)}" fill="none" stroke="currentColor" """ +
                    """stroke-width="${fmt(stroke)}"$spin/>""" +
                    """<circle cx="${fmt(left + width / 2)}" cy="${fmt(top + height / 2)}" """ +
                    """r="${fmt(width * KNOB_IN_KEY)}"/>"""
            } else """<rect x="${fmt(left)}" y="${fmt(top)}" """ +
                """width="${fmt(width)}" height="${fmt(height)}" """ +
                """rx="${fmt(radius)}"$spin/>"""
        }
    }

    /**
     * An encoder that sits on no key, drawn as a round knob in the 1u cell it occupies.
     * Round because a knob is, and because nothing else this site draws is: a circle
     * cannot be misread as a keycap. Same gap as a key, so it never touches a neighbour.
     */
    private fun knobShapes(centers: List<List<Double>>, scale: Double, dx: Double = 0.0, dy: Double = 0.0): String {
        val gap = minOf(maxOf(1.0, scale * 0.09), scale * 0.2)
        return centers.joinToString("") { (cx, cy) ->
            """<circle cx="${fmt(cx * scale + dx)}" cy="${fmt(cy * scale + dy)}" """ +
                """r="${fmt((scale - gap) * KNOB)}"/>"""
        }
    }

    /**
     * A closed polygon with its corners rounded, as an SVG path.
     *
     * `rx` on a `<rect>` does not reach an ISO enter, and neither does drawing two
     * overlapping rounded rects -- that leaves the seam rounded on the inside of the
     * keycap, which no keycap does.
     *
     * THE SWEEP IS PER CORNER, and that is the only real content here. Five of an ISO
     * enter's six corners are convex and one -- where the tab meets the lower leg -- is
     * REFLEX, and a reflex corner has to curve the opposite way or the notch fills in.
     * The turn direction falls out of the cross product of the incoming and outgoing
     * edges: y grows downward in SVG, so a positive cross product is a clockwise turn,
     * which is sweep flag 1.
     *
     * The radius is clamped to half of each adjoining edge so a corner cannot eat past
     * the middle of a short one. That is not defensive: at the row tier a quarter-unit
     * overhang is about 2px wide against a nominal radius of 1.04, and the two corners of
     * that little edge would otherwise cross each other.
     */
    private fun roundedPath(corners: List<Pair<Double, Double>>, radius: Double): String {
        val path = StringBuilder()
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
            val sweep = if (ux * vy - uy * vx > 0) 1 else 0
            path.append(if (i == 0) "M" else "L")
                .append(fmt(cx - ux * r)).append(' ').append(fmt(cy - uy * r))
            if (r > 0.0) path.append('A').append(fmt(r)).append(' ').append(fmt(r))
                .append(" 0 0 ").append(sweep).append(' ')
                .append(fmt(cx + vx * r)).append(' ').append(fmt(cy + vy * r))
        }
        // Closing draws the last edge back to the first corner's arc start, which is the
        // one edge no iteration emits.
        return path.append('Z').toString()
    }

    /**
     * ux.md §3.5's degradation rule: when the geometry is unknown, a dashed outline
     * SIZED TO THE KEY COUNT, never a generic keyboard icon. It has to read as "we
     * do not know this board's shape yet" rather than as a picture of a keyboard.
     *
     * Every ZMK design lands here. Ticket 41 chose QMK as the single geometry source
     * because ZMK cannot draw most of its own tree, and two renderers would make one
     * design look different depending on which drew it.
     */
    private fun placeholder(
        keyCount: Int,
        label: String,
        unit: Double,
        cap: Double,
        halves: Int = 1,
    ): String {
        // Four rows is the shape of most of the corpus; this is an outline standing
        // in for an unknown board, so it only has to be the right rough size.
        val rows = 4.0
        // A split we cannot draw is still KNOWN to be a split, so the placeholder
        // draws that many boxes with a gap. Splitness is the one shape fact we hold
        // independently of geometry, and withholding it would make the outline say
        // less than the row beside it already does.
        val cols = maxOf(1.0, Math.ceil(keyCount / rows / halves))
        val gapUnits = if (halves > 1) 1.0 else 0.0
        val totalCols = cols * halves + gapUnits * (halves - 1)
        val scale = minOf(unit, cap / totalCols)
        val width = totalCols * scale
        val height = rows * scale
        val inset = scale * 0.15
        val boxes = (0 until halves).joinToString("") { half ->
            val left = half * (cols + gapUnits) * scale
            """<rect x="${fmt(left + inset)}" y="${fmt(inset)}" """ +
                """width="${fmt(cols * scale - 2 * inset)}" height="${fmt(height - 2 * inset)}" """ +
                """rx="${fmt(scale * 0.2)}" fill="none" stroke="currentColor" """ +
                """stroke-width="${fmt(maxOf(1.0, scale * 0.06))}" """ +
                """stroke-dasharray="${fmt(scale * 0.3)}" opacity="0.35"/>"""
        }
        return svg(width, height, label, boxes)
    }

    /**
     * `role="img"` with a label that states the FACTS. ux.md §3.5: the silhouette is
     * decorative for sighted scanning but its information is not decorative, so the
     * label must not say "layout diagram".
     */
    private fun svg(width: Double, height: Double, label: String, body: String) =
        """<svg class="silhouette" viewBox="0 0 ${fmt(width)} ${fmt(height)}" """ +
            """width="${fmt(width)}" height="${fmt(height)}" role="img" """ +
            """aria-label="${escape(label)}" xmlns="http://www.w3.org/2000/svg">$body</svg>"""

    /**
     * Two decimals, then trailing zeros dropped. This is not fussiness: the index
     * inlines one SVG per design, so every wasted character is multiplied by the
     * result count. `"36.00"` to `"36"` took the 143-design page from 484 KB to
     * about a third less, and no silhouette needs sub-hundredth precision when a
     * key unit is eight pixels.
     */
    private fun fmt(value: Double): String {
        val rounded = Math.round(value * 100.0) / 100.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString()
        else String.format(java.util.Locale.ROOT, "%.2f", rounded).trimEnd('0')
    }

    private fun escape(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        /**
         * ux.md §3.5's ladder, reinterpreted as NOMINAL per ticket 41 §4: the named
         * size is what a mid-sized board renders at, and actual width varies because
         * the scale is common. 8px/unit puts a 36-key split at about 96px and a
         * fullsize at about 200.
         */
        const val ROW_UNIT = 8.0
        const val ROW_CAP = 208.0

        /** The detail page, where gaps become visible and the grid reads. */
        const val DETAIL_UNIT = 20.0
        const val DETAIL_CAP = 620.0

        /**
         * The overlay's own unit, twice the detail page's: it is the compare page's main
         * figure, and two splits show one hand each -- about seven keys across, which at
         * [DETAIL_UNIT] measured 152 px wide in Chrome on a page 832 px wide. A whole board
         * still fits, because [DETAIL_CAP] shrinks both boards together past it.
         */
        const val OVERLAY_UNIT = 40.0

        /** A standard MX key unit. The overlay draws one at [OVERLAY_UNIT] pixels. */
        private const val MX_MM = 19.05

        /** A knob's radius as a share of a drawn key's width: 0.4 is a 0.8u knob, about
         *  the 15 mm of an EC11 cap on an MX board. [KNOB_IN_KEY] is smaller so the
         *  hollow key's outline stays visible around it. */
        const val KNOB = 0.4
        const val KNOB_IN_KEY = 0.28

        /** Is [key] one of the [centers] listed as key-or-encoder? Matched on the key's
         *  own center, within a rounding step of the build's two-decimal output. Shared
         *  with the share-card renderer so both mark the same keys. */
        fun isKeyOrEncoder(key: List<Double>, centers: List<List<Double>>): Boolean {
            if (centers.isEmpty()) return false
            val (x, y, w, h) = key
            return centers.any { (cx, cy) -> Math.abs(x + w / 2 - cx) < 0.05 && Math.abs(y + h / 2 - cy) < 0.05 }
        }
    }
}
