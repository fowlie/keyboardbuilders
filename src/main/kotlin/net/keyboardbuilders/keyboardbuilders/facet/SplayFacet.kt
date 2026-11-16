package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

/** `FacetKey.Splay` and `DesignExtras.splay` have why `no` is measured, not a gap. */
object SplayFacet : Facet(FacetKey.Splay) {
    override val heading = "Splay"

    // "Splayed" / "Not splayed" rather than "Yes" / "No": a rail scanned quickly reads
    // checkboxes before headings.
    override val words = mapOf("yes" to "Splayed", "no" to "Not splayed")
}