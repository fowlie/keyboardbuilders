package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.cell
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.chocSocket
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.grid
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.kit
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.kitInStock
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.lily58
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.listing
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.mxSocket
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.niceNano
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.offer
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.proMicro
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.row
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.sod123
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A reader's picks (#113): a product or "I have this", per row, from the URL. */
class BuildGridPickTest {

    private val withKit = PartsFixtures.today + kitInStock
    private val mx855 = listing("855", mxSocket, pack = 10)
    private val choc6198 = listing("6198", chocSocket, pack = 10)

    private fun product(raw: String) = Pick.Product(raw)

    // -- a shop for the product (#115) -----------------------------------------------

    private val nanoHere = offer(listing("4808", niceNano), 2070, Stock.InStock)
    private val nanoThere = offer(listing("n2", niceNano, shop = "splitkb"), 2500, Stock.InStock)

    @Test
    fun `a shop chosen for the product is used, though dearer`() {
        val grid = grid(offers = listOf(nanoHere, nanoThere),
            picks = mapOf(NeedKind.Controller to Pick.Product("nice-nano-v2", shop = "splitkb")))
        val cell = assertIs<Cell.Picked>(grid.cell(NeedKind.Controller))
        assertEquals("splitkb", cell.offer.offer.listing.shop)
        assertEquals(Pick.Product("nice-nano-v2", shop = "splitkb"), grid.row(NeedKind.Controller).pick)
        assertEquals(emptyList(), grid.notes)
    }

    @Test
    fun `a shop whose offer cannot be counted falls back to the cheapest, and says so`() {
        val outThere = offer(listing("n2", niceNano, shop = "splitkb"), 2500, Stock.OutOfStock)
        for (shop in listOf("splitkb", "<script>")) {
            val grid = grid(offers = listOf(nanoHere, outThere),
                picks = mapOf(NeedKind.Controller to Pick.Product("nice-nano-v2", shop = shop)))
            val cell = assertIs<Cell.Picked>(grid.cell(NeedKind.Controller))
            assertEquals("42keebs", cell.offer.offer.listing.shop, shop)
            assertEquals(Pick.Product("nice-nano-v2"), grid.row(NeedKind.Controller).pick, "the shop is dropped from the links")
            assertEquals(listOf<PickNote>(PickNote.ShopNotCounted(NeedKind.Controller, niceNano)), grid.notes, shop)
        }
    }

    // -- a product -------------------------------------------------------------------

    @Test
    fun `a dearer countable pick replaces the default, and is marked as the reader's`() {
        val offers = listOf(offer(mx855, 150, Stock.InStock), offer(choc6198, 200, Stock.InStock))
        val grid = grid(offers = offers, picks = mapOf(NeedKind.SwitchSockets to product("kailh-choc-hotswap-socket")))

        val cell = assertIs<Cell.Picked>(grid.cell(NeedKind.SwitchSockets))
        assertEquals(chocSocket, cell.offer.product)
        assertEquals(true, cell.byReader)
        assertEquals(product("kailh-choc-hotswap-socket"), grid.row(NeedKind.SwitchSockets).pick)
        assertEquals(emptyList(), grid.notes)
    }

    /** Pro Micro in stock: the kit counts it on footprint; the design's ZMK still rules it out. */
    @Test
    fun `a pick applies in every column that buys the row`() {
        val niceNanoElsewhere = offer(listing("4808b", niceNano), 1900, Stock.InStock)
        val proMicroInStock = offer(listing("382", proMicro), 580, Stock.InStock)
        val offers = withKit.filter { it.listing.ref != "382" } + proMicroInStock + niceNanoElsewhere
        assertEquals(proMicro, (grid(offers = offers).cell(NeedKind.Controller, 1) as Cell.Picked).offer.product,
            "the kit's default is the cheap Pro Micro")

        val grid = grid(offers = offers, picks = mapOf(NeedKind.Controller to product("nice-nano-v2")))
        for (c in 0..1) {
            val cell = assertIs<Cell.Picked>(grid.cell(NeedKind.Controller, c))
            assertEquals(niceNano, cell.offer.product, "column $c")
            assertEquals(true, cell.byReader, "column $c")
            // the cheaper of the product's two listings, as the default would take
            assertEquals("4808b", cell.offer.offer.listing.ref, "column $c")
        }
        // 2 x 19.00 net at 25%, plus the kit's 43.75
        assertEquals(Total.Complete(4375L + 4750L, without = listOf(NeedKind.Switches)), grid.columns[1].total)
    }

