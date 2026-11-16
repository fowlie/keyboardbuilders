package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

/** What a design makes room for -- `FacetKey.Ext` has why it is presence only. */
object ExtFacet : Facet(FacetKey.Ext) {
    // `ux.md` §2.4's own name for this group. It shipped for an afternoon as "Optional
    // hardware", which was not wrong but invented a second vocabulary for what "Switch
    // footprint" already names three groups up the same rail — a place a part can go.
    // "Extensions" carries that without teaching a new word.
    override val heading = "Extensions & ports"

    // All six extensions are projected and every one is a legal filter, but speaker (6),
    // haptic (5), trackball (2) and trackpad (1) are exactly the sparsity backlog #41
    // measured and declined to spend rail rows on. Encoder at 84 designs and display at
    // 38 are real filters; the other four belong on the design's own page.
    override val railValues = listOf("encoder", "display")

    // The four the rail does not offer are still labelled: they are named on the
    // remainder line and a selected one renders its own checked box, and neither must
    // read `trackball` at the reader when every neighbour reads English.
    override val words = mapOf(
        "encoder" to "Encoder",
        "display" to "Display",
        "speaker" to "Speaker",
        "haptic" to "Haptic feedback",
        "trackball" to "Trackball",
        "trackpad" to "Trackpad",
    )
}
