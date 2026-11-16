package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

/** What joins a split's halves -- `FacetKey.Link` has the case for one-piece answering. */
object LinkFacet : Facet(FacetKey.Link) {
    override val heading = "Split link"

    // Closed, like `Splay`: most readers never ask it, and the heading still says the
    // question is askable.
    override val openByDefault = false

    // The cable's own name, as the design page's "Split link" row spells it. No "none": a
    // split whose connector nobody could name is "Not recorded" (2026-09-29).
    override val words = mapOf(
        "trrs" to "TRRS",
        "usb_c" to "USB-C",
        "ethernet" to "Ethernet",
        "mini_din" to "Mini-DIN (S-Video)",
        "bridge" to "Rigid PCB bridge",
        // Not a connector: the option that keeps one-piece boards in a "no TRRS" search,
        // since the group is OR-within and AND-across. "One piece" alone read as a kind
        // of link (owner, 2026-09-29), so the label says what it means.
        "one_piece" to "No cable: one piece",
    )
}