package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull

object StaggerFacet : Facet(FacetKey.Stagger) {
    // Same discipline as splay: a genuine measured verdict, not evidence-only.
    override fun project(design: Design, catalogue: Catalogue) =
        listOfNotNull(design.extras.stagger.knownOrNull()?.let { FacetRow(it.name.lowercase()) })

    override val heading = "Stagger"

    // The domain's own three words, in the reader's language rather than the enum's:
    // "ortho" is the URL's shorthand, "ortholinear" is what a builder searches for.
    override val words = mapOf("ortho" to "Ortholinear", "row" to "Row-staggered", "column" to "Column-staggered")
}