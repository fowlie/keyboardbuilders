package net.keyboardbuilders.keyboardbuilders.domain

/**
 * Parsing the search box's layout-shorthand grammar (`ux.md` §2.2) into a key-count guess.
 *
 * NOT AN EXACT-MATCH PARSER. `ux.md`'s own mock turns `3x6+3` into `shape=3x6%2B3`, an
 * exact match on [Shorthand]'s stored `{rows, cols, thumbs}` (`Design.kt`) — but that field
 * is recorded only from an explicit statement (ticket 04) and the corpus states it for
 * ZERO of 328 designs; deriving it from geometry was tried and measured at 73.5% with an
 * inverted confidence flag, which is why it is `Unknown`-by-default rather than computed.
 * Building the exact-match search would build a query that always returns nothing, on the
 * site's own flagship input. What the corpus DOES have, well populated, is [KeyCount] — so
 * this file answers a narrower, honest question: how many keys would a board matching this
 * shorthand have, so the search can filter on THAT instead.
 *
 * ROW/COLUMN ORDER DOES NOT MATTER HERE, on purpose. `ux.md`'s disambiguation heuristic
 * (rows 3–5, columns 4–7, "did you mean 6 rows × 3 columns?") exists because the STORED
 * shorthand distinguishes rows from columns. A key COUNT does not — `rows * cols` is the
 * same number either way round, so `3x6+3` and `6x3+3` derive the identical key count and
 * there is nothing to disambiguate. That heuristic is real work for a future exact-match
 * search and dead work for this one.
 *
 * SPLIT-VERSUS-UNIBODY MUST GATE THE COUNT, NOT JUST DOUBLE IT — found live, not in
 * review: searching `3x5+3` returned keypads and small unibody boards alongside real
 * splits, because the first cut OR'd both readings' key counts with no check on `Body` at
 * all. A 36-key UNIBODY board is not a match for "3x5+3 read as split" merely because the
 * arithmetic agrees — [KeyCount] alone cannot tell "two 18-key halves" from "one 36-key
 * slab", and only [Body] can. So the derived count and a `Body` requirement travel
 * together as one pair, never the count alone.
 */
data class ShorthandQuery(
    /** As the user's own grammar names it, for the interpretation line — `"3x6+3"`, not a
     *  re-derived re-spelling that could drift from what they typed. */
    val asTyped: String,
    val perHand: Int,
) {
    init {
        require(perHand > 0) { "a shorthand with no keys is not a shorthand" }
    }

    /** The two readings this notation is genuinely ambiguous between — for the
     *  interpretation line's wording, not for the query (see [Facets.forShorthand]). */
    val ifSplit: Int get() = perHand * 2
    val ifUnibody: Int get() = perHand
}

/** `3x6+3`, `3x5+3` — per-hand rows × columns, plus a flat thumb-key count. */
private val SIMPLE = Regex("""(\d+)\s*x\s*(\d+)\s*[+ ]\s*(\d+)""", RegexOption.IGNORE_CASE)

/** `5x6_2x3` — a main grid plus a separately-shaped thumb cluster, `ux.md` §2.2's
 *  "extended thumb clusters". Checked first: it would otherwise partially match [SIMPLE]
 *  on its leading `5x6`. */
private val EXTENDED = Regex("""(\d+)\s*x\s*(\d+)\s*_\s*(\d+)\s*x\s*(\d+)""", RegexOption.IGNORE_CASE)

/**
 * `null` for anything that is not this grammar — `ux.md` §2.2's own rule: "input that
 * looks like shorthand but won't parse falls back to free text," never a dead end. The
 * caller treats `null` as "run the ordinary search," exactly as if this file did not exist.
 *
 * THE WHOLE (TRIMMED) INPUT MUST BE THE GRAMMAR, not a substring of a longer search — the
 * box holds ONE of four grammars per `ux.md` §2.2's own framing ("one text box accepts
 * four kinds of input, in this precedence order"), and matching a substring would hijack
 * an ordinary free-text search that happens to contain something shaped like `2x4`.
 *
 * TOLERATES A SPACE WHERE `+` BELONGS. `ux.md` §1.3's own trap: a human-typed URL renders
 * `?q=3x6+3` as the literal characters `3x6+3`, but `application/x-www-form-urlencoded`
 * decodes a bare `+` to a space, so a pasted link commonly arrives here as `"3x6 3"`. Both
 * regexes accept `+` or a space in that slot for exactly this reason — silently fixing the
 * flagship query's most common breakage rather than teaching the encoding rule to a reader
 * who will never see it.
 */
fun parseShorthandQuery(raw: String): ShorthandQuery? {
    val text = raw.trim()
    EXTENDED.matchEntire(text)?.let { m ->
        val (rows, cols, thumbRows, thumbCols) = m.destructured.toList().map(String::toInt)
        return ShorthandQuery(text, rows * cols + thumbRows * thumbCols)
    }
    SIMPLE.matchEntire(text)?.let { m ->
        val (rows, cols, thumbs) = m.destructured
        return ShorthandQuery(text, rows.toInt() * cols.toInt() + thumbs.toInt())
    }
    return null
}

/** The one reading a request actually searches — chosen once, by [Facets.readingFor], and
 *  read by both the query (`Facets.forShorthand`) and the interpretation line, so the two
 *  can never say different things about what was searched. */
data class ShorthandReading(val keys: Int, val body: String)

/**
 * Which of [shorthand]'s two counts applies, and the `body` it is paired with — never the
 * count alone, for the reason [ShorthandQuery]'s doc comment gives.
 *
 * DEFAULTS TO SPLIT — the overwhelmingly common convention this notation was written for
 * (`ux.md` §2.3's own Corne example: "42 keys · split", not 21). The reader's OWN `body`
 * filter overrides that default when it disagrees: someone who has already ticked
 * "Unibody" and then types `3x5+3` almost certainly means the 18-key macropad reading, not
 * the 36-key split one, and forcing `split` on them would return zero for a filter
 * combination that made sense to them. Ticking BOTH, or neither, keeps the split default.
 */
fun Facets.readingFor(shorthand: ShorthandQuery): ShorthandReading {
    val onlyUnibody = of(FacetKey.Body).map { it.token }.let { "unibody" in it && "split" !in it }
    return if (onlyUnibody) ShorthandReading(shorthand.ifUnibody, "unibody")
    else ShorthandReading(shorthand.ifSplit, "split")
}

/**
 * [facets] with [reading] turned into a `keys` selection AND a matching `body` selection.
 *
 * NEVER PERSISTED TO THE URL. This is built fresh from `q` on every request and handed
 * only to the ONE query that answers it — `chips`, `rail`, `carry` and everything else
 * that renders the page still read the PARSED, unmodified [Facets], so the page shows
 * exactly the filters its own URL states. The shorthand's effect is legible in the
 * interpretation line, never as a phantom "keys"/"body" chip a bookmark never asked for.
 */
fun Facets.forShorthand(reading: ShorthandReading): Facets {
    fun merge(key: FacetKey, value: String) =
        Selection(key, of(key) + Term.Exact(FacetValue(value)))

    val replaced = groups.filterNot { it.key == FacetKey.Keys || it.key == FacetKey.Body }
    return Facets(
        (replaced + merge(FacetKey.Keys, reading.keys.toString()) + merge(FacetKey.Body, reading.body))
            .sortedBy { it.key.ordinal },
    )
}
