package net.keyboardbuilders.keyboardbuilders.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Backlog #46. The UX spec's mock reads "17 of 22 fields, 78% complete", which
 * is illustrative ASCII art rather than exact arithmetic — 17/22 is 77.27%, rounding to 77.
 * Rounding to nearest (not always down) is still the right rule: a record one field short
 * of complete should not read as further behind than it is.
 *
 * Owner-caught 2026-09-16 on `/designs/litl`: the count said "6 of 11" while the page showed
 * no "not recorded" anywhere. `layout.shorthand`, `extras.basedOn` and `Body.Split`'s
 * `reversible`/`link` are all `Recorded`, but the page renders their `Unknown` state as
 * nothing at all — by deliberate, documented choice on each one, not oversight. Fixed by
 * dropping those three from the optional count.
 *
 * Owner-ruled the same day, second look: the optional-only count undercounted a design by
 * omitting everything that makes it buildable. `Design.completeness()` now also counts the
 * nine gate fields (never `Unknown` by construction) and exposes the whole labelled list, not
 * a bare count, so the page can print exactly what it's counting.
 */
class CompletenessTest {

    /** A minimal design; each test varies the one field it is about. */
    private fun design(
        pitch: Recorded<KeyPitch> = Recorded.Unknown,
        layouts: Set<Layout> = setOf(Layout(KeyCount(42))),
        buildMethod: BuildMethod = BuildMethod.Pcb(),
        body: Body = Body.Unibody,
        controller: Controller = Controller.Socket(ControllerFootprint.ProMicro),
        firmware: Recorded<Set<FirmwareRef>> = Recorded.Known(setOf(FirmwareRef.Qmk())),
        extras: DesignExtras = DesignExtras(),
    ) = Design(
        core = DesignCore(
            name = DesignName("Test Board"),
            slug = Slug("test-board"),
            sources = setOf(SourceRef("https://example.com/board")),
            firmware = firmware,
            layouts = layouts,
            switches = setOf(SwitchFootprint.Mx),
            pitch = pitch,
            buildMethod = buildMethod,
            controller = controller,
            body = body,
            licensing = Licensing(
                hardware = LicenseState.Unstated,
                firmware = LicenseState.Unstated,
                documentation = LicenseState.Unstated,
            ),
        ),
        extras = extras,
    )

    /** The nine gate fields this file counts — see `Design.fields()`'s own doc comment. */
    private val GATE_FIELD_COUNT = 9

    @Test
    fun `a fresh import already has its gate fields, so it never reads 0 percent`() {
        val completeness = design().completeness()

        assertEquals(GATE_FIELD_COUNT, completeness.known, "only the gate fields are known")
        assertTrue(completeness.percent > 0, "gate fields alone put a fresh import above 0%")
    }

    @Test
    fun `every applicable field known is 100 percent`() {
        val full = design(
            pitch = Recorded.Known(KeyPitch.Mx),
            buildMethod = BuildMethod.Pcb(Recorded.Known(HardestComponent(SolderingDifficulty.SmdHand, setOf(ComponentKind.Mcu)))),
            controller = Controller.Soldered(Recorded.Known(Mcu.Atmega32u4)),
            firmware = Recorded.Known(setOf(FirmwareRef.Qmk(targetMcu = Recorded.Known(Mcu.Atmega32u4)))),
            extras = DesignExtras(
                summary = Recorded.Known("A board."),
                wiring = Recorded.Known(KeyWiring.Analog),
                switchMounts = Recorded.Known(setOf(SwitchMount.MxSocket)),
                splay = Recorded.Known(true),
                stagger = Recorded.Known(Stagger.Ortho),
            ),
        )

        val completeness = full.completeness()

        assertEquals(completeness.total, completeness.known)
        assertEquals(100, completeness.percent)
    }

    @Test
    fun `gate fields are never the reason a design is below 100 percent`() {
        // None of the nine gate fields can ever be Unknown -- DesignCore's own init block
        // refuses to construct one without them.
        val completeness = design().completeness()

        val gateLabels = setOf(
            "Layout", "Switch footprint", "Build method", "Controller", "Body",
            "Firmware", "Hardware license", "Firmware license", "Documentation license",
        )
        val gateFields = completeness.fields.filter { it.label in gateLabels }

        assertEquals(GATE_FIELD_COUNT, gateFields.size)
        assertTrue(gateFields.all { it.known }, "every gate field reads known, always")
    }

