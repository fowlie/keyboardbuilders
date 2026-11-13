package net.keyboardbuilders.keyboardbuilders.parts

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/** Packs rounded up, and VAT added once per line in whole cents (#112). */
class PackPriceTest {

    @Test
    fun `58 sockets sold in tens is six packs with two spare`() {
        assertEquals(Packs(packs = 6, spare = 2), packsFor(quantity = 58, pack = 10))
    }

    @Test
    fun `an exact multiple leaves nothing spare`() {
        assertEquals(Packs(packs = 4, spare = 0), packsFor(quantity = 40, pack = 10))
    }

    @Test
    fun `a pack of one is one pack per part`() {
        assertEquals(Packs(packs = 2, spare = 0), packsFor(quantity = 2, pack = 1))
    }

    @Test
    fun `a pack or a quantity below one is refused`() {
        assertThrows<IllegalArgumentException> { packsFor(quantity = 5, pack = 0) }
        assertThrows<IllegalArgumentException> { packsFor(quantity = 0, pack = 10) }
    }

    /** 42keebs, 2026-10-04: the Store API's net 2800 is the page's 35.00 at 25% Norwegian VAT. */
    @Test
    fun `25 percent VAT on a net price`() {
        assertEquals(1125L, gross(netMinor = 900, vatPercent = 25))
        assertEquals(3500L, gross(netMinor = 2800, vatPercent = 25))
    }

    @Test
    fun `VAT is added to the whole line, then rounded half up once`() {
        assertEquals(188L, gross(netMinor = 150, vatPercent = 25))
        assertEquals(5175L, gross(netMinor = 2070, vatPercent = 25, packs = 2))
        assertEquals(1125L, gross(netMinor = 150, vatPercent = 25, packs = 6))
    }

    @Test
    fun `no VAT leaves the price as it is`() {
        assertEquals(301L, gross(netMinor = 301, vatPercent = 0))
    }
}
