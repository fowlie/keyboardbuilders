package net.keyboardbuilders.keyboardbuilders.domain

/**
 * The query model's vocabulary. Forgejo #4, `contract.md` §1.
 *
 * ONE LIST, READ BY BOTH SIDES. `facets()` is a product contract with a test behind it
 * (the facet set (ticket 03) §6):
 * a facet in the filter rail must be a key the projection emits. Before this, the
 * importer hard-coded its key strings and a parser would have hard-coded its own — which
 * is precisely how the rail and the query drift apart, and this project has had that
 * failure once already, with 11 unprojected facets.
 */
enum class FacetKey(val param: String, val numeric: Boolean = false) {
    Body("body"),

    /**
     * What joins a split's halves — backlog #97, the owner's own request, 2026-09-25:
     * some builders avoid TRRS because unplugging it while powered can damage a
     * controller, and want to leave it out. Directly below `body` because it is the
     * same question asked one level down.
     *
     * EVERY DESIGN ANSWERS. A one-piece board projects `one_piece` rather than falling
     * into "Not recorded" beside the splits nobody read: it has no link, which is a
     * fact, and "no TRRS" is exactly what the reader leaving TRRS out wants. A split
     * projects one row per connector it offers (Stront MX-38 is both `trrs` and
     * `usb_c`), and nothing where its board places none this lexicon reads -- that
     * split is "Not recorded", because the scan's silence is not an absence. It had a
     * `none` value until 2026-09-29; `FacetValueRenames` sends `link=none` there.
     *
     * Measured 2026-09-25 over the 329-design seed: 205 one-piece; of 124 splits, 108
     * TRRS (4 of them also USB-C), 6 Ethernet, 2 USB-C alone, 8 the scan named nothing on.
     */
    Link("link"),
    Keys("keys", numeric = true),

    /**
     * Does a hand's finger columns fan out from one another, thumb cluster excluded —
     * the owner's own request, 2026-09-23. Placed beside `body` and `keys` because it
     * answers the same class of question, "what shape is this keyboard", before the
     * rail moves on to switches.
     *
     * A GENUINE `yes`/`no`, unlike `ext` below on the same rail:
     * [DesignExtras.splay]'s own doc comment has the reason — this is read off where a
     * design's own board actually places every switch, not a footprint-name lexicon
     * that can only prove presence. So `no` is a measured answer, not a gap.
     *
     * Measured 2026-09-23 over the 328-design seed: 26 splayed, 177 measured not
     * splayed, 125 with no verdict (no attributed board, or a board whose key count
     * matches no layout the design publishes).
     */
    Splay("splay"),

    /**
     * Ortholinear, row-staggered or column-staggered — [DesignExtras.stagger]'s own doc
     * comment has the two fitted signals. Placed beside `splay` for the same reason
     * `splay` sits beside `body` and `keys`: it answers the same "what shape is this
     * keyboard" question, before the rail moves on to switches.
     *
     * A GENUINE three-way answer, the same case `splay` makes for itself: read off
     * where a design's own board actually places every switch, not a footprint-name
     * lexicon that can only prove presence.
     *
     * Measured 2026-09-24 over the 328-design seed: 70 ortho, 33 row-staggered, 102
     * column-staggered, 123 with no verdict (no attributed board, a board whose key
     * count matches no layout the design publishes, the design's own board files
     * disagreeing with one another, or an owner override ruling that none of the three
     * kinds fits -- `touhoupad-v2`, a hand-shaped gamepad rather than a keyboard grid).
     */
    Stagger("stagger"),
    SwitchFootprint("switch_footprint"),

    /**
     * Which hotswap parts fit a design's switch positions — the builder's second switch
     * question, after what shape the switch is. DIRECTLY BELOW `switch_footprint` because
     * `FacetKey.entries` IS the rail's order (`DesignQueries.rail`).
     *
     * THE PARTS, NOT THE PADS (owner, 2026-10-02): `any`, `kailh`, `gateron`, `millmax`,
     * so a builder ticks what they hold. Derived from [DesignExtras.switchMounts] through
     * [HotswapPart]. `no` is a solder-only board, a measured answer read off the pads, so
     * it is a value rather than "Not recorded".
     *
     * Replaced `switch_socket` (`hotswap`/`millmax`); [FacetValueRenames] 301s the old URLs.
     */
    Hotswap("hotswap"),
    ControllerFootprint("controller_footprint"),
    BuildMethod("build_method"),
    Firmware("firmware"),

