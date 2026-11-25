package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.Design

/*
 * Backlog #47: `/designs/compare?a=…&b=…`, the page `ux.md`:576 specs -- two designs'
 * facts in one table with a "differences only" link -- with the two layouts drawn over
 * each other above it, which is what the owner filed the ticket for.
 *
 * Two boards, never more: `ux.md`:83 rules N-way comparison out of v1, and the owner
 * confirmed it on the ticket (2026-09-25). Past two, stacked outlines stop being readable.
 *
 * Built from each design's own `DesignPage`, so a fact reads here exactly as it does on
 * the design's page. A second way of describing a design would be a second place for the
 * two to drift apart.
 */

/**
 * One side of the comparison, and which of its layouts is drawn.
 *
 * [keys] is the layout named in the URL, null for the design's main picture. [layout] is
 * that layout's description for the legend, null when the design has only one -- there
 * is nothing to disambiguate. [choices] is empty unless more than one layout is DRAWN:
 * a layout with no drawing cannot be overlaid, so offering it would be a dead link.
 */
data class CompareSide(
    val name: String,
    val slug: String,
    val keys: Int? = null,
    val layout: String? = null,
    val choices: List<LayoutChoice> = emptyList(),
)

/** One layout on offer. `current` is the one drawn now, rendered as text, not a link. */
data class LayoutChoice(val label: String, val href: String, val current: Boolean)

/** One fact, side by side. `recorded = false` renders as `not recorded`, as on the design page. */
data class CompareRow(
    val label: String,
    val a: String,
    val aRecorded: Boolean,
    val b: String,
    val bRecorded: Boolean,
) {
    /**
     * ONLY TWO KNOWN VALUES CAN BE THE SAME. Two `not recorded`s are two gaps, not an
     * agreement, and hiding the row under "differences only" would claim the designs match
     * on a fact nobody holds for either. `ux.md` §5.3: Unknown is a value, not a blank.
     */
    val same: Boolean get() = aRecorded && bRecorded && a == b
}

data class ComparePage(
    val a: CompareSide,
    val b: CompareSide,
    /** Raw inline SVG, or null when either board has no drawing -- [notes] then says why. */
    val overlay: String?,
    /** What the reader needs to read the picture right: its scale and what is shown. */
    val notes: List<String>,
    /** Already filtered when [diffOnly]. */
    val rows: List<CompareRow>,
    val diffOnly: Boolean,
    /** How many rows [diffOnly] hid, so the toggle can say what it is hiding. */
    val hidden: Int,
) {
    val href: String get() = compareHref(a.slug, b.slug, al = a.keys, bl = b.keys)
    val diffHref: String get() = compareHref(a.slug, b.slug, diffOnly = true, al = a.keys, bl = b.keys)
    /** Each layout travels with its board, so swapping never changes what is drawn. */
    val swapHref: String get() = compareHref(b.slug, a.slug, diffOnly, al = b.keys, bl = a.keys)
    /** Keep one board, and its layout, and choose the other again. */
    val changeB: String get() = compareHref(a.slug, al = a.keys)
    val changeA: String get() = compareHref(b.slug, al = b.keys)
}

/** The chooser: `/designs/compare?a=…` with no second board yet. */
data class CompareChooser(
    /** Carries its [CompareSide.keys], so a layout chosen before choosing the second
     *  board survives choosing it. */
    val a: CompareSide,
    val q: String,
    /** Search results for [q], or "boards like this" when nothing was typed. */
    val candidates: List<CompareCandidate>,
    /** True when the reader asked to compare a board with itself. */
    val sameTwice: Boolean,
)

/** One board on offer: the card it has everywhere else, where it leads, and why it is here. */
data class CompareCandidate(val card: DesignCard, val href: String, val reason: String?)

/**
 * The one spelling of a compare URL: `a`, `b`, `al`, `bl`, `diff`, always in that order,
 * so one comparison has one URL (`ux.md` §1.1). `al`/`bl` are layouts by key count and are
 * left out for a design's main picture. Slugs are `[a-z0-9-]` by construction, so nothing
 * needs escaping; the template escapes `&` in the attribute.
 */
internal fun compareHref(
    a: String,
    b: String? = null,
    diffOnly: Boolean = false,
    al: Int? = null,
    bl: Int? = null,
): String =
    "/designs/compare?a=$a" + (b?.let { "&b=$it" } ?: "") +
        (al?.let { "&al=$it" } ?: "") + (bl?.let { "&bl=$it" } ?: "") +
        (if (diffOnly) "&diff=1" else "")

