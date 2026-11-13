package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.parts.Fitting
import net.keyboardbuilders.keyboardbuilders.parts.NeedKind
import net.keyboardbuilders.keyboardbuilders.parts.Part
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.alpsSwitch
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.grid
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.lily58
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.listing
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.offer
import net.keyboardbuilders.keyboardbuilders.parts.Pick
import net.keyboardbuilders.keyboardbuilders.parts.Stock
import net.keyboardbuilders.keyboardbuilders.parts.SwitchKind
import net.keyboardbuilders.keyboardbuilders.parts.SwitchSpec
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The switches picker page (#115) on fixtures: a Gateron CJ sold singly (58 for €36.25),
 * a Kailh Choc Pink in tens (6 packs for €36.75), and an Alps switch that does not fit.
 */
class SwitchPickerTest {

    private val brown = Part(Slug("gateron-brown"), "Gateron Brown", Fitting.Switch(setOf(SwitchFootprint.Mx)),
        switch = SwitchSpec(Recorded.Known(SwitchKind.Tactile)))
    private val brownOut = offer(listing("7001", brown), 30, Stock.OutOfStock)

    private fun picker(
        params: Map<String, String> = emptyMap(),
        picks: Map<NeedKind, Pick> = emptyMap(),
        offers: List<net.keyboardbuilders.keyboardbuilders.parts.Offer> = PartsFixtures.today,
    ): SwitchPickerPage {
        val products = PartsFixtures.products + brown
        return grid(products = products, offers = offers, picks = picks)
            .toSwitchPicker(lily58(), listOf(PartsFixtures.shop), PartsFixtures.norway, readPickerFilters(params))
    }

    // -- filters from the URL --------------------------------------------------------

    @Test
    fun `filters are read in the order given, prefixed so they never meet a pick`() {
        assertEquals(
            listOf(PickerFilter.Kind(KindFilter.Tactile), PickerFilter.Kind(KindFilter.Unknown), PickerFilter.InStock, PickerFilter.AnyFit),
            readPickerFilters(linkedMapOf("f-kind" to "tactile,unknown", "switches" to "gateron-cj", "f-stock" to "in", "f-fit" to "any")),
        )
    }

    @Test
    fun `a filter value we do not know is dropped, never echoed`() {
        assertEquals(listOf(PickerFilter.Kind(KindFilter.Linear)),
            readPickerFilters(mapOf("f-kind" to "<script>,linear,linear", "f-stock" to "maybe", "f-fit" to "")))
    }

    // -- the default view ------------------------------------------------------------

    @Test
    fun `fitting switches are products, cheapest for the whole board first, out of stock last`() {
        val page = picker(offers = PartsFixtures.today + brownOut)
        assertEquals(listOf("Gateron CJ", "Kailh Choc Pink", "Gateron Brown"), page.products.map { it.name })
        val cj = page.products[0]
        assertEquals("Linear", cj.kind)
        assertEquals("58 for €36.25 at 42. Keebs, in stock, seen 4 Oct 2026", cj.summary)
        assertEquals("/designs/lily58-pro/build?switches=gateron-cj#need-switches", cj.choose)
        assertEquals("6 packs of 10 (2 spare) for €36.75 at 42. Keebs, in stock, seen 4 Oct 2026", page.products[1].summary)
        val out = page.products[2]
        assertEquals("No offer we can count: out of stock", out.summary)
        assertNull(out.choose, "a switch nobody can count is offered as a choice")
        assertEquals("3 switches", page.count)
    }

    @Test
    fun `each product lists its offers, with the shop page`() {
        val cj = picker().products.single { it.name == "Gateron CJ" }
        assertEquals(1, cj.offers.size)
        assertEquals("https://42keebs.eu/26354", cj.offers.single().url)
        assertEquals("58 for €36.25 at 42. Keebs, in stock, seen 4 Oct 2026", cj.offers.single().detail)
    }

    @Test
    fun `switches that do not fit are one line with the reason, linking to them`() {
        val page = picker()
        assertFalse(page.products.any { it.name == "Alps White" })
        val more = assertNotNull(page.dontFit)
        assertEquals("1 more doesn't fit: Alps, the board takes MX or Choc v1", more.text)
        assertEquals("/designs/lily58-pro/build/switches?f-fit=any", more.href)
    }

    @Test
    fun `with the fit filter off, a switch that does not fit is listed with its reason and cannot be chosen`() {
        val page = picker(mapOf("f-fit" to "any"))
        val alps = page.products.last()
        assertEquals("Alps White", alps.name)
        assertEquals("Kind not recorded", alps.kind)
        assertEquals("Does not fit: Alps, the board takes MX or Choc v1", alps.summary)
        assertNull(alps.choose)
        assertNull(page.dontFit)
    }

    // -- the rail --------------------------------------------------------------------

    @Test
    fun `the rail counts what each option would show beside the other filters`() {
        val page = picker(offers = PartsFixtures.today + brownOut)
        val kind = page.rail.single { it.key == "f-kind" }
        assertEquals(listOf("Linear" to 2, "Tactile" to 1, "Silent linear" to 0, "Silent tactile" to 0, "Clicky" to 0),
            kind.known.map { it.label to it.count })
        // Alps is the only switch whose kind is not recorded, and it does not fit.
        assertEquals("Not recorded" to 0, kind.unrecorded!!.let { it.label to it.count })
        assertEquals("/designs/lily58-pro/build/switches?f-kind=tactile", kind.known[1].href)

        val fit = page.rail.single { it.key == "f-fit" }.known.single()
        assertEquals(Triple("Fits this board", 3, true), Triple(fit.label, fit.count, fit.selected))
        assertEquals("/designs/lily58-pro/build/switches?f-fit=any", fit.href)

        val stock = page.rail.single { it.key == "f-stock" }.known.single()
        assertEquals(Triple("In stock only", 2, false), Triple(stock.label, stock.count, stock.selected))
    }

    @Test
    fun `a filter link adds to the end and keeps the reader's picks`() {
        val page = picker(mapOf("f-kind" to "linear"), picks = mapOf(NeedKind.Diodes to Pick.Own))
        val stock = page.rail.single { it.key == "f-stock" }.known.single()
        assertEquals("/designs/lily58-pro/build/switches?diodes=own&f-kind=linear&f-stock=in", stock.href)
        val linear = page.rail.single { it.key == "f-kind" }.known.first()
        assertTrue(linear.selected)
        assertEquals("/designs/lily58-pro/build/switches?diodes=own", linear.href, "a selected option's link removes it")
    }

    @Test
    fun `in stock only hides what cannot be bought today`() {
        val page = picker(mapOf("f-stock" to "in"), offers = PartsFixtures.today + brownOut)
        assertEquals(listOf("Gateron CJ", "Kailh Choc Pink"), page.products.map { it.name })
    }

    // -- nothing left ----------------------------------------------------------------

    @Test
    fun `no result under the filters says so, and offers to drop the last one`() {
        val page = picker(linkedMapOf("f-stock" to "in", "f-kind" to "tactile"))
        assertTrue(page.products.isEmpty())
        val empty = assertNotNull(page.empty)
        assertEquals("No switches match these filters.", empty.text)
        assertEquals("Remove “Tactile”", empty.linkText)
        assertEquals("/designs/lily58-pro/build/switches?f-stock=in", empty.href)
    }

    // -- the reader's pick -----------------------------------------------------------

    @Test
    fun `the current pick is ticked, and can be cleared, every other pick kept`() {
        val page = picker(picks = mapOf(NeedKind.Switches to Pick.Product("gateron-cj"), NeedKind.Diodes to Pick.Own))
        val cj = page.products.single { it.name == "Gateron CJ" }
        assertTrue(cj.chosen)
        assertNull(cj.choose)
        val pink = page.products.single { it.name == "Kailh Choc Pink" }
        assertEquals("/designs/lily58-pro/build?diodes=own&switches=kailh-choc-pink#need-switches", pink.choose)
        assertEquals("/designs/lily58-pro/build?diodes=own#need-switches", page.clear)
        assertEquals("/designs/lily58-pro/build?diodes=own&switches=gateron-cj#need-switches", page.back)
    }

    @Test
    fun `with no pick there is nothing to clear`() {
        assertNull(picker().clear)
    }

    @Test
    fun `a stale price is not counted and goes to the end`() {
        val stale = PartsFixtures.today.map {
            if (it.listing.product.value == "gateron-cj") offer(it.listing, 50, Stock.InStock, Duration.ofDays(9)) else it
        }
        val page = picker(offers = stale)
        assertEquals(listOf("Kailh Choc Pink", "Gateron CJ"), page.products.map { it.name })
        assertEquals("No offer we can count: price more than 7 days old", page.products.last().summary)
    }
}