    /**
     * The Tier-1 "can I build it?" gate `product.md` §1 names alongside `requires_3d_print`
     * — backlog #18's `hardestComponent` finally gives it an honest answer. Boolean rather
     * than the four raw tiers: `smd_passive` is 5 designs, below the sparsity line `Ext`
     * already declined to give rail rows (backlog #41), and the tiers themselves are a
     * builder's SECOND question, answered on the design page's "Soldering" line, not the
     * rail's job of narrowing a list.
     *
     * Measured 2026-09-11 over the 328-design seed: yes 215, no 95, unknown 18 — nowhere
     * near one-click dead on either side.
     */
    SmdRequired("smd_required"),

    /**
     * What link does this keyboard use? **The only DERIVED facet** -- a join between the
     * design and the controller catalogue, computed in `SeedImporter.writeFacets` rather
     * than read off a field, because for a socketed design the answer belongs to the
     * (design, controller) pair and not to the design (`Connectivity.kt`).
     *
     * RENAMED FROM `Wireless("wireless")` ON 2026-09-10, owner-raised: *"I do not like
     * the query param `wireless=wireless`, it smells like bad design"*. He was right, and
     * the tautology had a cause -- the facet was named after a verdict rather than after
     * the thing, which also made `maybe` look like a legitimate value. Named for the
     * thing, `wireless`, `wireless_needs_firmware` and `wired_only` are the answers and
     * an unestablished one projects NO ROW, landing in the ordinary "Not recorded" tier.
     *
     * THREE VALUES SINCE #98 (2026-09-16), and the middle one is the ticket: a socket that
     * takes a wireless controller no firmware we have a record of can run. It is not
     * merged into `wireless` (a searcher would get 135 designs needing firmware work,
     * unflagged) and not left inside `wired_only` (which told them a lie). `bluetooth` was
     * renamed to `wireless` in the same change -- `Connectivity.facetValue` has the two
     * reasons, and `FacetValueRenames` keeps the old URL resolving.
     */
    Connectivity("connectivity"),
    License("license"),
    Pitch("pitch"),

    /**
     * What a design makes room for — `ux.md` §1.3's "Extensions & ports", backlog #66.
     * A PRESENCE facet: a row means we found evidence, and no row means we found none,
     * which is ordinary ignorance and lands in the "Not recorded" tier like any other.
     *
     * `ext` IS THE DOCUMENTED PARAM, not a new coinage — `ux.md` §1.3 spells
     * `?ext=encoder&ext=trackball` and §2.4 groups it as "Extensions & ports". It shipped
     * briefly as `fittings` on 2026-09-07 and was renamed the same day, before deploy,
     * because §0's "the product is the URLs" makes a rename cheap now and dear later.
     */
    Ext("ext"),

    /**
     * Parts laid out on top where the finished keyboard shows them, owner-requested
     * 2026-10-04 -- [DesignExtras.exposedComponents]. A measured yes/no like `splay`.
     *
     * Measured 2026-10-04 over the 338-design seed: 27 yes, 232 no, 79 not recorded (after four owner rulings).
     */
    ExposedComponents("exposed_components"),

    /**
     * An on/off switch on the board, owner-requested 2026-10-04 --
     * [DesignExtras.powerSwitch]. A measured yes/no like `splay`.
     *
     * Measured 2026-10-04 over the 338-design seed: 35 yes, 300 no, 3 not recorded.
     */
    PowerSwitch("power_switch"),
    ;

