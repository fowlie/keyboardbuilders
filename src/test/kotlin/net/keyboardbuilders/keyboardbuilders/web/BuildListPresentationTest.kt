package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.BoardDrawn
import net.keyboardbuilders.keyboardbuilders.domain.BoardOutline
import net.keyboardbuilders.keyboardbuilders.domain.BoardSize
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.DiodeDirection
import net.keyboardbuilders.keyboardbuilders.domain.KeyWiring
import net.keyboardbuilders.keyboardbuilders.domain.Mcu
import net.keyboardbuilders.keyboardbuilders.domain.PinCount
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.SplitHalves
import net.keyboardbuilders.keyboardbuilders.parts.BuildList
import net.keyboardbuilders.keyboardbuilders.parts.Listing
import net.keyboardbuilders.keyboardbuilders.parts.NeedKind
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.grid
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.kit
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.kitInStock
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.lily58
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.listing
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.mxSocket
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.offer
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.sod123f
import net.keyboardbuilders.keyboardbuilders.parts.Pick
import net.keyboardbuilders.keyboardbuilders.parts.Stock
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The build grid's sentences (#113), on fixtures: every kind of cell, the totals and the
 * notes, plus the cases the real seed does not reach today. `BuildListPageTest` renders
 * the seed's own.
 */
class BuildListPresentationTest {

    private val withKit = PartsFixtures.today + kitInStock

    private fun page(grid: BuildList, design: Design = lily58()) =
        grid.toPage(design, listOf(PartsFixtures.shop), PartsFixtures.norway)

    private fun BuildListPage.row(label: String) = grid.rows.single { it.label == label }

    // -- cells -----------------------------------------------------------------------

    /** #115: a taste row has no default, and is chosen on a page of its own. */
    @Test
    fun `an unpicked switches cell says choose, and links to the switches page`() {
        val row = page(grid(offers = withKit)).row("Switches")
        assertEquals(listOf(CellView("Choose switches →", null, "", href = "/designs/lily58-pro/build/switches")),
            row.cells.distinct())
        val cj = row.offers.single { it.name == "Gateron CJ" }
        assertEquals("/designs/lily58-pro/build?switches=gateron-cj#need-switches", cj.pick)
        assertEquals("", cj.mark)
        val alps = row.offers.single { it.name == "Alps White" }
        assertNull(alps.pick)
        assertTrue(alps.detail.endsWith("— does not fit: Alps, the board takes MX or Choc v1"), alps.detail)
    }

    @Test
    fun `a column left without switches says so, and a gap names them as not chosen`() {
        assertEquals("€95.50 before shipping, without switches", page(grid(offers = withKit)).grid.foots[1].total)
        val boxed = kit.copy(
            contents = kit.contents + net.keyboardbuilders.keyboardbuilders.parts.KitLine(
                Recorded.Known(58), "switches", NeedKind.Switches),
            needs = kit.needs.filter { it.fitting !is net.keyboardbuilders.keyboardbuilders.parts.Fitting.Switch },
        )
        val view = page(grid(products = PartsFixtures.products - kit + boxed, offers = withKit))
        assertEquals("Not priced: diodes, PCB, switches (not chosen yet)", view.grid.foots[0].gaps)
    }

    @Test
    fun `a priced cell names the part and what the line costs, and is the row's picker when there is a choice`() {
        val view = page(grid(offers = withKit))
        assertEquals(CellView("nice!nano v2, wireless", "2 for €51.75, on backorder", "", picker = true),
            view.row("Controller").cells[0])
        val one = page(grid(offers = listOf(offer(listing("855", mxSocket, pack = 10), 150, Stock.InStock))))
        assertEquals(CellView("Kailh MX hotswap socket", "6 packs of 10 for €11.25", "", picker = false),
            one.row("Switch sockets").cells[0], "one offer is nothing to choose between")
    }

