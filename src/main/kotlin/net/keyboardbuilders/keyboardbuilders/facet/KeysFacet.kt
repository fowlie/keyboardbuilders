package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.Term

object KeysFacet : Facet(FacetKey.Keys) {
    override val heading = "Keys"
    override val openByDefault = true

    /**
     * The bands the rail offers, instead of one checkbox per value.
     *
     * THE RAIL CANNOT SHOW 85 CHECKBOXES, which is what `keys` rendered: one row per exact
     * count, 69% of the whole rail and roughly 2,000px tall, so every other facet — switch
     * footprint, controller, licence, key spacing — sat below the fold and was never found.
     * And the options were wrong even at the right length: nobody wants "69 keys".
     *
     * BANDS ARE A DISPLAY DEVICE, NOT STORAGE. The facet set (ticket 03)
     * §4(b) is explicit — store the exact count, range at query time — because the hobby
     * names genres by exact number ("36-key", "34-key", "42-key") and a stored bucketing
     * destroys that. `?keys=34` is still an exact selection and still works; these are the
     * pre-canned spans the sidebar offers on top of it.
     *
     * The boundaries are measured against the corpus and cover it with no gaps. They are a
     * product judgement rather than a derived fact, and changing them is this list and its
     * labels — nothing else, which is the whole reason the bucketing is not in the database.
     *
     * SPLIT AT 20 ON 2026-09-02, the owner's call, because `36 or fewer` had grown to **104
     * of 264 designs — 39% of the corpus in one band**, and a filter returning two-fifths of
     * everything is not filtering. Inside it the corpus is two clusters with a gap:
     *
     *     2–20 keys   43   macropads, numpads, thumb boards
     *    21–33 keys   14   thin
     *    34–36 keys   44   Sweep and Cradio at 34; 3w6 and Corne-lite at 36
     *
     * so the band was holding a 2-key macropad and a 36-key ergo split in one bucket, which
     * are not the same search. **20 rather than 18**, which the owner also weighed: only
     * three designs sit between the two cuts, so the choice is not numeric — 20 is the top of
     * the macropad grid series (2×2, 3×3, 3×4, 4×4, 4×5) and the line falls where the
     * category actually ends, while 18 is not a shape anyone builds to.
     *
     * 34–36 was NOT split out despite being 17% of the corpus on three numbers: it would
     * leave 21–33 holding 14 designs, creating a sparse band to fix a fat one. Note `50–69`
     * is now the largest at 76 and will want the same treatment once the corpus outgrows it.
     */
    override val bands = listOf(
        Term.Span(null, 20),
        Term.Span(21, 36),
        Term.Span(37, 49),
        Term.Span(50, 69),
        Term.Span(70, 89),
        Term.Span(90, null),
    )

    // The bands, in the reader's words rather than the URL's. An exact count still
    // reaches this, because `?keys=34` remains a legal filter.
    override fun word(token: String): String? = when {
        token.startsWith("-") -> "${token.drop(1)} or fewer"
        token.endsWith("-") -> "${token.dropLast(1)} or more"
        "-" in token -> token.replace("-", "–")
        token.toIntOrNull() != null -> "$token keys"
        else -> null
    }
}
