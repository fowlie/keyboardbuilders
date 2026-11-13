package net.keyboardbuilders.keyboardbuilders.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Door B's SWITCHES section (`ux.md` §2.9, ticket 16). The rule under test is ticket 16's
 * own: `switch.footprint ∈ design.switch_footprint` AND `design.switch_sensing ∈
 * switch.sensing`, two independent memberships that must be checked separately because a
 * footprint failure and a sensing failure teach different lessons (decision 3 rule 1).
 */
class SwitchCompatibilityTest {

    private fun design(
        switches: Set<SwitchFootprint> = setOf(SwitchFootprint.Mx),
        wiring: Recorded<KeyWiring> = Recorded.Unknown,
    ) = Design(
        core = DesignCore(
            name = DesignName("Test Board"),
            slug = Slug("test-board"),
            sources = setOf(SourceRef("https://example.com/board")),
            firmware = Recorded.Known(setOf(FirmwareRef.Qmk())),
            layouts = setOf(Layout(KeyCount(42))),
            switches = switches,
            buildMethod = BuildMethod.Pcb(),
            controller = Controller.Socket(ControllerFootprint.ProMicro),
            body = Body.Unibody,
            licensing = Licensing(LicenseState.Unstated, LicenseState.Unstated, LicenseState.Unstated),
        ),
        extras = DesignExtras(wiring = wiring),
    )

    private fun fitFor(section: SwitchesSection, footprint: SwitchFootprint): SwitchFit {
        val rows = assertIs<SwitchesSection.Rows>(section)
        return rows.fits.single { it.footprint == footprint }
    }

    // -- sensing derivation: `Recorded<KeyWiring>.sensing` -----------------------------

    @Test
    fun `a matrix senses mechanically`() {
        val wiring = Recorded.Known(KeyWiring.Matrix(PinCount(6), PinCount(7), DiodeDirection.ColToRow))

        assertEquals(Recorded.Known(SwitchSensing.Mechanical), wiring.sensing)
    }

    @Test
    fun `direct pin wiring senses mechanically`() {
        val wiring = Recorded.Known(KeyWiring.DirectPin(PinCount(12)))

        assertEquals(Recorded.Known(SwitchSensing.Mechanical), wiring.sensing)
    }

    /**
     * The fork from the grilling session: `Analog` cannot tell Hall from optical from
     * capacitive, and guessing would be the wrong-value-that-looks-right failure this
     * project exists to avoid. So an analog-wired design's sensing family is `Unknown`
     * here, not a default.
     */
    @Test
    fun `analog wiring does not resolve to a specific sensing family`() {
        assertEquals(Recorded.Unknown, Recorded.Known<KeyWiring>(KeyWiring.Analog).sensing)
    }

    @Test
    fun `unrecorded wiring means unrecorded sensing`() {
        assertEquals(Recorded.Unknown, Recorded.Unknown.sensing)
    }

    // -- the SWITCHES section itself -----------------------------------------------------

    /**
     * Unrecorded sensing still compares FOOTPRINTS, which are a gate field and always known.
     * It once collapsed the whole section (ticket 16 decision 3 rule 2), and on 43 designs
     * -- Ferris, Sweep, Lily58 -- the page then said less than the design page's own
     * "Switch footprint" line. No design in the corpus records Hall or optical sensing, so
     * the rule hid what was known to guard against a case that does not occur. The rows say
     * which footprints fit; the page states that sensing is unrecorded.
     */
    @Test
    fun `unrecorded sensing still says which footprints fit`() {
        val section = design(switches = setOf(SwitchFootprint.ChocV1), wiring = Recorded.Unknown).switchesSection()

        assertTrue(section is SwitchesSection.SensingUnknown)
        val fits = (section as SwitchesSection.SensingUnknown).fits
        assertTrue(fits.single { it.footprint == SwitchFootprint.ChocV1 }.fits)
        assertEquals(
            SwitchExclusion.FootprintMismatch(setOf(SwitchFootprint.ChocV1)),
            fits.single { it.footprint == SwitchFootprint.Mx }.exclusion,
        )
    }

    /** The owner, on Ferris 0.1 Low: the page crossed out MX, Choc v1, Choc v2 and Alps and
     *  never named the Kailh Mini Choc the board takes. A design's own footprint is always
     *  a row, even outside the four standard families. */
    /** Backlog #109. Kailh's PG1232 page gives no keycap stem, so the answer is "not
     *  stated", never an assumed Choc v1 rail: the two do not share a footprint. */
    @Test
    fun `Mini Choc has no stem until a source states one`() {
        assertNull(SwitchFootprint.ChocMini.stem)
    }