    @Test
    fun `a kit cell says what is in the box, in the vendor's count`() {
        val view = page(grid(offers = withKit))
        assertEquals(CellView("In the kit", "60 × SMD diodes, package not stated", ""), view.row("Diodes").cells[1])
        assertEquals(CellView("In the kit", "2 × main PCBs", ""), view.row("PCB").cells[1])
    }

    @Test
    fun `a gap says why, greyed, and no offer counted opens on the offers that did not`() {
        val view = page(grid(offers = withKit))
        assertEquals(CellView("Not priced: no offer we can count", null, "unrecorded", picker = true), view.row("Diodes").cells[0])
        assertEquals(CellView("Not priced: fab prices not recorded yet", null, "unrecorded"), view.row("PCB").cells[0])
    }

    @Test
    fun `a kit that says nothing about a need says the vendor does not say`() {
        val silent = kit.copy(contents = kit.contents.filter { it.kind != NeedKind.Diodes })
        val view = page(grid(products = PartsFixtures.products - kit + silent, offers = withKit))
        assertEquals("Not priced: the vendor does not say", view.row("Diodes").cells[1].text)
    }

    @Test
    fun `I have this, and the reader's own pick, say so`() {
        val view = page(grid(offers = withKit, picks = mapOf(NeedKind.Diodes to Pick.Own,
            NeedKind.SwitchSockets to Pick.Product("kailh-mx-hotswap-socket"))))
        assertEquals(CellView("You have this", null, ""), view.row("Diodes").cells[0])
        assertEquals("6 packs of 10 for €11.25, your pick", view.row("Switch sockets").cells[0].sub)
    }

    @Test
    fun `nothing to buy is a fact, and the row says why`() {
        val corneShaped = lily58(controller = Controller.Soldered(Recorded.Known(Mcu.Rp2040)))
        val row = page(grid(design = corneShaped, products = PartsFixtures.products - kit), corneShaped).row("Controller")
        assertEquals(CellView("Nothing to buy", null, ""), row.cells.single())
        assertEquals("Nothing to buy: the RP2040 is on the board.", row.what)
        assertNull(row.own, "nothing to own on a row with nothing to buy")
        assertTrue(row.offers.isEmpty())
    }

    // -- heads and totals ------------------------------------------------------------

    @Test
    fun `the columns are buy it yourself, then each kit with its own price`() {
        val heads = page(grid(offers = withKit)).grid.heads
        assertEquals(ColumnHead("Buy it yourself", null, null), heads[0])
        assertEquals(ColumnHead(kit.name, kit.url, "€43.75, MX, black, in stock"), heads[1])
        assertEquals("No price seen yet", page(grid()).grid.heads[1].price)
    }

    @Test
    fun `a complete column is a price before shipping, with a shipping line per shop`() {
        val foot = page(grid(offers = withKit)).grid.foots[1]
        assertEquals(ColumnFoot("€95.50 before shipping, without switches", null, listOf("Shipping from 42. Keebs: not priced yet")), foot)
    }

    @Test
    fun `an incomplete column counts and names its gaps, with no price anywhere in it`() {
        val foot = page(grid(offers = withKit)).grid.foots[0]
        assertEquals("Incomplete (2 lines)", foot.total)
        assertEquals("Not priced: diodes, PCB", foot.gaps)
        assertFalse("€" in foot.total + foot.gaps + foot.shipping.joinToString())
        assertEquals("Incomplete (1 line)", page(grid(offers = withKit, picks = mapOf(NeedKind.Diodes to Pick.Own))).grid.foots[0].total)
        assertEquals("Not priced: the kit itself", page(grid()).grid.foots[1].gaps)
    }

    @Test
    fun `owning the diodes and the PCB gives the self-sourced column its total`() {
        val foot = page(grid(offers = withKit, picks = mapOf(NeedKind.Diodes to Pick.Own, NeedKind.Pcb to Pick.Own))).grid.foots[0]
        assertEquals("€63.00 before shipping, without switches", foot.total)
        assertNull(foot.gaps)
    }

