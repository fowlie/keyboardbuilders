package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

object PitchFacet : Facet(FacetKey.Pitch) {
    override val heading = "Key spacing"
    override val notApplicable = "Too few keys to measure"
    override val words = mapOf(
        "mx" to "MX — 19.05 mm",
        "choc" to "Choc — 18 × 17 mm",
        "other" to "Non-standard",
    )
}