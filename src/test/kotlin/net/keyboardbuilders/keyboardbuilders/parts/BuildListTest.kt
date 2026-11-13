package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFit
import net.keyboardbuilders.keyboardbuilders.domain.Mcu
import net.keyboardbuilders.keyboardbuilders.domain.McuFamily
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.cell
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.grid
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.chocSocket
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.lily58
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.line
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.listing
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.mxSocket
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.niceNano
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.offer
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.proMicro
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.sod123
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.sod123f
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A row's offers (#112): every offer that fits the need, in stock first and then the
 * cheaper line, packs rounded up, VAT in. The default is the first a reader could act on
 * today; one that cannot count stays listed so a reader sees why. The grid itself is
 * `BuildGridTest`.
 */
class BuildListTest {

    private val mx855 = listing("855", mxSocket, pack = 10)
    private val choc6198 = listing("6198", chocSocket, pack = 10)

    @Test
    fun `58 sockets sold in tens is six packs, two spare, VAT on the line`() {
        val counted = grid().line(NeedKind.SwitchSockets).counted!!
        assertEquals(mxSocket, counted.product)
        assertEquals(Recorded.Known(LineCost(packs = 6, spare = 2, grossMinor = 1125)), counted.line)
        assertEquals(188L, counted.packGrossMinor)
    }

    @Test
    fun `in stock beats a cheaper out-of-stock offer`() {
        val list = grid(offers = listOf(offer(choc6198, 100, Stock.OutOfStock), offer(mx855, 150, Stock.InStock)))
        val sockets = list.line(NeedKind.SwitchSockets)
        assertEquals(listOf(mxSocket, chocSocket), sockets.offers.map { it.product })
        assertEquals(mxSocket, sockets.counted?.product)
    }

    @Test
    fun `within one stock state the cheaper line comes first`() {
        val list = grid(offers = listOf(offer(mx855, 200, Stock.InStock), offer(choc6198, 150, Stock.InStock)))
        assertEquals(listOf(chocSocket, mxSocket), list.line(NeedKind.SwitchSockets).offers.map { it.product })
    }

    @Test
    fun `a backorder counts, after anything in stock`() {
        val list = grid(offers = listOf(offer(mx855, 100, Stock.Backorder), offer(choc6198, 150, Stock.InStock)))
        assertEquals(listOf(chocSocket, mxSocket), list.line(NeedKind.SwitchSockets).offers.map { it.product })
        assertEquals(niceNano, grid().line(NeedKind.Controller).counted?.product)
    }

    @Test
    fun `all out of stock lists every offer, counts none, and names the line`() {
        val list = grid(offers = listOf(offer(mx855, 150, Stock.OutOfStock), offer(choc6198, 150, Stock.OutOfStock)))
        val sockets = list.line(NeedKind.SwitchSockets)
        assertEquals(2, sockets.offers.size)
        assertNull(sockets.counted)
        assertEquals(Cell.NotPriced(UnpricedReason.NoOfferCounted), list.cell(NeedKind.SwitchSockets))
    }

    /** A listing nobody has looked at yet is Unknown, not a shop that does not sell it. */
    @Test
    fun `a listing with no look yet is on its line as unseen, and never counted`() {
        val list = grid(offers = listOf(offer(choc6198, 150, Stock.OutOfStock)), listings = listOf(mx855, choc6198))
        val sockets = list.line(NeedKind.SwitchSockets)
        assertEquals(listOf(UnseenListing(mx855, mxSocket, OfferFit.Fits)), sockets.unseen)
        assertEquals(listOf(chocSocket), sockets.offers.map { it.product })
        assertNull(sockets.counted)
        assertEquals(Cell.NotPriced(UnpricedReason.NoOfferCounted), list.cell(NeedKind.SwitchSockets))
        assertEquals(emptyList(), list.line(NeedKind.Diodes).unseen, "an MX socket is not a diode")
    }

    /** ZMK cannot run on the Pro Micro's ATmega32U4: listed with that verdict, never counted. */
    @Test
    fun `a controller that cannot run the design's firmware is listed and never counted`() {
        val list = grid(offers = listOf(offer(listing("382", proMicro), 580, Stock.InStock)))
        val controller = list.line(NeedKind.Controller)
        assertEquals(
            OfferFit.Controller(ControllerFit.RuledOut(McuFamily.Avr8, setOf("zmk"))),
            controller.offers.single().fit,
        )
        assertNull(controller.counted)
    }

    @Test
    fun `a socket-only controller is listed and never counted`() {
        val noFirmware = lily58().let { it.copy(core = it.core.copy(firmware = Recorded.Unknown)) }
        val list = grid(design = noFirmware, offers = listOf(offer(listing("4808", niceNano), 2070, Stock.InStock)))
        val controller = list.line(NeedKind.Controller)
        assertEquals(OfferFit.Controller(ControllerFit.SocketOnly(emptySet())), controller.offers.single().fit)
        assertNull(controller.counted)
    }