    @Test
    fun `a hand-wire has no soldering tier to know, so it never lowers the denominator`() {
        val pcb = design(buildMethod = BuildMethod.Pcb()).completeness()
        val handwire = design(buildMethod = BuildMethod.Handwire).completeness()

        assertEquals(pcb.total - 1, handwire.total, "Handwire drops exactly the one field Pcb carries")
    }

    @Test
    fun `a socketed controller has no chip to know, so it never lowers the denominator`() {
        val socket = design(controller = Controller.Socket(ControllerFootprint.ProMicro)).completeness()
        val soldered = design(controller = Controller.Soldered()).completeness()

        assertEquals(socket.total + 1, soldered.total, "Soldered carries one field Socket has none for")
    }

    @Test
    fun `a split's reversible and link never count, known or not`() {
        // show.kte's Body.describe() renders Unknown reversible/link as nothing at all
        // ("neither one is a thing to buy"), so counting them would claim a gap the page
        // never shows.
        val unibody = design(body = Body.Unibody).completeness()
        val splitUnknown = design(body = Body.Split(SplitHalves(2), Recorded.Unknown, Recorded.Unknown)).completeness()
        val splitKnown = design(
            body = Body.Split(SplitHalves(2), Recorded.Known(true), Recorded.Known(setOf(SplitLink.Trrs))),
        ).completeness()

        assertEquals(unibody.total, splitUnknown.total)
        assertEquals(splitUnknown.total, splitKnown.total)
        assertEquals(splitUnknown.known, splitKnown.known, "a known reversible/link must not move the count either")
    }

    @Test
    fun `a layout's shorthand never counts, known or not`() {
        // Layout.describe() renders Unknown shorthand as plain "N keys", no gap shown --
        // "Unknown for ~82% of splits is the accepted price".
        val bare = design(layouts = setOf(Layout(KeyCount(42)))).completeness()
        val withShorthand = design(
            layouts = setOf(Layout(KeyCount(42), shorthand = Recorded.Known(Shorthand(3, 6, 3)))),
        ).completeness()
        val twoLayouts = design(
            layouts = setOf(Layout(KeyCount(42)), Layout(KeyCount(36))),
        ).completeness()

        assertEquals(bare.total, withShorthand.total)
        assertEquals(bare.known, withShorthand.known)
        assertEquals(bare.total, twoLayouts.total, "a second layout adds no field either")
    }

    @Test
    fun `based_on never counts, known or not`() {
        // The design's own comment: "the page says nothing rather than 'not recorded'".
        val absent = design(extras = DesignExtras()).completeness()
        val credited = design(extras = DesignExtras(basedOn = Recorded.Known(emptySet()))).completeness()

        assertEquals(absent.total, credited.total)
        assertEquals(absent.known, credited.known)
    }

    @Test
    fun `room for never counts, known or not`() {
        // Owner-ruled 2026-09-17, "it's about the unknown knowns": there is no
        // Known(emptySet()) for extensions (Design.kt's own doc comment), so a macropad
        // that genuinely has no encoder, trackball or display reads identically to one
        // nobody has checked. Counting it would score that macropad as an incomplete
        // record of a fancier keyboard, rather than a complete record of a simple one.
        val absent = design(extras = DesignExtras()).completeness()
        val withExtras = design(
            extras = DesignExtras(extensions = Recorded.Known(setOf(Extension.Encoder))),
        ).completeness()

        assertEquals(absent.total, withExtras.total)
        assertEquals(absent.known, withExtras.known)
    }

    @Test
    fun `splay counts as a gap, unlike room for and based_on`() {
        // Unlike `extensions`, splay's `Known(false)` is a real measured verdict
        // (`measure_splay.py`), not an absence of evidence standing in for one -- so
        // its `Unknown` is ordinary ignorance and the field counts.
        val absent = design(extras = DesignExtras()).completeness()
        val measuredFalse = design(extras = DesignExtras(splay = Recorded.Known(false))).completeness()
        val measuredTrue = design(extras = DesignExtras(splay = Recorded.Known(true))).completeness()

        assertEquals(absent.total, measuredFalse.total, "splay always adds one to the denominator")
        assertEquals(absent.known + 1, measuredFalse.known, "a measured `false` still counts as known")
        assertEquals(measuredFalse.known, measuredTrue.known)
    }

