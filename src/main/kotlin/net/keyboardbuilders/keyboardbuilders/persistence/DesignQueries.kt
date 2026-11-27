package net.keyboardbuilders.keyboardbuilders.persistence

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.FacetValue
import net.keyboardbuilders.keyboardbuilders.domain.Facets
import net.keyboardbuilders.keyboardbuilders.domain.Selection
import net.keyboardbuilders.keyboardbuilders.domain.Sort
import net.keyboardbuilders.keyboardbuilders.domain.Term
import net.keyboardbuilders.keyboardbuilders.facet.facet
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import kotlin.random.Random

/**
 * Which designs a query selects, and — when it selects none — what to offer instead.
 * Forgejo #5.
 *
 * `design_facet` is long and thin on purpose: one row per queryable value, so a design
 * offering two switch families is two rows and both select it. That is what makes
 * OR-within-a-group a plain `IN` and AND-across-groups an intersection, with no join to
 * `design` in the hot path — `architecture.md` §3.5 measured that join at 30–40%.
 *
 * ARCHIVED IS SUNK, NOT EXCLUDED. The query model orders by stars descending with
 * archived last; a repo nobody has touched in three years is still a keyboard you can
 * build. Filtering it out would answer a question the user did not ask.
 */
@Component
class DesignQueries(private val db: JdbcClient) {

    /**
     * The slugs a query selects, in the site's ranking order, or null when nothing was
     * asked. Null and "everything matched" are different states: the first means no
     * filter was applied, and only the caller can tell them apart usefully.
     *
     * [sort] defaults to the site's long-standing order — backlog #37 — so every
     * existing caller keeps meaning what it meant before `sort` was readable at all.
     */
    fun slugsMatching(facets: Facets, text: String? = null, sort: Sort = Sort.DEFAULT): List<String>? {
        val words = terms(text)
        if (facets.isEmpty && words == null) return null
        var ids: Set<Long>? = null
        for (group in facets.groups) {
            val hits = idsFor(group)
            // AND across groups is an intersection. Done in Kotlin rather than as one
            // SQL statement because each group is a covering-index seek and the sets are
            // small; a single query with N subselects reads worse and measures the same.
            ids = ids?.intersect(hits) ?: hits
            if (ids.isEmpty()) break
        }
        // TEXT IS ANOTHER AND-ED TERM, not a separate mode. `?q=sofle&pitch=choc` is one
        // question -- the choc designs matching sofle -- so it intersects like a group.
        if (words != null && ids?.isEmpty() != true) {
            val hits = idsMatchingText(words)
            ids = ids?.intersect(hits) ?: hits
        }
        return ranked(ids ?: emptySet(), sort)
    }

