package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull

/** `DesignExtras.powerSwitch`: a measured yes/no like splay. */
object PowerSwitchFacet : Facet(FacetKey.PowerSwitch) {
    // `measure_power_switch.py`'s `no` is measured, so it is a row like splay's.
    override fun project(design: Design, catalogue: Catalogue) =
        listOfNotNull(design.extras.powerSwitch.knownOrNull()?.let { FacetRow(if (it) "yes" else "no") })

    override val heading = "Power switch"

    // Ordered: "no" (300) would bury "yes" (35), the answer a reader came for.
    override val railValues = listOf("yes", "no")
    override val words = mapOf("yes" to "Power switch", "no" to "No power switch")
}