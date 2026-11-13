package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.FamilyCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `/designs/families/{family}` -- backlog #107's families, given a page each. People search a
 * keyboard by its family ("corne keyboard", "lily58"), and since #107 no design is called
 * just "Corne", so until these pages no URL on the site answered that search.
 *
 * Against the real seed, on purpose: the pages exist to be indexed, and what matters is
 * that every confirmed family the build shipped has one, linking every board it groups.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FamilyPageTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var families: FamilyCatalog
    @Autowired lateinit var designs: DesignCatalog

    private fun html(path: String) = mockMvc.get(path).andReturn().response.contentAsString
    private fun title(page: String) = Regex("<title>(.*?)</title>").find(page)?.groupValues?.get(1)

    @Test
    fun `a family page is indexable, headed with the family's name and canonical to itself`() {
        val response = mockMvc.get("/designs/families/corne").andReturn().response
        val page = response.contentAsString

        assertEquals(200, response.status)
        assertEquals("Corne", Regex("<h1[^>]*>(.*?)</h1>").find(page)?.groupValues?.get(1))
        assertEquals("Corne keyboard — 2 designs compared · Keyboard Builders", title(page))
        assertTrue("""<link rel="canonical" href="https://keyboardbuilders.net/designs/families/corne">""" in page)
        assertFalse("noindex" in page, "a family page asks not to be indexed")
        // "In the catalog", since the title's count is ours and not the keyboard's (foostan
        // has made more Cornes than these two); "where recorded", since a cell can be a gap.
        assertTrue(
            """<meta name="description" content="The 2 Corne designs in the catalog, side by side: Corne Cherry v4 and """ +
                """Corne Chocolate v4. Keys, switches, controller, firmware and more, where recorded.">""" in page,
            Regex("""<meta name="description"[^>]*>""").find(page)?.value,
        )
        assertTrue("""href="https://github.com/foostan"""" in page, "the grouping's source is not linked")
        assertTrue("Grouped by this site: every design is published from" in page, "the account reads as the grouping's source")
    }

    @Test
    fun `every confirmed family has a page linking every board in it`() {
        for (family in families.all()) {
            val page = html("/designs/families/${family.slug}")
            val members = familyMembers(family.name, designs)
            assertTrue(members.size >= 2, family.name.value)
            for (member in members) {
                assertTrue(
                    """href="/designs/${member.core.slug.value}"""" in page,
                    "/designs/families/${family.slug} does not link ${member.core.slug.value}",
                )
            }
        }
    }

    /**
     * `/designs/families` sits beside `/designs/{slug}`, and Spring prefers the literal
     * segment, so "families" reaches this page rather than the design lookup. The other half
     * of that guarantee -- no design may be slugged "families" -- is `SeedDesignCatalog`'s.
     */
    @Test
    fun `the family routes win over the design route for the word families`() {
        val page = html("/designs/families/corne")
        assertEquals("Corne keyboard — 2 designs compared · Keyboard Builders", title(page))
    }

    /** Never deployed, so nothing points at it and no redirect is owed. */
    @Test
    fun `the old keyboards address is gone`() {
        assertEquals(404, mockMvc.get("/keyboards").andReturn().response.status)
        assertEquals(404, mockMvc.get("/keyboards/corne").andReturn().response.status)
    }

    /** The singular was never deployed either (owner, 2026-10-03: "rename to families"). */
    @Test
    fun `the singular family address is gone`() {
        assertEquals(404, mockMvc.get("/designs/family").andReturn().response.status)
        assertEquals(404, mockMvc.get("/designs/family/corne").andReturn().response.status)
    }

    @Test
    fun `an unknown family is a 404`() {
        assertEquals(404, mockMvc.get("/designs/families/no-such-keyboard").andReturn().response.status)
    }

    /**
     * UNKNOWN IS NEVER "NO". A member whose firmware no record names (Grooveboard's RP
     * boards and Pancake v2, 2026-10-03) shows the site's own ignorance treatment in the
     * comparison, the same as on its own page. `FamilyTest` holds the rule on fixtures;
     * this holds the template to it.
     */
    @Test
    fun `an unrecorded fact in the comparison reads as not recorded`() {
        val member = assertNotNull(
            designs.all().firstOrNull { it.extras.family is Recorded.Known && it.core.firmware == Recorded.Unknown },
            "every family member's firmware is recorded now -- point this at another Unknown field",
        )
        val family = (member.extras.family as Recorded.Known).value

        val page = html(familyHref(family))
        assertTrue("""<td class="unrecorded">&mdash; not recorded &mdash;</td>""" in page, familyHref(family))
    }

    @Test
    fun `a design page's family line links up to the family page`() {
        val page = html("/designs/corne-cherry-v4")
        val line = assertNotNull(Regex("""<p class="family">(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(page)).value

        assertTrue("""href="/designs/families/corne"""" in line, line)
    }

    /** Owner, 2026-10-04: "no point in saying 2 of 2 designs", and "move it up behind the
     *  title": "**Corne Chocolate v4** from the Corne family". The family page counts them. */
    @Test
    fun `a design page names its family right after its title, with no count`() {
        val page = html("/designs/corne-cherry-v4")
        val afterTitle = page.substringAfter("</h1>").trimStart()
        val line = assertNotNull(Regex("""<p class="family">(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(page)).groupValues[1]

        assertTrue(afterTitle.startsWith("<p class=\"family\">"), "the family line is not right after the title")
        assertEquals(
            """from the <a href="/designs/families/corne">Corne family</a>""",
            line.replace(Regex("""\s+"""), " ").trim(),
        )
    }

    /**
     * ONE VOCABULARY (owner, 2026-10-04): the site's noun is "design", so these pages say
     * "design families" and count "designs", never "variants". "Keyboard" stays only in a
     * family page's title, for search, and never in a URL.
     */
    @Test
    fun `the family pages say designs, never variants, and no family URL says keyboard`() {
        val corne = html("/designs/families/corne")
        assertTrue("""<a href="/designs">&larr; All designs</a>""" in corne, "crumb")
        assertTrue(""""name":"Corne designs"""" in corne, "JSON-LD name")

        for ((path, page) in listOf("/designs/families/corne" to corne)) {
            val visible = page.substringAfter("</title>")
            assertFalse(Regex("variant", RegexOption.IGNORE_CASE).containsMatchIn(visible),
                "$path still says variant: " + Regex(".{0,60}variant.{0,60}", RegexOption.IGNORE_CASE).find(visible)?.value)
        }

        val hrefs = Regex("""href="([^"]*famil[^"]*)"""").findAll(corne + html("/designs/corne-cherry-v4"))
            .map { it.groupValues[1] }.toList()
        assertTrue(hrefs.isNotEmpty())
        // The path only: the site's own host, keyboardbuilders.net, is in every canonical link.
        hrefs.forEach { assertFalse("keyboard" in it.removePrefix("https://keyboardbuilders.net").lowercase(), it) }
    }

    /**
     * NO LIST PAGE, AND NO WAY IN BUT A MEMBER'S OWN PAGE (owner, 2026-10-04): "families
     * should be linked to from a design page only. So remove the whole Families list page."
     * The family pages themselves stay in the sitemap: they exist to be found by search.
     */
    @Test
    fun `there is no families list page, and only design pages link to a family`() {
        assertEquals(404, mockMvc.get("/designs/families").andReturn().response.status)
        for (path in listOf("/", "/designs", "/learn", "/about")) {
            assertFalse("/designs/families" in html(path), "$path links to the families")
        }
        assertTrue("""href="/designs/families/corne"""" in html("/designs/corne-cherry-v4"))

        val listed = Regex("""<loc>([^<]*)</loc>""").findAll(html("/sitemap.xml")).map { it.groupValues[1] }.toSet()
        assertFalse("https://keyboardbuilders.net/designs/families" in listed)
        for (family in families.all()) {
            assertTrue("https://keyboardbuilders.net/designs/families/${family.slug}" in listed, family.slug)
        }
    }

    /**
     * /settings' "render first" is the owner's choice for exactly this page: he asked for it
     * because "browsing a family's boards one after another was REALLY nice on the renders".
     * A family page that kept showing photos ignored the setting where it matters most.
     */
    @Test
    fun `with render first chosen, each board's card shows its PCB render`() {
        val renderFirst = jakarta.servlet.http.Cookie("kb-hero", "render")
        val page = mockMvc.get("/designs/families/corne") { cookie(renderFirst) }.andReturn().response.contentAsString
        val cards = Regex("""<a class="strip-card card" href="/designs/([^"]+)".*?</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(page).associate { it.groupValues[1] to it.value }

        for (slug in listOf("corne-cherry-v4", "corne-chocolate-v4")) {
            val card = assertNotNull(cards[slug], "no card for $slug")
            assertTrue(Regex("""<img src="/renders/[0-9a-f]+\.png" alt="[^"]*, PCB render"""").containsMatchIn(card), card)
        }
    }

    @Test
    fun `without the setting, a board with a photo still leads with it`() {
        val page = html("/designs/families/corne")
        val cherry = assertNotNull(
            Regex("""<a class="strip-card card" href="/designs/corne-cherry-v4".*?</a>""", RegexOption.DOT_MATCHES_ALL).find(page)?.value,
        )
        assertTrue(""", built"""" in cherry, cherry)
        assertFalse("/renders/" in cherry, cherry)
    }
}
