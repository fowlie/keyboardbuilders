package net.keyboardbuilders.keyboardbuilders.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Door B run backwards -- `/parts/dev-board/:slug` asking "what can I build with the
 * board in my drawer" (owner's ruling, 2026-09-10). Three tiers: a 1:1 fit, the same
 * socket with the firmware unproven, and the same socket with a documented
 * impossibility.
 *
 * THE THIRD TIER IS THE ONLY ONE THAT NEEDED A NEW FACT, and this class is mostly about
 * keeping it small. `seed/controllers.toml`'s `firmware` is a POSITIVE list, so its
 * silence is Unknown and never Impossible -- the two rules that are genuinely
 * impossibility come from QMK's and ZMK's own documentation at MCU-family level. A test
 * that let a third rule in by accident would be the 2026-09-04 failure again, where
 * reading QMK support off the `development_board` enum put a false sentence on 16 live
 * pages (docs/research/controller-catalogue.md §3.4).
 */
class ControllerFitTest {

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

    /**
     * The rules, as `seed/firmwares.toml` states them -- a FIXTURE, not the real file.
     * `SeedFirmwareCatalogTest` cross-checks the real one; this class is about the rule
     * ENGINE, and a fixture is what lets it exercise a firmware whose wireless support is
     * `Unknown` (which today only `bluemicro` is) without depending on that staying true.
     */
    private val rules = FirmwareRules.of(
        listOf(
            FirmwareSupport("qmk", setOf(McuFamily.Avr8, McuFamily.Rp2040, McuFamily.Stm32),
                setOf(McuFamily.Nrf52), Recorded.Known(false)),
            FirmwareSupport("zmk", setOf(McuFamily.Nrf52, McuFamily.Rp2040, McuFamily.Stm32, McuFamily.Samd21),
                setOf(McuFamily.Avr8), Recorded.Known(true)),
            FirmwareSupport("kmk", setOf(McuFamily.Nrf52, McuFamily.Rp2040),
                emptySet(), Recorded.Known(true)),
            FirmwareSupport("bluemicro", setOf(McuFamily.Nrf52), emptySet(), Recorded.Unknown),
        )
    )

    /** The nice!nano, as `seed/controllers.toml` records it. */
    private fun Design.againstNiceNano() =
        fitFor(ControllerFootprint.ProMicro, setOf("zmk"), Recorded.Known(McuFamily.Nrf52), rules)

    /** Liatris: RP2040, QMK-only, same footprint. */
    private fun Design.againstLiatris() =
        fitFor(ControllerFootprint.ProMicro, setOf("qmk"), Recorded.Known(McuFamily.Rp2040), rules)

    // -- the footprint gate ------------------------------------------------------------

    /**
     * Null is "not on this page", never a tier. Backlog #39 decided that filing one
     * footprint under another states a board plugs into something it does not fit, and a
     * fourth tier reading "different connector" would invite exactly that reading back.
     */
    @Test
    fun `a design socketing another footprint is not on the page at all`() {
        val xiao = design(controller = Controller.Socket(ControllerFootprint.SeeedXiao))

        assertNull(xiao.againstNiceNano())
    }

    /**
     * #99: a PCB offering two footprints is on BOTH boards' pages. `prkl30` takes a Pro
     * Micro or a Feather, so the nice!nano's page lists it, and so will the Feather's.
     */
    @Test
    fun `a design offering several footprints is on the page of each`() {
        val prkl30 = design(controller = Controller.Socket(setOf(ControllerFootprint.ProMicro, ControllerFootprint.Feather)))

        assertNotNull(prkl30.againstNiceNano())
        assertNotNull(prkl30.fitFor(ControllerFootprint.Feather, setOf("qmk"), Recorded.Known(McuFamily.Avr8), rules))
        assertNull(prkl30.fitFor(ControllerFootprint.SeeedXiao, setOf("qmk"), Recorded.Known(McuFamily.Rp2040), rules))
    }

