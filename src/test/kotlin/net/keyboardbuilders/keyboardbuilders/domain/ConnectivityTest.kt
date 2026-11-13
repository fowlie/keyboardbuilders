package net.keyboardbuilders.keyboardbuilders.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * "Can this keyboard be wireless?" -- a catalogue join, never a field on the record.
 *
 * THE RULING THIS CLASS PROTECTS IS AN OLD ONE. `Design.kt` records that ticket 12's
 * `RadioProof` minted wirelessness from `Mcu`, ticket 50 removed `Mcu` from socketed
 * designs, and the conclusion was that *"`Wireless` could never be a design-level value
 * for the majority case"*. So every assertion below is about a derivation over two
 * catalogues, and none is about a stored field. A test that started asserting on a
 * `Design` property would mean the ruling had been quietly reopened.
 */
class ConnectivityTest {

    private fun design(
        firmware: Recorded<Set<FirmwareRef>> = Recorded.Known(setOf(FirmwareRef.Qmk())),
        controller: Controller = Controller.Socket(ControllerFootprint.ProMicro),
    ) = Design(
        core = DesignCore(
            name = DesignName("Test Board"),
            slug = Slug("test-board"),
            sources = setOf(SourceRef("https://example.com/board")),
            firmware = firmware,
            layouts = setOf(Layout(KeyCount(42))),
            switches = setOf(SwitchFootprint.Mx),
            buildMethod = BuildMethod.Pcb(),
            controller = controller,
            body = Body.Unibody,
            licensing = Licensing(LicenseState.Unstated, LicenseState.Unstated, LicenseState.Unstated),
        ),
        extras = DesignExtras(),
    )

    private data class Board(
        override val name: String,
        override val footprint: ControllerFootprint,
        override val wireless: Boolean,
        override val mcuFamily: Recorded<McuFamily>,
        override val firmware: Set<String>,
    ) : ControllerFacts

    private val niceNano = Board(
        "nice!nano v2", ControllerFootprint.ProMicro, true,
        Recorded.Known(McuFamily.Nrf52), setOf("zmk", "kmk"),
    )
    private val liatris = Board(
        "Liatris", ControllerFootprint.ProMicro, false,
        Recorded.Known(McuFamily.Rp2040), setOf("qmk"),
    )
    /** `BlueMicro840 v1`: wireless, and its firmware's own wireless support is unrecorded. */
    private val blueMicro = Board(
        "BlueMicro840 v1", ControllerFootprint.ProMicro, true,
        Recorded.Known(McuFamily.Nrf52), setOf("bluemicro"),
    )

    /** The Feather 32u4 Bluefruit LE: an AVR with a radio beside it, which QMK drives. */
    private val feather = Board(
        "Adafruit Feather 32u4 Bluefruit LE", ControllerFootprint.Feather, true,
        Recorded.Known(McuFamily.Avr8), setOf("qmk"),
    )

    private val rules = FirmwareRules.of(
        listOf(
            // `true` since #99, as `seed/firmwares.toml` says: QMK drives a radio on AVR.
            FirmwareSupport("qmk", setOf(McuFamily.Avr8, McuFamily.Rp2040, McuFamily.Stm32),
                setOf(McuFamily.Nrf52), Recorded.Known(true)),
            FirmwareSupport("zmk", setOf(McuFamily.Nrf52, McuFamily.Rp2040, McuFamily.Stm32, McuFamily.Samd21),
                setOf(McuFamily.Avr8), Recorded.Known(true)),
            FirmwareSupport("kmk", setOf(McuFamily.Nrf52, McuFamily.Rp2040), emptySet(), Recorded.Known(true)),
            FirmwareSupport("bluemicro", setOf(McuFamily.Nrf52), emptySet(), Recorded.Unknown),
        )
    )

    private fun Design.against(vararg boards: Board) = connectivityFor(boards.toList(), rules)

    // -- socketed: the swap, which is the whole feature -------------------------------

