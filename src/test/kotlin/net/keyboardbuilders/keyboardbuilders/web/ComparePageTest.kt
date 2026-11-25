package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.DesignCore
import net.keyboardbuilders.keyboardbuilders.domain.DesignName
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.KeyCount
import net.keyboardbuilders.keyboardbuilders.domain.KeyPitch
import net.keyboardbuilders.keyboardbuilders.domain.Layout
import net.keyboardbuilders.keyboardbuilders.domain.LayoutName
import net.keyboardbuilders.keyboardbuilders.domain.License
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Licensing
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import net.keyboardbuilders.keyboardbuilders.domain.SplitHalves
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ByteArrayResource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Backlog #47's page, `/designs/compare?a=…&b=…`, as a reader receives it: the two
 * layouts overlaid, then `ux.md`:576's table with its "differences only" link.
 *
 * Fixed designs and fixed geometry, for the reason `DesignsControllerTest` gives: the
 * seed is regenerated whenever the research improves, and a page test reading it would
 * go red on a good day's data work. `SilhouetteOverlayTest` covers the drawing itself;
 * this covers what the page says about it.
 */
// Parts off: the parts seed is checked against the real design and controller
// catalogues at startup, and this class swaps one of them for a fixture.
@SpringBootTest(properties = ["keyboardbuilders.parts.enabled=false"])
@AutoConfigureMockMvc
class ComparePageTest {

    @TestConfiguration
    class Fixture {
        @Bean
        @Primary
        fun fixedCatalog(): DesignCatalog = object : DesignCatalog {
            private val designs = listOf(mxSplit, chocSplit, unibody, undrawn, twinSplit)
            override fun all() = designs
            override fun bySlug(slug: String) = designs.firstOrNull { it.core.slug.value == slug }
        }

        @Bean
        @Primary
        fun fixedRenames(mapper: ObjectMapper) = SlugRenames(
            mapper,
            ByteArrayResource("""{"old-mx": "mx-split"}""".toByteArray()),
        )

        /** `unibody` has a drawing and no `mm`, so it is the not-to-scale case; `undrawn`
         *  has no drawing at all. */
        @Bean
        @Primary
        fun fixedSilhouettes(mapper: ObjectMapper): Silhouettes {
            val hands = """[[0,0,1,1],[1,0,1,1],[3,0,1,1],[4,0,1,1]]"""
            return Silhouettes(
                mapper,
                ByteArrayResource(
                    """
                    {"mx-split":{"w":5,"h":1,"mm":19.05,"split":2.5,"keys":$hands},
                     "choc-split":{"w":5,"h":1,"mm":17.0,"split":2.5,"keys":$hands},
                     "unibody":{"w":3,"h":1,"keys":[[0,0,1,1],[1,0,1,1],[2,0,1,1]]},
                     "twin-split":{"w":5,"h":1,"mm":19.05,"split":2.5,"keys":$hands}}
                    """.trimIndent().toByteArray()
                ),
                ByteArrayResource(
                    """{"mx-split":{"2":{"w":2.5,"h":1,"mm":19.05,"split":1.25,"keys":[[0,0,1,1],[1.5,0,1,1]]}},
                        "twin-split":{"2":{"w":2.5,"h":1,"mm":19.05,"split":1.25,"keys":[[0,0,1,1],[1.5,0,1,1]]}}}"""
                        .toByteArray()
                ),
            )
        }

