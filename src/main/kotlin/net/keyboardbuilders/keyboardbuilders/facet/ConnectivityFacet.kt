package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.connectivityFor
import net.keyboardbuilders.keyboardbuilders.domain.facetValue

/** What link this keyboard can use -- `FacetKey.Connectivity` has why it is a join. */
object ConnectivityFacet : Facet(FacetKey.Connectivity) {
    /*
     * THE ONE FACET THAT IS A JOIN, and the only one not read off a field of `design`.
     *
     * "Can this keyboard be wireless" is not a property of the design -- for a socketed
     * design it is a property of the (design, controller) pair, and the builder picks the
     * controller. `Design.kt` records that ticket 12's `RadioProof` tried to make it a
     * field, ticket 50 removed `Mcu` from socketed designs, and "`Wireless` could never be
     * a design-level value for the majority case". So it is computed against the
     * catalogue and stored as an ordinary facet value -- which is what lets every
     * existing rail, count, relaxation and URL mechanism work on it unchanged.
     *
     * NO ROW WHERE THE ANSWER IS NOT ESTABLISHED: `Connectivity.Unrecorded` projects null
     * and the rail's `unknown` anti-join puts those designs below the divider. It first
     * shipped storing a third `maybe` value (corrected 2026-09-10): a special case that
     * existed only because the facet was named after a verdict rather than the thing.
     */
    override fun project(design: Design, catalogue: Catalogue) =
        listOfNotNull(design.connectivityFor(catalogue.boards, catalogue.rules).facetValue()?.let { FacetRow(it) })

    override val heading = "Connectivity"

    /*
     * A SCALE, so it reads top-down as yes / yes-with-work / no. Count-sorting would put
     * "Wired only" (148) first, then "Wireless, needs firmware" (135), and bury plain
     * "Wireless" (37) at the bottom -- the answer a reader came for, under two they did not.
     */
    override val railValues = listOf("wireless", "wireless_needs_firmware", "wired_only")

    override val words = mapOf(
        /*
         * "Wireless" AND NOT "Bluetooth", and the label is not free to differ from the
         * param it sets. Owner's rule, 2026-09-16: never `wireless` in a URL and
         * "Bluetooth" on the checkbox, or the reverse -- confusing for a reader and
         * unmaintainable for us. `Connectivity.facetValue` carries the argument for which
         * word won; this is the half a reader sees.
         */
        "wireless" to "Wireless",
        /*
         * The socket takes a wireless controller; our firmware records do not show one
         * running on this design (#98). Named as a QUALIFICATION of the row above rather
         * than as its own concept, because that is what it is -- and the two read as a
         * scale only if they share a noun.
         */
        "wireless_needs_firmware" to "Wireless, needs firmware",
        /*
         * "Wired only", and the "only" is doing real work. A Bluetooth design is also
         * wired -- USB still flashes and charges it -- so this value means "no wireless
         * option", not "has a USB port". Every design has a USB port.
         *
         * It is also the WEAKER of two claims the state covers: a soldered ATmega32U4 has
         * no radio and cannot be made wireless by anything, while a socketed design merely
         * has no wireless controller *we have catalogued* that fits, and this catalogue is
         * incomplete by construction (`rpi_pico`: 8 designs, one board, none wireless,
         * while a Pico W exists in the world). One label covers both, so it takes the
         * weaker; the design pages say which applies.
         */
        "wired_only" to "Wired only",
    )
}