    @Test
    fun `an unknown slug is not listed, and the row stays on its default`() {
        val grid = grid(picks = mapOf(NeedKind.Controller to product("Nice Nano")))
        assertEquals(listOf<PickNote>(PickNote.NotListed(NeedKind.Controller)), grid.notes)
        assertNull(grid.row(NeedKind.Controller).pick)
        assertEquals(false, assertIs<Cell.Picked>(grid.cell(NeedKind.Controller)).byReader)
    }

    @Test
    fun `a part for another row is not listed on this one`() {
        val grid = grid(picks = mapOf(NeedKind.Diodes to product("nice-nano-v2")))
        assertEquals(listOf<PickNote>(PickNote.NotListed(NeedKind.Diodes)), grid.notes)
    }

    @Test
    fun `typed text that is no slug never throws`() {
        for (raw in listOf("<script>", "", " ", "Ä", "../../etc", "a".repeat(5000))) {
            val grid = grid(picks = NeedKind.entries.associateWith { product(raw) })
            assertEquals(NeedKind.entries, grid.notes.map { it.kind }, raw)
            assertEquals(true, grid.notes.all { it is PickNote.NotListed })
        }
    }

    /** ZMK cannot run on the Pro Micro, and 42keebs has none in stock: no column can count it. */
    @Test
    fun `a listed product no column can count is noted with every column that fell back`() {
        val grid = grid(offers = withKit, picks = mapOf(NeedKind.Controller to product("pro-micro")))
        assertEquals(listOf<PickNote>(PickNote.NotCounted(NeedKind.Controller, proMicro, listOf(null, kit))), grid.notes)
        assertNull(grid.row(NeedKind.Controller).pick, "a pick used nowhere is not kept in the links")
        assertEquals(niceNano, assertIs<Cell.Picked>(grid.cell(NeedKind.Controller, 1)).offer.product)
    }

    @Test
    fun `a pick that counts in one column and not another names only the one that fell back`() {
        // In stock, so the kit (footprint only) counts it; ZMK still rules it out of the design's own line.
        val offers = withKit.filter { it.listing.ref != "382" } + offer(listing("382", proMicro), 580, Stock.InStock)
        val grid = grid(offers = offers, picks = mapOf(NeedKind.Controller to product("pro-micro")))
        assertEquals(listOf<PickNote>(PickNote.NotCounted(NeedKind.Controller, proMicro, listOf(null))), grid.notes)
        assertEquals(product("pro-micro"), grid.row(NeedKind.Controller).pick)
        assertEquals(proMicro, assertIs<Cell.Picked>(grid.cell(NeedKind.Controller, 1)).offer.product)
        assertEquals(niceNano, assertIs<Cell.Picked>(grid.cell(NeedKind.Controller, 0)).offer.product)
    }

    /** The kit holds its sockets, so only "Buy it yourself" can fall back. */
    @Test
    fun `an out-of-stock socket is noted for the self-sourced column only`() {
        val grid = grid(offers = withKit, picks = mapOf(NeedKind.SwitchSockets to product("kailh-choc-hotswap-socket")))
        assertEquals(listOf<PickNote>(PickNote.NotCounted(NeedKind.SwitchSockets, chocSocket, listOf(null))), grid.notes)
        assertIs<Cell.InKit>(grid.cell(NeedKind.SwitchSockets, 1))
    }

    @Test
    fun `a stale offer of the picked product is not counted`() {
        val old = offer(choc6198, 100, Stock.InStock, Duration.ofDays(8))
        val grid = grid(offers = listOf(offer(mx855, 150, Stock.InStock), old),
                        picks = mapOf(NeedKind.SwitchSockets to product("kailh-choc-hotswap-socket")))
        assertEquals(mxSocket, assertIs<Cell.Picked>(grid.cell(NeedKind.SwitchSockets)).offer.product)
        assertIs<PickNote.NotCounted>(grid.notes.single())
    }