    /**
     * The FTS5 expression for what a reader typed, or null if they typed nothing usable.
     *
     * MATCH IS A QUERY LANGUAGE AND A SEARCH BOX IS NOT. `"` opens a phrase, `*` is a
     * prefix operator, `OR`/`NOT`/`NEAR` are keywords, and a malformed expression does
     * not match nothing -- it THROWS, which on a box built to be typed into is a 500
     * waiting for the first person who searches for `corne (v2)`.
     *
     * So the input is never passed through. Words are extracted, quoted as literal
     * phrases so no character inside can be an operator, and given the one operator we
     * do want: a trailing `*`, because a reader types as far as they have got.
     *
     * Words AND, which is what a name search means: "sofle choc" is narrower than
     * "sofle", never wider.
     */
    private fun terms(text: String?): String? {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(text.orEmpty()).map { it.value }.toList()
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "\"$it\"*" }
    }

    /**
     * Designs whose NAME OR SLUG matches. `{hay_a}` is FTS5's column filter, and it is
     * the whole v0.5 scope: `hay_b` is the summary and `hay_c` the board and firmware
     * names, and searching those makes "corne" match designs that merely mention it.
     *
     * `rowid` IS `design_id` -- the table is declared `content_rowid = 'design_id'`.
     */
    private fun idsMatchingText(words: String): Set<Long> = db.sql(
        "SELECT rowid FROM design_fts WHERE design_fts MATCH ?"
    ).param("{hay_a} : $words").query(Long::class.java).list().filterNotNull().toSet()

    /**
     * What the reader could drop to see results, with the count each would give.
     *
     * NOT AN ERROR PATH. `ux.md` §2.6 Risk 1 makes "no results" a main path, and the
     * corpus says so too: measured over 234 designs, **38% of two-filter combinations
     * return fewer than three results** and the median is 3. A rail that treats emptiness
     * as an exception would be wrong most of the time it mattered.
     *
     * Each suggestion is the query MINUS one group, which is the smallest step back
     * toward results and the one a reader can predict. Suggestions that would still
     * return nothing are not offered — an offer that leads to another empty page is
     * worse than no offer.
     */
    fun relaxations(facets: Facets, text: String? = null): List<Relaxation> =
        facets.groups.map { dropped ->
            val rest = Facets(facets.groups.filter { it != dropped })
            // WITH the text still applied. An offer computed without it would promise a
            // count that following the link does not deliver, which is worse than no
            // offer -- the reader spends a click to arrive somewhere else empty.
            Relaxation(dropped, slugsMatching(rest, text)?.size ?: total())
        }.filter { it.count > 0 }.sortedByDescending { it.count }

    /** Dropping [group] would leave [count] designs. */
    data class Relaxation(val group: Selection, val count: Int)

    /** Every live design. Public because "drop the search" needs the count it returns to. */
    fun total(): Int =
        db.sql("SELECT count(*) FROM design_search").query(Int::class.java).single()

    /**
     * One group's matches. OR within the group, which is a plain `IN`.
     *
     * `unknown` is where the long-and-thin shape earns itself back: a design with no
     * value for a facet has NO ROW for that key, so "not recorded" is an anti-join rather
     * than a magic value stored in the table. That is why `contract.md` can call it an
     * ordinary value — it behaves like one in the URL without being one in the data.
     */
    private fun idsFor(group: Selection): Set<Long> {
        val wanted = group.values.map { it.token }
        val real = wanted.filter { it != Facets.UNKNOWN }
        val ids = mutableSetOf<Long>()
        if (real.isNotEmpty()) {
            val holes = real.joinToString(",") { "?" }
            ids.addAll(db.sql(
                "SELECT design_id FROM design_facet WHERE key = ? AND value IN ($holes)"
            ).params(listOf(group.key.param) + real).query(Long::class.java).list().filterNotNull())
        }
        // Spans OR with the exact values beside them rather than replacing them: one
        // group, one `IN` and one `BETWEEN`, unioned. `?keys=34,50-69` is a single
        // question and must not become two.
        //
        // The existential over layouts is free here, and that matters: `design_facet`
        // holds one row PER LAYOUT, so a break-off-column design with rows 36 and 42
        // matches `37-49` through its 42 and `-36` through its 36. Both are correct.
        for (span in group.spans) ids.addAll(idsWithin(group.key, span))
        if (Facets.UNKNOWN in wanted) {
            ids.addAll(db.sql(
                """SELECT design_id FROM design_search
                    WHERE design_id NOT IN
                          (SELECT design_id FROM design_facet WHERE key = ?)"""
            ).param(group.key.param).query(Long::class.java).list().filterNotNull())
        }
        return ids
    }

    /**
     * How many of [within] have a value for [key] inside [span].
     *
     * `toSet()` because `design_facet` holds one row per LAYOUT: a design offering both
     * 36 and 42 keys has two rows, and counting rows would count it twice inside a band
     * that contains both. It still counts inside `-36` AND inside `37-49`, which is why
     * `Group` carries no total.
     */
    private fun countWithin(key: FacetKey, span: Term.Span, within: Set<Long>?): Int {
        val hits = idsWithin(key, span).toSet()
        return if (within == null) hits.size else hits.intersect(within).size
    }

    /** The designs whose numeric value for [key] falls inside [span]. */
    private fun idsWithin(key: FacetKey, span: Term.Span): List<Long> = db.sql(
        """SELECT design_id FROM design_facet
            WHERE key = ? AND num IS NOT NULL AND num BETWEEN ? AND ?"""
    ).params(key.param, span.lo ?: Int.MIN_VALUE, span.hi ?: Int.MAX_VALUE)
        .query(Long::class.java).list().filterNotNull()

    /** The slugs for [ids], in [sort]'s order. */
    private fun ranked(ids: Set<Long>, sort: Sort = Sort.DEFAULT): List<String> {
        if (ids.isEmpty()) return emptyList()
        val holes = ids.joinToString(",") { "?" }
        return db.sql("SELECT design_search.slug FROM $RANKED WHERE design_id IN ($holes) ${sort.orderBy()}")
            .params(ids.toList()).query(String::class.java).list().filterNotNull()
    }

    /**
     * Every live design, in that same order.
     *
     * `slugsMatching` returns null for an empty query on purpose — "nothing was asked"
     * and "nothing matched" are genuinely different states and only the caller can tell
     * them apart. But an unfiltered page still has to print its rows in SOME order, and
     * it is this one, so it is spelled here rather than left to each caller to invent.
     * It was invented once, as `catalog.all()`, and the answer was alphabetical.
     */
    fun allRanked(sort: Sort = Sort.DEFAULT): List<String> =
        db.sql("SELECT design_search.slug FROM $RANKED ${sort.orderBy()}").query(String::class.java)
            .list().filterNotNull()

    /**
     * [allRanked], reordered by a shuffle seeded on [dayOfYear].
     *
     * SEEDED, NOT RANDOM: the caller (the home page) needs the same order for every
     * request on a given day and a different one the next, not a fresh shuffle per
     * request. Seeding `Random` on the day number gives both for free, with no table
     * recording "today's picks" to write or expire.
     *
     * Different seeds produce different permutations of the full list, which is what
     * keeps two different days from landing on the same eight designs even after the
     * caller's own filtering (repo dedup, minimum key count) narrows it down.
     */
    fun allRankedShuffled(dayOfYear: Int, sort: Sort = Sort.DEFAULT): List<String> =
        allRanked(sort).shuffled(Random(dayOfYear.toLong()))

    private companion object {
        /**
         * THE SITE'S ORDER, spelled once per [Sort] — backlog #37 widened a single
         * constant into this. `archived` is sunk FIRST on every one of them, never
         * excluded: a repo nobody has touched in three years is still a keyboard you can
         * build, and a reader choosing HOW to browse the corpus is not choosing to see
         * stale repos ahead of live ones. `name ASC` closes every clause as the same
         * final, deterministic tiebreak the pre-#37 constant used.
         *
         * SQLite sorts NULLs last under DESC, which is why [Sort.Stars] and
         * [Sort.Updated] read right without a `COALESCE`: "we have no star count" falls
         * below every known count instead of beside the genuinely unstarred, and a
         * design with no `last_commit` falls below every dated one.
         *
         * A `when` over an enum, not a map keyed by [Sort.param]: the exhaustiveness
         * check is what stops a fifth [Sort] value from reaching SQL with no clause of
         * its own, at compile time rather than as a runtime `NullPointerException`.
         */
        /** The heart totals beside each design, for [Sort.Hearts]. A LEFT JOIN on slug:
         *  `heart` never references `design` (V6), and an unhearted design has no row. */
        const val RANKED = "design_search LEFT JOIN heart ON heart.slug = design_search.slug"

        private fun Sort.orderBy(): String = "ORDER BY archived ASC, " + when (this) {
            Sort.Hearts -> "COALESCE(heart.count, 0) DESC, stars DESC, last_commit DESC, name ASC"
            Sort.Stars -> "stars DESC, last_commit DESC, name ASC"
            Sort.Name -> "name ASC"
            Sort.Keys -> "keys ASC, name ASC"
            Sort.Updated -> "last_commit DESC, name ASC"
            // `completeness` is NOT NULL DEFAULT 0 (V2 migration), so this needs no NULLs
            // handling the way `stars`/`last_commit` do -- every row states one.
            Sort.Complete -> "completeness DESC, name ASC"
        }
    }

    /** One option in the rail: its value, how many it would find, and whether it is on. */
    data class Option(val value: String, val count: Int, val selected: Boolean)

    /**
     * One group of the rail. Deliberately NOT carrying a total.
     *
     * `contract.md` §3's third rule is that counts **do not sum to the corpus size** and
     * nothing may imply they do: a design offering several alternate layouts emits
     * several `keys` rows, and 20 of ZMK's 77 records have more than one layout. Handing
     * a caller a `total` beside the options is an invitation to render "21 of 234", which
     * would be arithmetic the data does not support.
     */
    data class Group(val key: FacetKey, val options: List<Option>)

    /**
     * The rail's counts, SELF-EXCLUDED. `architecture.md` §4.3; `DECISIONS.md` §1 calls
     * this the compatibility engine itself.
     *
     * Group K's counts are computed with K's OWN filter removed. Without that, selecting
     * `pitch=choc` makes every other pitch read 0 and the group becomes a dead end: the
     * reader can never switch from choc to mx, only clear and start again. Self-exclusion
     * is what makes a rail a rail rather than a one-way ratchet.
     *
     * A SELECTED VALUE IS ALWAYS EMITTED, AND CHECKED, WHATEVER ITS COUNT — `contract.md`
     * §3, and the trap this exists to avoid. Self-exclusion and "render what has a count"
     * interact to delete the user's own checkbox: the option returns 0, is never
     * rendered, and the control representing their filter vanishes from the page. It
     * looks like a rendering bug and is an arithmetic one.
     *
     * THE TEXT IS NOT SELF-EXCLUDED, and that is the one subtlety here. Self-exclusion
     * drops a group's OWN facet so its siblings stay switchable; the search belongs to no
     * group, so it survives the exclusion and narrows every group INCLUDING the one being
     * counted. Dropping it "by symmetry" is the bug this fixes, and it would come straight
     * back wherever a facet is already selected.
     */
    fun rail(facets: Facets, text: String? = null): List<Group> = FacetKey.entries.map { key ->
        val others = Facets(facets.groups.filter { it.key != key })
        val base = narrowedToText(slugIdsMatching(others), terms(text))
        val counted = countsWithin(key, base)
        val selected = facets.of(key).map { it.token }
        val bands = key.facet.bands
        val offered = key.facet.railValues
        val options = buildList {
            if (bands.isEmpty() && offered.isEmpty()) {
                (counted.keys + selected.filter { it != Facets.UNKNOWN })
                    .distinct().sorted()
                    .forEach { add(Option(it, counted[it] ?: 0, it in selected)) }
            } else if (bands.isEmpty()) {
                // A FACET WITH A CURATED FRONT ROW. Everything it holds is emitted here —
                // `contract.md` §3's "a truncated value list SAYS SO, and the remainder
                // stated" is a rule about what the page shows, and a query layer that
                // dropped the remainder outright would leave the rail nothing to state.
                // So this emits the union and `railValues` decides only the ORDER and the
                // split; `FacetRail` puts the rest on the remainder line.
                //
                // The curated values are emitted whatever their count, like a band and
                // unlike the branch above: a two-option group that drops an option when a
                // sibling filter zeroes it is the one-way ratchet self-exclusion exists to
                // prevent, and rule 4 says disabled rather than hidden anyway.
                offered.forEach { add(Option(it, counted[it] ?: 0, it in selected)) }
                (counted.keys + selected)
                    .filter { it != Facets.UNKNOWN && it !in offered }
                    .distinct().sorted()
                    .forEach { add(Option(it, counted[it] ?: 0, it in selected)) }
            } else {
                // A NUMERIC FACET OFFERS BANDS, NOT ONE ROW PER VALUE. `keys` had 85
                // checkboxes and pushed every other facet below the fold. The bands are
                // display only: the exact counts are still in the column and `?keys=34`
                // still selects on them.
                bands.forEach {
                    add(Option(it.token, countWithin(key, it, base), it.token in selected))
                }
                // An exact count is still a legal filter, so a hand-typed or bookmarked
                // `?keys=34` must render its own checkbox, checked, beside the bands —
                // the same rule that keeps any selected value on screen at count 0.
                selected.filter { token -> token != Facets.UNKNOWN && bands.none { it.token == token } }
                    .sorted()
                    .forEach { add(Option(it, counted[it] ?: 0, true)) }
            }
            // `unknown` is ALWAYS offered, even at zero. It is a tier of the contract's
            // shape rather than a value that happens to exist, and a group that omits it
            // when the count is 0 teaches nothing where it could say "this database has
            // no gap here". Rule 4: zero-count options are disabled, not hidden.
            add(Option(
                Facets.UNKNOWN,
                unknownCount(key, base, Facets.UNKNOWN),
                Facets.UNKNOWN in selected,
            ))
        }
        Group(key, options)
    }.let { groups ->
        val reach = reachOfEachFacet()
        groups.filter { group ->
            // Never withdraw the control representing the reader's own filter.
            group.options.any { it.selected } || (reach[group.key.param] ?: 0) >= 2
        }
    }

    /**
     * How many outcomes each facet can reach ACROSS THE WHOLE CORPUS — the test of
     * whether it belongs in the rail at all.
     *
     * Every one of the 234 designs is `build_method=pcb`, so the group rendered a single
     * checkbox that took 234 designs to 234. A control that provably cannot narrow
     * anything is worse than an absent one: it looks like a filter, spends a click, and
     * teaches the reader that this rail does nothing.
     *
     * CORPUS-WIDE, NOT WITHIN THE CURRENT SELECTION, and the difference is the whole
     * point. Asked of the filtered base instead, a group vanishes the moment the reader
     * narrows far enough that the survivors share a value — filter to choc and the
     * firmware group disappears because everything left is QMK. That is a rail whose
     * shape changes under the reader's hands, hiding the fact that *all the remaining
     * designs are QMK*, which is worth knowing and is exactly what a single option at
     * its full count says. `build_method` is uniform permanently, for every reader, and
     * that is a property of the facet rather than of one query.
     *
     * `unknown` counts as one of the two: one real value plus designs that lack it is a
     * genuine choice, and it is why the tier below the divider exists.
     */
    private fun reachOfEachFacet(): Map<String, Int> {
        val corpus = total()
        return db.sql(
            """SELECT key, count(DISTINCT value), count(DISTINCT design_id)
                 FROM design_facet GROUP BY key"""
        ).query { rs, _ ->
            val absent = if (rs.getInt(3) < corpus) 1 else 0
            rs.getString(1) to rs.getInt(2) + absent
        }.list().filter { it.first != null }.associate { it.first!! to it.second }
    }

    /** How many of [within] have NO value for [key]. Zero for every ordinary value. */
    private fun unknownCount(key: FacetKey, within: Set<Long>?, value: String): Int {
        if (value != Facets.UNKNOWN) return 0
        val missing = idsFor(Selection(key, setOf(Term.Exact(FacetValue(Facets.UNKNOWN)))))
        return if (within == null) missing.size else missing.intersect(within).size
    }

    /**
     * [ids] cut down to the designs the search also matches, or [ids] unchanged when
     * nothing usable was typed.
     *
     * The null in, null out case is what keeps "no filter at all" distinct from "a filter
     * that matched nothing": a null base means the whole corpus, and only a search that
     * actually produced terms may replace it with a set.
     */
    private fun narrowedToText(ids: Set<Long>?, words: String?): Set<Long>? {
        if (words == null) return ids
        val hits = idsMatchingText(words)
        return ids?.intersect(hits) ?: hits
    }

    /** Design ids matching every group, or null for "no filter, so everything". */
    private fun slugIdsMatching(facets: Facets): Set<Long>? {
        if (facets.isEmpty) return null
        var ids: Set<Long>? = null
        for (group in facets.groups) {
            ids = ids?.intersect(idsFor(group)) ?: idsFor(group)
            if (ids.isEmpty()) break
        }
        return ids
    }

    /**
     * How many of [within] carry each value of [key]. `within` is null for the whole
     * corpus, which is not the same as an empty set and must not be conflated.
     *
     * No join to `design` to filter `status='live'`: `design_facet` holds only live
     * designs BY PROJECTION INVARIANT, and `architecture.md` §3.5 measured that join at
     * 30-40% plus a transient automatic index the planner had to build.
     */
    private fun countsWithin(key: FacetKey, within: Set<Long>?): Map<String, Int> {
        if (within != null && within.isEmpty()) return emptyMap()
        val rows = if (within == null) {
            db.sql("SELECT value, count(*) FROM design_facet WHERE key = ? GROUP BY value")
                .param(key.param)
        } else {
            val holes = within.joinToString(",") { "?" }
            db.sql(
                """SELECT value, count(*) FROM design_facet
                    WHERE key = ? AND design_id IN ($holes) GROUP BY value"""
            ).params(listOf(key.param) + within.toList())
        }
        return rows.query { rs, _ -> rs.getString(1) to rs.getInt(2) }.list()
            .filter { it.first != null }.associate { it.first!! to it.second }
    }
}
