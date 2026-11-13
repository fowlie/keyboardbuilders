package net.keyboardbuilders.keyboardbuilders.persistence

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.FirmwareCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.Connectivity
import net.keyboardbuilders.keyboardbuilders.domain.connectivityFor
import net.keyboardbuilders.keyboardbuilders.domain.facetValue
import net.keyboardbuilders.keyboardbuilders.domain.family
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE GUARD THAT MAKES A STALE `wireless` FACET DETECTABLE, and the reason it exists is
 * structural rather than paranoid.
 *
 * `design_facet` is documented as a projection of `revision.payload`. `wireless` is the
 * one facet that is not: it is a JOIN between a design and the controller catalogue, which
 * `ControllerCatalog` itself calls "a different clock from the corpus's". Editing
 * `seed/controllers.toml` therefore changes what this facet SHOULD say, and nothing about
 * the design's own payload changes to signal it.
 *
 * So the invariant is checked the way this repo prefers: **two independent derivations of
 * one fact, with no number written down.** The stored rows come from
 * `SeedImporter.writeFacets`; the expected values are recomputed here from the catalogues
 * directly. A reseed cannot break it, and a catalogue edit that was never re-imported
 * will.
 */
@SpringBootTest
// Unused here, but the same annotations as the web tests put this class in their cached
// Spring context instead of starting a second one (#114).
@AutoConfigureMockMvc
class ConnectivityFacetTest {

    @Autowired lateinit var db: JdbcClient
    @Autowired lateinit var catalog: DesignCatalog
    @Autowired lateinit var controllers: ControllerCatalog
    @Autowired lateinit var firmwares: FirmwareCatalog


    private fun stored(): Map<String, String> =
        db.sql(
            """
            SELECT d.slug AS slug, f.value AS value
              FROM design_facet f JOIN design d ON d.id = f.design_id
             WHERE f.key = ?
            """
        ).param(FacetKey.Connectivity.param)
            .query { rs, _ -> rs.getString("slug") to rs.getString("value") }
            .list().toMap()

    /**
     * AT MOST ONE ROW PER DESIGN, AND FEWER THAN THERE ARE DESIGNS. Both halves matter.
     *
     * At most one, because two rows would let a design appear under both "Bluetooth" and
     * "Wired only" — contradictory, and `wired_only` is scoped to mean exactly "no
     * wireless option". Fewer than 325, because `Connectivity.Unrecorded` projects no row
     * at all: that is what puts those designs in the rail's ordinary "Not recorded" tier
     * rather than the special-cased third value this facet shipped with for an hour.
     *
     * Neither number is written down. The gap used to be asserted non-zero, "so only a
     * reseed that reads them all" would break it; that reseed happened on 2026-10-02 (#106)
     * and was noticed, so the gap may now be empty.
     */
    @Test
    fun `each design has at most one connectivity value, and the missing ones are unrecorded`() {
        val rows = db.sql("SELECT count(*) FROM design_facet WHERE key = ?")
            .param(FacetKey.Connectivity.param).query(Int::class.java).single()
        val designs = catalog.all().size

        assertEquals(rows, stored().size, "a design carries two connectivity rows")
        // The unrecorded tier MAY be empty: since #106 read every soldered board's chip
        // (2026-10-02) it is, the reseed this test's doc said was worth noticing. That an
        // unrecorded state projects no row is pinned on fixtures in `ConnectivityTest`.
        assertTrue(rows > 0, "no design got a row at all")

        val unrecorded = catalog.all().count {
            it.connectivityFor(controllers.all(), firmwares.rules) is Connectivity.Unrecorded
        }
        assertEquals(designs - rows, unrecorded, "the missing rows are exactly the unrecorded ones")
    }

    /** THE CROSS-DERIVATION. Neither side is written down. */
    @Test
    fun `the stored facet agrees with the capability recomputed from the catalogs`() {
        val boards = controllers.all()
        val rules = firmwares.rules
        val stored = stored()

        val disagreements = catalog.all().mapNotNull { design ->
            val slug = design.core.slug.value
            val expected = design.connectivityFor(boards, rules).facetValue()
            if (stored[slug] != expected) "$slug: stored=${stored[slug]} recomputed=$expected" else null
        }

        assertTrue(
            disagreements.isEmpty(),
            "the facet table and the catalog disagree for ${disagreements.size} designs:\n" +
                disagreements.take(10).joinToString("\n"),
        )
    }

    /**
     * Both values are stored and both are reachable. **Reachability is the half that
     * matters**: a facet value no design takes is a rail row nobody can click, and
     * `DesignQueries.reachOfEachFacet` withdraws a group that cannot reach two outcomes.
     * With two values plus an unrecorded tier this group reaches three, comfortably.
     */
    @Test
    fun `both values are stored and both are reachable`() {
        val values = db.sql("SELECT DISTINCT value FROM design_facet WHERE key = ?")
            .param(FacetKey.Connectivity.param).query(String::class.java).list().toSet()

        assertEquals(setOf("wireless", "wireless_needs_firmware", "wired_only"), values)
    }