    /**
     * The owner's own sentence, as a test: *"corne was designed with pro micro which is
     * wired only BUT you CAN actually swap it out with nice!nano to get wireless"*. The
     * board is named rather than counted, because "wireless with a nice!nano v2" is a
     * sentence a reader can act on and "1 wireless option" is not.
     */
    @Test
    fun `a ZMK design on a Pro Micro socket is wireless with a nice!nano`() {
        val zmk = design(firmware = Recorded.Known(setOf(FirmwareRef.Zmk("corne"))))

        val fit = assertIs<Connectivity.WirelessWithController>(zmk.against(niceNano, liatris))
        assertEquals(listOf("nice!nano v2"), fit.boards)
    }

    /**
     * **THIS TEST USED TO ASSERT THE BUG**, and that is worth recording rather than
     * quietly editing. It read `assertIs<WiredOnly.NoWirelessBoardFits>` and passed for
     * six days, because the derivation and the test made the same mistake: they treated
     * "QMK cannot run it" as "it does not fit". The socket fact and the firmware fact are
     * different facts, and nothing failed while they were conflated -- a green suite is
     * not evidence when the assertion encodes the misreading.
     *
     * THE GATE IS REAL, only misplaced. Every wireless board in the catalogue is nRF52 and
     * QMK's own docs exclude nRF52, so a QMK-only design genuinely cannot be flashed
     * wireless as it stands. What does not follow -- and what 135 live pages claimed -- is
     * that no wireless controller fits the socket. A nice!nano fits a Pro Micro socket
     * whatever firmware the design ships; that is what a footprint IS.
     */
    @Test
    fun `a QMK-only design's socket still takes a wireless board`() {
        val fit = assertIs<Connectivity.WirelessNeedsFirmware>(design().against(niceNano, liatris))

        assertEquals(listOf("nice!nano v2"), fit.boards)
        assertEquals(setOf(ControllerFootprint.ProMicro), fit.footprints)
    }

    /**
     * THE SIBLINGS FROM #98, which is the proof that convinced the owner and so is the
     * proof that belongs in the suite. `jorne-cherry-2-1` (QMK) and `jorne-cherry-3-0`
     * (ZMK) are one physical socket and gave opposite answers -- and the wrong half was
     * not "ZMK is wireless", it was "nothing fits". Asserted as a PAIR, because either
     * answer alone looks reasonable and it is the contradiction between them that was the
     * bug.
     */
    @Test
    fun `two designs on one socket differ only in the firmware record`() {
        val qmk = design(firmware = Recorded.Known(setOf(FirmwareRef.Qmk())))
        val zmk = design(firmware = Recorded.Known(setOf(FirmwareRef.Zmk("jorne"))))

        val gap = assertIs<Connectivity.WirelessNeedsFirmware>(qmk.against(niceNano, liatris))
        val fit = assertIs<Connectivity.WirelessWithController>(zmk.against(niceNano, liatris))

        // The SAME board is named by both. That is the point: the hardware never differed.
        assertEquals(fit.boards, gap.boards)
    }

    /**
     * THE MEMBER THAT STILL MEANS "NO", and keeping it reachable is what makes the split
     * honest rather than a blanket amnesty. A socket with catalogued boards, none of them
     * wireless, is a real "no wireless controller we know of fits" -- 19 designs on
     * `rp2040_zero`, `rpi_pico` and `blue_pill` in the live corpus.
     *
     * Without this, #98 would have emptied `NoWirelessBoardFits` and left a rail row
     * nobody can reach.
     */
    @Test
    fun `a socket with no wireless board cataloged is still a no`() {
        val fit = assertIs<Connectivity.WiredOnly.NoWirelessBoardFits>(design().against(liatris))

        assertEquals(setOf(ControllerFootprint.ProMicro), fit.footprints)
    }