    /** Ticket 50: a soldered design has no socket, so there is nothing to compare. */
    @Test
    fun `a soldered design is not on the page at all`() {
        val soldered = design(controller = Controller.Soldered(Recorded.Known(Mcu.Rp2040)))

        assertNull(soldered.againstNiceNano())
    }

    // -- tier 1: the 1:1 fit -----------------------------------------------------------

    @Test
    fun `a ZMK design on a ZMK board runs, and the overlap is the fact carried`() {
        val zmk = design(firmware = Recorded.Known(setOf(FirmwareRef.Zmk("corne"))))

        assertEquals(ControllerFit.Runs(setOf("zmk")), zmk.againstNiceNano())
    }

    /**
     * A design shipping both families on a board running one of them is a fit, and the
     * row should say which one -- "runs ZMK" and "runs QMK or ZMK" are different
     * sentences to a reader who owns one flashing setup.
     */
    @Test
    fun `a dual-firmware design reports only the family the board actually runs`() {
        val both = design(firmware = Recorded.Known(setOf(FirmwareRef.Qmk(), FirmwareRef.Zmk("corne"))))

        assertEquals(ControllerFit.Runs(setOf("zmk")), both.againstNiceNano())
    }

    // -- tier 1, and the chip check that keeps it honest -------------------------------

    /**
     * THE OVERCLAIM THE FORWARD PAGE CAN AFFORD AND THIS ONE CANNOT.
     * `FirmwareRef.Qmk.targetMcu` exists for exactly this reader: within the single
     * `pro_micro` footprint, 38 designs target atmega32u4 and 5 target RP2040, so
     * "both say QMK" is not "you can flash it". A reader who buys a Liatris on the word
     * "fits" and then cannot compile has been told a wrong thing.
     */
    @Test
    fun `a QMK design built for AVR needs a port onto an RP2040 board`() {
        val avr = design(firmware = Recorded.Known(setOf(FirmwareRef.Qmk(targetMcu = Recorded.Known(Mcu.Atmega32u4)))))

        val fit = assertIs<ControllerFit.NeedsPort>(avr.againstLiatris())
        assertEquals(Mcu.Atmega32u4, fit.designMcu)
        assertEquals(McuFamily.Rp2040, fit.boardFamily)
    }

    @Test
    fun `a QMK design built for the board's own chip family is a plain fit`() {
        val rp = design(firmware = Recorded.Known(setOf(FirmwareRef.Qmk(targetMcu = Recorded.Known(Mcu.Rp2040)))))

        assertEquals(ControllerFit.Runs(setOf("qmk")), rp.againstLiatris())
    }

    /**
     * AN UNRECORDED TARGET CHIP STAYS IN THE TOP TIER. Demoting it would turn "nobody
     * recorded this design's target chip" into "this probably will not work", which is
     * the absence-read-as-a-fact failure that cost 11 false pages on #66. Coverage is
     * 120 of 188 socketed designs, so this is the majority case, not an edge.
     */
    @Test
    fun `an unrecorded target chip does not demote a matching firmware family`() {
        assertEquals(ControllerFit.Runs(setOf("qmk")), design().againstLiatris())
    }

    /**
     * A design citing several QMK records is buildable if ANY of them targets the board's
     * family -- a builder needs one record that compiles, not all of them.
     */
    @Test
    fun `one buildable QMK record among several is enough`() {
        val mixed = design(
            firmware = Recorded.Known(setOf(
                FirmwareRef.Qmk("crkbd/rev1", Recorded.Known(Mcu.Atmega32u4)),
                FirmwareRef.Qmk("crkbd/rev4", Recorded.Known(Mcu.Rp2040)),
            ))
        )

        assertEquals(ControllerFit.Runs(setOf("qmk")), mixed.againstLiatris())
    }

