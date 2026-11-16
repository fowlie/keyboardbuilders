package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

object LicenseFacet : Facet(FacetKey.License) {
    override val heading = "License"

    // A licence slug is already the licence's own name; title-casing SPDX would make
    // `cc_by_sa4` read as prose it is not.
    override fun word(token: String) =
        if (token == "unstated") "No license stated" else token.uppercase().replace('_', '-')
}