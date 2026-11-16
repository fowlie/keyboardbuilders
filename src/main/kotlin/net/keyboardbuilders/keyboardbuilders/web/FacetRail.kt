package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.Facets
import net.keyboardbuilders.keyboardbuilders.facet.Facet
import net.keyboardbuilders.keyboardbuilders.facet.facet
import net.keyboardbuilders.keyboardbuilders.persistence.DesignQueries
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The filter rail, as the template receives it. Forgejo #7, `contract.md` §3.
 *
 * A facet's heading, labels and order are its own ([Facet]); this file arranges them into
 * the three tiers. The template receives strings and prints them: jte can branch, but then
 * exhaustiveness moves into a file the Kotlin compiler only sees after code generation.
 */

/** One checkbox: what it says, what it costs, and whether it can be clicked. */
data class RailOption(
    val value: String,
    val label: String,
    val count: Int,
    val selected: Boolean,
    /** The URL that toggles this option, preserving every other group. */
    val href: String,
) {
    /**
     * DISABLED, NOT HIDDEN — `contract.md` §3 rule 4. Hiding zero-count options leaves
     * headed cards with nothing inside, which teach nothing and read as a rendering
     * failure. A disabled row says "this exists and your other filters exclude it",
     * which is a fact worth having.
     *
     * A selected option is never disabled however small its count, or the control
     * representing the reader's own filter becomes unclickable and they cannot undo it.
     */
    val disabled: Boolean get() = count == 0 && !selected
}

/**
 * One group of the rail, in three tiers.
 *
 * THE DIVIDER IS THE DESIGN. It separates facts about the keyboard from facts about our
 * database: everything above is something a design is, everything below is something we
 * failed to record. Conflating them is how a database quietly teaches people that a gap
 * in its own coverage is a property of the world.
 */
data class RailGroup(
    val key: String,
    val heading: String,
    /** Above the divider: real values, then `not_applicable` in the facet's own words. */
    val known: List<RailOption>,
    /**
     * THE STATED REMAINDER — `contract.md` §3: *"A truncated value list says so. Top-8-by-
     * count, chosen values never cut, and the remainder stated."*
     *
     * Empty for every facet that shows everything it has. Non-empty only where
     * [Facet.railValues] curates a front row, and then it holds exactly what was left
     * out: for `ext`, the four pointing-and-noise-making parts too sparse for a
     * checkbox (#41) plus "No optional hardware", which is a real answer with the largest
     * count of all and still not something a reader shops by.
     *
     * These are LINKS, not checkboxes. The distinction is the point: a checkbox says "this
     * is one of the questions we expect you to ask", a link says "this exists and here it
     * is". Rendering the remainder as more checkboxes would undo #41's finding, and
     * dropping it silently is what this field exists to stop.
     */
    val more: List<RailOption>,
    /** Below the divider. Excluded by default, one click to include. */
    val unrecorded: RailOption?,
    /**
     * Whether the rail draws this group expanded. A group holding the reader's own
     * filter is never collapsed, whatever the facet's default says — hiding an applied
     * filter is the ratchet `contract.md` §3 rules against.
     */
    val openByDefault: Boolean,
)

/** Build the rail the template renders, from the counts the query layer produced. */
fun railFor(
    groups: List<DesignQueries.Group>,
    facets: Facets,
    text: String? = null,
    sort: String? = null,
): List<RailGroup> =
    groups.map { group ->
        val options = group.options.map { it.toOption(group.key, facets, text, sort) }
        RailGroup(
            key = group.key.param,
            heading = group.key.facet.heading,
            // `not_applicable` is an ORDINARY VALUE and sits above the divider, but last
            // among its peers: it answers the question by saying the question does not
            // apply, which reads oddly in the middle of a list of answers.
            // BY COUNT, EXCEPT WHERE THE ORDER IS THE MEANING. A numeric facet's bands
            // read `36 or fewer, 37-49, 50-69, ...`; sorting those by popularity would
            // shuffle a scale into a leaderboard and make the group unreadable.
            // A HAND-CURATED LIST IS AN ORDER TOO, which the band exemption already says
            // and this did not. `railValues` names encoder before display because 84
            // beats 38 across the corpus, and re-sorting by the CURRENT count throws that
            // away the moment a filter narrows: `?licence=beerware` rendered
            // `Display 1, Encoder 0`, so the pair swaps places as the reader types. The
            // rail's own vocabulary should not reorder itself underneath them.
            //
            // A CHOSEN VALUE IS NEVER CUT — §3 again — so a selected remainder value is
            // promoted out of the remainder and rendered as a checkbox like any other.
            known = options.filter { it.value != Facets.UNKNOWN }
                .let { shown ->
                    val curated = group.key.facet.railValues
                    if (group.key.facet.bands.isNotEmpty()) shown
                    else if (curated.isNotEmpty())
                        shown.filter { it.value in curated || it.selected }
                    else shown.sortedWith(
                        compareBy({ it.value == Facets.NOT_APPLICABLE }, { -it.count })
                    )
                },
            more = group.key.facet.railValues.takeIf { it.isNotEmpty() }?.let { curated ->
                options.filter {
                    it.value !in curated && !it.selected &&
                        it.value != Facets.UNKNOWN && it.count > 0
                }.sortedByDescending { it.count }
            }.orEmpty(),
            unrecorded = options.firstOrNull { it.value == Facets.UNKNOWN },
            openByDefault = group.key.facet.openByDefault || options.any { it.selected },
        )
    }