    /**
     * The chip mismatch must not swallow a second family that does run. A Blok runs QMK
     * and ZMK; a design shipping an AVR-targeted QMK record and a ZMK shield is
     * buildable on it via ZMK, and the row should say so rather than offering a port.
     */
    @Test
    fun `a chip mismatch on QMK still reports another family the board runs`() {
        val both = design(
            firmware = Recorded.Known(setOf(
                FirmwareRef.Qmk(targetMcu = Recorded.Known(Mcu.Atmega32u4)),
                FirmwareRef.Zmk("corne"),
            ))
        )
        val blok = both.fitFor(
            ControllerFootprint.ProMicro, setOf("qmk", "zmk"), Recorded.Known(McuFamily.Rp2040), rules,
        )

        assertEquals(ControllerFit.Runs(setOf("zmk")), blok)
    }

    // -- tier 2: same socket, nothing known either way ---------------------------------

    /**
     * Seeeduino XIAO is SAMD21, which appears NOWHERE in QMK's supported list -- and is
     * still not ruled out, because "absent from a list" is absence of evidence.
     * §3.4 keeps it ZMK-only for that reason and this is the tier that says so.
     */
    @Test
    fun `a chip merely missing from a firmware's list is unproven, not ruled out`() {
        val qmk = design()

        val fit = qmk.fitFor(
            ControllerFootprint.ProMicro, setOf("zmk"), Recorded.Known(McuFamily.Samd21), rules,
        )

        assertEquals(ControllerFit.SocketOnly(setOf("qmk")), fit)
    }

    /**
     * The five boards the research doc names no chip for (michi, imera, svlinky, bonsai
     * C4, bonsai C3). No rule can fire without a family, which is the correct outcome for
     * a chip nobody has read -- and the reason `unknown` is stated in the seed rather
     * than guessed.
     */
    @Test
    fun `a board with no recorded chip rules nothing out`() {
        val zmk = design(firmware = Recorded.Known(setOf(FirmwareRef.Zmk("corne"))))

        val fit = zmk.fitFor(ControllerFootprint.ProMicro, setOf("qmk"), Recorded.Unknown, rules)

        assertEquals(ControllerFit.SocketOnly(setOf("zmk")), fit)
    }

    // -- tier 3: the two documented impossibilities ------------------------------------

    /**
     * `docs.qmk.fm/compatible_microcontrollers` lists AVR, STM32, RP2040, Kinetis, WB32,
     * AT32 and GD32 -- no nRF52 and no Bluetooth anywhere. This is the tier's headline
     * case and the one a reader hits most: 118 of the 153 Pro Micro designs are QMK-only,
     * and a nice!nano cannot build any of them.
     */
    @Test
    fun `a QMK-only design is ruled out on an nRF52 board`() {
        val fit = assertIs<ControllerFit.RuledOut>(design().againstNiceNano())

        assertEquals(McuFamily.Nrf52, fit.family)
        assertEquals(setOf("qmk"), fit.firmwares)
    }

    /**
     * ZMK's own FAQ: *"Zephyr™ only supports 32-bit and 64-bit platforms."* Fires on no
     * catalogue board today -- `seed/controllers.toml` has no plain ATmega32U4 Pro Micro
     * row, per backlog #39 -- so this test is what makes adding that row a data change
     * rather than a code change.
     */
    @Test
    fun `a ZMK-only design is ruled out on an 8-bit AVR board`() {
        val zmk = design(firmware = Recorded.Known(setOf(FirmwareRef.Zmk("corne"))))

        val fit = zmk.fitFor(ControllerFootprint.ProMicro, setOf("qmk"), Recorded.Known(McuFamily.Avr8), rules)

        val ruled = assertIs<ControllerFit.RuledOut>(fit)
        assertEquals(McuFamily.Avr8, ruled.family)
        assertEquals(setOf("zmk"), ruled.firmwares)
    }

