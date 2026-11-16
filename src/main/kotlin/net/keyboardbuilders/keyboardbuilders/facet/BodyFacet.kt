package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

object BodyFacet : Facet(FacetKey.Body) {
    override val heading = "Body"
    override val openByDefault = true
    override val words = mapOf("split" to "Split", "unibody" to "Unibody", "one_handed" to "One-handed")
}