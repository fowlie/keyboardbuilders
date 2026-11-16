package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

/** The Tier-1 "can I build it?" gate -- `FacetKey.SmdRequired` has why it is boolean. */
object SmdRequiredFacet : Facet(FacetKey.SmdRequired) {
    override val heading = "SMD soldering"

    // Ordered, not count-sorted: "yes" (215) above "no" (95) would bury the answer the
    // Tier-1 "can I build it?" reader came for under the one they did not.
    override val railValues = listOf("no", "yes")

    // "No SMD soldering" rather than "No" -- the group heading is above the fold on its
    // own line, and a rail scanned quickly reads checkboxes before headings.
    override val words = mapOf("no" to "No SMD soldering", "yes" to "SMD soldering required")
}