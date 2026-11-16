package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey

object ControllerFootprintFacet : Facet(FacetKey.ControllerFootprint) {
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