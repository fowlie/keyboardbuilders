package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

/** Which hotswap parts fit -- `FacetKey.Hotswap` has why these are parts, not pads. */
object HotswapFacet : Facet(FacetKey.Hotswap) {
    // The owner's word, 2026-10-02, with the parts under it: "Hotswap: Kailh socket".
    override val heading = "Hotswap"

    // The owner's order, 2026-10-02. `no` (solder only) sits on the remainder line: a real
    // answer, like "No optional hardware", and not something a reader shops by.
    override val railValues = listOf("any", "kailh", "gateron", "millmax")

    // The URL's word spelled for a person, the #98 rule: `?hotswap=kailh` is "Kailh
    // socket" under the "Hotswap" heading.
    override val words = mapOf(
        "any" to "Any",
        "kailh" to "Kailh socket",
        "gateron" to "Gateron socket",
        "millmax" to "Mill-Max",
        "no" to "No hotswap",
    )
}