    /**
     * A rule reaching only ONE of a design's firmwares does not rule the design out. In
     * practice such a design matches tier 1 first, so this pins the guard rather than a
     * page: the day a board runs a family we do not model, the fallback must not become
     * "ruled out" on a partial match.
     */
    @Test
    fun `a rule against one of two firmwares does not rule the design out`() {
        val both = design(firmware = Recorded.Known(setOf(FirmwareRef.Qmk(), FirmwareRef.Zmk("corne"))))

        // An nRF52 board recorded as running neither family: QMK is barred, ZMK is not.
        val fit = both.fitFor(ControllerFootprint.ProMicro, setOf("kmk"), Recorded.Known(McuFamily.Nrf52), rules)

        assertEquals(ControllerFit.SocketOnly(setOf("qmk", "zmk")), fit)
    }

    // -- the rule set itself -----------------------------------------------------------

    /**
     * THE GUARD ON THE WHOLE FILE. Two rules, named, and nothing else. A third arriving
     * without its own primary source is the 2026-09-04 regression, and it is much easier
     * to add one than to notice one was added -- the failure mode is a page that reads
     * perfectly while quietly refusing boards that work.
     */
    @Test
    fun `there are exactly two impossibility rules, and they are the documented two`() {
        assertEquals(
            setOf("qmk" to McuFamily.Nrf52, "zmk" to McuFamily.Avr8),
            rules.exclusions(),
        )
    }

    @Test
    fun `no rule bars a pairing outside its own firmware and family`() {
        for (family in McuFamily.entries) {
            for (firmware in listOf("qmk", "zmk", "kmk", "bluemicro")) {
                val expected = (firmware to family) in rules.exclusions()
                assertEquals(expected, rules.excludes(firmware, family), "$firmware on $family")
            }
        }
    }

    /**
     * A FAMILY WITH NO ROW BARS NOTHING. `familyKey()` lowercases a
     * `FirmwareRef.Custom`'s name, so a design citing a firmware this catalogue has never
     * heard of is real and reachable. The permissive answer is the only safe one: the
     * alternative -- treating "no row" as "runs nowhere" -- would rule such a design out
     * everywhere, which is absence read as a fact.
     */
    @Test
    fun `a firmware with no catalog row rules nothing out`() {
        val exotic = design(firmware = Recorded.Known(setOf(FirmwareRef.Custom("Vial-RS"))))

        assertEquals(ControllerFit.SocketOnly(setOf("vial-rs")), exotic.againstNiceNano())
    }

    /** Every `Mcu` member lands in a family, and the AVR members land in the one the ZMK
     *  rule is written against -- the mapping is what connects a design's recorded chip
     *  to a board's recorded family, so a miswired member is a silently lost rule. */
    @Test
    fun `every AVR chip the corpus names maps to the 8-bit AVR family`() {
        val avr = listOf(Mcu.Atmega16u2, Mcu.Atmega328p, Mcu.Atmega32a, Mcu.Atmega32u2, Mcu.Atmega32u4)

        assertTrue(avr.all { it.family() == McuFamily.Avr8 }, avr.map { it to it.family() }.toString())
        assertEquals(McuFamily.Nrf52, Mcu.Nrf52840.family())
        assertEquals(McuFamily.Rp2040, Mcu.Rp2040.family())
        assertEquals(McuFamily.Stm32, Mcu.Stm32f411.family())
    }

    // -- firmware not recorded (#106) ----------------------------------------------------

    /**
     * A design with no firmware record still sockets the board, and nothing is known
     * either way: [ControllerFit.SocketOnly]'s own definition. NOT [ControllerFit.RuledOut]:
     * an empty set of firmwares is trivially "all of them barred", which is how an unknown
     * would have become a documented impossibility.
     */
    @Test
    fun `a design whose firmware is not recorded only shares the socket`() {
        val unknown = design(firmware = Recorded.Unknown)

        assertEquals(ControllerFit.SocketOnly(emptySet()), unknown.againstNiceNano())
        assertEquals(ControllerFit.SocketOnly(emptySet()), unknown.againstLiatris())
    }
}