    /**
     * NO ROW FOR THE FOOTPRINT IS NOT "NO WIRELESS BOARD EXISTS". The first measurement of
     * this facet filed 10 `rp2040_zero` designs under "no wireless option" while the
     * catalogue held no `rp2040_zero` board at all -- an empty catalogue read as a
     * finding, which is `project_extractors_prove_presence`. Distinct member, distinct
     * sentence.
     */
    @Test
    fun `a footprint with no cataloged board is a maybe, not a no`() {
        val orphan = design(controller = Controller.Socket(ControllerFootprint.Rp2040Zero))

        val fit = assertIs<Connectivity.Unrecorded.NoBoardsCataloged>(orphan.against(niceNano, liatris))
        assertEquals(setOf(ControllerFootprint.Rp2040Zero), fit.footprints)
    }

    /**
     * A wireless board fits and runs the design's firmware, but that firmware's own
     * wireless support is unrecorded. It must not collapse into "no" -- the owner's
     * no-hardcoding ruling exists so a firmware can gain wireless support later, and an
     * `Unknown` silently read as `false` is how that would go unnoticed.
     */
    @Test
    fun `a fitting wireless board whose firmware's wireless support is unknown is a maybe`() {
        val exotic = design(firmware = Recorded.Known(setOf(FirmwareRef.Custom("BlueMicro"))))

        val fit = assertIs<Connectivity.Unrecorded.FirmwareWirelessUnknown>(exotic.against(blueMicro))
        assertEquals("bluemicro", fit.firmware)
    }

    /**
     * BOTH FACTS ARE REQUIRED: a firmware that does wireless AND a board with a radio.
     * `Blok` is the real case -- a wired RP2040 board that runs ZMK -- so a ZMK design on
     * a Blok is NOT wireless, however wireless-capable ZMK is. Flashing a radio-less board
     * with a radio-capable firmware does not conjure a radio.
     *
     * ADDED BECAUSE MUTATION TESTING FOUND ITS ABSENCE. Deleting the `board.wireless`
     * filter left every other assertion in this class green: on a nice!nano the answer was
     * already yes, and on a Liatris QMK's own `wireless = false` happened to catch it. It
     * took a board that is wired AND runs a wireless firmware to tell the two checks
     * apart, and that board exists in the catalogue.
     */
    @Test
    fun `a wired board running a wireless-capable firmware is still not wireless`() {
        val blok = Board(
            "Blok", ControllerFootprint.ProMicro, false,
            Recorded.Known(McuFamily.Rp2040), setOf("qmk", "zmk"),
        )
        val zmk = design(firmware = Recorded.Known(setOf(FirmwareRef.Zmk("corne"))))

        assertEquals(Connectivity.WiredOnly.NoWirelessBoardFits(setOf(ControllerFootprint.ProMicro)), zmk.against(blok))
    }

    /** A design shipping both is wireless via the one that does wireless. */
    @Test
    fun `a dual-firmware design is wireless through whichever half can be`() {
        val both = design(firmware = Recorded.Known(setOf(FirmwareRef.Qmk(), FirmwareRef.Zmk("corne"))))

        assertIs<Connectivity.WirelessWithController>(both.against(niceNano, liatris))
    }

    // -- one PCB, several footprints (#99) -------------------------------------------

    /**
     * `prkl30`, the case #99 was rewritten around. One PCB places a Pro Micro footprint
     * AND a Feather footprint, and the design ships QMK. On the Pro Micro alone it is
     * [Connectivity.WirelessNeedsFirmware] -- a nice!nano fits and QMK cannot run it --
     * but the Feather is a proven route, and a proven route on ANY footprint is the
     * answer. The page must name the Feather, not ask for a ZMK config.
     */
    private val proMicroOrFeather =
        Controller.Socket(setOf(ControllerFootprint.ProMicro, ControllerFootprint.Feather))

    @Test
    fun `a PCB that takes a Pro Micro or a Feather is wireless through the Feather`() {
        val prkl30 = design(controller = proMicroOrFeather)

        val fit = assertIs<Connectivity.WirelessWithController>(prkl30.against(niceNano, liatris, feather))
        assertEquals(listOf("Adafruit Feather 32u4 Bluefruit LE"), fit.boards)
    }