    // -- notes -----------------------------------------------------------------------

    @Test
    fun `a pick that is not listed says so, and never repeats what was typed`() {
        val view = page(grid(picks = mapOf(NeedKind.Controller to Pick.Product("<script>alert(1)</script>"))))
        assertEquals(listOf("We could not find your pick for the controller, so the default is shown."), view.notes)
    }

    @Test
    fun `a pick no column can count is named, with the columns`() {
        val view = page(grid(offers = withKit, picks = mapOf(NeedKind.Controller to Pick.Product("pro-micro"))))
        assertEquals(listOf("Your pick for the controller, Pro Micro, can't be counted in either column, so the default is shown."),
            view.notes)
    }

    @Test
    fun `a pick one column can count names only the one that fell back`() {
        val view = page(grid(offers = withKit, picks = mapOf(NeedKind.SwitchSockets to Pick.Product("kailh-choc-hotswap-socket"))))
        assertEquals(listOf("Your pick for the switch sockets, Kailh Choc V1 hotswap socket, can't be counted here, so the default is shown."),
            view.notes)

        val offers = withKit.filter { it.listing.ref != "382" } + offer(listing("382", PartsFixtures.proMicro), 580, Stock.InStock)
        val partly = page(grid(offers = offers, picks = mapOf(NeedKind.Controller to Pick.Product("pro-micro"))))
        assertEquals(listOf("Your pick for the controller, Pro Micro, can't be counted under Buy it yourself, so the default is shown there."),
            partly.notes)
    }

    /** A taste row has no default to fall back on (#115). */
    @Test
    fun `a switch pick that cannot be used leaves the row to choose, and says so`() {
        val unfit = page(grid(offers = withKit, picks = mapOf(NeedKind.Switches to Pick.Product("alps-white"))))
        assertEquals(listOf("Your pick for the switches, Alps White, can't be counted in either column, so none is picked."),
            unfit.notes)
        val typo = page(grid(offers = withKit, picks = mapOf(NeedKind.Switches to Pick.Product("gateron"))))
        assertEquals(listOf("We could not find your pick for the switches, so none is picked."), typo.notes)
    }

    // -- the row's picker and links -------------------------------------------------

    /** #115: the picked switch's name goes back to the page it was chosen on, picks kept. */
    @Test
    fun `a picked switch links to the switches page`() {
        val view = page(grid(offers = withKit, picks = mapOf(NeedKind.Switches to Pick.Product("gateron-cj"))))
        val cell = view.row("Switches").cells[0]
        assertEquals("Gateron CJ", cell.text)
        assertEquals("/designs/lily58-pro/build/switches?switches=gateron-cj", cell.href)
        assertFalse(cell.picker)
    }

    /** NN/g's ~15: past it a commodity row would leave the cell too; at 15 it stays in it. */
    @Test
    fun `a row picks on its own page when it is a taste row or has more than 15 options`() {
        fun sockets(n: Int) = (1..n).map { offer(listing("s$it", mxSocket, pack = 10), 150L + it, Stock.InStock) }
        assertFalse(grid(offers = sockets(15)).rows.single { it.kind == NeedKind.SwitchSockets }.picksOnItsOwnPage)
        assertTrue(grid(offers = sockets(16)).rows.single { it.kind == NeedKind.SwitchSockets }.picksOnItsOwnPage)
        assertTrue(grid(offers = emptyList()).rows.single { it.kind == NeedKind.Switches }.picksOnItsOwnPage)
    }

