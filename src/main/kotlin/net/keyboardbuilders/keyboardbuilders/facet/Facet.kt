package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.Facets
import net.keyboardbuilders.keyboardbuilders.domain.Term

/**
 * One facet, whole: its URL key, and how the rail words, orders and opens it.
 *
 * ONE FILE PER FACET. Power switch (c35b0e4) and exposed components (eb049bc) each touched
 * nine files, because a facet's tokens, its rail heading, its labels and its order lived
 * in separate `when`s across `FacetKey`, `FacetRail` and `SeedImporter`, each ending in
 * `else -> value` -- so a token added on one side and forgotten on another rendered raw
 * (`choc_v1`) with nothing failing. Each facet now answers every part in its own object,
 * and [label] returns null for a token it does not know, which `FacetVocabularyTest`
 * checks against everything the seed projects.
 *
 * [FacetKey] stays the URL vocabulary the query layer is generic over (`DesignQueries`,
 * `Facets.parse`); its ORDER is still the rail's order. [facet] maps each key to its
 * object, exhaustively, so a new key does not compile until it has one.
 */
abstract class Facet(val key: FacetKey) {
    /** The rail group's heading. */
    abstract val heading: String

    /** Whether the rail draws this group expanded when nothing in it is selected. */
    open val openByDefault: Boolean = false

    /**
     * The values this facet offers as checkboxes, in this order, or empty for "whatever
     * the corpus has, by count".
     *
     * What is stored and what is offered are different questions. A value left out here
     * is still selectable by URL, renders its own checkbox when selected, and is named on
     * the rail's remainder line -- `DesignQueries.rail` and `railFor` keep that promise.
     */
    open val railValues: List<String> = emptyList()

    /** The pre-canned spans a numeric facet offers instead of one checkbox per value. */
    open val bands: List<Term.Span> = emptyList()

    /**
     * `not_applicable` in this facet's own words -- `contract.md` §3 rule 2. The token is
     * shared so the query grammar has one reserved word; "not applicable" tells a reader
     * nothing about a keyboard while "MCU soldered on" tells them what they are choosing.
     */
    open val notApplicable: String = "Does not apply"

    /** The reader's words for each token, for a facet whose tokens are a closed set. */
    protected open val words: Map<String, String> = emptyMap()

    /** A token this facet writes, in the reader's words; null if it is not one of its own. */
    protected open fun word(token: String): String? = words[token]

    fun label(token: String): String? = when (token) {
        Facets.UNKNOWN -> "Not recorded"
        Facets.NOT_APPLICABLE -> notApplicable
        else -> word(token)
    }
}

val FacetKey.facet: Facet get() = when (this) {
    FacetKey.Body -> BodyFacet
    FacetKey.Link -> LinkFacet
    FacetKey.Keys -> KeysFacet
    FacetKey.Splay -> SplayFacet
    FacetKey.Stagger -> StaggerFacet
    FacetKey.SwitchFootprint -> SwitchFootprintFacet
    FacetKey.Hotswap -> HotswapFacet
    FacetKey.ControllerFootprint -> ControllerFootprintFacet
    FacetKey.BuildMethod -> BuildMethodFacet
    FacetKey.Firmware -> FirmwareFacet
    FacetKey.SmdRequired -> SmdRequiredFacet
    FacetKey.Connectivity -> ConnectivityFacet
    FacetKey.License -> LicenseFacet
    FacetKey.Pitch -> PitchFacet
    FacetKey.Ext -> ExtFacet
    FacetKey.ExposedComponents -> ExposedComponentsFacet
    FacetKey.PowerSwitch -> PowerSwitchFacet
}