    /** The other half of the same PCB: without the Feather row it is what it was. */
    @Test
    fun `the same PCB with no Feather cataloged still gets the nice!nano swap`() {
        val prkl30 = design(controller = proMicroOrFeather)

        val gap = assertIs<Connectivity.WirelessNeedsFirmware>(prkl30.against(niceNano, liatris))
        assertEquals(listOf("nice!nano v2"), gap.boards)
    }

    /**
     * A FOOTPRINT WITH NOTHING CATALOGUED MUST NOT HIDE A FACT ABOUT THE OTHER ONE. With
     * one footprint the two cannot collide -- an empty catalogue means nothing fits -- so
     * this ordering question only exists once a socket holds a set.
     */
    @Test
    fun `a wireless board on one footprint outranks an uncataloged other footprint`() {
        val board = design(controller = Controller.Socket(setOf(ControllerFootprint.ProMicro, ControllerFootprint.SeeedXiao)))

        assertIs<Connectivity.WirelessNeedsFirmware>(board.against(niceNano, liatris))
    }

    /**
     * And the "no" has to be earned on EVERY footprint. Wired boards on the Pro Micro say
     * nothing about the XIAO position nobody has catalogued, so this is a maybe, and it
     * names the footprint that is missing.
     */
    @Test
    fun `wired boards on one footprint and nothing cataloged on the other is a maybe`() {
        val board = design(controller = Controller.Socket(setOf(ControllerFootprint.ProMicro, ControllerFootprint.SeeedXiao)))

        val fit = assertIs<Connectivity.Unrecorded.NoBoardsCataloged>(board.against(liatris))
        assertEquals(setOf(ControllerFootprint.SeeedXiao), fit.footprints)
    }