    @Test
    fun `the offer in effect carries the tick, a countable other one a pick link, and one that cannot count neither`() {
        val row = page(grid(offers = withKit)).row("Controller")
        assertEquals(2, row.offers.size)
        assertEquals(listOf("✓ ", ""), row.offers.map { it.mark })
        assertEquals(listOf("fits", "excluded"), row.offers.map { it.css })
        assertEquals(listOf(null, null), row.offers.map { it.pick })
        assertTrue("8-bit AVR chips cannot run ZMK" in row.offers[1].detail, row.offers[1].detail)

        val two = listOf(offer(listing("855", mxSocket, pack = 10), 150, Stock.InStock),
                         offer(listing("6198", PartsFixtures.chocSocket, pack = 10), 200, Stock.InStock))
        val sockets = page(grid(offers = two)).row("Switch sockets")
        assertEquals(listOf(null, "/designs/lily58-pro/build?sockets=kailh-choc-hotswap-socket#need-sockets"),
            sockets.offers.map { it.pick })
        val picked = page(grid(offers = two, picks = mapOf(NeedKind.SwitchSockets to Pick.Product("kailh-choc-hotswap-socket"))))
            .row("Switch sockets")
        assertEquals(listOf("", "✓ "), picked.offers.map { it.mark })
        assertEquals(listOf("/designs/lily58-pro/build#need-sockets", null), picked.offers.map { it.pick })
    }

    @Test
    fun `the own link toggles`() {
        assertEquals(OwnLink("I have this", "/designs/lily58-pro/build?diodes=own#need-diodes"), page(grid()).row("Diodes").own)
        assertEquals(OwnLink("You have this · undo", "/designs/lily58-pro/build#need-diodes"),
            page(grid(picks = mapOf(NeedKind.Diodes to Pick.Own))).row("Diodes").own)
    }

    @Test
    fun `each row has an id the links land on`() {
        assertEquals(listOf("need-controller", "need-sockets", "need-diodes", "need-pcb", "need-switches"), page(grid()).grid.rows.map { it.id })
    }

    // -- carried over from #112 ------------------------------------------------------

    @Test
    fun `a stale price shows its date and says it is not counted`() {
        val old = offer(listing("855", mxSocket, pack = 10), 150, Stock.InStock, age = Duration.ofDays(8))
        val row = page(grid(offers = listOf(old))).row("Switch sockets").offers.single()
        assertEquals("excluded", row.css)
        assertTrue("seen 26 Sep 2026" in row.detail, row.detail)
        assertTrue("price more than 7 days old, not counted" in row.detail, row.detail)
    }

    @Test
    fun `unrecorded wiring says so, never nothing to buy`() {
        val view = page(grid(design = lily58(wiring = Recorded.Unknown), offers = emptyList()))
        val diodes = view.row("Diodes")
        assertEquals("unrecorded", diodes.whatCss)
        assertTrue("not recorded" in diodes.what, diodes.what)
        assertTrue("Nothing to buy" !in diodes.what)
        assertEquals("Not priced: not recorded", diodes.cells[0].text)
    }

    @Test
    fun `a split whose wiring does not prove one chip per half gives no count`() {
        val wholeBoardScan = KeyWiring.Matrix(PinCount(8), PinCount(12), DiodeDirection.ColToRow)
        val row = page(grid(design = lily58(wiring = Recorded.Known(wholeBoardScan)))).row("Controller")
        assertEquals("dev board for a Pro Micro socket, how many not recorded, from the design's record.", row.what)
        assertEquals("Not priced: how many is not recorded", row.cells[0].text)
    }

    /** The design page says "23 direct pins per half"; the build list must not say "in all". */
    @Test
    fun `a split's direct pins are counted per half, in the design page's words`() {
        val corneShaped = lily58(
            wiring = Recorded.Known(KeyWiring.DirectPin(PinCount(23))),
            controller = Controller.Soldered(Recorded.Known(Mcu.Rp2040)),
        )
        assertEquals("Nothing to buy: each key has its own pin (23 direct pins per half), so there are no diodes.",
            page(grid(design = corneShaped), corneShaped).row("Diodes").what)
    }

