package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.parts.Observation
import net.keyboardbuilders.keyboardbuilders.parts.OfferObservations
import net.keyboardbuilders.keyboardbuilders.parts.Stock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `/designs/:slug/build` (#112) as a reader receives it, against the real design and parts
 * seed. The prices are this test's own, written in [seedOffers], so the totals below are
 * arithmetic on literals and not a reading of the shop.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BuildListPageTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var observations: OfferObservations
    @Autowired lateinit var db: JdbcClient
    @Autowired lateinit var renames: SlugRenames

    private fun html(path: String) = mockMvc.get(path).andReturn().response.contentAsString

    /** Visible text only, so a phrase split by a tag still matches. */
    private fun text(path: String) = html(path).replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")

    /** The 2026-10-04 prices, all seen just now: net, before Norway's 25%. */
    @BeforeEach
    fun seedOffers() {
        db.sql("DELETE FROM offer_observation").update()
        val now = Instant.now()
        fun look(ref: String, net: Long, stock: Stock) =
            observations.record(Observation("42keebs", ref, now, net, "EUR", stock))
        look("855", 150, Stock.InStock)          // MX sockets, per 10
        look("6198", 150, Stock.OutOfStock)      // Choc sockets, per 10
        look("7336", 25, Stock.InStock)          // SOD-123F diodes, per 10
        look("4808", 2070, Stock.InStock)        // nice!nano v2
        look("382", 580, Stock.OutOfStock)       // Pro Micro
        look("6241", 3500, Stock.InStock)        // the kit, MX black
        look("26354", 50, Stock.InStock)         // Gateron CJ switch, one each
    }

    /** The context is shared (#114), so these prices go with the test that wrote them. */
    @AfterEach
    fun clearOffers() {
        db.sql("DELETE FROM offer_observation").update()
    }

    @Test
    fun `the Lily58 Pro build list is one grid, a row per need and a column per way to buy`() {
        val response = mockMvc.get("/designs/lily58-pro/build").andReturn().response
        val body = response.contentAsString
        val page = text("/designs/lily58-pro/build")

        assertEquals(200, response.status)
        assertTrue("noindex" in body, "a local-only page asks to be indexed")
        assertEquals(1, Regex("<table class=\"compare build-grid\">").findAll(body).count())
        for (id in listOf("need-controller", "need-sockets", "need-diodes", "need-pcb")) {
            assertTrue("<tbody id=\"$id\">" in body, id)
        }
        assertEquals(2, Regex("<th scope=\"col\">").findAll(body).count(), "one head per column")
        assertTrue("Buy it yourself" in page, page)
        assertTrue("6 packs of 10 for €11.25" in page, page)
        assertTrue("not ruled a fit" in page, "the SOD-123F diodes read as a fit")
        assertTrue("8-bit AVR chips cannot run ZMK" in page, "the Pro Micro's reason is missing")
        assertTrue("PCB About 142.5 × 106.3 mm, 2 boards (one per half)." in page, page)
        assertTrue("Not priced: fab prices not recorded yet" in page, page)
        assertTrue("In the kit 2 × main PCBs" in page, page)
        // Self-sourced misses the diodes and the PCB, so it has no number at all.
        assertTrue("Incomplete (2 lines) Not priced: diodes, PCB" in page, page)
        assertFalse("€63.00" in page, "a partial sum is shown beside a whole kit")
        // The kit at 43.75 plus the two nice!nanos it leaves out.
        assertTrue("€95.50 before shipping, without switches" in page, page)
        assertTrue("Shipping from 42. Keebs: not priced yet" in page, page)
        assertTrue("per 42. Keebs" in page, "a kit line does not say whose fact it is")
        assertTrue("Brass standoffs and screws" in page)
        assertTrue("MIT" in page, "the design's licence is not beside the based-on link")
        assertTrue("Only the controller, switch sockets, diodes and switches are priced here." in page, page)
    }

    /**
     * The owner clicked "nice!nano v2" in the grid to choose it and landed on the shop, in
     * the same tab. A part name in the grid now opens the row's offers, each picked by its
     * name; the shop is a separate link that says it opens a new tab.
     */
    @Test
    fun `a part in the grid is picked in its cell, and the shop opens in a new tab`() {
        // The Choc sockets back in stock, so the sockets row has a second choice to pick.
        observations.record(Observation("42keebs", "6198", Instant.now().plusSeconds(1), 150, "EUR", Stock.InStock))
        val body = html("/designs/lily58-pro/build")
        val grid = body.substringAfter("<table class=\"compare build-grid\">").substringBefore("</table>")
        val needs = grid.substringAfter("</thead>")

        assertFalse(Regex("<a [^>]*href=\"https://42keebs\\.eu/[^\"]*\"[^>]*>\\s*nice!nano v2").containsMatchIn(grid),
            "the nice!nano's name still links to the shop")
        assertTrue(Regex("<summary><span class=\"part\">nice!nano v2, wireless</span>").containsMatchIn(needs), "the cell is not a picker")
        assertTrue("colspan" !in grid, "a need still spans the columns")
        assertEquals(5, Regex("<th scope=\"row\"").findAll(needs.substringBefore("<tfoot>")).count(), "one row head per need")
        assertTrue("Choose from" !in body, "the separate row picker is still there")

        // With both sockets countable, the one not in effect is picked by its name, back to this row.
        assertTrue(Regex("<li class=\"fits\"><a href=\"/designs/lily58-pro/build\\?sockets=kailh-[a-z0-9-]+#need-sockets\">Kailh ")
            .containsMatchIn(needs), needs)

        val shopLinks = Regex("<a [^>]*href=\"https://[^\"]*\"[^>]*>").findAll(body.substringBefore("<footer")).map { it.value }.toList()
        assertTrue(shopLinks.isNotEmpty())
        for (a in shopLinks) {
            assertTrue("target=\"_blank\"" in a && "class=\"external\"" in a && "rel=\"nofollow noopener\"" in a, a)
        }
    }

    /** 2 x 20.70 and 6 x 1.50 net, at 25%: 51.75 + 11.25. */
    @Test
    fun `owning the diodes and the PCB gives the self-sourced column its total`() {
        val page = text("/designs/lily58-pro/build?diodes=own&pcb=own")
        assertTrue("€63.00 before shipping, without switches" in page, page)
        assertTrue("You have this · undo" in page, page)
    }

    /** #115: no default switch, so the totals leave them out and say so, until the reader picks. */
    @Test
    fun `switches are the reader's choice, picked through the URL and priced per key`() {
        val unpicked = text("/designs/lily58-pro/build")
        assertTrue("Switches 58 × MX or Choc v1 switch, one per key." in unpicked, unpicked)
        assertTrue("Choose switches" in unpicked, unpicked)
        assertFalse("Not priced: switches" in unpicked, unpicked)

        // 58 x 0.50 net at 25%, in both columns, so the kit's €95.50 becomes €131.75.
        val picked = text("/designs/lily58-pro/build?switches=gateron-cj")
        assertTrue("Gateron CJ 58 for €36.25, your pick" in picked, picked)
        assertTrue("€131.75 before shipping" in picked, picked)
        assertFalse("without switches" in picked, picked)
    }

    /** #115: the controller is a commodity row, so the reader sees what the default chose. */
    @Test
    fun `a controller says whether it is wireless or wired`() {
        val page = text("/designs/lily58-pro/build")
        assertTrue("nice!nano v2, wireless 2 for €51.75" in page, page)
        assertTrue("Pro Micro (ATmega32U4), wired 2 for €14.50" in page, page)
    }

    @Test
    fun `a pick nobody can count, typed rubbish and tracking tags all give the page`() {
        val proMicro = mockMvc.get("/designs/lily58-pro/build?controller=pro-micro").andReturn().response
        assertEquals(200, proMicro.status)
        assertTrue("can't be counted in either column" in text("/designs/lily58-pro/build?controller=pro-micro"))

        val script = mockMvc.get("/designs/lily58-pro/build") { param("controller", "<script>alert(1)</script>") }.andReturn().response
        assertEquals(200, script.status)
        assertFalse("alert(1)" in script.contentAsString, "the typed text is echoed")
        assertTrue("We could not find your pick for the controller" in script.contentAsString)

        val utm = mockMvc.get("/designs/lily58-pro/build?utm_source=x").andReturn().response
        assertEquals(200, utm.status)
        assertFalse("your pick" in utm.contentAsString.lowercase(), "a tracking tag made a note")
    }

    @Test
    fun `a key given twice takes its first value`() {
        val page = text("/designs/lily58-pro/build?controller=nice-nano-v2&controller=pro-micro")
        assertTrue("2 for €51.75, your pick" in page, page)
        assertFalse("can't be counted" in page, page)
    }

    @Test
    fun `an unknown design is a 404 and a renamed one a 301 to its build list, picks kept`() {
        assertEquals(404, mockMvc.get("/designs/no-such-board/build").andReturn().response.status)

        // Whichever rename the real file lists first, so no particular one is leaned on. It
        // had a one-rename fixture of its own until #114, which cost a Spring context.
        val (former, current) = renames.all().entries.first()
        val moved = mockMvc.get("/designs/$former/build").andReturn().response
        assertEquals(301, moved.status)
        assertEquals("/designs/$current/build", moved.getHeader("Location"))
        // A URI, not a string: MockMvc would expand a string as a template and encode the % again.
        val picked = mockMvc.get(URI("/designs/$former/build?diodes=own&controller=%7Bx%7D")).andReturn().response
        assertEquals(301, picked.status)
        assertEquals("/designs/$current/build?diodes=own&controller=%7Bx%7D", picked.getHeader("Location"))
    }

    @Test
    fun `the build list is in no sitemap and linked from no design page`() {
        assertFalse("/build" in html("/sitemap.xml"))
        assertFalse("/build\"" in html("/designs/lily58-pro"))
    }
}
