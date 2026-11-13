package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.SeedDesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `seed/parts.json`, the curated layer of #112. A part, kit or listing the rest of the
 * site cannot resolve is a refused start, never a line that quietly drops off a page.
 */
class PartsCatalogTest {

    private val mapper = jacksonObjectMapper()

    private val realControllers = ControllerCatalog(mapper, ClassPathResource("seed/controllers.json"))

    private fun real() = PartsCatalog(
        mapper, ClassPathResource("seed/parts.json"), realControllers,
        SeedDesignCatalog(mapper, ClassPathResource("seed/designs.json")),
    )

    // -- the real seed ------------------------------------------------------------

    @Test
    fun `the real seed loads, its dev boards resolved from the controller catalogue`() {
        val catalog = real()
        val niceNano = catalog.products.single { it.slug == Slug("nice-nano-v2") } as Part
        assertEquals(realControllers.bySlug("nice-nano-v2"), niceNano.devBoard)
        assertEquals(Fitting.ControllerSocket(setOf(ControllerFootprint.ProMicro)), niceNano.fits)
    }

    @Test
    fun `the real seed's Lily58 Pro kit is based on the catalogue's Lily58 Pro`() {
        val kit = real().products.filterIsInstance<Kit>().single { it.slug == Slug("42keebs-lily58-pro") }
        assertEquals(Slug("lily58-pro"), kit.basedOn)
        assertTrue(kit.contents.any { it.quantity == Recorded.Unknown }, "the standoffs line lost its missing count")
        assertEquals(NeedKind.Pcb, kit.contents.single { it.text == "main PCBs" }.kind, "the grid's PCB row (#113)")
    }

    /** Measured from the board file, not ruled; every other design is Unknown, never "none". */
    @Test
    fun `a design's diode package is the measured one, or not recorded`() {
        val catalog = real()
        assertEquals(Recorded.Known(DiodePackage.Sod123), catalog.diodePackage(Slug("lily58-pro")))
        assertEquals(Recorded.Unknown, catalog.diodePackage(Slug("corne-chocolate-v4")))
    }

    /** #115: 42keebs' switch shelf, each variation its own part, the kind only as the vendor says it. */
    @Test
    fun `the real seed's switches fit a switch footprint and carry the vendor's kind`() {
        val catalog = real()
        val switches = catalog.products.filterIsInstance<Part>().filter { it.fits is Fitting.Switch }
        assertEquals(18, switches.size)
        val sunset = switches.single { it.slug == Slug("kailh-choc-sunset") }
        assertEquals(Fitting.Switch(setOf(SwitchFootprint.ChocV1)), sunset.fits)
        assertEquals(Recorded.Known(SwitchKind.Tactile), sunset.switch?.kind)
        val nocturnal = switches.single { it.slug == Slug("ambients-nocturnal") }
        assertEquals(Recorded.Known(SwitchKind.SilentLinear), nocturnal.switch?.kind)
        assertEquals(10, catalog.listings.single { it.product == Slug("jwick-black") }.pack)
        assertEquals(1, catalog.listings.single { it.product == Slug("gateron-cj") }.pack)
    }

    @Test
    fun `the real Lily58 Pro kit leaves the switches to the builder, MX or Choc V1`() {
        val kit = real().products.filterIsInstance<Kit>().single { it.slug == Slug("42keebs-lily58-pro") }
        assertEquals(
            Requirement(Fitting.Switch(setOf(SwitchFootprint.Mx, SwitchFootprint.ChocV1)), Recorded.Known(58)),
            kit.needs.single { it.fitting.kind == NeedKind.Switches },
        )
    }

    @Test
    fun `every listing in the real seed is one shop's one ref`() {
        val keys = real().listings.map { it.shop to it.ref }
        assertEquals(keys.distinct(), keys)
    }

    // -- refusals ------------------------------------------------------------------

    private val oneDesign = object : DesignCatalog {
        private val lily58 = PartsFixtures.lily58()
        override fun all(): List<Design> = listOf(lily58)
        override fun bySlug(slug: String): Design? = lily58.takeIf { it.core.slug.value == slug }
    }

