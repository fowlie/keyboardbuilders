package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.License
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState

object LicenseFacet : Facet(FacetKey.License) {
    override fun project(design: Design, catalogue: Catalogue) = when (val licensing = design.core.licensing.hardware) {
        // "all rights reserved" is a fact about the repo, so it is selectable rather than
        // absent. An absent licence and an unreadable one are different, and only the
        // first reaches here.
        is LicenseState.Unstated -> listOf(FacetRow("unstated"))
        is LicenseState.Stated -> licensing.licenses.map { FacetRow(slug(it)) }
    }

    /**
     * `License` is a sealed interface rather than an enum, so the tail variant has to be
     * spelled out: `Other` carries text nobody enumerated, and it is slugged as given
     * rather than collapsed to "other", which would make two unrelated bespoke licences
     * look like one facet value.
     */
    fun slug(license: License) = when (license) {
        is License.Other -> license.spdxOrName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        else -> license::class.simpleName!!.snake()
    }

    override val heading = "License"

    // A licence slug is already the licence's own name; title-casing SPDX would make
    // `cc_by_sa4` read as prose it is not.
    override fun word(token: String) =
        if (token == "unstated") "No license stated" else token.uppercase().replace('_', '-')
}