        private val split = Body.Split(SplitHalves(2), Recorded.Known(true), Recorded.Unknown)
        /** Two layouts, like KLOR: the main 4-key picture and a smaller one named "Tiny". */
        private val mxSplit = board(
            "mx-split", "MX Split", SwitchFootprint.Mx, Recorded.Known(KeyPitch.Mx), split,
            layouts = setOf(Layout(KeyCount(4)), Layout(KeyCount(2), name = Recorded.Known(LayoutName("Tiny")))),
        )
        /** A second multi-layout design, so both sides can offer a choice at once. */
        private val twinSplit = board(
            "twin-split", "Twin Split", SwitchFootprint.Mx, Recorded.Known(KeyPitch.Mx), split,
            layouts = setOf(Layout(KeyCount(4)), Layout(KeyCount(2), name = Recorded.Known(LayoutName("Small")))),
        )
        private val chocSplit = board("choc-split", "Choc Split", SwitchFootprint.ChocV1, Recorded.Known(KeyPitch.Choc), split)
        private val unibody = board("unibody", "Plain Unibody", SwitchFootprint.Mx, Recorded.Unknown, Body.Unibody)
        private val undrawn = board("undrawn", "Never Drawn", SwitchFootprint.Mx, Recorded.Unknown, Body.Unibody)

        private fun board(
            slug: String, name: String, switch: SwitchFootprint, pitch: Recorded<KeyPitch>, body: Body,
            layouts: Set<Layout> = setOf(Layout(KeyCount(4))),
        ) = Design(
            core = DesignCore(
                name = DesignName(name),
                slug = Slug(slug),
                sources = setOf(SourceRef("https://github.com/example/$slug")),
                firmware = Recorded.Known(setOf(FirmwareRef.Zmk(slug))),
                layouts = layouts,
                switches = setOf(switch),
                buildMethod = BuildMethod.Pcb(),
                controller = Controller.Socket(ControllerFootprint.ProMicro),
                body = body,
                licensing = Licensing(
                    hardware = LicenseState.Stated(setOf(License.Mit)),
                    firmware = LicenseState.Stated(setOf(License.Mit)),
                    documentation = LicenseState.Unstated,
                ),
                pitch = pitch,
            ),
        )
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun html(query: String) =
        mockMvc.get("/designs/compare?$query").andReturn().response.contentAsString

