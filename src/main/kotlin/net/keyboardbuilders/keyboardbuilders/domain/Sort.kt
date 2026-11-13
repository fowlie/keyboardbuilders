package net.keyboardbuilders.keyboardbuilders.domain

/**
 * How a list of designs is ordered — backlog #37.
 *
 * `?sort=` used to be accepted and silently ignored: [Facets.parse] already treats it as
 * a query parameter no facet owns, and until now nothing else read it either — a 200 that
 * looked like it worked and did not, "tolerated, not honoured" in the ticket's own words.
 *
 * FOUR VALUES, NOT A FREE STRING, for the same reason a facet value is never a bare
 * caller string reaching SQL: `stars`, `name`, `keys` and `updated` are also the only
 * columns `DesignQueries` knows how to order by, so this allowlist and that ORDER BY
 * fragment cannot drift into two different vocabularies for the same parameter.
 */
enum class Sort(val param: String) {
    /** The site's long-standing default. Every URL that never mentioned `sort` keeps
     *  meaning exactly what it meant before this type existed. */
    Stars("stars"),

    /** The only way to find something alphabetically without the search box. */
    Name("name"),

    /** "Show me the small ones" is a real browsing move, and key count is already
     *  projected into `design_search` for every design. */
    Keys("keys"),

    /** Most recently pushed, sunk-ranking archived repos exactly as the default does. */
    Updated("updated"),

    /** Backlog #46: "how well is this design represented" as a browsing order, not just a
     *  per-page fact — ux.md §2.3's own plan for it. Most-filled-in records first; the
     *  opposite direction (a to-do list of stubs) is `ux.md`'s own `sort=complete` plus a
     *  `quality=stub` facet, and that facet does not exist in this build yet. */
    Complete("complete");

    companion object {
        val DEFAULT = Stars

        /**
         * `sort` read the way every other query parameter is — REFUSED, not silently
         * defaulted, on a value this build does not know. Falling back quietly would be
         * the same "looks like it worked" failure this type exists to close, wearing a
         * different value.
         */
        fun of(param: String?): Sort {
            if (param.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.param == param } ?: throw UnreadableQuery(
                "`sort=$param` is not a value this build reads; sort is one of " +
                    entries.joinToString(", ") { it.param }
            )
        }
    }
}