    @Test
    fun `a listing with no look yet is shown as such`() {
        val mx855 = listing("855", mxSocket, pack = 10)
        val row = page(grid(offers = emptyList(), listings = listOf(mx855))).row("Switch sockets")
        assertEquals(1, row.offers.size)
        assertEquals("listed at 42. Keebs, no price seen yet", row.offers.single().detail)
        assertNull(row.offers.single().pick)
    }

    @Test
    fun `the page says it prices only the slice's kinds`() {
        assertEquals(
            "Only the controller, switch sockets, diodes and switches are priced here. " +
                "The keycaps and everything else the board needs are not.",
            page(grid()).notPriced,
        )
    }

    /** 42keebs labels its diode variations "SMD" and "Through-hole", which the part's name already says. */
    @Test
    fun `a listing's label is added to the part's name only where it says something new`() {
        val smd = Listing("42keebs", "7336", sod123f.slug, "SMD", 10, "https://42keebs.eu/7336")
        val black = Listing("42keebs", "855", mxSocket.slug, "black", 10, "https://42keebs.eu/855")
        val view = page(grid(offers = listOf(offer(smd, 25, Stock.InStock), offer(black, 150, Stock.InStock))))
        assertEquals(listOf("1N4148W diode, SMD"), view.row("Diodes").offers.map { it.name })
        assertEquals("Kailh MX hotswap socket (black)", view.row("Switch sockets").cells[0].text)
    }

    @Test
    fun `the kit's facts are the vendor's, dated, with the design's licence`() {
        val kitView = page(grid(offers = withKit)).kits.single()
        assertEquals("In the box, per 42. Keebs, read 4 Oct 2026:", kitView.contentsIntro)
        assertEquals("hardware licence MIT", kitView.licence)
        assertEquals("1 variation", kitView.offersSummary)
        assertEquals("Also needed, not priced here: TRRS cable (for a wired build).", kitView.notPriced)
        assertNull(page(grid()).kits.single().offersSummary, "the fixture has no kit offer")
    }

    // -- the PCB: measured, not priced (owner, 2026-10-04) ---------------------------

    private fun pcb(design: Design) = page(grid(design = design), design).row("PCB").what

    @Test
    fun `the PCB row gives each board's size and how many`() {
        assertEquals("About 142.5 × 106.3 mm, 2 boards (one per half).",
            pcb(lily58(boardSize = Recorded.Known(BoardSize.Each(BoardOutline(142.52, 106.33))), drawn = Recorded.Known(BoardDrawn.Half))))
    }

    @Test
    fun `an unread size says not recorded, and both halves in one file say so`() {
        val corne = lily58(drawn = Recorded.Known(BoardDrawn.BothHalves))
        assertEquals("Size not recorded, 2 boards (both halves in one file).", pcb(corne))
        assertEquals("unrecorded", page(grid(design = corne), corne).row("PCB").whatCss)
    }

    @Test
    fun `halves of different sizes are each given, left first`() {
        val fourier = lily58(drawn = Recorded.Known(BoardDrawn.Half), boardSize = Recorded.Known(
            BoardSize.Halves(BoardOutline(124.42, 80.37), BoardOutline(129.18, 80.37))))
        assertEquals("About 124.4 × 80.4 mm (left) and 129.2 × 80.4 mm (right), 2 boards (one per half).", pcb(fourier))
    }

    @Test
    fun `a unibody is one board, and a reversible split says reversible`() {
        val size = Recorded.Known(BoardSize.Each(BoardOutline(285.0, 94.6)))
        assertEquals("About 285.0 × 94.6 mm, 1 board.",
            pcb(lily58(body = Body.Unibody, boardSize = size, drawn = Recorded.Known(BoardDrawn.Board))))
        assertEquals("About 285.0 × 94.6 mm, 2 boards (reversible).",
            pcb(lily58(body = Body.Split(SplitHalves(2), Recorded.Known(true), Recorded.Unknown), boardSize = size)))
    }
}
