package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.Facets

object ControllerFootprintFacet : Facet(FacetKey.ControllerFootprint) {
    override fun project(design: Design, catalogue: Catalogue) = when (val controller = design.core.controller) {
        // One row per footprint (#99): a PCB taking a Pro Micro or a Feather answers both
        // filters, the way a design on two firmwares answers both.
        is Controller.Socket -> controller.footprints.map { FacetRow(token(it)) }
        // NOT_APPLICABLE, not "soldered". This facet asks WHICH DEV-BOARD FOOTPRINT the
        // design takes, and a design with the chip on its own PCB does not take one — the
        // question does not apply rather than having "soldered" as an answer.
        //
        // The distinction is not pedantry. `not_applicable` is a HARD non-match against
        // every other value in its group, where a value called "soldered" reads as one
        // option among the sockets and would invite a rail that offers "Pro Micro or
        // soldered" as if those answered the same question.
        is Controller.Soldered -> listOf(FacetRow(Facets.NOT_APPLICABLE))
    }

    /** The facet value a footprint projects to, which `/parts` links to as well. */
    fun token(footprint: ControllerFootprint) = when (footprint) {
        ControllerFootprint.ProMicro -> "pro_micro"
        ControllerFootprint.SeeedXiao -> "seeed_xiao"
        ControllerFootprint.Rp2040Zero -> "rp2040_zero"
        ControllerFootprint.RpiPico -> "rpi_pico"
        ControllerFootprint.BlackPill -> "black_pill"
        ControllerFootprint.BluePill -> "blue_pill"
        ControllerFootprint.Feather -> "feather"
    }

    /*
     * "Controller socket", NOT "Controller board" -- renamed 2026-09-10 after the owner
     * went looking for a nice!nano in this group and asked whether it had been removed.
     * It never existed here: every value in the group is a FOOTPRINT (Pro Micro, Seeed
     * XIAO, "MCU soldered on"), so a group promising boards was the whole cause of the
     * question. It also contradicted the domain's own ruling, ticket 27 decision 4 --
     * "`Socket` names a FORM FACTOR, never a board" -- which is why `pro_micro` covers
     * Elite-C, nice!nano, Sea-Picro and Proton C alike.
     *
     * The URL param stays `controller_footprint`, so no link or bookmark changes. A
     * board-level facet was considered and MEASURED TO FAIL: the 34 catalogue boards
     * select only 8 distinct design sets, because a board's set is (footprint x firmware)
     * and its identity adds nothing. "I have a nice!nano" is a lookup with one answer, and
     * it lives at `/parts/dev-board/:slug`.
     */
    override val heading = "Controller socket"
    override val notApplicable = "MCU soldered on"
    override val words = mapOf(
        "pro_micro" to "Pro Micro",
        "seeed_xiao" to "Seeed XIAO",
        "rp2040_zero" to "RP2040-Zero",
        "rpi_pico" to "Raspberry Pi Pico",
        "black_pill" to "BlackPill",
        "blue_pill" to "BluePill",
        "feather" to "Feather",
    )
}