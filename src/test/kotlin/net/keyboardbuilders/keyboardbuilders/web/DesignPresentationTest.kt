package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Ancestor
import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.Connectivity
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.DesignCore
import net.keyboardbuilders.keyboardbuilders.domain.DesignExtras
import net.keyboardbuilders.keyboardbuilders.domain.DesignName
import net.keyboardbuilders.keyboardbuilders.domain.FacetValue
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.FamilyName
import net.keyboardbuilders.keyboardbuilders.domain.McuFamily
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareSupport
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRules
import net.keyboardbuilders.keyboardbuilders.domain.Extension
import net.keyboardbuilders.keyboardbuilders.domain.CapFootprint
import net.keyboardbuilders.keyboardbuilders.domain.ComponentKind
import net.keyboardbuilders.keyboardbuilders.domain.Hand
import net.keyboardbuilders.keyboardbuilders.domain.HardestComponent
import net.keyboardbuilders.keyboardbuilders.domain.Hundredths
import net.keyboardbuilders.keyboardbuilders.domain.DiodeDirection
import net.keyboardbuilders.keyboardbuilders.domain.KeyCount
import net.keyboardbuilders.keyboardbuilders.domain.KeyWiring
import net.keyboardbuilders.keyboardbuilders.domain.PinCount
import net.keyboardbuilders.keyboardbuilders.domain.KeyPitch
import net.keyboardbuilders.keyboardbuilders.domain.KeycapFit
import net.keyboardbuilders.keyboardbuilders.domain.KeycapStem
import net.keyboardbuilders.keyboardbuilders.domain.Layout
import net.keyboardbuilders.keyboardbuilders.domain.LayoutName
import net.keyboardbuilders.keyboardbuilders.domain.License
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Licensing
import net.keyboardbuilders.keyboardbuilders.domain.Mcu
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Shorthand
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.SolderingDifficulty
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import net.keyboardbuilders.keyboardbuilders.domain.SplitHalves
import net.keyboardbuilders.keyboardbuilders.domain.SplitLink
import net.keyboardbuilders.keyboardbuilders.domain.UsbPortAnswer
import net.keyboardbuilders.keyboardbuilders.domain.UsbPort
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import net.keyboardbuilders.keyboardbuilders.domain.Term
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesignPresentationTest {

    /**
     * A two-entry geometry, not the shipped file. These tests are about how a design
     * turns into strings; wiring them to the real seed would make them fail whenever
     * `build_silhouettes.py` reruns. `SilhouettesTest` covers the drawing.
     */
    private val silhouettes = Silhouettes(
        jacksonObjectMapper(),
        ByteArrayResource(
            """{"test-board":{"w":6.0,"h":4.0,"keys":[[0.0,0.0,1.0,1.0],[1.0,0.0,1.0,1.0]]}}"""
                .toByteArray()
        ),
        ByteArrayResource("{}".toByteArray()),
    )

    /** A minimal design that passes the gate; each test varies the one field it is about. */
    private fun design(
        layouts: Set<Layout> = setOf(Layout(KeyCount(42))),
        switches: Set<SwitchFootprint> = setOf(SwitchFootprint.Mx),
        firmware: Recorded<Set<FirmwareRef>> = Recorded.Known(setOf(FirmwareRef.Qmk())),
        buildMethod: BuildMethod = BuildMethod.Pcb(),
        controller: Controller = Controller.Socket(ControllerFootprint.ProMicro),
        body: Body = Body.Unibody,
        licensing: Licensing = Licensing(
            hardware = LicenseState.Unstated,
            firmware = LicenseState.Unstated,
            documentation = LicenseState.Unstated,
        ),
        extras: DesignExtras = DesignExtras(),
        pitch: Recorded<KeyPitch> = Recorded.Unknown,
    ) = Design(
        core = DesignCore(
            name = DesignName("Test Board"),
            slug = Slug("test-board"),
            sources = setOf(SourceRef("https://example.com/board")),
            firmware = firmware,
            layouts = layouts,
            switches = switches,
            buildMethod = buildMethod,
            controller = controller,
            body = body,
            licensing = licensing,
            pitch = pitch,
        ),
        extras = extras,
    )

    /**
     * The real `seed/firmwares.toml` rules in miniature: QMK excludes nRF52 and does no
     * wireless, ZMK and KMK do. Door B's CONTROLLER section needs them since #98 -- it
     * names the firmware a wireless swap would want, and that name is read from here
     * rather than written into a string.
     */
    private val rules = FirmwareRules.of(
        listOf(
            FirmwareSupport("qmk", setOf(McuFamily.Avr8, McuFamily.Rp2040, McuFamily.Stm32),
                setOf(McuFamily.Nrf52), Recorded.Known(false)),
            FirmwareSupport("zmk", setOf(McuFamily.Nrf52, McuFamily.Rp2040, McuFamily.Stm32),
                setOf(McuFamily.Avr8), Recorded.Known(true)),
            FirmwareSupport("kmk", setOf(McuFamily.Nrf52, McuFamily.Rp2040), emptySet(),
                Recorded.Known(true)),
        )
    )

    private fun spec(page: DesignPage, label: String) = page.spec.single { it.label == label }

    // -- the three states of a fact, which is the point of the whole record --------

    // -- what the firmware is built for, which is not what is on the board ----------

    /**
     * THE ONLY CHIP FACT A SOCKETED DESIGN HAS. Its board carries no MCU — ticket 50 —
     * so `Controller.Socket` names a footprint and stops. What QMK's record states is
     * which chip the FIRMWARE is built for, and that is what tells a builder whether the
     * Pro Micro in their drawer will actually run it: 91 socketed designs in the corpus
     * target atmega32u4 and 4 target RP2040 on the same `pro_micro` footprint.
     */
    @Test
    fun `a socketed design names the chip its firmware is built for`() {
        val line = spec(
            design(firmware = Recorded.Known(setOf(
                FirmwareRef.Qmk("crkbd/rev1", Recorded.Known(Mcu.Atmega32u4))
            ))).toPage(silhouettes),
            "Firmware targets",
        )

        assertEquals("ATmega32U4", line.value)
        assertTrue(line.recorded)
    }

    /**
     * A KNOWN SOLDERED CHIP ALREADY ANSWERS IT. A record naming any other chip is dropped
     * at seed build (2026-10-02), so the line could only repeat the Controller line --
     * the owner, 2026-11-25: "isn't firmware target and controller much the same data?"
     */
    @Test
    fun `a board with its chip soldered on prints no firmware targets line`() {
        val page = design(
            controller = Controller.Soldered(Recorded.Known(Mcu.Atmega32u4)),
            firmware = Recorded.Known(setOf(FirmwareRef.Qmk("ferris/0_1", Recorded.Known(Mcu.Atmega32u4)))),
        ).toPage(silhouettes)

        assertTrue(page.spec.none { it.label == "Firmware targets" })
    }

    /** An unrecorded soldered chip leaves the firmware's target as the only chip fact. */
    @Test
    fun `a soldered board whose chip is not recorded keeps its firmware targets line`() {
        val line = spec(
            design(
                controller = Controller.Soldered(Recorded.Unknown),
                firmware = Recorded.Known(setOf(FirmwareRef.Qmk("x", Recorded.Known(Mcu.Rp2040)))),
            ).toPage(silhouettes),
            "Firmware targets",
        )

        assertEquals("RP2040", line.value)
    }

    /** A shared QMK record states nothing about any one design that cites it, so the
     *  extractor withholds it and this must read as ignorance rather than as absence. */
    @Test
    fun `a design whose record does not say reads as not recorded`() {
        val line = spec(
            design(firmware = Recorded.Known(setOf(FirmwareRef.Qmk("ianklug/grooveboard")))).toPage(silhouettes),
            "Firmware targets",
        )

        assertEquals(NOT_RECORDED, line.value)
        assertFalse(line.recorded)
    }

    /** Two records that disagree is a fact about the design, printed rather than
     *  resolved behind the reader's back. One design in the corpus is like this. */
    @Test
    fun `records that disagree about the chip both reach the page`() {
        val line = spec(
            design(firmware = Recorded.Known(setOf(
                FirmwareRef.Qmk("a", Recorded.Known(Mcu.Atmega32u4)),
                FirmwareRef.Qmk("b", Recorded.Known(Mcu.Rp2040)),
            ))).toPage(silhouettes),
            "Firmware targets",
        )

        assertEquals("ATmega32U4, RP2040", line.value)
    }

    /**
     * `extensions` carries evidence and nothing else — see [SeedExtensionsTest]. The page
     * is where a reader meets a value the rail spends no checkbox on.
     */
    @Test
    fun `room for names what the boards make room for`() {
        val line = spec(
            design(extras = DesignExtras(
                extensions = Recorded.Known(setOf(Extension.Encoder, Extension.Trackball)))
            ).toPage(silhouettes),
            "Room for",
        )

        assertEquals("encoder, trackball", line.value)
        assertTrue(line.recorded)
    }

    /** `ux.md` §3.1 — every detail page a lateral-exploration surface, and the only
     *  place a reader meets "trackpad", since the rail spends no checkbox on it. */
    @Test
    fun `each thing a board has room for links to the designs that share it`() {
        val line = spec(
            design(extras = DesignExtras(extensions = Recorded.Known(setOf(Extension.Trackpad))))
                .toPage(silhouettes),
            "Room for",
        )

        assertEquals(listOf("trackpad"), line.terms.map { it.text })
        assertEquals("/designs?ext=trackpad", line.terms.single().href)
    }

    // -- key wiring on a split: one half's scan, said as one half's ----------------------

    private fun wiringRow(keys: Int, body: Body, wiring: KeyWiring) = spec(
        design(
            layouts = setOf(Layout(KeyCount(keys))),
            body = body,
            extras = DesignExtras(wiring = Recorded.Known(wiring)),
        ).toPage(silhouettes),
        "Key wiring",
    )

    private val twoHalves = Body.Split(SplitHalves(2), Recorded.Unknown, Recorded.Unknown)

    /**
     * THE SWEEP HAS 34 DIRECT-WIRED KEYS, NOT 17. Its ZMK shield declares 17 inputs and
     * both halves include that one file, so the record is one half's and the page must
     * say so: "17 direct pins" on a 34-key board reads as half the keys missing.
     */
    @Test
    fun `a split whose wiring cannot reach every key says it is one half's`() {
        assertEquals("17 direct pins per half",
            wiringRow(34, twoHalves, KeyWiring.DirectPin(PinCount(17))).value)
        assertEquals("5×6 matrix per half, column-to-row diodes",
            wiringRow(58, twoHalves, KeyWiring.Matrix(PinCount(5), PinCount(6), DiodeDirection.ColToRow)).value)
    }

    /**
     * THE ARITHMETIC IS THE PROOF, AND WITHOUT IT THE PAGE SAYS NOTHING EXTRA. Some splits
     * run both halves from ONE controller, and their record is the whole board: Cheapino
     * (36 keys) is an 8×12 matrix, Pando (58) a 5×12. "Per half" there would be false.
     * Only a scan with fewer positions than the board has keys cannot be the whole board.
     */
    @Test
    fun `a split whose wiring could cover every key is not called one half's`() {
        assertEquals("8×12 matrix, row-to-column diodes",
            wiringRow(36, twoHalves, KeyWiring.Matrix(PinCount(8), PinCount(12), DiodeDirection.RowToCol)).value)
    }

    @Test
    fun `a unibody's wiring is the board's`() {
        assertEquals("4×12 matrix, column-to-row diodes",
            wiringRow(42, Body.Unibody, KeyWiring.Matrix(PinCount(4), PinCount(12), DiodeDirection.ColToRow)).value)
    }

    private fun hotswap(vararg mounts: SwitchMount) = spec(
        design(extras = DesignExtras(switchMounts = Recorded.Known(mounts.toSet()))).toPage(silhouettes),
        "Hotswap",
    )

    /**
     * THE PARTS, NOT THE PADS (#107 step 9, owner 2026-10-02). A builder holds a bag of
     * sockets, so the line names the parts that fit, each linking to the rail value.
     */
    @Test
    fun `hotswap names the parts that fit and links each`() {
        val mx = hotswap(SwitchMount.MxSocket)

        assertEquals("Kailh socket, Gateron socket", mx.value)
        assertEquals(listOf("/designs?hotswap=kailh", "/designs?hotswap=gateron"), mx.terms.map { it.href })
    }

    @Test
    fun `a board that also solders says so`() {
        assertEquals("Mill-Max, or solder the switches in", hotswap(SwitchMount.Solder, SwitchMount.MillMax).value)
    }

    /** Measured off the pads, so a solder-only board is a fact, not a gap. */
    @Test
    fun `a solder-only board says no`() {
        val line = hotswap(SwitchMount.Solder)

        assertEquals("No, the switches solder in", line.value)
        assertTrue(line.recorded)
    }

    /**
     * THE 223, AND THE CORRECTION THAT COST A DAY. This read "Room for: nothing" until
     * the measurement behind it was checked against QMK: the scan proves presence and
     * cannot prove absence, and `bastardkb/dilemma` — which declares a Cirque trackpad in
     * its own firmware — was one of 11 pages asserting the opposite.
     */
    @Test
    fun `a design we found no evidence for says not recorded, never nothing`() {
        val line = spec(design().toPage(silhouettes), "Room for")

        assertEquals(NOT_RECORDED, line.value)
        assertFalse(line.recorded)
        assertTrue(line.terms.isEmpty())
    }

    @Test
    fun `an undocumented PCB says its soldering is not recorded, and marks it as such`() {
        val line = spec(design(buildMethod = BuildMethod.Pcb()).toPage(silhouettes), "Soldering")

        assertEquals(NOT_RECORDED, line.value)
        assertFalse(line.recorded, "unrecorded soldering must render in the ignorance style")
    }

    @Test
    fun `a hand-wire says soldering does not apply, which is a value and not ignorance`() {
        val line = spec(design(buildMethod = BuildMethod.Handwire).toPage(silhouettes), "Soldering")

        assertEquals("n/a — no board to solder", line.value)
        assertTrue(line.recorded, "'does not apply' is a stated fact, not a gap")
    }

    /** Spacing is centre to centre and says nothing about stems. Ferris 0.1 Base is Choc
     *  switches at 19.05 mm, and the line once told it "MX-stem keycaps", which do not fit
     *  a Choc stem. Which caps fit is the keycap section's answer, from stem and spacing. */
    @Test
    fun `key spacing names the grid and its centre-to-centre size, never a keycap stem`() {
        fun line(p: KeyPitch) =
            spec(design(switches = setOf(SwitchFootprint.ChocV1), pitch = Recorded.Known(p)).toPage(silhouettes), "Key spacing").value

        assertEquals("MX spacing · 19.05 × 19.05 mm", line(KeyPitch.Mx))
        assertEquals("Choc spacing · 18 × 17 mm", line(KeyPitch.Choc))
        assertEquals("Non-standard · 17.50 mm", line(KeyPitch.Custom(Hundredths(1750))))
    }

    @Test
    fun `no license stated renders as all rights reserved rather than as a blank`() {
        val page = design().toPage(silhouettes)

        assertEquals("all rights reserved — no license stated", page.licenses.single { it.label == "Hardware" }.value)
    }

    @Test
    fun `SMD required is unknown for a PCB nobody documented, not false`() {
        val line = spec(design(buildMethod = BuildMethod.Pcb()).toPage(silhouettes), "SMD required")

        assertEquals(NOT_RECORDED, line.value)
        assertFalse(line.recorded)
    }

    @Test
    fun `SMD required is no for a documented through-hole board`() {
        val board = BuildMethod.Pcb(
            Recorded.Known(HardestComponent(SolderingDifficulty.ThroughHole, setOf(ComponentKind.Diode)))
        )

        assertEquals("no", spec(design(buildMethod = board).toPage(silhouettes), "SMD required").value)
    }

    @Test
    fun `SMD required is yes for a documented SMD board`() {
        val board = BuildMethod.Pcb(
            Recorded.Known(HardestComponent(SolderingDifficulty.SmdFine, setOf(ComponentKind.Usb)))
        )

        assertEquals("yes", spec(design(buildMethod = board).toPage(silhouettes), "SMD required").value)
    }

    @Test
    fun `a documented board names its hardest tier and every part tied for it`() {
        val board = BuildMethod.Pcb(
            Recorded.Known(
                HardestComponent(SolderingDifficulty.SmdHand, setOf(ComponentKind.Led, ComponentKind.Diode))
            )
        )

        val line = spec(design(buildMethod = board).toPage(silhouettes), "Soldering")

        assertEquals("SMD, hand-solderable (LED, diode)", line.value)
        assertTrue(line.recorded)
    }

    @Test
    fun `a socketed design names the footprint and no chip`() {
        val page = design(controller = Controller.Socket(ControllerFootprint.ProMicro)).toPage(silhouettes)

        assertEquals("Pro Micro socket", spec(page, "Controller").value)
    }

    @Test
    fun `a soldered design with an unrecorded chip says so instead of guessing one`() {
        val page = design(controller = Controller.Soldered()).toPage(silhouettes)

        assertEquals("soldered MCU, chip not recorded", spec(page, "Controller").value)
        assertEquals("soldered nRF52840", design(controller = Controller.Soldered(Recorded.Known(Mcu.Nrf52840))).toPage(silhouettes)
            .let { spec(it, "Controller").value })
    }

    // -- the result row --------------------------------------------------------

    /**
     * THE ROW IS THE SCANNING SURFACE; THE SPEC TABLE IS WHERE THIS SITE ADVERTISES ITS
     * OWN GAPS. `DesignRow.stars` already states that rule and follows it — a repo with
     * no known star count draws nothing rather than "not recorded". The controller chip
     * broke it: `MX · soldered MCU, chip not recorded · QMK` was the single most repeated
     * string on the index, 29 rows of it, and it spent the row's one discriminating line
     * saying what we failed to record.
     *
     * The page keeps the full phrase, because there the admission is the point.
     */
    @Test
    fun `the row does not spend its line on what we failed to record`() {
        val unrecorded = design(controller = Controller.Soldered())

        assertEquals("soldered MCU", unrecorded.toRow(silhouettes).electronics[1])
        assertEquals("soldered MCU, chip not recorded",
            spec(unrecorded.toPage(silhouettes), "Controller").value)
    }

    /** A chip we DO know is a fact about the keyboard, and reads the same in both. */
    @Test
    fun `a known chip reads the same on the row as on the page`() {
        val known = design(controller = Controller.Soldered(Recorded.Known(Mcu.Nrf52840)))

        assertEquals("soldered nRF52840", known.toRow(silhouettes).electronics[1])
        assertEquals("soldered nRF52840", spec(known.toPage(silhouettes), "Controller").value)
    }

    @Test
    fun `the row leads with the shorthand and folds several layouts into one key count`() {
        val row = design(
            layouts = setOf(
                Layout(KeyCount(42), Recorded.Known(Shorthand(3, 6, 3))),
                Layout(KeyCount(36), Recorded.Known(Shorthand(3, 5, 3))),
            ),
            body = Body.Split(SplitHalves(2), Recorded.Known(true), Recorded.Unknown),
        ).toRow(silhouettes)

        // "split" alone: a card leaves halves and PCBs to the design page (Body.brief).
        assertEquals(listOf("3x5+3", "3x6+3", "42 / 36 keys", "split"), row.shape)
    }

    /** A second geometry for the 36-key layout, distinguishable by width from the
     *  42-key one `silhouettes` (the class fixture) draws. */
    private val silhouettesWithLayouts = Silhouettes(
        jacksonObjectMapper(),
        ByteArrayResource(
            """{"test-board":{"w":6.0,"h":4.0,"keys":[[0.0,0.0,1.0,1.0],[1.0,0.0,1.0,1.0]]}}"""
                .toByteArray()
        ),
        ByteArrayResource(
            """{"test-board":{"36":{"w":3.0,"h":4.0,"keys":[[0.0,0.0,1.0,1.0]]}}}""".toByteArray()
        ),
    )

    private fun widthOf(svg: String) =
        Regex("""width="([0-9.]+)"""").find(svg)!!.groupValues[1].toDouble()

    @Test
    fun `a keys filter picks the layout it matched, when that layout has its own geometry`() {
        val twoLayout = design(
            layouts = setOf(Layout(KeyCount(42)), Layout(KeyCount(36))),
        )

        val filtered = twoLayout.toRow(
            silhouettesWithLayouts,
            keysFilter = setOf(Term.Exact(FacetValue("36"))),
        )
        val unfiltered = twoLayout.toRow(silhouettesWithLayouts)

        assertTrue(widthOf(filtered.silhouette) < widthOf(unfiltered.silhouette))
    }

    /**
     * A card cycles through every drawn layout on hover (owner, 2026-11-25), not only the
     * ones the keys filter matched: the card already prints "52 / 44 keys", so the picture
     * may show both. The filter picks which frame the card rests on, and that one is first.
     */
    @Test
    fun `a card carries a frame per drawn layout, starting from the one it rests on`() {
        val twoLayout = design(layouts = setOf(Layout(KeyCount(42)), Layout(KeyCount(36))))

        val unfiltered = twoLayout.toRow(silhouettesWithLayouts)
        val filtered = twoLayout.toRow(silhouettesWithLayouts, keysFilter = setOf(Term.Exact(FacetValue("36"))))

        assertEquals(listOf(42, 36), unfiltered.frames.map { it.keys })
        assertEquals(listOf(36, 42), filtered.frames.map { it.keys })
        assertEquals(filtered.silhouette, filtered.frames.first().svg)
        assertEquals(unfiltered.silhouette, unfiltered.frames.first().svg)
    }

    @Test
    fun `a layout with no drawing of its own gets no frame, so a one-drawing card does not cycle`() {
        val twoLayout = design(layouts = setOf(Layout(KeyCount(42)), Layout(KeyCount(36))))

        // `silhouettes`, the class fixture, has no per-layout geometry at all.
        val row = twoLayout.toRow(silhouettes)

        assertEquals(listOf(42), row.frames.map { it.keys })
        assertFalse(row.cycles)
    }

    /** The chip's numbers, each tied to the frame that draws it -- or to none. */
    @Test
    fun `the key count chip names which frame draws each count`() {
        val threeLayout = design(
            layouts = setOf(Layout(KeyCount(42)), Layout(KeyCount(39)), Layout(KeyCount(36))),
        )

        val row = threeLayout.toRow(silhouettesWithLayouts, keysFilter = setOf(Term.Exact(FacetValue("36"))))

        assertEquals("42 / 39 / 36 keys", row.keysChip)
        assertEquals(listOf(42 to 1, 39 to null, 36 to 0), row.keyCounts.map { it.keys to it.frame })
    }

    @Test
    fun `a keys filter falls back to the largest layout when no separate geometry was ever recorded for the match`() {
        val twoLayout = design(
            layouts = setOf(Layout(KeyCount(42)), Layout(KeyCount(36))),
        )

        // `silhouettes`, the class fixture, has no per-layout geometry at all.
        val filtered = twoLayout.toRow(silhouettes, keysFilter = setOf(Term.Exact(FacetValue("36"))))

        assertEquals(twoLayout.toRow(silhouettes).silhouette, filtered.silhouette)
    }

    /**
     * The lightbox caption is the picture's `aria-label`, so every layout's picture has to
     * carry its own name. KLOR's largest row borrowed the design's main picture, labeled
     * "KLOR, 42 keys, …", and opening it never said "Polydactyl".
     */
    @Test
    fun `every layout picture is labeled with that layout's name, the largest too`() {
        val klor = design(
            layouts = setOf(
                Layout(KeyCount(42), name = Recorded.Known(LayoutName("Polydactyl"))),
                Layout(KeyCount(36), name = Recorded.Known(LayoutName("Saegewerk"))),
            ),
        )

        val (largest, smallest) = klor.toPage(silhouettesWithLayouts).layouts

        assertTrue("42 keys — Polydactyl" in largest.silhouette!!, largest.silhouette)
        assertTrue("36 keys — Saegewerk" in smallest.silhouette!!, smallest.silhouette)
    }

    @Test
    fun `a layout with no stated shorthand contributes only its key count`() {
        val row = design(layouts = setOf(Layout(KeyCount(41)))).toRow(silhouettes)

        assertEquals(listOf("41 keys", "unibody"), row.shape)
    }

    @Test
    fun `the row shows the firmware family and leaves the keyboard path to the detail page`() {
        val design = design(firmware = Recorded.Known(setOf(FirmwareRef.Qmk("crkbd/rev1"))))

        assertEquals("QMK", design.toRow(silhouettes).electronics.last())
        assertEquals("QMK (crkbd/rev1)", spec(design.toPage(silhouettes), "Firmware").value)
    }

    @Test
    fun `switch footprints are listed in a stable order`() {
        val row = design(switches = setOf(SwitchFootprint.Mx, SwitchFootprint.ChocV1)).toRow(silhouettes)

        assertEquals("Choc v1 / MX", row.electronics.first())
    }

    @Test
    fun `a split whose reversibility nobody recorded says only that it is a split`() {
        val unknown = Body.Split(SplitHalves(2), Recorded.Unknown, Recorded.Unknown)
        assertEquals("split, 2 halves", unknown.describe())
    }

    @Test
    fun `a hand-specific split warns that both boards must be ordered`() {
        val handSpecific = Body.Split(SplitHalves(2), Recorded.Known(false), Recorded.Unknown)
        assertEquals("split, 2 halves (left and right PCBs differ, order both)",
                     handSpecific.describe())
    }

    private fun split(vararg links: SplitLink) =
        Body.Split(SplitHalves(2), Recorded.Unknown, Recorded.Known(links.toSet()))

    @Test
    fun `a TRRS link is named, because it is one more thing to buy`() {
        assertEquals("split, 2 halves, TRRS cable between halves", split(SplitLink.Trrs).describe())
    }

    @Test
    fun `an Ethernet link is named as the cable a builder buys, not as RJ45`() {
        assertEquals("split, 2 halves, Ethernet cable between halves", split(SplitLink.Ethernet).describe())
    }

    @Test
    fun `a Mini-DIN link is named by the S-Video cable a builder buys`() {
        assertEquals("split, 2 halves, Mini-DIN (S-Video) cable between halves", split(SplitLink.MiniDin).describe())
    }

    @Test
    fun `a USB-C link is named, because it is a second USB-C cable to buy`() {
        assertEquals("split, 2 halves, USB-C cable between halves", split(SplitLink.UsbC).describe())
    }

    @Test
    fun `a board offering two links names both, as the builder's choice`() {
        assertEquals("split, 2 halves, TRRS or USB-C cable between halves",
                     split(SplitLink.UsbC, SplitLink.Trrs).describe())
    }

    @Test
    fun `no link connector says nothing extra -- it could be wireless, or unbuilt`() {
        assertEquals("split, 2 halves", split().describe())
    }

    /**
     * A CARD SAYS "split" AND STOPS. Owner's call, 2026-09-26: the half count, the PCB
     * ordering note and the link cable are build detail, and belong on the design page
     * -- on a card they turned "split" into a sentence. The full wording above is
     * unchanged; this is a second, brief one.
     */
    @Test
    fun `a card names the body in one word, with no halves, PCBs or cables`() {
        val everything = Body.Split(SplitHalves(2), Recorded.Known(true), Recorded.Known(setOf(SplitLink.Trrs)))
        assertEquals("split", everything.brief())
        assertEquals("unibody", Body.Unibody.brief())
    }

    @Test
    fun `a rigid PCB bridge is named, because it is not a cable you can substitute`() {
        assertEquals("split, 2 halves, joined by a rigid PCB bridge", split(SplitLink.Bridge).describe())
    }

    @Test
    fun `the Spec table gives the link a row of its own, and leaves it off the Body row`() {
        val page = design(body = split(SplitLink.Trrs, SplitLink.UsbC)).toPage(silhouettes)

        assertEquals("split, 2 halves", spec(page, "Body").value)
        assertEquals("TRRS or USB-C cable", spec(page, "Split link").value)
    }

    @Test
    fun `the page's facts line says only split, and leaves halves and cable to the Spec table`() {
        val page = design(body = split(SplitLink.Trrs)).toPage(silhouettes)

        assertTrue("split" in page.shape, "facts line: ${page.shape}")
        assertTrue(page.shape.none { "halves" in it || "cable" in it }, "facts line: ${page.shape}")
        assertEquals("split, 2 halves", spec(page, "Body").value)
        assertEquals("TRRS cable", spec(page, "Split link").value)
    }

    @Test
    fun `a split with no named link connector is a gap, never a claim of none`() {
        // The catalog never builds this (it reads `[]` as Unknown), but the page must not
        // turn one into "none on the board" if it ever arrives: that claimed an absence
        // the scan cannot prove, and Pando's USB-C link read as exactly that.
        val page = design(body = split()).toPage(silhouettes)

        assertFalse(spec(page, "Split link").recorded, "an empty reading printed as an answer")
    }

    @Test
    fun `an unread link is a visible gap`() {
        val page = design(body = Body.Split(SplitHalves(2), Recorded.Unknown, Recorded.Unknown))
            .toPage(silhouettes)

        assertFalse(spec(page, "Split link").recorded)
    }

    @Test
    fun `a one-piece board has no Split link row at all`() {
        assertTrue(design().toPage(silhouettes).spec.none { it.label == "Split link" })
    }

    // -- USB port: which cable reaches the computer, backlog #97 -------------------------

    private fun usbRow(answer: UsbPortAnswer) = spec(design().toPage(silhouettes, usbPort = answer), "USB port")

    @Test
    fun `a port on the board is named in the words a builder shops with`() {
        assertEquals("USB-C", usbRow(UsbPortAnswer.OnBoard(UsbPort.UsbC)).value)
        assertEquals("micro-USB", usbRow(UsbPortAnswer.OnBoard(UsbPort.MicroB)).value)
        assertEquals("mini-USB", usbRow(UsbPortAnswer.OnBoard(UsbPort.MiniB)).value)
    }

    @Test
    fun `a daughterboard says the cable goes somewhere else`() {
        assertEquals("on a separate USB daughterboard", usbRow(UsbPortAnswer.Daughterboard).value)
    }

    @Test
    fun `a dev board that only comes one way says so, and says where`() {
        val row = usbRow(UsbPortAnswer.OnDevBoard(mapOf(UsbPort.UsbC to listOf("Seeed XIAO RP2040"))))

        assertEquals("USB-C, on the dev board", row.value)
    }

    @Test
    fun `a socket whose boards differ says it depends, and the hint says which board gives which`() {
        val row = usbRow(UsbPortAnswer.OnDevBoard(mapOf(
            UsbPort.MicroB to listOf("Pro Micro"),
            UsbPort.UsbC to listOf("nice!nano v2", "Pro Micro"),
        )))

        assertEquals("USB-C or micro-USB, depends on the dev board", row.value)
        assertEquals("USB-C: nice!nano v2, Pro Micro. Micro-USB: Pro Micro.", row.hint)
    }

    @Test
    fun `an unread port is a visible gap`() {
        assertFalse(usbRow(UsbPortAnswer.Unrecorded).recorded)
    }

    @Test
    fun `the USB port row sits under Electronics, right after Connectivity`() {
        val labels = design().toPage(silhouettes, usbPort = UsbPortAnswer.Daughterboard)
            .specSections.single { it.heading == "Electronics" }.lines.map { it.label }

        // Connectivity is absent here (no catalog passed), so USB port follows Controller.
        assertEquals(listOf("Controller", "USB port"), labels.take(2))
    }

    @Test
    fun `no answer passed means no row, as fixture tests of this function want`() {
        assertTrue(design().toPage(silhouettes).spec.none { it.label == "USB port" })
    }

    @Test
    fun `power switch prints as a yes or no under Electronics, and the gap as not recorded`() {
        fun line(extras: DesignExtras) = design(extras = extras).toPage(silhouettes)
            .specSections.single { it.heading == "Electronics" }.lines.single { it.label == "Power switch" }

        assertEquals("yes", line(DesignExtras(powerSwitch = Recorded.Known(true))).value)
        assertEquals("no", line(DesignExtras(powerSwitch = Recorded.Known(false))).value)
        assertFalse(line(DesignExtras()).recorded)
    }

    @Test
    fun `exposed components prints as a yes or no under Electronics, and the gap as not recorded`() {
        fun line(extras: DesignExtras) = design(extras = extras).toPage(silhouettes)
            .specSections.single { it.heading == "Electronics" }.lines.single { it.label == "Exposed components" }

        assertEquals("yes", line(DesignExtras(exposedComponents = Recorded.Known(true))).value)
        assertEquals("no", line(DesignExtras(exposedComponents = Recorded.Known(false))).value)
        assertFalse(line(DesignExtras()).recorded)
    }

    // -- the Spec table's sections, in the rail's own order ------------------------------

    @Test
    fun `the Spec table is grouped in the rail's order -- shape, switches, electronics, building`() {
        val page = design(body = split(SplitLink.Trrs)).toPage(silhouettes)

        assertEquals(listOf("Shape", "Switches", "Electronics", "Building it"),
                     page.specSections.map { it.heading })
        assertEquals(listOf("Body", "Split link", "Splay", "Stagger", "Key spacing"),
                     page.specSections[0].lines.map { it.label })
        assertEquals(listOf("Switch footprint", "Hotswap"),
                     page.specSections[1].lines.map { it.label })
        assertEquals(listOf("Build method", "Soldering", "SMD required"),
                     page.specSections[3].lines.map { it.label })
    }

    @Test
    fun `every spec line sits in exactly one section, so nothing is dropped by the grouping`() {
        val page = design(body = split(SplitLink.Trrs)).toPage(silhouettes)

        assertEquals(page.spec, page.specSections.flatMap { it.lines })
    }

    @Test
    fun `stars are not a spec of the keyboard, so they leave the Spec table`() {
        val page = design().toPage(silhouettes)

        assertTrue(page.spec.none { it.label == "Stars" })
        assertEquals("Stars", page.stars.label)
    }

    // -- the headline: what the page title calls the design --------------------------

    /**
     * "KEYBOARD" IS THE WORD PEOPLE SEARCH WITH. "corne keyboard", "sofle keyboard": the
     * title said "42 keys, split" and never the noun, so the query's second word matched
     * nothing on 338 pages. The owner's KLOR ruling (2026-09-26) still holds -- no halves,
     * no cable -- and these pin the form it takes now.
     */
    @Test
    fun `one layout reads as an N-key split keyboard`() {
        assertEquals("42-key split keyboard", design(body = split(SplitLink.Trrs)).toPage(silhouettes).headline)
    }

    @Test
    fun `a one-piece board is an N-key keyboard, whatever its size`() {
        // No macropad notion in the corpus, so a 4-key pad is called what it is.
        assertEquals("42-key keyboard", design().toPage(silhouettes).headline)
        assertEquals("4-key keyboard", design(layouts = setOf(Layout(KeyCount(4)))).toPage(silhouettes).headline)
    }

    @Test
    fun `several layouts keep every count, largest first, as the KLOR ruling left them`() {
        val klor = design(
            body = split(),
            layouts = setOf(36, 38, 40, 42).map { Layout(KeyCount(it)) }.toSet(),
        ).toPage(silhouettes)

        assertEquals("split keyboard with 42 / 40 / 38 / 36 keys", klor.headline)
    }

    @Test
    fun `a one-handed board says so in its headline`() {
        assertEquals(
            "42-key one-handed keyboard",
            design(body = Body.OneHanded(Hand.Right)).toPage(silhouettes).headline,
        )
    }

    // -- the description a design with no summary falls back to ----------------------

    /**
     * 166 OF 338 DESIGNS HAVE NO SUMMARY, and their search snippet was a comma list --
     * "Corne: 42 keys, split. MX, Pro Micro socket, QMK." -- that read as a fragment. The
     * fallback is now one sentence, built only from what is recorded, in the site's own
     * voice: a summary is a quotation of the author, and a sentence we wrote must not
     * pass for one ("The 3w6 is a low profile, split ortholinear keyboard..." is how the
     * authors' own sentences open).
     */
    @Test
    fun `a design with every fact recorded gets one sentence naming them`() {
        val page = design(
            body = split(SplitLink.Trrs),
            extras = DesignExtras(switchMounts = Recorded.Known(setOf(SwitchMount.MxSocket))),
        ).toPage(silhouettes)

        assertEquals(
            "Test Board: a 42-key split keyboard for MX switches (hotswap with Kailh or Gateron sockets), " +
                "with a Pro Micro socket and QMK firmware.",
            page.facts,
        )
    }

    @Test
    fun `nothing unrecorded reaches the sentence, and a gap never reads as a no`() {
        val page = design(
            controller = Controller.Soldered(),
            firmware = Recorded.Unknown,
        ).toPage(silhouettes)

        assertEquals(
            "Test Board: a 42-key keyboard for MX switches, with a soldered-on controller chip.",
            page.facts,
        )
    }

    @Test
    fun `a solder-only board is a recorded fact and says so`() {
        val page = design(
            extras = DesignExtras(switchMounts = Recorded.Known(setOf(SwitchMount.Solder))),
            controller = Controller.Soldered(Recorded.Known(Mcu.Rp2040)),
        ).toPage(silhouettes)

        assertEquals(
            "Test Board: a 42-key keyboard for MX switches (soldered in), " +
                "with a soldered RP2040 and QMK firmware.",
            page.facts,
        )
    }

    /**
     * MILL-MAX IS A HOLE SIZE, NOT A PCB. Every design in the corpus that takes Mill-Max
     * also takes soldered switches -- it is the same plated hole, wide enough for a
     * receptacle -- so a snippet naming the sockets alone turned 75 solder boards into
     * hotswap ones (the solder Lily58 among them). The choice stays a choice, as the
     * Hotswap spec line has it.
     */
    @Test
    fun `a board that takes Mill-Max or solder keeps the solder choice`() {
        val page = design(
            extras = DesignExtras(switchMounts = Recorded.Known(setOf(SwitchMount.MillMax, SwitchMount.Solder))),
        ).toPage(silhouettes)

        assertEquals(
            "Test Board: a 42-key keyboard for MX switches (soldered in, or hotswap with Mill-Max sockets), " +
                "with a Pro Micro socket and QMK firmware.",
            page.facts,
        )
    }

    @Test
    fun `sockets beside solder pads keep the solder choice too`() {
        val page = design(
            switches = setOf(SwitchFootprint.ChocV1),
            extras = DesignExtras(switchMounts = Recorded.Known(setOf(SwitchMount.ChocSocket, SwitchMount.Solder))),
        ).toPage(silhouettes)

        assertTrue("for Choc v1 switches (soldered in, or hotswap with Kailh sockets)" in page.facts, page.facts)
    }

    /** Only the state where a catalogued wireless board both fits and runs the firmware. */
    @Test
    fun `wireless is said only where a board fits and runs the firmware`() {
        val wireless = design(firmware = Recorded.Known(setOf(FirmwareRef.Zmk())))
            .toPage(silhouettes, connectivity = Connectivity.WirelessWithController(listOf("nice!nano v2")))
        val needsWork = design()
            .toPage(silhouettes, connectivity = Connectivity.WirelessNeedsFirmware(
                listOf("nice!nano v2"), setOf(ControllerFootprint.ProMicro), listOf("zmk"),
            ))

        assertTrue("a Pro Micro socket (wireless with a nice!nano v2)" in wireless.facts, wireless.facts)
        assertFalse("wireless" in needsWork.facts, needsWork.facts)
    }

    /** "Wireless" is in the query far more often than "QMK", so firmware goes first. */
    @Test
    fun `a sentence too long for both keeps the wireless board and drops the firmware`() {
        val page = design(
            body = split(SplitLink.Trrs),
            extras = DesignExtras(switchMounts = Recorded.Known(setOf(SwitchMount.MxSocket))),
            firmware = Recorded.Known(setOf(FirmwareRef.Qmk(), FirmwareRef.Zmk(), FirmwareRef.Kmk())),
        ).toPage(silhouettes, connectivity = Connectivity.WirelessWithController(listOf("nice!nano v2")))

        assertEquals(
            "Test Board: a 42-key split keyboard for MX switches (hotswap with Kailh or Gateron sockets), " +
                "with a Pro Micro socket (wireless with a nice!nano v2).",
            page.facts,
        )
    }

    @Test
    fun `the sentence fits a snippet by dropping the least needed fact, never mid-word`() {
        val page = design(
            body = split(SplitLink.Trrs),
            switches = setOf(SwitchFootprint.Mx, SwitchFootprint.ChocV1, SwitchFootprint.ChocV2),
            layouts = setOf(36, 38, 40, 42).map { Layout(KeyCount(it)) }.toSet(),
            extras = DesignExtras(switchMounts = Recorded.Known(setOf(SwitchMount.MxSocket, SwitchMount.MillMax))),
            firmware = Recorded.Known(setOf(FirmwareRef.Qmk(), FirmwareRef.Zmk(), FirmwareRef.Kmk())),
        ).toPage(silhouettes, connectivity = Connectivity.WirelessWithController(listOf("nice!nano v2")))

        assertTrue(page.facts.length <= 155, "${page.facts.length}: ${page.facts}")
        assertTrue(page.facts.endsWith(".") && "…" !in page.facts, page.facts)
        assertTrue("Choc v1, Choc v2 or MX switches" in page.facts, page.facts)
    }

    @Test
    fun `eight, eleven, eighteen and the eighties take an, as they are said`() {
        assertTrue("an 8-key keyboard" in design(layouts = setOf(Layout(KeyCount(8)))).toPage(silhouettes).facts)
        assertTrue("an 18-key keyboard" in design(layouts = setOf(Layout(KeyCount(18)))).toPage(silhouettes).facts)
        assertTrue("an 84-key keyboard" in design(layouts = setOf(Layout(KeyCount(84)))).toPage(silhouettes).facts)
        assertTrue("a 36-key keyboard" in design(layouts = setOf(Layout(KeyCount(36)))).toPage(silhouettes).facts)
    }

    /** "One" is said "wun": no live design is one-handed with several layouts yet. */
    @Test
    fun `a one-handed board with several layouts takes a, as one is said`() {
        val page = design(
            body = Body.OneHanded(Hand.Right),
            layouts = setOf(Layout(KeyCount(5)), Layout(KeyCount(4))),
        ).toPage(silhouettes)

        assertTrue("Test Board: a one-handed keyboard with 5 / 4 keys" in page.facts, page.facts)
    }

    // -- the Controller line links the board a socket is named for --------------------

    private val everyBoard = setOf("pro-micro", "raspberry-pi-pico", "rp2040-zero", "bluepill", "weact-blackpill")

    /**
     * A Pro Micro socket is named for the Pro Micro, and that board has a page saying which
     * designs it runs -- this one among them, in whichever list it belongs. Indexable, and
     * until now nothing on a design page linked to it.
     */
    @Test
    fun `a socket named for a board links to that board's page`() {
        val line = spec(design().toPage(silhouettes, devBoards = everyBoard), "Controller")

        assertEquals("Pro Micro socket", line.value)
        assertEquals(listOf(SpecTerm("Pro Micro socket", "/parts/dev-board/pro-micro")), line.terms)
    }

    @Test
    fun `a namesake with no page of its own is not linked`() {
        val line = spec(design().toPage(silhouettes, devBoards = setOf("nice-nano-v2")), "Controller")

        assertEquals("Pro Micro socket", line.value)
        assertTrue(line.terms.isEmpty(), "${line.terms}")
    }

    @Test
    fun `a footprint several boards share equally is not linked to any one of them`() {
        val xiao = design(controller = Controller.Socket(ControllerFootprint.SeeedXiao))
            .toPage(silhouettes, devBoards = everyBoard + "seeeduino-xiao")

        assertTrue(spec(xiao, "Controller").terms.isEmpty())
    }

    @Test
    fun `a socket offering a choice, and a soldered chip, stay plain text`() {
        val choice = design(controller = Controller.Socket(setOf(ControllerFootprint.ProMicro, ControllerFootprint.Feather)))
        val soldered = design(controller = Controller.Soldered(Recorded.Known(Mcu.Rp2040)))

        assertTrue(spec(choice.toPage(silhouettes, devBoards = everyBoard), "Controller").terms.isEmpty())
        assertTrue(spec(soldered.toPage(silhouettes, devBoards = everyBoard), "Controller").terms.isEmpty())
    }

    @Test
    fun `a one-handed design names the hand, because that is which half you build`() {
        assertEquals("one-handed, left", Body.OneHanded(Hand.Left).describe())
        assertEquals("one-handed, either hand", Body.OneHanded(Hand.Either).describe())
    }

    @Test
    fun `an extra nobody filled in leaves the summary null for the template to handle`() {
        assertNull(design().toPage(silhouettes).summary)
    }

    @Test
    fun `licenses render under their common names`() {
        val licensing = Licensing(
            hardware = LicenseState.Stated(setOf(License.CernOhlS2, License.CcBySa4)),
            firmware = LicenseState.Stated(setOf(License.Gpl2Only)),
            documentation = LicenseState.Stated(setOf(License.Other("WTFPL"))),
        )
        val page = design(licensing = licensing).toPage(silhouettes)

        assertEquals("CC BY-SA 4.0, CERN-OHL-S 2.0", page.licenses.single { it.label == "Hardware" }.value)
        assertEquals("WTFPL", page.licenses.single { it.label == "Documentation" }.value)
    }

    // -- based_on, backlog #24 -------------------------------------------------------

    /** A `DesignCatalog` of exactly the designs a test names, nothing more. */
    private fun catalogOf(vararg designs: Design) = object : net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog {
        override fun all() = designs.toList()
        override fun bySlug(slug: String) = designs.firstOrNull { it.core.slug.value == slug }
    }

    // -- the reverse: designs derived from this one, backlog #110 step 4 ---------------

    private fun named(name: String, slug: String, basedOn: Set<Ancestor>? = null) = design(
        extras = basedOn?.let { DesignExtras(basedOn = Recorded.Known(it)) } ?: DesignExtras(),
    ).let { it.copy(core = it.core.copy(name = DesignName(name), slug = Slug(slug))) }

    @Test
    fun `a design lists the designs that credit it as their ancestor, by name`() {
        val chocofi = named("Chocofi", "chocofi")
        val temper = named("Temper", "temper", setOf(Ancestor.Held(Slug("chocofi"))))
        val alpha = named("Alpha", "alpha", setOf(Ancestor.Held(Slug("chocofi")),
            Ancestor.External("Fifi", "https://github.com/raychengy/fifi_split_keeb")))
        val unrelated = named("Corne", "corne")

        val page = chocofi.toPage(silhouettes, catalog = catalogOf(chocofi, temper, alpha, unrelated))

        assertEquals(listOf("alpha", "temper"), page.derivedFromThis.map { it.row.slug })
    }

    @Test
    fun `a design nothing credits lists nothing`() {
        val chocofi = named("Chocofi", "chocofi")
        val page = chocofi.toPage(silhouettes, catalog = catalogOf(chocofi, named("Corne", "corne")))

        assertTrue(page.derivedFromThis.isEmpty())
    }

    @Test
    fun `the descendant's own credit still points up, not down`() {
        val chocofi = named("Chocofi", "chocofi")
        val temper = named("Temper", "temper", setOf(Ancestor.Held(Slug("chocofi"))))

        val page = temper.toPage(silhouettes, catalog = catalogOf(chocofi, temper))

        assertTrue(page.derivedFromThis.isEmpty())
        assertEquals(listOf(AncestorView("Chocofi", "/designs/chocofi", external = false)), page.basedOn)
    }

    @Test
    fun `no credited ancestor is absent, not empty text`() {
        val page = design().toPage(silhouettes)

        assertTrue(page.basedOn.isEmpty())
    }

    @Test
    fun `a held ancestor links to the design it names`() {
        val ancestor = design().let { it.copy(core = it.core.copy(
            name = DesignName("Ancestor Board"), slug = Slug("ancestor-board"),
        )) }
        val page = design(extras = DesignExtras(
            basedOn = Recorded.Known(setOf(Ancestor.Held(Slug("ancestor-board")))),
        )).toPage(silhouettes, catalog = catalogOf(ancestor))

        assertEquals(
            listOf(AncestorView("Ancestor Board", "/designs/ancestor-board", external = false)),
            page.basedOn,
        )
    }

    /**
     * A credit whose target this catalog does not hold is DROPPED from the page, not
     * a broken link and not a 500. `toAncestorViews`'s own doc comment states why: a
     * stale credit is a display extra, and losing it costs the reader less than a page
     * that will not render. Distinct from the `External` case below, which is not
     * stale -- it is correctly, permanently outside the corpus (ticket 22).
     */
    @Test
    fun `a held ancestor that no longer resolves is dropped, not broken`() {
        val page = design(extras = DesignExtras(
            basedOn = Recorded.Known(setOf(Ancestor.Held(Slug("does-not-exist")))),
        )).toPage(silhouettes, catalog = catalogOf())

        assertTrue(page.basedOn.isEmpty())
    }

    @Test
    fun `an external ancestor links out under the name the credit stated`() {
        val page = design(extras = DesignExtras(
            basedOn = Recorded.Known(setOf(
                Ancestor.External("Dactyl", "https://github.com/adereth/dactyl-keyboard"),
            )),
        )).toPage(silhouettes)

        assertEquals(
            listOf(AncestorView("Dactyl", "https://github.com/adereth/dactyl-keyboard", external = true)),
            page.basedOn,
        )
    }

    @Test
    fun `several ancestors render sorted by name, held and external mixed freely`() {
        val zed = design().let { it.copy(core = it.core.copy(
            name = DesignName("Zed"), slug = Slug("zed"),
        )) }
        val page = design(extras = DesignExtras(
            basedOn = Recorded.Known(setOf(
                Ancestor.Held(Slug("zed")),
                Ancestor.External("Alpha", "https://example.com/alpha"),
            )),
        )).toPage(silhouettes, catalog = catalogOf(zed))

        assertEquals(listOf("Alpha", "Zed"), page.basedOn.map { it.name })
    }

    // -- a credit naming a family, owner 2026-11-25 -----------------------------------
    //
    // Lime's README says "Based on Lily58, Corne, Helix and Sofle": projects, not PCBs.
    // Fanned out to every member, the line read "derived from Corne Cherry v4, Corne
    // Chocolate v4, helix, Lily58, Lily58 Lite Rev3, ...".

    private fun member(name: String, slug: String, family: String, basedOn: Set<Ancestor>? = null) =
        named(name, slug, basedOn).let {
            it.copy(extras = it.extras.copy(family = Recorded.Known(FamilyName(family))))
        }

    @Test
    fun `a family credit reads as the family's name and links its page`() {
        val cherry = member("Corne Cherry v4", "corne-cherry-v4", "Corne")
        val choc = member("Corne Chocolate v4", "corne-chocolate-v4", "Corne")
        val lime = named("Lime", "lime", setOf(Ancestor.Family(FamilyName("Corne"))))

        val page = lime.toPage(silhouettes, catalog = catalogOf(cherry, choc, lime))

        assertEquals(listOf(AncestorView("Corne", "/designs/families/corne", external = false)), page.basedOn)
    }

    /** A family with fewer than two members in the catalog has no page to link. */
    @Test
    fun `a family credit with no family page behind it is dropped, not broken`() {
        val lime = named("Lime", "lime", setOf(Ancestor.Family(FamilyName("Corne"))))
        val cherry = member("Corne Cherry v4", "corne-cherry-v4", "Corne")

        assertTrue(lime.toPage(silhouettes, catalog = catalogOf(cherry, lime)).basedOn.isEmpty())
    }

    /**
     * Every member's page says it, owner 2026-11-25 -- under its own heading, "derived from
     * the Corne family", so Corne Chocolate's page never claims Lime started from that
     * very board. A design crediting this one by slug stays in the first list only.
     */
    @Test
    fun `every member lists the designs that credit its family, apart from its own credits`() {
        val cherry = member("Corne Cherry v4", "corne-cherry-v4", "Corne")
        val choc = member("Corne Chocolate v4", "corne-chocolate-v4", "Corne")
        val lime = named("Lime", "lime", setOf(Ancestor.Family(FamilyName("Corne"))))
        val both = named("Both", "both", setOf(Ancestor.Family(FamilyName("Corne")), Ancestor.Held(Slug("corne-chocolate-v4"))))
        val catalog = catalogOf(cherry, choc, lime, both)

        val chocPage = choc.toPage(silhouettes, catalog = catalog)
        assertEquals(listOf("both"), chocPage.derivedFromThis.map { it.row.slug })
        assertEquals(listOf("lime"), chocPage.derivedFromFamily.map { it.row.slug })

        val cherryPage = cherry.toPage(silhouettes, catalog = catalog)
        assertTrue(cherryPage.derivedFromThis.isEmpty())
        assertEquals(listOf("both", "lime"), cherryPage.derivedFromFamily.map { it.row.slug })
    }

    // -- a photo from somewhere other than the design's own repo -------------------

    private fun photos(entry: String) = Photos(
        jacksonObjectMapper(),
        ByteArrayResource("""{"test-board":$entry}""".toByteArray()),
    )

    /**
     * The README caption says "from the project's own README", and the author credit
     * beside it names the repo. Neither is true of a photo from kbd.news or a builder's
     * post, so that photo carries its own credit -- or the page would pass it off as the
     * designer's.
     */
    @Test
    fun `a photo from elsewhere is credited to where it came from`() {
        val photo = design().toPage(silhouettes, photos = photos(
            """{"jpg":"abc","width":4,"height":3,"sourceUrl":"https://kbd.news/some-post",""" +
                """"credit":"https://www.kbd.news/some-post"}"""
        )).photo!!

        assertEquals("https://www.kbd.news/some-post", photo.creditUrl)
        assertEquals("kbd.news", photo.creditName)
    }

    @Test
    fun `a README photo carries no credit of its own`() {
        val photo = design().toPage(silhouettes, photos = photos(
            """{"jpg":"abc","width":4,"height":3,"sourceUrl":"https://example.com/board"}"""
        )).photo!!

        assertNull(photo.creditUrl)
        assertNull(photo.creditName)
    }

    /** #106: a design with no firmware record publishes, and the page says so in the
     *  ignorance style. No "Firmware targets" line: it is a fact about a record. */
    @Test
    fun `a design with no firmware record reads firmware not recorded`() {
        val page = design(firmware = Recorded.Unknown).toPage(silhouettes)

        val line = spec(page, "Firmware")
        assertEquals(NOT_RECORDED, line.value)
        assertFalse(line.recorded)
        assertTrue(page.spec.none { it.label == "Firmware targets" })
    }
}
