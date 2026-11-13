package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.alpsSwitch
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.cell
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.chocPink
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.gateronCj
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.grid
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.kit
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.kitInStock
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.lily58
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.listing
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.offer
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.row
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Switches, the first taste row (#115): no default pick, so the totals either leave the
 * row out of every column, saying so, or name it as a gap where some route covers it.
 */
class SwitchesRowTest {

    private val withKit = PartsFixtures.today + kitInStock
    private fun pick(raw: String) = mapOf(NeedKind.Switches to Pick.Product(raw))

    // -- the need --------------------------------------------------------------------

    @Test
    fun `a design needs one switch per key, in any of its footprints`() {
        val need = lily58().needs(Recorded.Unknown).single { it.kind == NeedKind.Switches }
        assertEquals(
            Need.Buy(Requirement(Fitting.Switch(setOf(SwitchFootprint.Mx, SwitchFootprint.ChocV1)), Recorded.Known(58)),
                     setOf(Basis.OnePerKey)),
            need,
        )
    }

    // -- no default ------------------------------------------------------------------

    @Test
    fun `an unpicked switches cell is choose, in every column that buys them`() {
        val grid = grid(offers = withKit)
        assertEquals(Cell.Choose, grid.cell(NeedKind.Switches, 0))
        assertEquals(Cell.Choose, grid.cell(NeedKind.Switches, 1))
    }

    @Test
    fun `the row lists every switch, and one that does not fit is listed but never counted`() {
        val offers = grid().row(NeedKind.Switches).offers
        assertEquals(setOf(gateronCj, chocPink, alpsSwitch), offers.map { it.product }.toSet())
        val alps = offers.single { it.product == alpsSwitch }
        assertEquals(OfferFit.DoesNotFit, alps.fit)
        assertEquals(false, alps.countable)
    }

    // -- totals ----------------------------------------------------------------------

    /** The owner's rule, 2026-10-05: "€95.50 before shipping, without switches". */
    @Test
    fun `when no route includes switches, every column leaves them out and says so`() {
        val grid = grid(offers = withKit)
        assertEquals(Total.Complete(4375L + 5175L, without = listOf(NeedKind.Switches)), grid.columns[1].total)
        val self = assertIs<Total.Incomplete>(grid.columns[0].total)
        assertEquals(listOf(NeedKind.Diodes, NeedKind.Pcb), self.gaps.map { (it as LeftOut.Unpriced).kind })
    }

    @Test
    fun `when a kit includes switches, a column without them is incomplete until the reader picks`() {
        val boxed = kit.copy(
            contents = kit.contents + KitLine(Recorded.Known(58), "Gateron Yellow switches", NeedKind.Switches),
            needs = kit.needs.filter { it.fitting.kind != NeedKind.Switches },
        )
        val products = PartsFixtures.products - kit + boxed
        val unpicked = grid(products = products, offers = withKit)
        assertIs<Cell.InKit>(unpicked.cell(NeedKind.Switches, 1))
        assertEquals(Total.Complete(4375L + 5175L), unpicked.columns[1].total)
        val gaps = assertIs<Total.Incomplete>(unpicked.columns[0].total).gaps
        assertEquals(LeftOut.Unpriced(NeedKind.Switches, UnpricedReason.NotChosen), gaps.last())

        val picked = grid(products = products, offers = withKit, picks = pick("gateron-cj"))
        val self = assertIs<Total.Incomplete>(picked.columns[0].total).gaps
        assertEquals(listOf(NeedKind.Diodes, NeedKind.Pcb), self.map { (it as LeftOut.Unpriced).kind })
    }

    // -- a pick ----------------------------------------------------------------------

    /** 58 Choc Pinks in tens: six packs at 4.90 net, 25% VAT, two spare. */
    @Test
    fun `a picked switch is priced in packs, rounded up, in every column that buys them`() {
        val grid = grid(offers = withKit, picks = pick("kailh-choc-pink"))
        for (c in 0..1) {
            val cell = assertIs<Cell.Picked>(grid.cell(NeedKind.Switches, c))
            assertEquals(chocPink, cell.offer.product)
            assertEquals(Recorded.Known(LineCost(6, 2, 3675L)), cell.offer.line)
        }
        assertEquals(Total.Complete(4375L + 5175L + 3675L), grid.columns[1].total)
    }

    @Test
    fun `a switch that does not fit the design is never counted, and the row stays choose`() {
        val grid = grid(offers = withKit, picks = pick("alps-white"))
        assertEquals(Cell.Choose, grid.cell(NeedKind.Switches, 0))
        assertEquals(listOf<PickNote>(PickNote.NotCounted(NeedKind.Switches, alpsSwitch, listOf(null, kit))), grid.notes)
        assertNull(grid.row(NeedKind.Switches).pick)
        assertEquals(Total.Complete(4375L + 5175L, without = listOf(NeedKind.Switches)), grid.columns[1].total)
    }

    @Test
    fun `an out-of-stock switch is listed but not counted`() {
        val soldOut = PartsFixtures.today.filter { it.listing.product != gateronCj.slug } +
            offer(listing("26354", gateronCj), 50, Stock.OutOfStock) + kitInStock
        val grid = grid(offers = soldOut, picks = pick("gateron-cj"))
        assertEquals(Cell.Choose, grid.cell(NeedKind.Switches, 0))
    }

    @Test
    fun `having the switches already drops the row from every column`() {
        val grid = grid(offers = withKit, picks = mapOf(NeedKind.Switches to Pick.Own))
        assertEquals(Cell.Owned, grid.cell(NeedKind.Switches, 0))
        assertEquals(Total.Complete(4375L + 5175L), grid.columns[1].total)
    }
}
