package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.KeyPitch
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull

object PitchFacet : Facet(FacetKey.Pitch) {
    override fun project(design: Design, catalogue: Catalogue) = listOfNotNull(
        design.core.pitch.knownOrNull()?.let { measured ->
            FacetRow(
                when (measured) {
                    KeyPitch.Mx -> "mx"
                    KeyPitch.Choc -> "choc"
                    is KeyPitch.Custom -> "other"
                }
            )
        }
    )

    override val heading = "Key spacing"
    override val notApplicable = "Too few keys to measure"
    override val words = mapOf(
        "mx" to "MX — 19.05 mm",
        "choc" to "Choc — 18 × 17 mm",
        "other" to "Non-standard",
    )
}