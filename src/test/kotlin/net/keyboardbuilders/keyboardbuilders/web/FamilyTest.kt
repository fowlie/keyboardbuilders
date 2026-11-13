package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.DesignCore
import net.keyboardbuilders.keyboardbuilders.domain.DesignExtras
import net.keyboardbuilders.keyboardbuilders.domain.DesignName
import net.keyboardbuilders.keyboardbuilders.domain.FamilyName
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.KeyCount
import net.keyboardbuilders.keyboardbuilders.domain.Layout
import net.keyboardbuilders.keyboardbuilders.domain.Licensing
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import net.keyboardbuilders.keyboardbuilders.domain.SplitHalves
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.catalog.FamilyRecord
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Backlog #107. The owner asked why the corpus had "so many sweep boards": six real PCBs
 * from one repo, which read as six unrelated keyboards because nothing said they belong
 * together. A design page now names its family and every board in it.
 */
class FamilyTest {

    private fun design(
        name: String,
        family: String? = null,
        keys: Int = 34,
        switches: Set<SwitchFootprint> = setOf(SwitchFootprint.ChocV1),
        body: Body = Body.Unibody,
        firmware: Recorded<Set<FirmwareRef>> = Recorded.Known(setOf(FirmwareRef.Qmk())),
        source: String = "https://example.com/$name",
    ) = Design(
        core = DesignCore(
            name = DesignName(name),
            slug = Slug(name.lowercase().replace(' ', '-')),
            sources = setOf(SourceRef(source)),
            firmware = firmware,
            layouts = setOf(Layout(KeyCount(keys))),
            switches = switches,
            buildMethod = BuildMethod.Pcb(),
            controller = Controller.Socket(ControllerFootprint.ProMicro),
            body = body,
            licensing = Licensing(LicenseState.Unstated, LicenseState.Unstated, LicenseState.Unstated),
        ),
        extras = DesignExtras(
            family = family?.let { Recorded.Known(FamilyName(it)) } ?: Recorded.Unknown,
        ),
    )

    private fun catalogOf(vararg designs: Design) = object : DesignCatalog {
        override fun all() = designs.toList()
        override fun bySlug(slug: String) = designs.firstOrNull { it.core.slug.value == slug }
    }

    /**
     * "from the Corne family" beside the title (owner, 2026-10-04), the family's name
     * linking its page, which lists and compares every member.
     */
    @Test
    fun `a family member names its own family and links its page`() {
        val sweep2 = design("Sweep 2", "Sweep")
        val bling = design("Sweep Bling LP", "Sweep")
        val cradio = design("Cradio", "Cradio")
        val loner = design("Corne Cherry v4")

        val family = familyOf(bling, catalogOf(bling, cradio, sweep2, loner))!!

        assertEquals("Sweep", family.name)
        assertEquals("/designs/families/sweep", family.href)
    }

    @Test
    fun `a design in no recorded family shows no family`() {
        assertNull(familyOf(design("Corne Cherry v4"), catalogOf(design("Corne Cherry v4"))))
    }

    /** A family of one says nothing a reader can use, and means a member was dropped from
     *  the corpus without its family row noticing. */
    @Test
    fun `a family with no other member in the corpus shows no family`() {
        val alone = design("Sweep 2", "Sweep")
        assertNull(familyOf(alone, catalogOf(alone)))
    }

    // -- the family page, /designs/families/{slug} ----------------------------------------------

    private val silhouettes = Silhouettes(
        jacksonObjectMapper(),
        ByteArrayResource("{}".toByteArray()),
        ByteArrayResource("{}".toByteArray()),
    )

    private val corne = FamilyRecord(FamilyName("Corne"), SourceRef("https://github.com/foostan"))

    private fun member(d: Design) =
        FamilyMember(d, d.toPage(silhouettes), d.toCard(d.toRow(silhouettes), null, silhouettes))

    @Test
    fun `the lead names every variant and states only what all of them share`() {
        val page = familyPage(corne, listOf(
            member(design("Corne Cherry v4", "Corne", keys = 42, switches = setOf(SwitchFootprint.Mx), body = split)),
            member(design("Corne Chocolate v4", "Corne", keys = 36, switches = setOf(SwitchFootprint.ChocV1), body = split)),
        ))!!

        assertEquals("corne", page.slug)
        assertEquals(
            "Corne is a split keyboard with 2 designs in the catalog: Corne Cherry v4 and " +
                "Corne Chocolate v4. Between them they are built to 42 or 36 keys, for Choc v1 or MX switches.",
            page.lead,
        )
    }

    /**
     * "Between them" is only true of counts that differ. Atom47, Cradio, Ferris and Sweep
     * share one key count across every variant, and "between them they are built to 34
     * keys" reads as if the 34 were split between the boards (review, 2026-10-03).
     */
    @Test
    fun `a key count or a footprint every variant shares is said of all of them`() {
        val same = familyPage(corne, listOf(
            member(design("A", "Corne", body = split)),
            member(design("B", "Corne", body = split)),
        ))!!
        assertTrue(same.lead.endsWith(" All are built to 34 keys, for Choc v1 switches."), same.lead)

        val keysDiffer = familyPage(corne, listOf(
            member(design("A", "Corne", keys = 36)),
            member(design("B", "Corne", keys = 42)),
        ))!!
        assertTrue(
            keysDiffer.lead.endsWith(" Between them they are built to 42 or 36 keys, all for Choc v1 switches."),
            keysDiffer.lead,
        )

        val switchesDiffer = familyPage(corne, listOf(
            member(design("A", "Corne")),
            member(design("B", "Corne", switches = setOf(SwitchFootprint.Mx))),
        ))!!
        assertTrue(
            switchesDiffer.lead.endsWith(" All are built to 34 keys, for Choc v1 or MX switches."),
            switchesDiffer.lead,
        )
    }