    private val seed = """{
        "shops": [{"slug": "42keebs", "name": "42. Keebs", "currency": "EUR",
                   "prices_include_vat": false, "vat": {"NO": 25}}],
        "parts": [
            {"slug": "kailh-mx-hotswap-socket", "name": "Kailh MX hotswap socket",
             "fits": {"switch_socket": ["mx_socket"]}},
            {"slug": "1n4148w-sod123f", "name": "1N4148W", "fits": {"diode": "sod_123f"}},
            {"slug": "nice-nano-v2", "dev_board": "nice-nano-v2"},
            {"slug": "gateron-cj", "name": "Gateron CJ", "fits": {"switch": ["mx"]}, "switch_kind": "linear"},
            {"slug": "mystery-switch", "name": "Mystery", "fits": {"switch": ["choc_v1"]}}],
        "kits": [{"slug": "42keebs-lily58-pro", "name": "Lily58 Pro kit", "shop": "42keebs",
                  "url": "https://42keebs.eu/kit", "based_on": "lily58-pro", "read_on": "2026-10-04",
                  "contents": [{"quantity": 60, "text": "SMD diodes", "kind": "diodes"},
                               {"text": "Brass standoffs and screws"}],
                  "needs": [{"fits": {"controller_socket": ["pro_micro"]}, "quantity": 2}],
                  "not_priced": ["TRRS cable"]}],
        "listings": [
            {"shop": "42keebs", "ref": "855", "product": "kailh-mx-hotswap-socket", "pack": 10,
             "url": "https://42keebs.eu/855"},
            {"shop": "42keebs", "ref": "6241", "product": "42keebs-lily58-pro", "pack": 1,
             "url": "https://42keebs.eu/6241"}],
        "diode_packages": [{"design": "lily58-pro", "package": "sod_123", "source": "board-scan.json"}]
    }"""

    private fun load(json: String) =
        PartsCatalog(mapper, ByteArrayResource(json.toByteArray()), realControllers, oneDesign)

    private fun refused(from: String, to: String): String {
        assertTrue(from in seed, "the fixture has no '$from' to change")
        return assertFailsWith<IllegalArgumentException> { load(seed.replace(from, to)) }.message!!
    }

    @Test
    fun `the fixture itself loads, every field read`() {
        val catalog = load(seed)
        val kit = catalog.products.filterIsInstance<Kit>().single()
        assertEquals(
            listOf(KitLine(Recorded.Known(60), "SMD diodes", NeedKind.Diodes),
                   KitLine(Recorded.Unknown, "Brass standoffs and screws", null)),
            kit.contents,
        )
        assertEquals(
            listOf(Requirement(Fitting.ControllerSocket(setOf(ControllerFootprint.ProMicro)), Recorded.Known(2))),
            kit.needs,
        )
        assertEquals(Fitting.SwitchSocket(setOf(SwitchMount.MxSocket)),
            (catalog.products.single { it.slug == Slug("kailh-mx-hotswap-socket") } as Part).fits)
        assertEquals(listOf(Shop("42keebs", "42. Keebs", "EUR", false, mapOf("NO" to 25))), catalog.shops)
        assertEquals(10, catalog.listings.single { it.ref == "855" }.pack)
    }

    @Test
    fun `a switch's kind is the vendor's, and Unknown where the vendor gives none`() {
        val catalog = load(seed)
        val cj = catalog.products.single { it.slug == Slug("gateron-cj") } as Part
        assertEquals(Fitting.Switch(setOf(SwitchFootprint.Mx)), cj.fits)
        assertEquals(SwitchSpec(Recorded.Known(SwitchKind.Linear)), cj.switch)
        assertEquals(SwitchSpec(Recorded.Unknown), (catalog.products.single { it.slug == Slug("mystery-switch") } as Part).switch)
        assertEquals(null, (catalog.products.single { it.slug == Slug("kailh-mx-hotswap-socket") } as Part).switch)
    }

    /** #115: what the listing states, per that shop and dated; the rest Unknown, never "no". */
    @Test
    fun `the real seed's switch specs are the vendor's, in gf and mm`() {
        val black = (real().products.single { it.slug == Slug("jwick-black") } as Part).switch!!.stated!!
        assertEquals(StatedSpec(
            per = "42keebs", readOn = LocalDate.of(2026, 10, 5),
            actuationForceGf = Recorded.Known(45.0), bottomOutForceGf = Recorded.Known(63.5),
            actuationMm = Recorded.Known(2.0), travelMm = Recorded.Known(4.0),
            factoryLubed = Recorded.Known(true), pins = Recorded.Known(5),
            housing = Recorded.Known("Black nylon top and bottom housing"), stem = Recorded.Known("Black POM stem"),
            vendorSays = Recorded.Known("Very smooth pre-lubed heavier linear switches"),
        ), black)
        val pink = (real().products.single { it.slug == Slug("kailh-choc-pink") } as Part).switch!!.stated!!
        assertEquals(Recorded.Unknown, pink.actuationForceGf, "an unlabelled force is filed as actuation")
        assertEquals(Recorded.Unknown, pink.factoryLubed)
        assertEquals(null, (real().products.single { it.slug == Slug("ambients-sunrise") } as Part).switch!!.stated)
    }

