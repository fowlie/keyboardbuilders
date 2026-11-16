package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull

object FirmwareFacet : Facet(FacetKey.Firmware) {
    // Projected only when known: no record is the rail's "Not recorded" gap (#106).
    override fun project(design: Design, catalogue: Catalogue) =
        design.core.firmware.knownOrNull().orEmpty().map { FacetRow(familySlug(it)) }

    /**
     * The FAMILY, not the reference. `qmk:sofle/rev1` and `qmk:crkbd/rev1` are the same
     * facet value: somebody filtering by firmware wants "runs QMK", and the path is a fact
     * about this design rather than something to select on.
     */
    fun familySlug(ref: FirmwareRef) = when (ref) {
        is FirmwareRef.Qmk -> "qmk"
        is FirmwareRef.Zmk -> "zmk"
        is FirmwareRef.Kmk -> "kmk"
        is FirmwareRef.Custom -> "custom"
    }

    override val heading = "Firmware"

    // A firmware family's slug is its name: `qmk`, `zmk`, `kmk`.
    override fun word(token: String) = token.uppercase()
}