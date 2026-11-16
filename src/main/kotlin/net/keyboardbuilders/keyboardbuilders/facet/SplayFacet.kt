package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull

/** `FacetKey.Splay` and `DesignExtras.splay` have why `no` is measured, not a gap. */
object SplayFacet : Facet(FacetKey.Splay) {
    override fun project(design: Design, catalogue: Catalogue) =
        listOfNotNull(design.extras.splay.knownOrNull()?.let { FacetRow(if (it) "yes" else "no") })

    override val heading = "Splay"

    // "Splayed" / "Not splayed" rather than "Yes" / "No": a rail scanned quickly reads
    // checkboxes before headings.
    override val words = mapOf("yes" to "Splayed", "no" to "Not splayed")
}