    /**
     * THE FALSE SENTENCE, AS A PROPERTY OVER THE REAL CORPUS. Backlog #98: 135 designs
     * read *"no wireless controller we know of fits this socket"* while a catalogued
     * wireless board fitted the socket -- seven of them, for a Pro Micro design.
     *
     * CHECKED AS AN IMPLICATION RATHER THAN A COUNT, deliberately: no number is written
     * down, so importing a ZMK shield (#89 has 26 waiting) or catalogueing a Pico W moves
     * designs between buckets without touching this test. What it forbids is the
     * CONTRADICTION -- a design saying "nothing fits" while something fits.
     *
     * This is the check whose absence let the bug ship. The old suite asserted that a
     * QMK design got `NoWirelessBoardFits` and never asked whether that was true.
     */
    @Test
    fun `no design claims nothing fits while a wireless board fits its socket`() {
        val boards = controllers.all()
        val rules = firmwares.rules

        val contradictions = catalog.all().mapNotNull { design ->
            val state = design.connectivityFor(boards, rules)
            if (state !is Connectivity.WiredOnly.NoWirelessBoardFits) return@mapNotNull null
            val fitting = boards.filter { it.footprint in state.footprints && it.wireless }
            if (fitting.isEmpty()) null
            else "${design.core.slug.value}: says nothing fits ${state.footprints}, " +
                "but ${fitting.joinToString { it.name }} do"
        }

        assertTrue(
            contradictions.isEmpty(),
            "${contradictions.size} designs deny a fit that exists:\n" +
                contradictions.take(10).joinToString("\n"),
        )
    }

    /**
     * THE OTHER HALF, and without it the test above is satisfiable by emptying the member.
     * A design in `wireless_needs_firmware` must really have a wireless board fitting its
     * socket and really ship no firmware recorded as doing wireless on one -- otherwise it
     * belongs in `wireless`, and we would have traded a false "no" for a false "maybe".
     *
     * `feedback_verify_the_property_not_a_stand_in`: a check that cannot fail is not a
     * check, so this asserts the member is non-empty as well as sound.
     */
    @Test
    fun `every design in the new bucket really has a fitting wireless board`() {
        val boards = controllers.all()
        val rules = firmwares.rules

        val states = catalog.all().map { it to it.connectivityFor(boards, rules) }
        val gap = states.filter { (_, state) -> state is Connectivity.WirelessNeedsFirmware }

        assertTrue(gap.isNotEmpty(), "the new bucket is empty; it cannot fail anything")

        for ((design, state) in gap) {
            state as Connectivity.WirelessNeedsFirmware
            val slug = design.core.slug.value
            assertTrue(state.boards.isNotEmpty(), "$slug: in the bucket naming no board")

            val named = boards.filter { it.name in state.boards }
            assertTrue(
                named.all { it.wireless && it.footprint in state.footprints },
                "$slug: names a board that is not a wireless fit for ${state.footprints}",
            )
            assertEquals(
                "wireless_needs_firmware", state.facetValue(),
                "$slug: the new state must not share a filter with either neighbour",
            )
        }
    }

    /**
     * THE PROPERTY THE OWNER'S ANTENNA POINT TURNS INTO A TEST, checked against the real
     * corpus rather than a fixture: a soldered design whose chip family has no radio is
     * `none`, and one whose chip is unrecorded is `maybe`. It is the asymmetry that keeps
     * 129 of 137 soldered designs out of "maybe" — without it the facet would be useless.
     *
     * Prints the distribution, because a count that moves is worth seeing in the log even
     * though nothing here asserts on one.
     */
    @Test
    fun `soldered designs split on whether the chip could have a radio at all`() {
        val stored = stored()
        var noRadio = 0
        var unrecorded = 0

        for (design in catalog.all()) {
            val controller = design.core.controller as? Controller.Soldered ?: continue
            val value = stored[design.core.slug.value]
            when (val mcu = controller.mcu.knownOrNull()) {
                null -> {
                    unrecorded++
                    assertEquals(null, value, "${design.core.slug.value}: chip unrecorded, so no row")
                }
                else -> if (!mcu.family().radio) {
                    noRadio++
                    assertEquals("wired_only", value, "${design.core.slug.value}: $mcu has no radio")
                }
            }
        }

        val distribution = stored.values.groupingBy { it }.eachCount()
        println("  connectivity facet: $distribution, " +
            "${catalog.all().size - stored.size} not recorded")
        println("  soldered: $noRadio radio-less chips, $unrecorded with no chip recorded")

        assertTrue(noRadio > 0, "no soldered design has a radio-less chip, which cannot be right")
        // `unrecorded` may be 0 since #106 read every soldered board's chip; the no-row rule
        // for that tier is pinned on fixtures in `ConnectivityTest`.
    }
}
