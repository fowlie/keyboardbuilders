package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.DesignCore
import net.keyboardbuilders.keyboardbuilders.domain.DesignExtras
import net.keyboardbuilders.keyboardbuilders.domain.DesignName
import net.keyboardbuilders.keyboardbuilders.domain.Extension
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.KeyCount
import net.keyboardbuilders.keyboardbuilders.domain.KeyPitch
import net.keyboardbuilders.keyboardbuilders.domain.Layout
import net.keyboardbuilders.keyboardbuilders.domain.Licensing
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ux.md §3.6's own requirement: "rank by shared-facet count with rarer facets weighted
 * higher." These tests exist to hold that specific claim, not just "some ranking
 * happens" — a strip that ranked by raw facet COUNT alone (no rarity weight) would pass
 * every other plausible test and still be the wrong behaviour this doc rules out.
 */
class RelatedDesignsTest {

    private fun design(
        slug: String,
        keys: Int = 42,
        switches: Set<SwitchFootprint> = setOf(SwitchFootprint.Mx),
        body: Body = Body.Unibody,
        controller: Controller = Controller.Socket(ControllerFootprint.ProMicro),
        pitch: Recorded<KeyPitch> = Recorded.Unknown,
        extensions: Recorded<Set<Extension>> = Recorded.Unknown,
    ) = Design(
        core = DesignCore(
            name = DesignName(slug),
            slug = Slug(slug),
            sources = setOf(SourceRef("https://example.com/$slug")),
            firmware = Recorded.Known(setOf(FirmwareRef.Qmk())),
            layouts = setOf(Layout(KeyCount(keys))),
            switches = switches,
            buildMethod = net.keyboardbuilders.keyboardbuilders.domain.BuildMethod.Pcb(),
            controller = controller,
            body = body,
            licensing = Licensing(LicenseState.Unstated, LicenseState.Unstated, LicenseState.Unstated),
            pitch = pitch,
        ),
        extras = DesignExtras(extensions = extensions),
    )

    private fun catalogOf(vararg designs: Design) = object : DesignCatalog {
        override fun all() = designs.toList()
        override fun bySlug(slug: String) = designs.firstOrNull { it.core.slug.value == slug }
    }

    @Test
    fun `a design sharing a rare facet outranks one sharing only a common one`() {
        // 8 of 10 designs share Mx (common); only 2 share Choc (rare). The target is
        // Choc, so the Choc match must weigh more than the Mx-only competitor even
        // though every design here shares exactly one facet each -- equal COUNT,
        // unequal RARITY, and rarity is what must decide it.
        val target = design("target", switches = setOf(SwitchFootprint.ChocV1))
        val rareMatch = design("rare-match", switches = setOf(SwitchFootprint.ChocV1))
        val commonMatches = (1..8).map { design("common-$it", switches = setOf(SwitchFootprint.Mx)) }
        val commonAlsoMx = design("also-mx", switches = setOf(SwitchFootprint.Mx))

        val catalog = catalogOf(target, rareMatch, commonAlsoMx, *commonMatches.toTypedArray())
        val related = RelatedDesigns(catalog)

        val ranked = related.similarTo(target, limit = 1)

        assertEquals("rare-match", ranked.single().design.core.slug.value)
    }

    @Test
    fun `a design sharing nothing is not offered`() {
        val target = design("target", switches = setOf(SwitchFootprint.Mx), body = Body.Unibody)
        val unrelated = design(
            "unrelated", keys = 90, switches = setOf(SwitchFootprint.Alps),
            body = Body.Split(
                net.keyboardbuilders.keyboardbuilders.domain.SplitHalves(2),
                Recorded.Unknown, Recorded.Unknown,
            ),
            controller = Controller.Soldered(),
        )

        val related = RelatedDesigns(catalogOf(target, unrelated))

        assertTrue(related.similarTo(target).isEmpty())
    }

    @Test
    fun `the reason names the matched facets and a size comparison`() {
        // Split, not the shared Unibody default, so switches is the ONLY matched
        // facet -- isolating the size-comparison claim from the "top 2 reasons"
        // capping this test is not about.
        val target = design(
            "target", keys = 40, switches = setOf(SwitchFootprint.ChocV1),
            body = Body.Split(
                net.keyboardbuilders.keyboardbuilders.domain.SplitHalves(2),
                Recorded.Unknown, Recorded.Unknown,
            ),
        )
        val bigger = design(
            "bigger", keys = 60, switches = setOf(SwitchFootprint.ChocV1),
            controller = Controller.Soldered(),
        )

        val related = RelatedDesigns(catalogOf(target, bigger))
        val match = related.similarTo(target).single()

        assertEquals("same switches, more keys", match.reason)
    }

    @Test
    fun `the reason caps at two scored facets even when more than two match`() {
        val target = design(
            "target", switches = setOf(SwitchFootprint.ChocV1), body = Body.Unibody,
            controller = Controller.Socket(ControllerFootprint.ProMicro),
            pitch = Recorded.Known(KeyPitch.Choc),
        )
        val quadMatch = design(
            "quad", switches = setOf(SwitchFootprint.ChocV1), body = Body.Unibody,
            controller = Controller.Socket(ControllerFootprint.ProMicro),
            pitch = Recorded.Known(KeyPitch.Choc),
        )

        val related = RelatedDesigns(catalogOf(target, quadMatch))

        assertEquals(2, related.similarTo(target).single().reason.split(", ").size)
    }

    @Test
    fun `an ancestor's own repeated matches do not double the count -- one weight per matched value`() {
        // Two designs sharing BOTH Mx and ChocV1 (a dual-footprint board) contribute
        // two match weights, not one — a real board (e.g. Cantor MX) legitimately
        // carries both, and the score should reflect two independent facts agreeing.
        val target = design("target", switches = setOf(SwitchFootprint.Mx, SwitchFootprint.ChocV1))
        val dual = design("dual", switches = setOf(SwitchFootprint.Mx, SwitchFootprint.ChocV1))
        val single = design("single", switches = setOf(SwitchFootprint.Mx))

        val related = RelatedDesigns(catalogOf(target, dual, single))
        val ranked = related.similarTo(target, limit = 1)

        assertEquals("dual", ranked.single().design.core.slug.value)
    }
}