    @Test
    fun `stagger counts as a gap, unlike room for and based_on`() {
        // Same argument as `splay` just above: a board is always ortho, row-staggered or
        // column-staggered -- there is no fourth "none of these" case, so `Unknown` here
        // is ordinary ignorance and the field counts, for whichever of the three it is.
        val absent = design(extras = DesignExtras()).completeness()
        val ortho = design(extras = DesignExtras(stagger = Recorded.Known(Stagger.Ortho))).completeness()
        val row = design(extras = DesignExtras(stagger = Recorded.Known(Stagger.Row))).completeness()
        val column = design(extras = DesignExtras(stagger = Recorded.Known(Stagger.Column))).completeness()

        assertEquals(absent.total, ortho.total, "stagger always adds one to the denominator")
        assertEquals(absent.known + 1, ortho.known, "a measured verdict counts as known regardless of which one")
        assertEquals(ortho.known, row.known)
        assertEquals(row.known, column.known)
    }

    @Test
    fun `only QMK firmware entries carry a target-MCU field`() {
        val qmkOnly = design(firmware = Recorded.Known(setOf(FirmwareRef.Qmk()))).completeness()
        val zmkOnly = design(firmware = Recorded.Known(setOf(FirmwareRef.Zmk()))).completeness()

        assertEquals(qmkOnly.total - 1, zmkOnly.total, "ZMK has no target-MCU concept to leave Unknown")
    }

    @Test
    fun `several QMK records still carry exactly one firmware-targets field`() {
        // The Spec table's own "Firmware targets" line unions every QMK record into one
        // line (DesignPresentation.kt's toPage()) -- a design citing six records must not
        // read as six fields when the page only ever prints one.
        val one = design(firmware = Recorded.Known(setOf(FirmwareRef.Qmk()))).completeness()
        val six = design(
            firmware = Recorded.Known((1..6).map { FirmwareRef.Qmk(keyboardPath = "kb$it") }.toSet()),
        ).completeness()

        assertEquals(one.total, six.total)
        assertEquals(1, six.fields.count { it.label == "Firmware targets" })
    }

    /** The page prints no targets line when a known soldered chip already answers it, so
     *  the accounting must not count one -- it would be a field the reader cannot see. */
    @Test
    fun `a known soldered chip carries no firmware-targets field`() {
        val fields = design(
            controller = Controller.Soldered(Recorded.Known(Mcu.Atmega32u4)),
            firmware = Recorded.Known(setOf(FirmwareRef.Qmk(targetMcu = Recorded.Known(Mcu.Atmega32u4)))),
        ).completeness().fields

        assertTrue(fields.none { it.label == "Firmware targets" })
    }

    @Test
    fun `the firmware-targets field is known if any one QMK record states it`() {
        val fields = design(
            firmware = Recorded.Known(setOf(
                FirmwareRef.Qmk(keyboardPath = "a", targetMcu = Recorded.Unknown),
                FirmwareRef.Qmk(keyboardPath = "b", targetMcu = Recorded.Known(Mcu.Atmega32u4)),
            )),
        ).completeness().fields

        assertTrue(fields.single { it.label == "Firmware targets" }.known)
    }

    @Test
    fun `17 of 22 is 77 percent, rounded to nearest`() {
        val fields = List(17) { CompletenessField("known-$it", true) } +
            List(5) { CompletenessField("unknown-$it", false) }

        assertEquals(22, fields.size)
        assertEquals(77, DesignCompleteness(fields).percent)
    }

    @Test
    fun `rounding goes up, not always down`() {
        // 5 of 8 is 62.5%, which must round to 63 rather than truncate to 62.
        val fields = List(5) { CompletenessField("known-$it", true) } +
            List(3) { CompletenessField("unknown-$it", false) }

        assertEquals(63, DesignCompleteness(fields).percent)
    }

    @Test
    fun `a completeness with no fields at all is unconstructible`() {
        assertFailsWith<IllegalArgumentException> { DesignCompleteness(emptyList()) }
    }

    /**
     * NOT COUNTED WHEN UNKNOWN, the `extensions` rule: no build can prove a keyboard has no
     * firmware anywhere, so a board nobody wrote firmware for reads like one nobody has
     * checked. Scoring that as a gap would mark a record down for a fact it cannot learn.
     */
    @Test
    fun `an unrecorded firmware is left off the accounting rather than scored as a gap`() {
        val fields = design(firmware = Recorded.Unknown).completeness().fields

        assertTrue(fields.none { it.label == "Firmware" || it.label == "Firmware targets" })
        assertTrue(design().completeness().fields.single { it.label == "Firmware" }.known)
    }
}
