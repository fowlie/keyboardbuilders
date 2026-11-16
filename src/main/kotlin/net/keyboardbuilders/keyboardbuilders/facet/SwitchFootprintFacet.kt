package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint

object SwitchFootprintFacet : Facet(FacetKey.SwitchFootprint) {
    override fun project(design: Design, catalogue: Catalogue) =
        design.core.switches.map {
            FacetRow(
                when (it) {
                    is SwitchFootprint.Mx -> "mx"
                    is SwitchFootprint.ChocV1 -> "choc_v1"
                    is SwitchFootprint.ChocV2 -> "choc_v2"
                    is SwitchFootprint.Alps -> "alps"
                    is SwitchFootprint.ChocMini -> "choc_mini"
                    is SwitchFootprint.KailhX -> "kailh_x"
                    // "Assigned by a human, never by the importer" — so the seed cannot
                    // produce one today. Projected rather than refused, because the day a
                    // human assigns one it must be selectable, and a `when` that threw
                    // here would make that a code change instead of a data change.
                    is SwitchFootprint.Bespoke -> "bespoke"
                }
            )
        }

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