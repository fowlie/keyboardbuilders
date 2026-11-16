package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

object SwitchFootprintFacet : Facet(FacetKey.SwitchFootprint) {
    override val heading = "Switch footprint"
    override val openByDefault = true
    override val notApplicable = "No switches on this board"
    override val words = mapOf(
        "mx" to "MX",
        "choc_v1" to "Choc v1",
        "choc_v2" to "Choc v2",
        "alps" to "Alps",
        "choc_mini" to "Mini Choc",
        "kailh_x" to "Kailh X",
        "bespoke" to "Bespoke",
    )
}