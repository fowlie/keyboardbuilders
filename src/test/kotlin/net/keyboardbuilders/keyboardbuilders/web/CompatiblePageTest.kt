package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
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
import net.keyboardbuilders.keyboardbuilders.domain.License
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Licensing
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
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
 * Door B's page as a reader receives it: `/designs/:slug/compatible`, rendered.
 *
 * WHY THIS EXISTS SEPARATELY FROM `DesignPresentationTest`. That class covers
 * `toCompatiblePage()` thoroughly and none of it renders a template, so until this class
 * existed the KEYCAPS section's **rows** branch had never been rendered by anything —
 * only its message branch, incidentally, by `PartsControllerTest`'s link check. A
 * flattened message-or-rows pair is exactly the shape where the template is the half that
 * breaks: `@if(page.keycapMessage != null)` is one keystroke from inverted, and every
 * presentation test would still pass.
 *
 * Two fixture designs, one per branch, and both catalogues fixed for the reason
 * `DesignsControllerTest` gives: the seed is regenerated whenever the research improves,
 * and a page test asserting on its contents would go red on a good day's data work.
 */
// Parts off: the parts seed is checked against the real design and controller
// catalogues at startup, and this class swaps one of them for a fixture.
@SpringBootTest(properties = ["keyboardbuilders.parts.enabled=false"])
@AutoConfigureMockMvc
class CompatiblePageTest {

    @TestConfiguration
    class Fixture {
        @Bean
        @Primary
        fun fixedCatalog(): DesignCatalog = object : DesignCatalog {
            private val designs = listOf(chocSpaced, mxSpaced, noFirmware)
            override fun all() = designs
            override fun bySlug(slug: String) = designs.firstOrNull { it.core.slug.value == slug }
        }

        /** `Chicago Stenographer` carries no `url` so the template's plain-text branch is
         *  rendered by something. The real seed gives every row a citation, so nothing else
         *  would ever exercise it. */
        @Bean
        @Primary
        fun fixedKeycaps(mapper: ObjectMapper) = KeycapCatalog(
            mapper,
            ByteArrayResource(
                """[{"name":"MBK Choc Low Profile","slug":"mbk","stem":"choc_v1","caps":"reduced",
                     "url":"https://splitkb.com/mbk"},
                    {"name":"Chicago Stenographer","slug":"chicago-stenographer","stem":"choc_v1",
                     "caps":"reduced"}]"""
                    .toByteArray()
            ),
        )

        @Bean
        @Primary
        fun fixedControllers(mapper: ObjectMapper) = ControllerCatalog(
            mapper,
            ByteArrayResource(
                """[{"name":"nice!nano v2","slug":"nice-nano-v2","footprint":"pro_micro","wireless":true,"mcu_family":"nrf52",
                     "firmware":["zmk"]}]"""
                    .toByteArray()
            ),
        )

        private val chocSpaced = board("choc-spaced", SwitchFootprint.ChocV1, KeyPitch.Choc)
        private val mxSpaced = board("mx-spaced", SwitchFootprint.Mx, KeyPitch.Mx)
        private val noFirmware = board("no-firmware", SwitchFootprint.Mx, KeyPitch.Mx, Recorded.Unknown)

        private fun board(
            slug: String,
            switch: SwitchFootprint,
            pitch: KeyPitch,
            firmware: Recorded<Set<FirmwareRef>> = Recorded.Known(setOf(FirmwareRef.Zmk(slug))),
        ) = Design(
            core = DesignCore(
                name = DesignName(slug),
                slug = Slug(slug),
                sources = setOf(SourceRef("https://github.com/example/$slug")),
                firmware = firmware,
                layouts = setOf(Layout(KeyCount(42))),
                switches = setOf(switch),
                buildMethod = BuildMethod.Pcb(),
                controller = Controller.Socket(ControllerFootprint.ProMicro),
                body = Body.Unibody,
                licensing = Licensing(
                    hardware = LicenseState.Stated(setOf(License.Mit)),
                    firmware = LicenseState.Stated(setOf(License.Mit)),
                    documentation = LicenseState.Unstated,
                ),
                pitch = Recorded.Known(pitch),
            ),
        )
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun html(slug: String) = mockMvc.get("/designs/$slug/compatible").andReturn().response.contentAsString

