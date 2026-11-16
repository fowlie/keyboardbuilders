package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull

/** What a design makes room for -- `FacetKey.Ext` has why it is presence only. */
object ExtFacet : Facet(FacetKey.Ext) {
    // PRESENCE ONLY, and the "Not recorded" bucket that falls out of it is HONEST here in
    // a way it briefly was not. This projected a `not_applicable` row for designs whose
    // boards matched no footprint name, on the reading that they had been measured and
    // found bare. They had not: the scan is a regex over footprint NAMES and proves
    // presence only — 11 of those 223 designs are contradicted by their own QMK record,
    // `bastardkb/dilemma` among them, which declares a Cirque trackpad our board scan
    // cannot see because the module hangs off a generic pin header. So no evidence is
    // ordinary ignorance and belongs in the gap.
    override fun project(design: Design, catalogue: Catalogue) =
        design.extras.extensions.knownOrNull().orEmpty().map { FacetRow(it.name.lowercase()) }

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