    @Test
    fun `a footprint outside the standard four still gets its own row`() {
        val miniChoc = SwitchFootprint.ChocMini
        for (wiring in listOf<Recorded<KeyWiring>>(
            Recorded.Unknown,
            Recorded.Known(KeyWiring.Matrix(PinCount(4), PinCount(5), DiodeDirection.ColToRow)),
        )) {
            val fits = when (val s = design(switches = setOf(miniChoc), wiring = wiring).switchesSection()) {
                is SwitchesSection.Rows -> s.fits
                is SwitchesSection.SensingUnknown -> s.fits
            }
            assertTrue(fits.single { it.footprint == miniChoc }.fits, "$wiring")
            assertEquals(5, fits.size, "the four standard families, then the design's own")
        }
    }

    @Test
    fun `a mechanical board's own footprint fits, with no exclusion`() {
        val section = design(
            switches = setOf(SwitchFootprint.ChocV1),
            wiring = Recorded.Known(KeyWiring.Matrix(PinCount(6), PinCount(7), DiodeDirection.ColToRow)),
        ).switchesSection()

        val fit = fitFor(section, SwitchFootprint.ChocV1)
        assertTrue(fit.fits)
        assertNull(fit.exclusion)
    }

    /** The Corne case: MX and Choc v1 boards both ship, and neither claims the other. */
    @Test
    fun `a footprint the design does not carry is excluded by footprint, not sensing`() {
        val section = design(
            switches = setOf(SwitchFootprint.Mx),
            wiring = Recorded.Known(KeyWiring.Matrix(PinCount(6), PinCount(7), DiodeDirection.ColToRow)),
        ).switchesSection()

        val fit = fitFor(section, SwitchFootprint.ChocV2)
        assertTrue(!fit.fits)
        val exclusion = assertIs<SwitchExclusion.FootprintMismatch>(fit.exclusion)
        assertEquals(setOf(SwitchFootprint.Mx), exclusion.designFootprints)
    }

    /**
     * `void_switch_65` (ticket 11's follow-up): a fully custom Hall-effect board. None of
     * the four tracked classes claim non-mechanical sensing (ticket 16 decision 1's
     * accepted cost), so a design known to sense magnetically is excluded by SENSING on
     * every one — distinctly from a footprint miss, per decision 3 rule 1's "different
     * lessons" requirement.
     *
     * Exercised directly against `switchFit`, not through `Design.switchesSection()`:
     * nothing in today's model can actually produce a `Design` whose sensing resolves to
     * `Hall` (see the derivation tests above and `switchFit`'s own doc comment) — this
     * rule is still specified by ticket 16 and worth verifying on its own terms.
     */
    @Test
    fun `a Hall-effect design excludes every tracked class by sensing, not footprint`() {
        val fit = switchFit(SwitchFootprint.Mx, setOf(SwitchFootprint.Mx), SwitchSensing.Hall)

        assertTrue(!fit.fits)
        val exclusion = assertIs<SwitchExclusion.SensingMismatch>(fit.exclusion)
        assertEquals(SwitchSensing.Hall, exclusion.designSensing)
    }

    /** `switchFit` checks footprint before sensing, so a design excluded on BOTH axes is
     *  reported by footprint — the more fundamental mismatch (you don't even own the
     *  right shape of switch) rather than the secondary one. */
    @Test
    fun `a footprint miss is reported even when sensing would also exclude the candidate`() {
        val fit = switchFit(SwitchFootprint.ChocV1, setOf(SwitchFootprint.Mx), SwitchSensing.Hall)

        assertIs<SwitchExclusion.FootprintMismatch>(fit.exclusion)
    }

    @Test
    fun `a design with two footprints has both fit independently`() {
        val section = design(
            switches = setOf(SwitchFootprint.Mx, SwitchFootprint.ChocV1),
            wiring = Recorded.Known(KeyWiring.Matrix(PinCount(6), PinCount(7), DiodeDirection.ColToRow)),
        ).switchesSection()

        assertTrue(fitFor(section, SwitchFootprint.Mx).fits)
        assertTrue(fitFor(section, SwitchFootprint.ChocV1).fits)
        assertTrue(!fitFor(section, SwitchFootprint.ChocV2).fits)
    }
}
