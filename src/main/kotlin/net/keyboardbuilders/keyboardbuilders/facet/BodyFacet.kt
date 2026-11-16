package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

object BodyFacet : Facet(FacetKey.Body) {
    override fun project(design: Design, catalogue: Catalogue) = listOf(
        when (val body = design.core.body) {
            is Body.Unibody -> FacetRow("unibody")
            is Body.Split -> FacetRow("split", body.halves.value)
            // `num` carries the half count for a split, so "three-piece" is queryable
            // without a second key. A one-hander has one half by definition, and which
            // hand is procurement rather than a property of the design, so it is not
            // folded into `split` — somebody filtering for a split does not want one.
            is Body.OneHanded -> FacetRow("one_handed", 1)
        }
    )

    override val heading = "Body"
    override val openByDefault = true
    override val words = mapOf("split" to "Split", "unibody" to "Unibody", "one_handed" to "One-handed")
}