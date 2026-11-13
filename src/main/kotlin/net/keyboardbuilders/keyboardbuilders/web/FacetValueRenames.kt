package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.Facets
import org.springframework.util.MultiValueMap
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * What a facet VALUE used to be called. Backlog #98.
 *
 * WHY A VALUE RENAME NEEDS THIS AND A KEY RENAME DID NOT, which is the whole reason this
 * file exists rather than being waved through. When `?wireless=wireless` became
 * `?connectivity=`, the old URL broke LOUDLY: `Facets.parse` refuses an unknown key, and
 * `DesignsController` turns that into a 400 with the reason. Nothing was needed.
 *
 * A value is different. `FacetValue` validates SHAPE only -- `[a-z0-9][a-z0-9_-]*` -- so a
 * renamed value stays a perfectly legal token that simply matches no design. The old URL
 * would come back **200 with zero results**: the silent narrowing `contract.md` §1 forbids
 * and `DesignsController.index` names as the reason its parsing refuses rather than
 * ignores. A rename that quietly empties a bookmarked page is exactly that failure, just
 * arriving by a different door.
 *
 * SO: 301, THE SAME ANSWER [SlugRenames] GIVES. Ticket 07 decision 8 ruled that a rename
 * leaves a redirect rather than a dead link, and a facet URL is no less bookmarkable than
 * a design's -- `ux.md` §0 makes the URLs the product. Permanent, because the old value is
 * never coming back.
 *
 * NOT SEEDED, unlike `renames.json`. Design slugs are renamed by whoever confirms a row,
 * which is a data event and needs a data file; a facet value is renamed by editing
 * `Connectivity.kt`, which is a code event. One entry here per code change, and the map
 * stays short because a facet value that churns is a design problem this would only hide.
 */
object FacetValueRenames {

    /**
     * `bluetooth` -> `wireless`, 2026-09-16, #98. The reasons are on
     * `Connectivity.facetValue`: nothing in the corpus records a protocol, and
     * OpenController ships a CERN-OHL-W 2.4 GHz link that is wireless and not Bluetooth.
     *
     * Six days live, one internal link (the front page doorway, itself already labelled
     * "Wireless"), so the blast radius is small -- but a 301 costs a line and guessing at
     * the blast radius of a live URL is not a thing worth being right about.
     */
    private val moved: Map<Pair<FacetKey, String>, String> = mapOf(
        (FacetKey.Connectivity to "bluetooth") to "wireless",
        // `link=none` -> `link=unknown`, 2026-09-29. Not a rename but a retirement: "none
        // on the board" claimed an absence a name-matching scan cannot prove (Pando plugs
        // its halves together over USB-C and read as none). Those splits now sit in "Not
        // recorded", so that is where the old URL's visitors were always looking.
        (FacetKey.Link to "none") to Facets.UNKNOWN,
        // `other_low_profile` -> `kailh_x`, 2026-10-02. The owner ruled out a generic
        // "Other" switch category, and Kailh X was the one board left in it.
        (FacetKey.SwitchFootprint to "other_low_profile") to "kailh_x",
        // `switch_socket=hotswap` -> `hotswap=any`, 2026-10-02: the owner's Hotswap group
        // names parts, and the old single checkbox is its "Any". (`millmax` keeps its word.)
        (FacetKey.Hotswap to "hotswap") to "any",
    )

    /**
     * The query string this one should have been, or **null when nothing moved** -- and
     * null is the hot path, so a request that needs no redirect allocates nothing beyond
     * the lookups.
     *
     * Non-facet parameters (`page`, `q`, `sort`) pass through untouched: [FacetKey.of]
     * returns null for them and they are copied verbatim. Order is preserved so the
     * redirect target reads like the URL the visitor typed, and values are de-duplicated
     * per key because `?connectivity=bluetooth&connectivity=wireless` must not arrive as
     * `wireless` twice.
     */
    /**
     * Facet KEYS that moved, old param -> the key it is now. `licence` -> `license`,
     * 2026-10-01: the owner moved the site to US English, and the param and the label are
     * one vocabulary, so the label "License" took the URL with it. `?licence=` was live and
     * indexed, and an unknown param is ignored rather than refused -- so without this an old
     * link would silently drop its filter and show the whole corpus.
     */
    private val movedKeys: Map<String, FacetKey> = mapOf(
        "licence" to FacetKey.License,
        "switch_socket" to FacetKey.Hotswap,
    )

    fun rewrite(params: MultiValueMap<String, String>): String? {
        var moved = false
        val parts = mutableListOf<String>()

        for ((given, values) in params) {
            val param = movedKeys[given]?.param?.also { moved = true } ?: given
            val key = FacetKey.of(param)
            val mapped = values.map { value ->
                val now = key?.let { currentFor(it, value) }
                if (now != null) moved = true
                now ?: value
            }
            for (value in mapped.distinct()) {
                parts += "${encode(param)}=${encode(value)}"
            }
        }

        return if (moved) parts.joinToString("&") else null
    }

    fun currentFor(key: FacetKey, former: String): String? = moved[key to former]

    private fun encode(raw: String) = URLEncoder.encode(raw, StandardCharsets.UTF_8)
}
