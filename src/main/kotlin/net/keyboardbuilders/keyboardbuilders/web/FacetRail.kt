package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.Facets
import net.keyboardbuilders.keyboardbuilders.domain.bands
import net.keyboardbuilders.keyboardbuilders.persistence.DesignQueries
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The filter rail, as the template receives it. Forgejo #7, `contract.md` §3.
 *
 * Every `when` over a facet's vocabulary lives in this file, for the same reason
 * `DesignPresentation` gives: jte can branch, but then exhaustiveness moves into a file
 * the Kotlin compiler only sees after code generation, and adding a value would fail at
 * render time instead of at build time. The template receives strings and prints them.
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
     * [FacetKey.railValues] curates a front row, and then it holds exactly what was left
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
            heading = group.key.heading(),
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
                    val curated = group.key.railValues
                    if (group.key.bands.isNotEmpty()) shown
                    else if (curated.isNotEmpty())
                        shown.filter { it.value in curated || it.selected }
                    else shown.sortedWith(
                        compareBy({ it.value == Facets.NOT_APPLICABLE }, { -it.count })
                    )
                },
            more = group.key.railValues.takeIf { it.isNotEmpty() }?.let { curated ->
                options.filter {
                    it.value !in curated && !it.selected &&
                        it.value != Facets.UNKNOWN && it.count > 0
                }.sortedByDescending { it.count }
            }.orEmpty(),
            unrecorded = options.firstOrNull { it.value == Facets.UNKNOWN },
            openByDefault = group.key.openByDefault() || options.any { it.selected },
        )
    }

