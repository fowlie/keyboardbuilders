package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

object FirmwareFacet : Facet(FacetKey.Firmware) {
    override val heading = "Firmware"

    // A firmware family's slug is its name: `qmk`, `zmk`, `kmk`.
    override fun word(token: String) = token.uppercase()
}