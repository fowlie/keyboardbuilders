package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.persistence.SeedImporter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import kotlin.test.assertEquals

/**
 * The offer layer's table (`V7__offer_observation.sql`), in the shared test context, which
 * has the parts module switched on. The rows are this class's own and go after each test.
 */
@SpringBootTest
// Unused here, but the same annotations as the web tests put this class in their cached
// Spring context instead of starting a second one (#114).
@AutoConfigureMockMvc
class OfferObservationsTest {

    @Autowired lateinit var observations: OfferObservations
    @Autowired lateinit var importer: SeedImporter
    @Autowired lateinit var db: JdbcClient

    /** After as well as before: the context is shared (#114), and a price left behind
     *  would show on another class's build list. */
    @BeforeEach @AfterEach fun clear() { db.sql("DELETE FROM offer_observation").update() }

    private fun look(ref: String, at: String, net: Long, stock: Stock = Stock.InStock, shop: String = "42keebs") =
        Observation(shop, ref, Instant.parse(at), net, "EUR", stock)

    @Test
    fun `the latest look at each ref is the newest one, whatever order they were written in`() {
        observations.record(look("4808", "2026-10-04T12:00:00Z", 2070, Stock.InStock))
        observations.record(look("4808", "2026-10-06T08:00:00Z", 2100, Stock.OutOfStock))
        observations.record(look("4808", "2026-10-05T08:00:00Z", 1990, Stock.Backorder))
        observations.record(look("855", "2026-10-04T12:00:00Z", 150))

        val latest = observations.latest("42keebs", listOf("4808", "855", "382"))

        assertEquals(look("4808", "2026-10-06T08:00:00Z", 2100, Stock.OutOfStock), latest["4808"])
        assertEquals(look("855", "2026-10-04T12:00:00Z", 150), latest["855"])
        assertEquals(setOf("4808", "855"), latest.keys, "a ref nobody has looked at has no latest look")
    }

    @Test
    fun `another shop's look at the same ref is not this shop's`() {
        observations.record(look("855", "2026-10-04T12:00:00Z", 150))
        observations.record(look("855", "2026-10-05T12:00:00Z", 999, shop = "other"))

        assertEquals(150, observations.latest("42keebs", listOf("855")).getValue("855").netMinor)
    }

    /** Stored to the second, so the table's text comparison stays right (V7's header). */
    @Test
    fun `a look is kept to the second`() {
        observations.record(look("855", "2026-10-04T12:00:00.700Z", 150))
        observations.record(look("855", "2026-10-04T12:00:01Z", 160))

        assertEquals(Instant.parse("2026-10-04T12:00:01Z"),
            observations.latest("42keebs", listOf("855")).getValue("855").observedAt)
    }

    @Test
    fun `a listing becomes an offer only once someone has looked at it`() {
        val sockets = Listing("42keebs", "855", Slug("kailh-mx-hotswap-socket"), null, 10, "https://42keebs.eu/855")
        val never = Listing("42keebs", "382", Slug("pro-micro"), null, 1, "https://42keebs.eu/382")
        observations.record(look("855", "2026-10-04T12:00:00Z", 150))

        assertEquals(
            listOf(Offer(sockets, look("855", "2026-10-04T12:00:00Z", 150))),
            observations.offers(listOf(sockets, never)),
        )
    }

    /** Every boot's import opens with DELETE FROM design. The price history must not go with it. */
    @Test
    fun `looks survive the seed import that runs on every boot`() {
        observations.record(look("4808", "2026-10-04T12:00:00Z", 2070))

        importer.importAll()

        assertEquals(2070, observations.latest("42keebs", listOf("4808")).getValue("4808").netMinor)
    }
}
