package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull

/** `DesignExtras.exposedComponents`: a measured yes/no like splay. */
object ExposedComponentsFacet : Facet(FacetKey.ExposedComponents) {
    override fun project(design: Design, catalogue: Catalogue) =
        listOfNotNull(design.extras.exposedComponents.knownOrNull()?.let { FacetRow(if (it) "yes" else "no") })

    override val heading = "Exposed components"

    // Ordered: "Not exposed" (232) would bury "Exposed" (27), the answer a reader opens
    // this group for.
    override val railValues = listOf("yes", "no")
    override val words = mapOf("yes" to "Exposed", "no" to "Not exposed")
}