package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.FirmwareCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Connectivity
import net.keyboardbuilders.keyboardbuilders.domain.connectivityFor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TWO PAGES, ONE SOCKET, AND THEY MUST NOT DISAGREE.
 *
 * The bug this exists for, owner-caught 2026-09-16 and the reason #98 needed a second
 * commit: `/designs/rhymestone` said *"A nice!nano v2 or 6 other wireless controllers fit
 * this socket"* while `/designs/rhymestone/compatible` said *"19 wired options"* and named
 * none of the seven. Both pages read the same catalogue; they simply asked it different
 * questions and neither knew the other existed.
 *
 * WHY A TEST RATHER THAN CARE. The two surfaces derive the same fact through code that has
 * no reason to stay in step -- `Connectivity.connectivityFor` for the design page,
 * `controllerFacts` for Door B -- and #98 had already proved that a fix applied to one
 * surface leaves the other stating the opposite. Anything that re-narrows one derivation
 * and not the other fails here rather than on a live page.
 *
 * NO NUMBER IS WRITTEN DOWN, deliberately. It is an equality between two derivations over
 * the real corpus, so importing any of backlog #89's 26 missing ZMK shields moves designs
 * between states and breaks nothing -- which is the same discipline `ConnectivityFacetTest`
 * uses, and the reason neither test had to be touched when the corpus grew.
 */
@SpringBootTest
// Unused here, but the same annotations as the web tests put this class in their cached
// Spring context instead of starting a second one (#114).
@AutoConfigureMockMvc
class ControllerSurfacesAgreeTest {

    @Autowired lateinit var catalog: DesignCatalog
    @Autowired lateinit var controllers: ControllerCatalog
    @Autowired lateinit var firmwares: FirmwareCatalog
    @Autowired lateinit var keycaps: KeycapCatalog

    /**
     * THE EXACT CONTRADICTION THE OWNER SAW. A design whose spec line names wireless
     * controllers must list those same controllers on its "what fits" page -- the same
     * boards, not merely the same count, because "seven fit" and then seven *different*
     * names would be a subtler version of the same lie.
     */
    @Test
    fun `a design that names wireless controllers lists them on its what-fits page`() {
        val boards = controllers.all()
        val rules = firmwares.rules
        var checked = 0

        for (design in catalog.all()) {
            val state = design.connectivityFor(boards, rules)
            if (state !is Connectivity.WirelessNeedsFirmware) continue
            checked++

            val page = design.toCompatiblePage(controllers, keycaps, rules)
            assertEquals(
                state.boards.toSet(),
                page.controllerNeedsFirmware.map { it.name }.toSet(),
                "${design.core.slug.value}: the design page and Door B name different boards",
            )
            assertTrue(
                page.controllerMessage == null,
                "${design.core.slug.value}: Door B says no answer while the design page names boards",
            )
        }

        // Guard against the whole loop being vacuous -- a check that cannot fail is not a
        // check (`feedback_verify_the_property_not_a_stand_in`).
        assertTrue(checked > 0, "no design is in the needs-firmware state; this test proved nothing")
    }

    /**
     * THE CONVERSE, and without it the test above passes by emptying the group. A design
     * whose spec line still says *"no wireless controller we know of fits this socket"*
     * must show no wireless boards on Door B -- otherwise we have moved the contradiction
     * rather than removed it.
     */
    @Test
    fun `a design that denies a wireless fit shows no wireless boards either`() {
        val boards = controllers.all()
        val rules = firmwares.rules
        var checked = 0

        for (design in catalog.all()) {
            val state = design.connectivityFor(boards, rules)
            if (state !is Connectivity.WiredOnly.NoWirelessBoardFits) continue
            checked++

            val page = design.toCompatiblePage(controllers, keycaps, rules)
            assertTrue(
                page.controllerNeedsFirmware.isEmpty() && page.controllerWireless.isEmpty(),
                "${design.core.slug.value}: denies a wireless fit but Door B lists " +
                    (page.controllerNeedsFirmware + page.controllerWireless).joinToString { it.name },
            )
        }

        assertTrue(checked > 0, "no design denies a wireless fit; this test proved nothing")
    }

    /**
     * THE GROUPS NEVER MERGE, which is the 2026-09-03 ruling #98 had to preserve: a
     * ZMK-only `BlueMicro840 v1` shown as a plain option for a QMK-only design was a real
     * bug, and listing it at all only stays honest while it sits apart with the caveat in
     * its caption. A board appearing in both lists would be that bug returning.
     */
    @Test
    fun `no board is offered as both a plain option and a needs-firmware one`() {
        val rules = firmwares.rules
        for (design in catalog.all()) {
            val page = design.toCompatiblePage(controllers, keycaps, rules)
            val plain = (page.controllerWireless + page.controllerWired).map { it.name }.toSet()
            val caveated = page.controllerNeedsFirmware.map { it.name }.toSet()

            assertTrue(
                (plain intersect caveated).isEmpty(),
                "${design.core.slug.value}: ${plain intersect caveated} offered both ways",
            )
            // The caption is the only text a reader behind a disclosure is sure to see.
            assertEquals(
                caveated.isNotEmpty(), page.controllerNeedsFirmwareSummary != null,
                "${design.core.slug.value}: rows and caption disagree about existing",
            )
        }
    }
}