    /** One split and one unibody would make "a split keyboard" a claim about the second
     *  board that its own record contradicts. The family rule forbids it; the sentence
     *  must not depend on that. */
    @Test
    fun `members of different body kinds are not called split or unibody`() {
        val page = familyPage(corne, listOf(
            member(design("A", "Corne", body = split)),
            member(design("B", "Corne", body = Body.Unibody)),
            member(design("C", "Corne", body = split)),
        ))!!

        assertTrue(page.lead.startsWith("Corne is a keyboard with 3 designs in the catalog: A, B and C."), page.lead)
    }

    @Test
    fun `the comparison has one column per variant and a row per fact, labelled as the design page labels them`() {
        val page = familyPage(corne, listOf(
            member(design("Corne Cherry v4", "Corne", keys = 42, switches = setOf(SwitchFootprint.Mx))),
            member(design("Corne Chocolate v4", "Corne")),
        ))!!

        assertEquals(listOf("Corne Cherry v4", "Corne Chocolate v4"), page.variants.map { it.name })
        assertEquals("/designs/corne-cherry-v4", page.variants[0].href)
        assertEquals(
            listOf("Keys", "Switch footprint", "Hotswap", "Controller", "Connectivity", "Firmware"),
            page.rows.map { it.label },
        )
        assertEquals(listOf("42", "34"), page.rows.first { it.label == "Keys" }.cells.map { it.value })
        assertEquals(listOf("MX", "Choc v1"), page.rows.first { it.label == "Switch footprint" }.cells.map { it.value })
    }

    /**
     * UNKNOWN IS NEVER "NO". An unrecorded firmware, and a Connectivity line the page did
     * not compute at all, both read "not recorded" in the ignorance style -- the same
     * treatment the design page and the compare page give a gap.
     */
    @Test
    fun `an unrecorded fact reads as not recorded, never as a value`() {
        val page = familyPage(corne, listOf(
            member(design("A", "Corne")),
            member(design("B", "Corne", firmware = Recorded.Unknown)),
        ))!!

        val firmware = page.rows.first { it.label == "Firmware" }.cells
        assertTrue(firmware[0].recorded)
        assertFalse(firmware[1].recorded)
        assertEquals(NOT_RECORDED, firmware[1].value)
        // toPage was given no connectivity, so neither page has the line at all.
        page.rows.first { it.label == "Connectivity" }.cells.forEach {
            assertFalse(it.recorded)
            assertEquals(NOT_RECORDED, it.value)
        }
        // switchMounts is Unknown on both fixtures: not recorded, and never "No".
        page.rows.first { it.label == "Hotswap" }.cells.forEach {
            assertFalse(it.recorded)
            assertFalse(it.value.startsWith("No"), it.value)
        }
    }

    /**
     * THE SOURCE LINE SAYS WHAT THE SOURCE IS. For 26 of the 27 families the cited source
     * is the author's GitHub account, which never calls the boards one keyboard: the
     * grouping is this site's ruling under the #107 rule. "Source for this grouping:
     * github.com/foostan" credited it to a page that does not state it (review,
     * 2026-10-03). An account is shown as what the variants share, and only when every
     * variant's own record has a source under it; a source that lists the variants itself
     * (Sweep's README) is still the grouping's source.
     */
    @Test
    fun `an account source is shown as where the variants are published, not as the grouping's source`() {
        val byAccount = familyPage(corne, listOf(
            member(design("A", "Corne", source = "https://github.com/foostan/crkbd")),
            member(design("B", "Corne", source = "https://github.com/Foostan/crkbd-choc")),
        ))!!
        assertEquals("Grouped by this site: every design is published from", byAccount.sourceIntro)

        // One variant whose record names no source under the account: the line claims nothing.
        val unchecked = familyPage(corne, listOf(
            member(design("A", "Corne", source = "https://github.com/foostan/crkbd")),
            member(design("B", "Corne", source = "https://gitlab.com/someone/b")),
        ))!!
        assertEquals("Grouped by this site; the source cited for it:", unchecked.sourceIntro)

        val sweep = FamilyRecord(FamilyName("Sweep"), SourceRef("https://github.com/davidphilipbarr/Sweep#what-are-the-different-types"))
        val listed = familyPage(sweep, listOf(
            member(design("Sweep 2", "Sweep", source = "https://github.com/davidphilipbarr/Sweep")),
            member(design("Sweep High", "Sweep", source = "https://github.com/davidphilipbarr/Sweep")),
        ))!!
        assertEquals("Source for this grouping:", listed.sourceIntro)
    }

    @Test
    fun `a family with fewer than two members has no page`() {
        assertNull(familyPage(corne, listOf(member(design("Corne Cherry v4", "Corne")))))
    }

    @Test
    fun `members are the designs carrying the family's name, by name`() {
        val members = familyMembers(FamilyName("Sweep"), catalogOf(
            design("Sweep High", "Sweep"), design("Cradio", "Cradio"), design("Sweep 2", "Sweep"), design("Loner"),
        ))
        assertEquals(listOf("Sweep 2", "Sweep High"), members.map { it.core.name.value })
    }

    @Test
    fun `the family line on a design page links to the family's own page`() {
        val family = familyOf(design("Sweep 2", "Sweep"), catalogOf(design("Sweep 2", "Sweep"), design("Sweep High", "Sweep")))!!
        assertEquals("/designs/families/sweep", family.href)
    }

    private val split = Body.Split(SplitHalves(2), Recorded.Unknown, Recorded.Unknown)
}