    /**
     * The values this facet offers in the rail, or empty for "whatever the corpus has".
     *
     * The same device as [bands] and for the same reason: what is stored and what is
     * offered are different questions. All six extensions are projected and every one is a
     * legal filter, but speaker (6), haptic (5), trackball (2) and trackpad (1) are
     * exactly the sparsity backlog #41 measured and declined to spend rail rows on.
     * Encoder at 84 designs and display at 38 are real filters; the other four belong on
     * the design's own page.
     *
     * A value left out here is still selectable by URL and still renders its own checkbox
     * when selected — `DesignQueries.rail` keeps that promise for bands already, and it
     * is the same promise.
     */
    val railValues: List<String> get() = when (this) {
        /*
         * CURATION IS NOT OPTIONAL FOR THIS ONE, unlike `ext` where it drops sparse
         * values. All three are offered; the list exists to fix their ORDER.
         *
         * Without it `railFor` sorts a group by descending count, which would put
         * "Wired only" (282) above "Bluetooth" (35) and bury the answer a reader came
         * for under the one they did not. These three are a scale, so
         * they read as one: yes, then unsure, then no.
         */
        /*
         * Ordered, not count-sorted. `railFor` sorts a group by descending count, which
         * would put "Wired only" (282) above "Bluetooth" (35) and bury the answer a
         * reader came for under the one they did not.
         */
        /*
         * A SCALE, so it reads top-down as yes / yes-with-work / no. Count-sorting would
         * put "Wired only" (148) first, then "Wireless, needs firmware" (135), and bury
         * plain "Wireless" (37) at the bottom -- the answer a reader came for, under two
         * they did not.
         */
        Connectivity -> listOf("wireless", "wireless_needs_firmware", "wired_only")
        // Same call as `Connectivity`: count-sorting would put "yes" (215) above "no"
        // (95) and bury the answer the Tier-1 "can I build it?" reader came for under
        // the one they did not.
        SmdRequired -> listOf("no", "yes")
        Ext -> listOf("encoder", "display")
        // The owner's order, 2026-10-02. `no` (solder only) sits on the remainder line:
        // a real answer, like "No optional hardware", and not something a reader shops by.
        Hotswap -> listOf("any", "kailh", "gateron", "millmax")
        // Same call as `SmdRequired`: count-sorting would put "Not exposed" (232) above
        // "Exposed" (27), the answer a reader opens this group for.
        ExposedComponents -> listOf("yes", "no")
        // Same call: "no" (300) would bury "yes" (35), the answer a reader came for.
        PowerSwitch -> listOf("yes", "no")
        else -> emptyList()
    }

    companion object {
        fun of(param: String): FacetKey? = entries.firstOrNull { it.param == param }
    }
}

/**
 * The bands a numeric facet offers in the rail, instead of one checkbox per value.
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
val FacetKey.bands: List<Term.Span> get() = when (this) {
    FacetKey.Keys -> listOf(
        Term.Span(null, 20),
        Term.Span(21, 36),
        Term.Span(37, 49),
        Term.Span(50, 69),
        Term.Span(70, 89),
        Term.Span(90, null),
    )
    else -> emptyList()
}

/** A query asked for something this build cannot answer. The caller turns it into a 400. */
class UnreadableQuery(message: String) : IllegalArgumentException(message)

/**
 * One value in a facet's list. `unknown` is one of these and NOT a mode — `contract.md`
 * §1 is explicit: *"There is no `include_unknown` parameter. 'Include the not-recorded
 * ones' is `unknown` appended to that group's value list, which is plain OR-within-group."*
 * So it needs no special case here, and that is the point.
 */
@JvmInline
value class FacetValue(val token: String) {
    init {
        require(TOKEN.matches(token)) { "not a facet value: \"$token\"" }
    }

    override fun toString() = token

    companion object {
        /**
         * Lower-case, because the values the projection writes are slugs and a URL that
         * differs only by case would be a second address for one page. `-` is allowed
         * because a bespoke licence slugs to one.
         */
        private val TOKEN = Regex("[a-z0-9][a-z0-9_-]*")
    }
}

/**
 * One term in a facet's list.
 *
 * A GROUP HOLDS VALUES AND SPANS TOGETHER — the query model (ticket 09)
 * §6, and it is explicit that this is not `Value | Range` *per selection* but a per-facet
 * set that may contain both. `?keys=34,50-69` is one group, plain OR within it.
 */
sealed interface Term {
    /** How this term is spelled in a URL. The URL is the truth of what is being shown. */
    val token: String

    data class Exact(val value: FacetValue) : Term {
        override val token: String get() = value.token
    }

    /**
     * `lo..hi` over a numeric facet's `num` column, either end open.
     *
     * A SPAN AND NOT AN EXPANDED `IN` LIST, which was the leading candidate until the URL
     * was examined: `?keys=38,40,42,44,46,48` freezes the corpus at click time, so adding
     * a 39-key design next month makes an old bookmark silently exclude it while still
     * displaying "37–48". `?keys=37-48` means 37–48 permanently.
     */
    data class Span(val lo: Int?, val hi: Int?) : Term {
        init {
            require(lo != null || hi != null) { "a span open at both ends is not a filter" }
            require(lo == null || hi == null || lo <= hi) { "a span that cannot match: $lo-$hi" }
        }

        override val token: String get() = "${lo ?: ""}-${hi ?: ""}"
    }
}

/** One group: a facet and the terms OR-ed within it. */
data class Selection(val key: FacetKey, val terms: Set<Term>) {
    init {
        require(terms.isNotEmpty()) { "a selection with no terms is not a filter" }
    }

    /** The exact values, for the `IN` half of the query. */
    val values: Set<FacetValue> get() =
        terms.filterIsInstance<Term.Exact>().map { it.value }.toSet()