    @Test
    fun `a switch spec is read, and a field left out is Unknown`() {
        val catalog = load(seed.replace("\"switch_kind\": \"linear\"}",
            "\"switch_kind\": \"linear\", \"spec\": {\"per\": \"42keebs\", \"read_on\": \"2026-10-05\", \"pins\": 3, \"travel_mm\": 4}}"))
        val spec = (catalog.products.single { it.slug == Slug("gateron-cj") } as Part).switch!!.stated!!
        assertEquals(Recorded.Known(3), spec.pins)
        assertEquals(Recorded.Known(4.0), spec.travelMm)
        assertEquals(Recorded.Unknown, spec.actuationForceGf)
        assertEquals(Recorded.Unknown, spec.housing)
    }

    @Test
    fun `a switch spec with a bad number or no source is refused`() {
        fun spec(json: String) = refused("\"switch_kind\": \"linear\"}", "\"switch_kind\": \"linear\", \"spec\": $json}")
        assertTrue("pins" in spec("{\"per\": \"42keebs\", \"read_on\": \"2026-10-05\", \"pins\": 4}"))
        assertTrue("kbdfans" in spec("{\"per\": \"kbdfans\", \"read_on\": \"2026-10-05\"}"))
        assertTrue("travel_mm" in spec("{\"per\": \"42keebs\", \"read_on\": \"2026-10-05\", \"travel_mm\": 40}"))
        assertTrue("actuation_force_gf" in spec("{\"per\": \"42keebs\", \"read_on\": \"2026-10-05\", \"actuation_force_gf\": 0}"))
    }

    @Test
    fun `a switch token or kind nobody knows is refused`() {
        assertTrue("cherry_mx" in refused("{\"switch\": [\"mx\"]}", "{\"switch\": [\"cherry_mx\"]}"))
        assertTrue("bespoke" in refused("{\"switch\": [\"mx\"]}", "{\"switch\": [\"bespoke\"]}"))
        assertTrue("silent" in refused("\"switch_kind\": \"linear\"", "\"switch_kind\": \"silent\""))
    }

    @Test
    fun `only a switch has a switch kind`() {
        assertTrue("1n4148w-sod123f" in refused("\"fits\": {\"diode\": \"sod_123f\"}}",
            "\"fits\": {\"diode\": \"sod_123f\"}, \"switch_kind\": \"linear\"}"))
    }

    @Test
    fun `a kit line may be the PCB`() {
        val catalog = load(seed.replace("\"text\": \"SMD diodes\", \"kind\": \"diodes\"", "\"text\": \"main PCBs\", \"kind\": \"pcb\""))
        assertEquals(NeedKind.Pcb, catalog.products.filterIsInstance<Kit>().single().contents.first().kind)
    }

    /** "own" is the build list's "I have this" in the URL (#113). */
    @Test
    fun `a product called own is refused`() {
        assertTrue("own" in refused("\"slug\": \"1n4148w-sod123f\"", "\"slug\": \"own\""))
    }

    @Test
    fun `an unknown token is refused, not read as Unknown`() {
        assertTrue("sod123" in refused("\"sod_123f\"", "\"sod123\""))
        assertTrue("solder" in refused("[\"mx_socket\"]", "[\"solder\"]"))
        assertTrue("promicro" in refused("[\"pro_micro\"]", "[\"promicro\"]"))
        assertTrue("diode" in refused("\"kind\": \"diodes\"", "\"kind\": \"diode\""))
        load(seed.replace("\"kind\": \"diodes\"", "\"kind\": \"switches\""))
    }

    @Test
    fun `a dev board the controller catalogue does not have is refused`() {
        assertTrue("frood" in refused("\"dev_board\": \"nice-nano-v2\"", "\"dev_board\": \"frood\""))
    }

    @Test
    fun `a kit based on no design is refused`() {
        assertTrue("corne-v3" in refused("\"based_on\": \"lily58-pro\"", "\"based_on\": \"corne-v3\""))
    }

    @Test
    fun `a diode package for no design is refused`() {
        assertTrue("corne-v3" in refused("\"design\": \"lily58-pro\"", "\"design\": \"corne-v3\""))
    }

    @Test
    fun `a listing of a product nobody defined is refused`() {
        assertTrue("gateron" in refused("\"product\": \"kailh-mx-hotswap-socket\"", "\"product\": \"gateron\""))
    }

    @Test
    fun `a pack of none is refused`() {
        refused("\"pack\": 10", "\"pack\": 0")
    }

    @Test
    fun `two listings of one ref are refused`() {
        assertTrue("855" in refused("\"ref\": \"6241\"", "\"ref\": \"855\""))
    }

    @Test
    fun `a fitting that names two interfaces is refused`() {
        refused("{\"diode\": \"sod_123f\"}", "{\"diode\": \"sod_123f\", \"switch_socket\": [\"mx_socket\"]}")
    }
}