    @Test
    fun `a SOD-123F diode on SOD-123 pads is not ruled a fit, and never counted`() {
        val diodes = grid().line(NeedKind.Diodes)
        assertEquals(OfferFit.NotRuledAFit, diodes.offers.single().fit)
        assertNull(diodes.counted)
    }

    @Test
    fun `on an unknown package every diode is not ruled a fit`() {
        val withExact = PartsFixtures.products + sod123
        val offers = PartsFixtures.today + offer(listing("9999", sod123, pack = 10), 25, Stock.InStock)
        val diodes = grid(diodePackage = Recorded.Unknown, products = withExact, offers = offers).line(NeedKind.Diodes)
        assertEquals(listOf(OfferFit.NotRuledAFit, OfferFit.NotRuledAFit), diodes.offers.map { it.fit })
        assertNull(diodes.counted)
    }

    @Test
    fun `the board's own package fits and counts`() {
        val offers = PartsFixtures.today + offer(listing("9999", sod123, pack = 10), 25, Stock.InStock)
        val diodes = grid(products = PartsFixtures.products + sod123, offers = offers).line(NeedKind.Diodes)
        assertEquals(sod123, diodes.counted?.product)
        assertEquals(OfferFit.Fits, diodes.counted?.fit)
    }

    @Test
    fun `a price older than seven days is stale and left out of the total`() {
        val old = grid(offers = listOf(offer(mx855, 150, Stock.InStock, Duration.ofDays(7).plusSeconds(1))))
        val sockets = old.line(NeedKind.SwitchSockets)
        assertTrue(sockets.offers.single().stale)
        assertNull(sockets.counted)
        assertEquals(Cell.NotPriced(UnpricedReason.NoOfferCounted), old.cell(NeedKind.SwitchSockets))

        for (age in listOf(Duration.ofDays(6), Duration.ofDays(7))) {
            val fresh = grid(offers = listOf(offer(mx855, 150, Stock.InStock, age))).line(NeedKind.SwitchSockets)
            assertFalse(fresh.offers.single().stale, "$age")
            assertEquals(mxSocket, fresh.counted?.product, "$age")
        }
    }

    @Test
    fun `an unknown quantity is listed at its pack price and never counted`() {
        val list = grid(design = lily58(wiring = Recorded.Unknown))
        val controller = list.line(NeedKind.Controller)
        val nano = controller.offers.first()
        assertEquals(niceNano, nano.product)
        assertEquals(Recorded.Unknown, nano.line)
        assertEquals(2588L, nano.packGrossMinor)
        assertNull(controller.counted)
        assertEquals(Cell.NotPriced(UnpricedReason.QuantityNotRecorded), list.cell(NeedKind.Controller))
    }

    @Test
    fun `the total names every line it left out, and nothing to buy is not one of them`() {
        val list = grid(design = lily58(wiring = Recorded.Unknown, mounts = Recorded.Unknown,
                                        controller = Controller.Soldered(Recorded.Known(Mcu.Rp2040))),
                        products = PartsFixtures.products - PartsFixtures.kit)
        assertEquals(
            Total.Incomplete(listOf(
                LeftOut.Unpriced(NeedKind.SwitchSockets, UnpricedReason.NotRecorded),
                LeftOut.Unpriced(NeedKind.Diodes, UnpricedReason.NotRecorded),
                LeftOut.Unpriced(NeedKind.Pcb, UnpricedReason.FabNotPriced),
            )),
            list.columns.single().total,
        )
    }

    @Test
    fun `Mill-Max-only pads are left out as out of this slice`() {
        val list = grid(design = lily58(mounts = Recorded.Known(setOf(SwitchMount.MillMax))))
        assertEquals(Cell.NotPriced(UnpricedReason.OutOfSlice), list.cell(NeedKind.SwitchSockets))
    }

    @Test
    fun `a line lists only parts that fit its interface`() {
        val list = grid()
        assertEquals(setOf(niceNano, proMicro), list.line(NeedKind.Controller).offers.map { it.product }.toSet())
        assertEquals(setOf(mxSocket, chocSocket), list.line(NeedKind.SwitchSockets).offers.map { it.product }.toSet())
        assertEquals(setOf(sod123f), list.line(NeedKind.Diodes).offers.map { it.product }.toSet())
    }

    @Test
    fun `a price in another currency than the shop's is refused`() {
        val dollars = Offer(mx855, Observation("42keebs", "855", PartsFixtures.now, 150, "USD", Stock.InStock))
        assertThrows<IllegalArgumentException> { grid(offers = listOf(dollars)) }
    }

    @Test
    fun `an offer is one listing and its own observation`() {
        assertThrows<IllegalArgumentException> {
            Offer(mx855, Observation("42keebs", "6198", PartsFixtures.now, 150, "EUR", Stock.InStock))
        }
    }
}