    @Test
    fun `a listing nobody has looked at yet is listed, not counted`() {
        val grid = grid(offers = listOf(offer(mx855, 150, Stock.InStock)), listings = listOf(mx855, choc6198),
                        picks = mapOf(NeedKind.SwitchSockets to product("kailh-choc-hotswap-socket")))
        assertEquals(PickNote.NotCounted(NeedKind.SwitchSockets, chocSocket, listOf(null)), grid.notes.single())
    }

    // -- I have this -----------------------------------------------------------------

    @Test
    fun `I have this drops a row's priced and unpriced cells, and the kit keeps what is in the box`() {
        val grid = grid(offers = withKit, picks = mapOf(NeedKind.Diodes to Pick.Own, NeedKind.Controller to Pick.Own))
        assertEquals(Cell.Owned, grid.cell(NeedKind.Diodes, 0))
        assertIs<Cell.InKit>(grid.cell(NeedKind.Diodes, 1))
        assertEquals(Cell.Owned, grid.cell(NeedKind.Controller, 0))
        assertEquals(Cell.Owned, grid.cell(NeedKind.Controller, 1))
        assertEquals(Pick.Own, grid.row(NeedKind.Diodes).pick)
    }

    /** 2 x 20.70 and 6 x 1.50 net, at 25%: 51.75 + 11.25. */
    @Test
    fun `owning the diodes and the PCB makes the self-sourced column complete`() {
        val grid = grid(offers = withKit, picks = mapOf(NeedKind.Diodes to Pick.Own, NeedKind.Pcb to Pick.Own))
        val without = listOf(NeedKind.Switches)
        assertEquals(Total.Complete(5175L + 1125L, without), grid.columns[0].total)
        assertEquals(Total.Complete(4375L + 5175L, without), grid.columns[1].total, "the kit is paid for either way")
    }

    @Test
    fun `owning the diodes alone leaves the PCB as the one gap`() {
        val grid = grid(picks = mapOf(NeedKind.Diodes to Pick.Own))
        assertEquals(Total.Incomplete(listOf(LeftOut.Unpriced(NeedKind.Pcb, UnpricedReason.FabNotPriced))), grid.columns[0].total)
    }

    @Test
    fun `I have this on a row with nothing to buy changes nothing and is not kept`() {
        val soldered = lily58(controller = Controller.Soldered(Recorded.Unknown))
        val grid = grid(design = soldered, products = PartsFixtures.products - kit, picks = mapOf(NeedKind.Controller to Pick.Own))
        assertIs<Cell.NothingToBuy>(grid.cell(NeedKind.Controller))
        assertNull(grid.row(NeedKind.Controller).pick)
    }

    // -- shops -----------------------------------------------------------------------

    @Test
    fun `a column's shops are the kit's and each priced cell's, once each`() {
        val other = Shop("other", "Other", "EUR", pricesIncludeVat = true, vat = emptyMap())
        val elsewhere = Listing("other", "1", mxSocket.slug, null, 10, "https://other.example/1")
        val offers = listOf(offer(listing("4808", niceNano), 2070, Stock.InStock),
            Offer(elsewhere, Observation("other", "1", PartsFixtures.now, 150, "EUR", Stock.InStock)), kitInStock)
        val grid = buildList(lily58(), Recorded.Known(DiodePackage.Sod123), PartsFixtures.products,
            listOf(PartsFixtures.shop, other), offers.map { it.listing }, offers, PartsFixtures.norway,
            PartsFixtures.now, PartsFixtures.rules)
        assertEquals(listOf("42keebs", "other"), grid.columns[0].shops)
        assertEquals(listOf("42keebs"), grid.columns[1].shops)
        assertEquals(emptyList(), grid(products = PartsFixtures.products - kit, offers = emptyList()).columns[0].shops)
    }
}