private fun DesignQueries.Option.toOption(
    key: FacetKey,
    facets: Facets,
    text: String?,
    sort: String?,
) = RailOption(
    value = value,
    label = key.label(value),
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

private fun FacetKey.heading(): String = when (this) {
    FacetKey.Body -> "Body"
    FacetKey.Link -> "Split link"
    FacetKey.Keys -> "Keys"
    FacetKey.Splay -> "Splay"
    FacetKey.Stagger -> "Stagger"
    FacetKey.SwitchFootprint -> "Switch footprint"
    // The owner's word, 2026-10-02, with the parts under it: "Hotswap: Kailh socket".
    FacetKey.Hotswap -> "Hotswap"
    /*
     * "Controller socket", NOT "Controller board" -- renamed 2026-09-10 after the owner
     * went looking for a nice!nano in this group and asked whether it had been removed.
     * It never existed here: every value in the group is a FOOTPRINT (Pro Micro, Seeed
     * XIAO, "MCU soldered on"), so a group promising boards was the whole cause of the
     * question. It also contradicted the domain's own ruling, ticket 27 decision 4 --
     * "`Socket` names a FORM FACTOR, never a board" -- which is why `pro_micro` covers
     * Elite-C, nice!nano, Sea-Picro and Proton C alike.
     *
     * The URL param stays `controller_footprint`, so no link or bookmark changes. A
     * board-level facet was considered and MEASURED TO FAIL: the 34 catalogue boards
     * select only 8 distinct design sets, because a board's set is (footprint x firmware)
     * and its identity adds nothing. "I have a nice!nano" is a lookup with one answer, and
     * it lives at `/parts/dev-board/:slug`.
     */
    FacetKey.ControllerFootprint -> "Controller socket"
    FacetKey.Connectivity -> "Connectivity"
    FacetKey.BuildMethod -> "Build method"
    FacetKey.SmdRequired -> "SMD soldering"
    FacetKey.Firmware -> "Firmware"
    FacetKey.License -> "License"
    FacetKey.Pitch -> "Key spacing"
    // `ux.md` §2.4's own name for this group. It shipped for an afternoon as "Optional
    // hardware", which was not wrong but invented a second vocabulary for what "Switch
    // footprint" already names three groups up the same rail — a place a part can go.
    // "Extensions" carries that without teaching a new word.
    FacetKey.Ext -> "Extensions & ports"
    FacetKey.ExposedComponents -> "Exposed components"
    FacetKey.PowerSwitch -> "Power switch"
}

/** Exhaustive like [heading], so a facet added later has to answer this deliberately. */
private fun FacetKey.openByDefault(): Boolean = when (this) {
    FacetKey.Body -> true
    // Closed, like `Splay` below: most readers never ask it, and the heading still says
    // the question is askable.
    FacetKey.Link -> false
    FacetKey.Keys -> true
    // Closed, like `SmdRequired` below: one checkbox pair, not a group worth an open
    // row by default. The heading still says the question is askable.
    FacetKey.Splay -> false
    // Closed too, same reasoning as `Splay` just above: three checkboxes, not a group
    // worth an open row by default.
    FacetKey.Stagger -> false
    FacetKey.SwitchFootprint -> true
    // Closed, like every group below the first three.
    FacetKey.Hotswap -> false
    FacetKey.ControllerFootprint -> false
    FacetKey.Connectivity -> false
    FacetKey.BuildMethod -> false
    FacetKey.SmdRequired -> false
    FacetKey.Firmware -> false
    FacetKey.License -> false
    FacetKey.Pitch -> false
    FacetKey.Ext -> false
    FacetKey.ExposedComponents -> false
    FacetKey.PowerSwitch -> false
}

/**
 * A value in the facet's own words.
 *
 * `not_applicable` IS NEVER RENDERED AS "not applicable" — `contract.md` §3 rule 2. The
 * token is shared so the query grammar has one reserved word; the words on screen are the
 * facet's, because "not applicable" tells a reader nothing about a keyboard while "MCU
 * soldered on" tells them exactly what they are choosing.
 */
private fun FacetKey.label(value: String): String {
    if (value == Facets.UNKNOWN) return "Not recorded"
    if (value == Facets.NOT_APPLICABLE) return when (this) {
        FacetKey.ControllerFootprint -> "MCU soldered on"
        FacetKey.SwitchFootprint -> "No switches on this board"
        FacetKey.Pitch -> "Too few keys to measure"
        else -> "Does not apply"
    }
    return when (this) {
        /*
         * THE NEGATIVE IS "No wireless option known", NOT "Wired", and the weakening is
         * deliberate. The state covers two different strengths of claim: a soldered
         * ATmega32U4 has no radio and cannot be made wireless by anything (absolute),
         * while a socketed design merely has no wireless controller *we have catalogued*
         * that fits -- and this catalogue is incomplete by construction. `rpi_pico` is the
         * live example: 8 designs, one catalogued board, none wireless, while a Pico W
         * exists in the world and is not in `seed/controllers.toml`.
         *
         * One label must cover both, so it takes the weaker: understating is safe,
         * overstating is not. The design pages say which of the two applies, because there
         * is room there for a sentence (`Connectivity.WiredOnly`'s two members).
         *
         * NARROWER SINCE #98 (2026-09-16): the 135 designs whose socket takes a wireless
         * board their firmware cannot run have left this value for
         * `wireless_needs_firmware`, so "Wired only" now covers 148 rather than 283. The
         * weaker-claim rule still governs what is left.
         */
        FacetKey.Connectivity -> when (value) {
            /*
             * "Wireless" AND NOT "Bluetooth", and the label is not free to differ from
             * the param it sets. Owner's rule, 2026-09-16: never `wireless` in a URL and
             * "Bluetooth" on the checkbox, or the reverse -- confusing for a reader and
             * unmaintainable for us. `Connectivity.facetValue` carries the argument for
             * which word won; this is the half a reader sees.
             *
             * The front page already said "Wireless" on the doorway that pointed at
             * `?connectivity=bluetooth`, so this rename closes a live inconsistency
             * rather than opening one.
             */
            "wireless" -> "Wireless"
            /*
             * The socket takes a wireless controller; our firmware records do not show one
             * running on this design (#98). Named as a QUALIFICATION of the row above
             * rather than as its own concept, because that is what it is -- and the two
             * read as a scale only if they share a noun.
             */
            "wireless_needs_firmware" -> "Wireless, needs firmware"
            /*
             * "Wired only", and the "only" is doing real work. A Bluetooth design is also
             * wired -- USB still flashes and charges it -- so this value means "no
             * wireless option", not "has a USB port". Every design has a USB port.
             *
             * It is also the WEAKER of two claims the state covers: a soldered ATmega32U4
             * has no radio and cannot be made wireless by anything, while a socketed
             * design merely has no wireless controller *we have catalogued* that fits, and
             * this catalogue is incomplete by construction (`rpi_pico`: 8 designs, one
             * board, none wireless, while a Pico W exists in the world). One label covers
             * both, so it takes the weaker; the design pages say which applies.
             */
            "wired_only" -> "Wired only"
            else -> value
        }
        // The cable's own name, as the design page's "Split link" row spells it. No
        // "none": a split whose connector nobody could name is "Not recorded" (2026-09-29).
        FacetKey.Link -> when (value) {
            "trrs" -> "TRRS"
            "usb_c" -> "USB-C"
            "ethernet" -> "Ethernet"
            "mini_din" -> "Mini-DIN (S-Video)"
            "bridge" -> "Rigid PCB bridge"
            // Not a connector: the option that keeps one-piece boards in a "no TRRS"
            // search, since the group is OR-within and AND-across. "One piece" alone read
            // as a kind of link (owner, 2026-09-29), so the label says what it means.
            "one_piece" -> "No cable: one piece"
            else -> value
        }
        FacetKey.Body -> when (value) {
            "split" -> "Split"
            "unibody" -> "Unibody"
            "one_handed" -> "One-handed"
            else -> value
        }
        // "Splayed" / "Not splayed" rather than "Yes" / "No" -- the group heading is
        // above the fold on its own line, same reasoning `SmdRequired` gives below for
        // reading the checkbox without the heading in view.
        FacetKey.PowerSwitch -> when (value) {
            "yes" -> "Power switch"
            "no" -> "No power switch"
            else -> value
        }
        FacetKey.ExposedComponents -> when (value) {
            "yes" -> "Exposed"
            "no" -> "Not exposed"
            else -> value
        }
        FacetKey.Splay -> when (value) {
            "yes" -> "Splayed"
            "no" -> "Not splayed"
            else -> value
        }
        // The domain's own three words, in the reader's language rather than the enum's:
        // "ortho" is the URL's shorthand, "ortholinear" is what a builder searches for.
        FacetKey.Stagger -> when (value) {
            "ortho" -> "Ortholinear"
            "row" -> "Row-staggered"
            "column" -> "Column-staggered"
            else -> value
        }
        // The bands, in the reader's words rather than the URL's. An exact count still
        // reaches this branch, because `?keys=34` remains a legal filter.
        FacetKey.Keys -> when {
            value.startsWith("-") -> "${value.drop(1)} or fewer"
            value.endsWith("-") -> "${value.dropLast(1)} or more"
            "-" in value -> value.replace("-", "\u2013")
            else -> "$value keys"
        }
        // The URL's word spelled for a person, the #98 rule: `?hotswap=kailh` is "Kailh
        // socket" under the "Hotswap" heading.
        FacetKey.Hotswap -> when (value) {
            "any" -> "Any"
            "kailh" -> "Kailh socket"
            "gateron" -> "Gateron socket"
            "millmax" -> "Mill-Max"
            "no" -> "No hotswap"
            else -> value
        }
        FacetKey.SwitchFootprint -> when (value) {
            "mx" -> "MX"
            "choc_v1" -> "Choc v1"
            "choc_v2" -> "Choc v2"
            "alps" -> "Alps"
            "choc_mini" -> "Mini Choc"
            "kailh_x" -> "Kailh X"
            "bespoke" -> "Bespoke"
            else -> value
        }
        FacetKey.ControllerFootprint -> when (value) {
            "pro_micro" -> "Pro Micro"
            "seeed_xiao" -> "Seeed XIAO"
            "rp2040_zero" -> "RP2040-Zero"
            "rpi_pico" -> "Raspberry Pi Pico"
            "black_pill" -> "BlackPill"
            "blue_pill" -> "BluePill"
            "feather" -> "Feather"
            else -> value
        }
        FacetKey.BuildMethod -> when (value) {
            "pcb" -> "PCB"
            "handwire" -> "Hand-wired"
            else -> value
        }
        // "No SMD soldering" rather than "No" -- the group heading is above the fold on
        // its own line, and a rail scanned quickly reads checkboxes before headings.
        FacetKey.SmdRequired -> when (value) {
            "no" -> "No SMD soldering"
            "yes" -> "SMD soldering required"
            else -> value
        }
        FacetKey.Firmware -> value.uppercase()
        // A licence slug is already the licence's own name; title-casing SPDX would make
        // `cc_by_sa4` read as prose it is not.
        FacetKey.License -> if (value == "unstated") "No license stated"
        else value.uppercase().replace('_', '-')
        FacetKey.Pitch -> when (value) {
            "mx" -> "MX — 19.05 mm"
            "choc" -> "Choc — 18 × 17 mm"
            "other" -> "Non-standard"
            else -> value
        }
        // The four the rail does not offer are still labelled: they are named on the
        // remainder line and a selected one renders its own checked box, and neither
        // must read `trackball` at the reader when every neighbour reads English.
        FacetKey.Ext -> when (value) {
            "encoder" -> "Encoder"
            "display" -> "Display"
            "speaker" -> "Speaker"
            "haptic" -> "Haptic feedback"
            "trackball" -> "Trackball"
            "trackpad" -> "Trackpad"
            else -> value
        }
    }
}