/**
 * [design] as one side, drawing its [keys]-key layout (null: the main picture).
 *
 * [drawn] and [default] are `Silhouettes.drawnLayouts`/`defaultLayout`; [hrefFor] builds
 * the URL with this side's layout swapped for another, which only the caller can do
 * because it holds the other side. The default layout's choice links WITHOUT a key count,
 * so the picker never produces a URL that 301s.
 */
internal fun compareSide(
    design: Design,
    keys: Int?,
    drawn: List<Int>,
    default: Int?,
    hrefFor: (Int?) -> String,
): CompareSide {
    val layouts = design.core.layouts.sortedByDescending { it.keys.value }
    val shown = keys ?: default
    val choices = layouts.filter { it.keys.value in drawn }.map {
        val count = it.keys.value
        LayoutChoice(it.describe(), hrefFor(count.takeIf { c -> c != default }), current = count == shown)
    }
    return CompareSide(
        name = design.core.name.value,
        slug = design.core.slug.value,
        keys = keys,
        layout = layouts.takeIf { it.size > 1 }?.firstOrNull { it.keys.value == shown }?.describe(),
        choices = choices.takeIf { it.size > 1 }.orEmpty(),
    )
}

/**
 * The page, from both designs' own pages and the overlay drawn for them.
 *
 * [aDrawn]/[bDrawn] and [aScaled]/[bScaled] are asked separately from [overlay] so the
 * notes can NAME the board that lacks a drawing or a scale, rather than saying
 * "one of them" and leaving a reader to guess which.
 */
internal fun comparePage(
    a: DesignPage,
    b: DesignPage,
    aSide: CompareSide,
    bSide: CompareSide,
    overlay: Silhouettes.Overlay?,
    aDrawn: Boolean,
    bDrawn: Boolean,
    aScaled: Boolean,
    bScaled: Boolean,
    eitherSplit: Boolean,
    diffOnly: Boolean,
): ComparePage {
    val all = rows(a, b)
    val shown = if (diffOnly) all.filterNot { it.same } else all
    return ComparePage(
        a = aSide,
        b = bSide,
        overlay = overlay?.svg,
        notes = if (overlay == null) {
            listOf(aSide to aDrawn, bSide to bDrawn).filterNot { it.second }.map { (side, _) ->
                if (side.keys == null) "No overlay: ${side.name}’s layout has not been drawn."
                else "No overlay: ${side.name}’s ${side.layout ?: "${side.keys}-key"} layout has not been drawn."
            }
        } else listOf(
            if (overlay.toScale) "To scale, from each board’s own key spacing."
            else "Not to scale: no key spacing is measured for " +
                listOf(aSide to aScaled, bSide to bScaled).filterNot { it.second }
                    .joinToString(" or ") { it.first.name } +
                ", so both are drawn with keys the same size.",
            when {
                overlay.hands -> "Left halves only, lined up at their top inner corner."
                eitherSplit -> "Whole boards, lined up at their centers. " +
                    "The gap between a split’s halves is the drawing’s, not the board’s."
                else -> "Whole boards, lined up at their centers."
            },
        ),
        rows = shown,
        diffOnly = diffOnly,
        hidden = all.size - shown.size,
    )
}

/**
 * Every fact either page prints, in [a]'s order, with anything only [b] has appended.
 * A label one side lacks is `not recorded` on that side rather than a missing cell.
 */
private fun rows(a: DesignPage, b: DesignPage): List<CompareRow> {
    val left = lines(a).byLabel()
    val right = lines(b).byLabel()
    val labels = (left.keys + right.keys).distinct()
    return labels.map { label ->
        val x = left[label]
        val y = right[label]
        CompareRow(
            label = label,
            a = x?.value ?: NOT_RECORDED,
            aRecorded = x?.recorded ?: false,
            b = y?.value ?: NOT_RECORDED,
            bRecorded = y?.recorded ?: false,
        )
    }
}

/**
 * A page's facts by label. Layouts first: it is what the picture above is of.
 *
 * The licence rows get " license" added. On the design page they sit under their own
 * heading, but this table has none, and "Firmware: MIT" replaced "Firmware: QMK" here
 * (owner, 2026-11-25). A label that still repeats fails loudly rather than losing a row.
 */
private fun lines(page: DesignPage): List<SpecLine> =
    (listOf(SpecLine("Layouts", page.layouts.joinToString("; ") { it.label })) + page.spec +
        page.stars + page.licenses.map { it.copy(label = "${it.label} license") })
        .also { all ->
            val repeated = all.groupBy { it.label }.filterValues { it.size > 1 }.keys
            check(repeated.isEmpty()) { "compare rows repeat a label, and one would be lost: $repeated" }
        }

private fun List<SpecLine>.byLabel(): Map<String, SpecLine> = associateBy { it.label }
