package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

object BuildMethodFacet : Facet(FacetKey.BuildMethod) {
    override fun project(design: Design, catalogue: Catalogue) = listOf(
        when (design.core.buildMethod) {
            is BuildMethod.Pcb -> FacetRow("pcb")
            is BuildMethod.Handwire -> FacetRow("handwire")
        }
    )

    override val heading = "Build method"
    override val words = mapOf("pcb" to "PCB", "handwire" to "Hand-wired")
}