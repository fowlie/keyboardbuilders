package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

object StaggerFacet : Facet(FacetKey.Stagger) {
    override val heading = "Stagger"

    // The domain's own three words, in the reader's language rather than the enum's:
    // "ortho" is the URL's shorthand, "ortholinear" is what a builder searches for.
    override val words = mapOf("ortho" to "Ortholinear", "row" to "Row-staggered", "column" to "Column-staggered")
}