private fun DesignQueries.Option.toOption(
    key: FacetKey,
    facets: Facets,
    text: String?,
    sort: String?,
) = RailOption(
    value = value,
    label = key.facet.label(value) ?: value,
    count = count,
    selected = selected,
    href = key.toggled(value, facets, text, sort),
)

/**
 * The URL with this value flipped on or off, EVERY OTHER PART OF THE QUERY UNTOUCHED.
 *
 * That includes the free-text search, and it did not: this function is built from
 * `facets` alone, so clicking any rail option silently discarded whatever the reader
 * had typed. Reported from the running site -- search "cantor", click "36 or fewer",
 * and the search chip vanishes while the result set WIDENS, from a control that looks
 * like it only narrows.
 *
 * The chip's remove link and the zero-recovery offers had the same defect and were
 * fixed together; this one is built here rather than in the controller and was missed.
 * A URL is the truth of what is being shown, and a link that drops half the question
 * makes the page answer something the reader did not ask.
 */
private fun FacetKey.toggled(value: String, facets: Facets, text: String? = null, sort: String? = null): String {
    val current = facets.of(this).map { it.token }.toSet()
    val next = if (value in current) current - value else current + value
    val groups = facets.groups.filter { it.key != this }
        .associate { it.key.param to it.terms.map { term -> term.token } }
        .toMutableMap()
    if (next.isNotEmpty()) groups[param] = next.sorted()
    val query = queryString(groups, text, sort)
    return if (query.isEmpty()) "/designs" else "/designs?$query"
}

/**
 * `key=value&...`, sorted by key, then `q=` and finally `sort=` (backlog #37) if there
 * are any — the shared shape [toggled] and [pageQuery] both build. Neither ever emits
 * `page`: [toggled] answers "what does a rail click carry", and pagination is not a rail
 * click. Extracted rather than duplicated because the two callers drifting apart on
 * quoting or ordering would be a defect only a byte-for-byte diff of two URLs would ever
 * surface.
 *
 * `sort` IS CARRIED VERBATIM, NEVER RE-VALIDATED. By the time a template builds a link,
 * the controller has already refused an unreadable `sort` with a 400 — this function
 * only ever sees a value that request already survived, so it has nothing left to
 * check and every reason to preserve exactly what the reader asked for.
 */
private fun queryString(groups: Map<String, List<String>>, text: String?, sort: String? = null): String {
    val parts = groups.entries.sortedBy { it.key }
        .map { "${it.key}=${it.value.joinToString(",")}" }
        .toMutableList()
    if (!text.isNullOrBlank()) {
        parts += "q=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
    }
    if (!sort.isNullOrBlank()) {
        parts += "sort=$sort"
    }
    return parts.joinToString("&")
}

/**
 * The current query, unpaginated — backlog #9. A `<nav class="pages">` link appends
 * `&page=N` to this (or, on an unfiltered listing, this is empty and the link is
 * exactly `page=N`), which is what stops a paginated, filtered URL from ever BEGINNING
 * `page=` — the invariant `SeoController`'s `Allow: /designs?page=` already assumes.
 *
 * Before this existed, `index.kte`'s pager was the literal string `/designs?page=N`
 * regardless of the query, so paging forward from a search or a filtered list silently
 * dropped it: the reader asked for page 2 of their result and got page 2 of the whole
 * catalogue instead. `sort` (backlog #37) carries the same way for the same reason —
 * paging past a sorted list must not silently reset it to the default order.
 */
fun pageQuery(facets: Facets, text: String? = null, sort: String? = null): String =
    queryString(
        facets.groups.associate { it.key.param to it.terms.map { term -> term.token } },
        text,
        sort,
    )
