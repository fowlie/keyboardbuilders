package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

object BuildMethodFacet : Facet(FacetKey.BuildMethod) {
    override val heading = "Build method"
    override val words = mapOf("pcb" to "PCB", "handwire" to "Hand-wired")
}