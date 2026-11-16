package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

/** `DesignExtras.powerSwitch`: a measured yes/no like splay. */
object PowerSwitchFacet : Facet(FacetKey.PowerSwitch) {
    override val heading = "Power switch"

    // Ordered: "no" (300) would bury "yes" (35), the answer a reader came for.
    override val railValues = listOf("yes", "no")
    override val words = mapOf("yes" to "Power switch", "no" to "No power switch")
}