    @Test
    fun `a socket with no footprint is refused`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { Controller.Socket(emptySet()) }
    }

    // -- soldered: the asymmetry that keeps the facet useful --------------------------

    /**
     * **THE ASYMMETRY.** A chip with no radio is an absolute no, needing no further fact,
     * and it is what keeps 129 of 137 soldered designs out of "maybe". Without it the
     * antenna requirement would put all 137 there and the facet would be useless.
     */
    @Test
    fun `a soldered radio-less chip is a hard no on physics`() {
        val avr = design(controller = Controller.Soldered(Recorded.Known(Mcu.Atmega32u4)))

        assertEquals(Connectivity.WiredOnly.ChipHasNoRadio(Mcu.Atmega32u4), avr.against(niceNano))
    }

    /**
     * The other half of the same asymmetry: a radio-capable chip is NOT a yes. The radio
     * is on-die but the ANT pin is unmatched, so the antenna and matching network are the
     * board designer's job -- the owner's point, and it holds.
     *
     * Unreachable in today's corpus (no soldered design is nRF52), which is exactly why it
     * is tested: arriving data must be a data change.
     */
    @Test
    fun `a soldered radio-capable chip is a maybe, never a yes`() {
        val nrf = design(controller = Controller.Soldered(Recorded.Known(Mcu.Nrf52840)))

        assertEquals(Connectivity.Unrecorded.RadioNotConfirmed(Mcu.Nrf52840), nrf.against(niceNano))
    }

    /** The owner's rule 1, and the 8 designs it covers -- 7 Ferris variants + dropin-v2. */
    @Test
    fun `a soldered design with no recorded chip is a maybe`() {
        val unknown = design(controller = Controller.Soldered(Recorded.Unknown))

        assertEquals(Connectivity.Unrecorded.ChipNotRecorded, unknown.against(niceNano))
    }

    /** A soldered design ignores the catalogue entirely: nothing plugs in. */
    @Test
    fun `a soldered design's answer does not depend on the catalog`() {
        val avr = design(controller = Controller.Soldered(Recorded.Known(Mcu.Atmega32u4)))

        assertEquals(avr.against(), avr.against(niceNano, liatris, blueMicro))
    }

    // -- the three facet values --------------------------------------------------------

    /**
     * THREE VALUES AND A NULL, the shape after #98. `maybe` is still not among them: the
     * 2026-09-10 rename established that an unestablished answer projects **null**, so
     * those designs land in the rail's ordinary "Not recorded" tier, and the new member
     * does not reopen that. `wireless_needs_firmware` is a POSITIVE fact about a socket,
     * not a hedge about a design.
     *
     * ASSERTED AS A MAPPING RATHER THAN A SET, because the grouping is the product
     * decision: all four `Unrecorded` reasons must collapse to the same absence, and both
     * `WiredOnly` members to one value despite reading differently on the page -- while
     * `WirelessNeedsFirmware` must NOT join either neighbour, which is the entire ruling.
     */
    @Test
    fun `the four unrecorded reasons project no row, and the two wired ones share a value`() {
        val states = listOf(
            Connectivity.WirelessWithController(listOf("nice!nano v2")),
            Connectivity.WirelessNeedsFirmware(listOf("nice!nano v2"), setOf(ControllerFootprint.ProMicro), listOf("kmk", "zmk")),
            Connectivity.Unrecorded.ChipNotRecorded,
            Connectivity.Unrecorded.RadioNotConfirmed(Mcu.Nrf52840),
            Connectivity.Unrecorded.NoBoardsCataloged(setOf(ControllerFootprint.Rp2040Zero)),
            Connectivity.Unrecorded.FirmwareWirelessUnknown("bluemicro"),
            Connectivity.WiredOnly.ChipHasNoRadio(Mcu.Atmega32u4),
            Connectivity.WiredOnly.NoWirelessBoardFits(setOf(ControllerFootprint.ProMicro)),
        )

        assertEquals(
            listOf(
                "wireless", "wireless_needs_firmware", null, null, null, null,
                "wired_only", "wired_only",
            ),
            states.map { it.facetValue() },
        )
        // No fourth value can appear without this failing -- the guard on the rail's shape.
        assertEquals(
            setOf("wireless", "wireless_needs_firmware", "wired_only"),
            states.mapNotNull { it.facetValue() }.toSet(),
        )
        /*
         * THE ONE THAT MUST NOT COLLAPSE. #98's ruling was that the new state shares a
         * filter with neither neighbour: merged into `wireless` a searcher gets designs
         * needing firmware work unflagged, and left in `wired_only` they are unfindable,
         * which was the objection. Asserted directly rather than trusting the list above
         * to be read carefully.
         */
        val gap = Connectivity.WirelessNeedsFirmware(listOf("nice!nano v2"), setOf(ControllerFootprint.ProMicro), listOf("kmk", "zmk"))
        assertEquals("wireless_needs_firmware", gap.facetValue())
    }

    // -- firmware not recorded (#106) ----------------------------------------------------

    /**
     * A wireless board fits the socket and we do not know what the design runs, so it is a
     * maybe. Not [Connectivity.WirelessNeedsFirmware]: that says the design ships a firmware
     * the board cannot run, and we know nothing of the kind.
     */
    @Test
    fun `a socketed design with no firmware record is a maybe when a wireless board fits`() {
        val unknown = design(firmware = Recorded.Unknown)

        assertEquals(
            Connectivity.Unrecorded.FirmwareNotRecorded(listOf("nice!nano v2")),
            unknown.against(niceNano, liatris),
        )
        assertEquals(null, Connectivity.Unrecorded.FirmwareNotRecorded(listOf("nice!nano v2")).facetValue())
    }

    /** No wireless board fits the socket: a physical fact, whatever firmware it runs. */
    @Test
    fun `a socketed design with no firmware record and no wireless board that fits is wired`() {
        val unknown = design(firmware = Recorded.Unknown)

        assertIs<Connectivity.WiredOnly.NoWirelessBoardFits>(unknown.against(liatris))
    }
}