    @Test
    fun `all three decided sections are on the page`() {
        val page = html("choc-spaced")

        assertEquals(200, mockMvc.get("/designs/choc-spaced/compatible").andReturn().response.status)
        for (heading in listOf("Controller", "Switches", "Keycaps")) {
            assertTrue("<h2>$heading</h2>" in page, "no $heading section")
        }
        assertFalse("not built yet" in page, "a section still calls itself unbuilt")
    }

    /** The rows branch, and the link that carries the profile's own source. */
    @Test
    fun `a choc-spaced board lists the choc-spaced profiles`() {
        val page = html("choc-spaced")

        assertTrue("""<a href="https://splitkb.com/mbk" rel="nofollow noopener">MBK Choc Low Profile</a>""" in page, page)
        assertTrue("Chicago Stenographer" in page, "the row with no citation vanished")
        assertFalse(
            """<a href="">Chicago Stenographer""" in page,
            "a row with no cited source rendered an empty link rather than plain text",
        )
    }

    /**
     * The message branch, on the case that is 151 of the 324 built designs. Asserted here
     * as well as in `DesignPresentationTest` because this is where an inverted `@if` would
     * show up: the sentence must reach the reader, not just the page object.
     */
    @Test
    fun `an MX board at MX spacing is told the whole market fits`() {
        val page = html("mx-spaced")

        assertTrue("Any MX keycap set fits" in page, page)
        assertFalse("MBK" in page, "a choc-spaced set was offered to an MX board")
    }

    /**
     * THE DOOR IS REACHABLE, which until 2026-09-04 it was not: nothing anywhere on the site
     * linked `/designs/:slug/compatible`, so the route — and `/parts/dev-board/:slug` behind
     * it — could only be reached by typing a URL. `ux.md` §2.9's own first sentence is
     * "reached from a prominent link in the design page's stat block".
     *
     * The link is followed rather than merely matched, because the failure this guards is a
     * template building the href by hand and getting the slug wrong, which no assertion on
     * the design page alone would catch.
     */
    @Test
    fun `the design page links to Door B, and the link resolves`() {
        val page = mockMvc.get("/designs/choc-spaced").andReturn().response.contentAsString

        val href = Regex("""href="(/designs/[a-z0-9-]+/compatible)"""").find(page)?.groupValues?.get(1)
        assertEquals("/designs/choc-spaced/compatible", href, "no link to Door B on the design page")
        assertTrue("What fits this board" in page, "the link is there but not in ux.md's own words")
        assertEquals(200, mockMvc.get(href!!).andReturn().response.status, "Door B's own link is dead")
    }

    /** Door C's twin button in ux.md §3.1's mock stays unbuilt: `/build-with` is out of scope
     *  by map ticket 29, and a prominent link to a route that does not exist is worse than
     *  no link. Asserted so nobody adds it from the mock without building the route. */
    @Test
    fun `the design page does not offer Door C, which does not exist`() {
        val page = mockMvc.get("/designs/choc-spaced").andReturn().response.contentAsString

        assertFalse("/build-with" in page, "a link to an unbuilt route")
    }

    /** `indexable = false` is still the ruling, and it is now waiting on #59's picker
     *  rather than on any missing fact — `DesignsController.compatible` says so. */
    @Test
    fun `the page is still noindex while its interactive half is unbuilt`() {
        val head = html("choc-spaced").substringAfter("<head>").substringBefore("</head>")

        assertTrue("""<meta name="robots" content="noindex, follow">""" in head, head)
    }

    /**
     * #106: a design with no firmware record still sockets a Pro Micro, and the nice!nano
     * fits it. What we cannot say is whether it RUNS this design, so the section says that,
     * and never "none of the boards run it", which would state the unknown as a no.
     */
    @Test
    fun `a design with no firmware record says so instead of claiming nothing runs it`() {
        val page = html("no-firmware")

        assertEquals(200, mockMvc.get("/designs/no-firmware/compatible").andReturn().response.status)
        assertTrue("firmware isn't recorded" in page, page)
        assertFalse("but none of the" in page, "an unknown firmware read as one no board runs")
        assertEquals(200, mockMvc.get("/designs/no-firmware").andReturn().response.status)
    }
}