    /** The row the table printed for [label], or null where it printed none. */
    private fun row(page: String, label: String): String? =
        Regex("""<tr[^>]*>\s*<th scope="row">$label</th>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1)

    // -- the overlay ----------------------------------------------------------------

    @Test
    fun `two drawn designs are overlaid, to scale, and the page says so`() {
        val response = mockMvc.get("/designs/compare?a=mx-split&b=choc-split").andReturn().response
        val page = response.contentAsString

        assertEquals(200, response.status)
        assertTrue("""class="silhouette overlay"""" in page, "no overlay drawn")
        assertTrue("MX Split" in page && "Choc Split" in page)
        assertTrue("To scale" in page, "the scale is not stated")
        assertTrue("Left halves only" in page, "the hand rule is not stated")
    }

    /** Never 19.05 filled in silently: the page names the board it has no scale for. */
    @Test
    fun `not to scale is said out loud, naming the board without one`() {
        val page = html("a=unibody&b=mx-split")

        assertTrue("Not to scale" in page, page)
        assertTrue(Regex("""Not to scale[^<]*Plain Unibody""").containsMatchIn(page), "the unscaled board is not named")
    }

    /** A placeholder is an invented shape; a real board over one would read as a comparison. */
    @Test
    fun `a design with no drawing gets a sentence instead of an overlay, and the table stays`() {
        val page = html("a=mx-split&b=undrawn")

        assertFalse("""class="silhouette overlay"""" in page, "overlaid a board that has no drawing")
        assertTrue("Never Drawn&rsquo;s layout has not been drawn" in page || "Never Drawn’s layout has not been drawn" in page, page)
        assertTrue(row(page, "Key spacing") != null, "the table went with the overlay")
    }

    // -- the table ------------------------------------------------------------------

    @Test
    fun `the table lines the two designs' facts up by label`() {
        val spacing = row(html("a=mx-split&b=choc-split"), "Key spacing")!!

        assertTrue("19.05 mm" in spacing, spacing)
        assertTrue("18 × 17 mm" in spacing, spacing)
    }

    /**
     * Owner, 2026-11-25, comparing Stront MX-38 and MX-40: the table said "Firmware: MIT".
     * The design page has two rows labelled "Firmware", one under Electronics and one
     * under the licences, and the table keyed rows by label, so the licence replaced QMK.
     */
    @Test
    fun `the firmware row is the firmware and its licence has a row of its own`() {
        val page = html("a=mx-split&b=choc-split")
        val firmware = row(page, "Firmware")!!
        val licence = row(page, "Firmware license")

        assertTrue("ZMK" in firmware, firmware)
        assertFalse("MIT" in firmware, firmware)
        assertTrue(licence != null && "MIT" in licence, "no Firmware license row: $licence")
        assertTrue(row(page, "Hardware license") != null, "the hardware licence lost its row")
    }

    /**
     * ONLY TWO KNOWN VALUES CAN BE THE SAME. Both designs `not recorded` on Splay is not a
     * match, it is two gaps -- hiding the row would say "these agree" about a fact nobody
     * holds for either (`ux.md` §5.3: Unknown is a first-class value, not a blank).
     */
    @Test
    fun `differences only drops what both share and keeps what either does not know`() {
        val page = html("a=mx-split&b=choc-split&diff=1")

        assertEquals(null, row(page, "Build method"), "a row both designs share survived diff=1")
        assertTrue(row(page, "Key spacing") != null, "a real difference was hidden")
        assertTrue(row(page, "Splay") != null, "two unknowns were treated as agreeing")
    }

    /** `ux.md`:576 -- the toggle is a link, so the diff view is a URL like any other. */
    @Test
    fun `the differences toggle is a link both ways`() {
        assertTrue("""href="/designs/compare?a=mx-split&amp;b=choc-split&amp;diff=1"""" in html("a=mx-split&b=choc-split"))
        assertTrue("""href="/designs/compare?a=mx-split&amp;b=choc-split"""" in html("a=mx-split&b=choc-split&diff=1"))
    }

    /** `ux.md` §7.5: a scrollable wrapper nobody can focus is a keyboard trap. */
    @Test
    fun `the table scrolls inside a focusable, labeled wrapper`() {
        val page = html("a=mx-split&b=choc-split")

        assertTrue(Regex("""<div class="compare-scroll" tabindex="0" aria-label="[^"]+">\s*<table""").containsMatchIn(page), page)
    }

    // -- which layout ---------------------------------------------------------------

    /** Owner-reported 2026-09-25: comparing against KLOR offered no way to pick Konrad. */
    @Test
    fun `a design with several drawn layouts offers each one, by name`() {
        val page = html("a=mx-split&b=choc-split")

        assertTrue("""href="/designs/compare?a=mx-split&amp;b=choc-split&amp;al=2"""" in page, page)
        assertTrue("Tiny" in page)
        assertFalse("bl=" in page, "offered a choice for a design with one layout")
    }

    @Test
    fun `the chosen layout is the one drawn and named in the legend`() {
        val page = html("a=mx-split&b=choc-split&al=2")

        assertTrue("""class="silhouette overlay"""" in page)
        assertTrue(Regex("""MX Split</a> \([^)]*Tiny[^)]*\), filled""").containsMatchIn(page), page)
    }

    /** `ux.md` §1.1: one canonical form, everything else 301s. The largest is the default. */
    @Test
    fun `naming the default layout redirects to the URL without it`() {
        val response = mockMvc.get("/designs/compare?a=mx-split&b=choc-split&al=4").andReturn().response

        assertEquals(301, response.status)
        assertEquals("/designs/compare?a=mx-split&b=choc-split", response.getHeader("Location"))
    }

    @Test
    fun `a layout the design does not have is a 400`() {
        assertEquals(400, mockMvc.get("/designs/compare?a=mx-split&b=choc-split&al=7").andReturn().response.status)
    }

    /**
     * Owner-asked 2026-09-25: two designs that both have several layouts. Each picker
     * changes only its own side and keeps whatever the other side has chosen.
     */
    @Test
    fun `when both designs have several layouts, each picker keeps the other side's choice`() {
        val page = html("a=mx-split&b=twin-split&al=2")

        // twin-split's own "Small" choice keeps mx-split on its 2-key layout...
        assertTrue("""href="/designs/compare?a=mx-split&amp;b=twin-split&amp;al=2&amp;bl=2"""" in page, page)
        // ...and mx-split's link back to its default keeps twin-split where it is.
        assertTrue("""href="/designs/compare?a=mx-split&amp;b=twin-split"""" in page, page)
        assertEquals(2, Regex("""<p class="layout-choice">""").findAll(page).count(), "one picker per side")

        val both = html("a=mx-split&b=twin-split&al=2&bl=2")
        assertTrue("""href="/designs/compare?a=mx-split&amp;b=twin-split&amp;bl=2"""" in both, "a's default link dropped b's layout")
        assertTrue("""href="/designs/compare?a=mx-split&amp;b=twin-split&amp;al=2"""" in both, "b's default link dropped a's layout")
        assertTrue("""href="/designs/compare?a=twin-split&amp;b=mx-split&amp;al=2&amp;bl=2"""" in both, "swap lost a layout")
    }

    @Test
    fun `swapping carries each layout with its board`() {
        val page = html("a=mx-split&b=choc-split&al=2")

        assertTrue("""href="/designs/compare?a=choc-split&amp;b=mx-split&amp;bl=2"""" in page, page)
    }

    // -- the URL --------------------------------------------------------------------

    @Test
    fun `a renamed slug redirects permanently to the current comparison`() {
        val response = mockMvc.get("/designs/compare?a=old-mx&b=choc-split").andReturn().response

        assertEquals(301, response.status)
        assertEquals("/designs/compare?a=mx-split&b=choc-split", response.getHeader("Location"))
    }

    @Test
    fun `an unknown design is a 404 and a missing one a 400`() {
        assertEquals(404, mockMvc.get("/designs/compare?a=mx-split&b=nope").andReturn().response.status)
        assertEquals(400, mockMvc.get("/designs/compare").andReturn().response.status)
    }

    /** ~54,000 pairs, all near-duplicates of two design pages. Never indexed. */
    @Test
    fun `the comparison and the chooser are both noindex`() {
        for (query in listOf("a=mx-split&b=choc-split", "a=mx-split")) {
            val head = html(query).substringAfter("<head>").substringBefore("</head>")
            assertTrue("""<meta name="robots" content="noindex, follow">""" in head, query)
        }
    }

    /** And closed to crawlers from the first deploy -- the facet flood (2026-09-08) was
     *  a crawler walking exactly this kind of combinatorial query space. */
    @Test
    fun `robots closes the comparison space to every crawler group`() {
        val robots = mockMvc.get("/robots.txt").andReturn().response.contentAsString
        val groups = robots.split("User-agent:").drop(1)

        assertTrue(groups.isNotEmpty())
        for (group in groups) {
            assertTrue("Disallow: /designs/compare" in group, "a crawler group can walk the pairs:\n$group")
        }
    }

    // -- choosing the second board --------------------------------------------------

    @Test
    fun `without a second design, the page lets you find one by name`() {
        val page = html("a=mx-split&q=choc")

        assertTrue("""name="q"""" in page, "no search box")
        assertTrue("""href="/designs/compare?a=mx-split&amp;b=choc-split"""" in page, page)
        assertFalse("b=unibody" in page, "a name that does not match was offered")
        assertFalse("b=mx-split" in page, "offered to compare the board with itself")
    }

    @Test
    fun `the same design twice goes back to choosing`() {
        val page = html("a=mx-split&b=mx-split")

        assertTrue("""name="q"""" in page)
        assertFalse("""class="silhouette overlay"""" in page)
    }

    /** Followed, not matched, so a hand-built href with the wrong slug fails here. */
    @Test
    fun `the design page links to the comparison, and the link resolves`() {
        val page = mockMvc.get("/designs/mx-split").andReturn().response.contentAsString

        val href = Regex("""href="(/designs/compare\?a=[a-z0-9-]+)"""").find(page)?.groupValues?.get(1)
        assertEquals("/designs/compare?a=mx-split", href, "no compare link on the design page")
        assertEquals(200, mockMvc.get(href!!).andReturn().response.status)
    }
}