    /** The spans, for the `BETWEEN` half. Empty for every non-numeric facet. */
    val spans: List<Term.Span> get() = terms.filterIsInstance<Term.Span>()
}

/**
 * A parsed query. Groups AND together; values within a group OR.
 *
 * Empty is the ordinary state — `/designs` with no query is every design — so this is not
 * a nullable or an `Optional`, it is a `Facets` with no groups.
 */
data class Facets(val groups: List<Selection>) {
    val isEmpty: Boolean get() = groups.isEmpty()

    fun of(key: FacetKey): Set<Term> =
        groups.firstOrNull { it.key == key }?.terms ?: emptySet()

    companion object {
        val none = Facets(emptyList())

        /**
         * Bind query parameters to a typed selection, or refuse.
         *
         * REFUSING IS THE FEATURE. `contract.md` §1: *"A token that cannot be parsed is an
         * error on screen, never a silent narrowing."* That rule was violated once and it
         * failed SILENTLY — `?keys=65-,unknown` fell through to the value list, matched
         * nothing for `65-`, and returned the exact complement of what the chip bar
         * claimed, on a bookmarkable URL.
         *
         * An unknown KEY is refused for the same reason at one remove: ignoring
         * `?frobnicate=1` lets a rail offer a filter the query does not apply, and the
         * user cannot see that the page is answering a different question than the one
         * they asked.
         *
         * `sort` and `page` are not facets and are handled by their own binders — [Sort]
         * and `DesignsController`'s own `@RequestParam page`, backlog #37 — so they are
         * skipped rather than refused here. A query string is shared between concerns.
         */
        fun parse(params: Map<String, List<String>>): Facets {
            val groups = mutableListOf<Selection>()
            for ((param, raw) in params) {
                if (param in NOT_FACETS) continue
                val key = FacetKey.of(param)
                    ?: throw UnreadableQuery(
                        "no facet called `$param`; this build knows " +
                            FacetKey.entries.joinToString(", ") { it.param }
                    )
                val tokens = raw.flatMap { it.split(",") }.map(String::trim)
                if (tokens.any(String::isEmpty)) {
                    throw UnreadableQuery("`$param` has an empty value")
                }
                groups += Selection(key, tokens.map { key.term(param, it) }.toSet())
            }
            return Facets(groups.sortedBy { it.key.ordinal })
        }

        /**
         * One token, read as the kind of term its facet allows — or refused.
         *
         * REFUSING IS STILL THE FEATURE. What changed is only *what* is readable: a span
         * over a numeric facet is now grammar, so `?keys=65-` is a filter rather than a
         * 400. Everything a numeric facet cannot mean is still an error on screen, and
         * that now includes a BACKWARDS span — `48-37` matches nothing by construction,
         * which is precisely the silent narrowing this rule exists to prevent.
         *
         * A span is only ever read for a numeric facet, because `-` is an ordinary
         * character elsewhere: `cc-by-sa4` is a licence, not a range.
         */
        private fun FacetKey.term(param: String, token: String): Term {
            if (numeric && token != UNKNOWN) {
                SPAN.matchEntire(token)?.let { match ->
                    val (lo, hi) = match.destructured
                    return runCatching {
                        Term.Span(lo.toIntOrNull(), hi.toIntOrNull())
                    }.getOrElse { refused -> throw UnreadableQuery("`$param=$token`: ${refused.message}") }
                }
                if (token.toIntOrNull() == null) {
                    throw UnreadableQuery(
                        "`$param=$token` is neither a number nor a range like `37-48`"
                    )
                }
            }
            return runCatching { Term.Exact(FacetValue(token)) }.getOrElse {
                throw UnreadableQuery("`$param=$token` is not a value this build reads")
            }
        }

        /** `37-48`, `65-`, `-36`. Both ends empty is caught by `Span` itself. */
        private val SPAN = Regex("""(\d*)-(\d*)""")

        /** Not a filter, and not an error either: other binders own these. */
        private val NOT_FACETS = setOf("sort", "page", "q")

        /** Spelled once. It is an ordinary value everywhere, which is why it needs a name. */
        const val UNKNOWN = "unknown"

        /**
         * The question does not apply to this design — a different fact from not knowing.
         *
         * An ordinary value, above the divider, and a HARD non-match against every other
         * value in its group. Its LABEL is the facet's own words and never these ones:
         * `controller_footprint` renders it "MCU soldered on".
         */
        const val NOT_APPLICABLE = "not_applicable"
    }
}
