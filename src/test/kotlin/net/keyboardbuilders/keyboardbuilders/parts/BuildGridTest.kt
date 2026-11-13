package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.Mcu
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.cell
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.grid
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.kit
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.kitInStock
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.kitListing
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.lily58
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.mxSocket
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.niceNano
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.offer
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.row
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The build list as a grid (#113): a row per need, a column per way to get it. A column
 * with a gap has no total, so a self-sourced €63.00 can never stand beside a kit's
 * €95.50 as if it bought the same things.
 */
class BuildGridTest {

    private val withKit = PartsFixtures.today + kitInStock

    // -- shape -------------------------------------------------------------------------

    @Test
    fun `one row per need kind, in kind order, and one cell per column`() {
        val grid = grid(offers = withKit)
        assertEquals(NeedKind.entries, grid.rows.map { it.kind })
        assertEquals(listOf(null, kit), grid.columns.map { it.kit })
        assertTrue(grid.rows.all { it.cells.size == grid.columns.size })
    }

    @Test
    fun `a design with no kit has the one column`() {
        val grid = grid(products = PartsFixtures.products - kit)
        assertEquals(listOf(null), grid.columns.map { it.kit })
        assertTrue(grid.rows.all { it.cells.size == 1 })
    }

    @Test
    fun `only kits based on this design get a column`() {
        val other = kit.copy(slug = Slug("42keebs-corne"), basedOn = Slug("corne-chocolate-v4"))
        assertEquals(listOf(null, kit), grid(products = PartsFixtures.products + other).columns.map { it.kit })
    }

    // -- defaults ----------------------------------------------------------------------

    @Test
    fun `the self-sourced column starts on the default offers, and the diodes are not priced`() {
        val grid = grid()
        val controller = assertIs<Cell.Picked>(grid.cell(NeedKind.Controller))
        assertEquals(niceNano, controller.offer.product)
        assertEquals(false, controller.byReader)
        assertEquals(mxSocket, assertIs<Cell.Picked>(grid.cell(NeedKind.SwitchSockets)).offer.product)
        assertEquals(Cell.NotPriced(UnpricedReason.NoOfferCounted), grid.cell(NeedKind.Diodes))
        assertEquals(Cell.NotPriced(UnpricedReason.FabNotPriced), grid.cell(NeedKind.Pcb))
    }

    @Test
    fun `a row's offers are the self-sourced line's, in offer order`() {
        val grid = grid()
        assertEquals(listOf(niceNano, PartsFixtures.proMicro), grid.row(NeedKind.Controller).offers.map { it.product })
        assertEquals(listOf(mxSocket, PartsFixtures.chocSocket), grid.row(NeedKind.SwitchSockets).offers.map { it.product })
        assertEquals(emptyList(), grid.row(NeedKind.Pcb).offers)
    }

    @Test
    fun `nothing to buy, not recorded and out of slice each have their own cell`() {
        val grid = grid(design = lily58(wiring = Recorded.Unknown, mounts = Recorded.Known(setOf(
            net.keyboardbuilders.keyboardbuilders.domain.SwitchMount.MillMax)),
            controller = Controller.Soldered(Recorded.Known(Mcu.Rp2040))))
        assertEquals(Cell.NothingToBuy(NoneReason.OnTheBoard(Recorded.Known(Mcu.Rp2040))), grid.cell(NeedKind.Controller))
        assertEquals(Cell.NotPriced(UnpricedReason.OutOfSlice), grid.cell(NeedKind.SwitchSockets))
        assertEquals(Cell.NotPriced(UnpricedReason.NotRecorded), grid.cell(NeedKind.Diodes))
    }

    @Test
    fun `an unknown quantity is not priced, and says why`() {
        assertEquals(Cell.NotPriced(UnpricedReason.QuantityNotRecorded),
            grid(design = lily58(wiring = Recorded.Unknown)).cell(NeedKind.Controller))
    }

    @Test
    fun `a hand-wired design has no PCB to buy`() {
        val handwired = lily58().let { it.copy(core = it.core.copy(buildMethod = BuildMethod.Handwire)) }
        assertEquals(Cell.NothingToBuy(NoneReason.Handwired), grid(design = handwired).cell(NeedKind.Pcb))
    }

    // -- kit cells ---------------------------------------------------------------------

    @Test
    fun `what the kit says to buy is priced, what is in the box is in the kit`() {
        val grid = grid(offers = withKit)
        assertEquals(niceNano, assertIs<Cell.Picked>(grid.cell(NeedKind.Controller, 1)).offer.product)
        assertEquals(kit.contents.single { it.kind == NeedKind.SwitchSockets }, assertIs<Cell.InKit>(grid.cell(NeedKind.SwitchSockets, 1)).line)
        assertIs<Cell.InKit>(grid.cell(NeedKind.Diodes, 1))
        assertIs<Cell.InKit>(grid.cell(NeedKind.Pcb, 1))
    }

    /** The design's facts never carry over to a closed variant. */
    @Test
    fun `a kind the vendor says nothing about is not recorded in the kit column`() {
        val silent = kit.copy(contents = kit.contents.filter { it.kind != NeedKind.Diodes })
        val grid = grid(products = PartsFixtures.products - kit + silent, offers = withKit)
        assertEquals(Cell.NotPriced(UnpricedReason.NotRecorded), grid.cell(NeedKind.Diodes, 1))
    }

    /** The design's ZMK rules out a Pro Micro; the kit's own facts say nothing about firmware. */
    @Test
    fun `a kit's controller matches on footprint alone`() {
        val proMicroInStock = listOf(kitInStock, offer(PartsFixtures.listing("382", PartsFixtures.proMicro), 580, Stock.InStock))
        val grid = grid(offers = proMicroInStock)
        assertEquals(Cell.NotPriced(UnpricedReason.NoOfferCounted), grid.cell(NeedKind.Controller))
        assertEquals(PartsFixtures.proMicro, assertIs<Cell.Picked>(grid.cell(NeedKind.Controller, 1)).offer.product)
    }

    @Test
    fun `a row whose design need buys nothing takes its offers from the kit's line`() {
        val soldered = lily58(controller = Controller.Soldered(Recorded.Unknown))
        val offers = grid(design = soldered, offers = withKit).row(NeedKind.Controller).offers
        assertEquals(listOf(niceNano, PartsFixtures.proMicro), offers.map { it.product })
    }

    // -- totals ------------------------------------------------------------------------

    @Test
    fun `a column with a gap has no total, only the gaps, in row order`() {
        assertEquals(
            Total.Incomplete(listOf(LeftOut.Unpriced(NeedKind.Diodes, UnpricedReason.NoOfferCounted),
                                    LeftOut.Unpriced(NeedKind.Pcb, UnpricedReason.FabNotPriced))),
            grid(offers = withKit).columns[0].total,
        )
    }

    /** Today's 42keebs: kit 35.00 net and two nice!nanos at 20.70 net, at 25% VAT. */
    @Test
    fun `the Lily58 kit is complete, the kit plus what it leaves out`() {
        assertEquals(Total.Complete(4375L + 5175L, without = listOf(NeedKind.Switches)), grid(offers = withKit).columns[1].total)
    }

    @Test
    fun `a kit with no price we can count is a gap`() {
        assertEquals(Total.Incomplete(listOf(LeftOut.KitPrice)), grid().columns[1].total)
        val soldOut = grid(offers = PartsFixtures.today + offer(kitListing, 3500, Stock.OutOfStock))
        assertEquals(1, soldOut.columns[1].kitOffers.size)
        assertEquals(Total.Incomplete(listOf(LeftOut.KitPrice)), soldOut.columns[1].total)
    }

    /** The #112 demo's bug: €63.00 for controllers and sockets beside a whole kit. */
    @Test
    fun `no self-sourced sum is ever made while the diodes are a gap`() {
        val total = grid(offers = withKit).columns[0].total
        assertIs<Total.Incomplete>(total)
        assertNull((total as? Total.Complete)?.grossMinor)
    }

    @Test
    fun `a kit's variations are ordered like any other offers`() {
        val white = kitListing.copy(ref = "6242", label = "MX, white")
        val grid = grid(offers = listOf(offer(kitListing, 3500, Stock.OutOfStock), offer(white, 3500, Stock.InStock)))
        assertEquals(listOf("6242", "6241"), grid.columns[1].kitOffers.map { it.offer.listing.ref })
    }

    /** 42keebs adds a power switch the open Lily58 Pro lacks. It stays the kit's fact. */
    @Test
    fun `a kit never changes the design or its self-sourced column`() {
        val design = lily58()
        val withKits = grid(design = design, offers = withKit)
        val withoutKits = grid(design = design, products = PartsFixtures.products - kit, offers = withKit)
        assertEquals(withoutKits.rows.map { it.cells[0] }, withKits.rows.map { it.cells[0] })
        assertEquals(withoutKits.columns[0], withKits.columns[0])
        assertEquals(Recorded.Known(false), design.extras.powerSwitch)
    }

    /** "Brass standoffs and screws": the vendor gives no count, so none is made up. */
    @Test
    fun `a kit line the vendor gives no count for is not recorded, never one`() {
        assertEquals(Recorded.Unknown, KitLine(Recorded.Unknown, "Brass standoffs and screws", null).quantity)
        kotlin.test.assertFailsWith<IllegalArgumentException> { KitLine(Recorded.Known(0), "SMD diodes", NeedKind.Diodes) }
    }

    @Test
    fun `the design's licence is carried for the kits`() {
        assertEquals(PartsFixtures.mit, grid().licence)
    }
}
