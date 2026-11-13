package net.keyboardbuilders.keyboardbuilders.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `architecture.md` §1.5 makes keycap fit a FUNCTION of pitch rather than a field, so
 * that the two cannot disagree. These pin the function, and the worked case is the
 * owner's: `diepala/cantor` ships a Classic at Choc spacing, which takes only MBK caps,
 * and an MX sibling at 19.05, which takes ordinary MX-stem caps. Recording only the
 * switch families makes those look like a subset relation when they are a different
 * keycap ecosystem.
 */
class KeyPitchTest {

    @Test
    fun `MX spacing takes a full-size keycap`() {
        assertEquals(CapFootprint.Full, KeyPitch.Mx.caps)
    }

    /** The owner's Cantor Classic: choc switches at choc spacing, so MBK only. */
    @Test
    fun `choc spacing takes only a reduced keycap`() {
        assertEquals(CapFootprint.Reduced, KeyPitch.Choc.caps)
    }

    /**
     * The two designs the whole facet exists to tell apart. Both are buildable with Choc
     * switches; only one of them can wear an SA or OEM cap, and `switches` alone says
     * nothing about that.
     */
    @Test
    fun `two designs taking the same switch can take different keycaps`() {
        val classic = KeyPitch.Choc
        val mx = KeyPitch.Mx

        assertTrue(classic.caps != mx.caps, "choc-spaced and MX-spaced caps must differ")
    }

    /**
     * A measured pitch is carried, not rounded to the nearest standard. `dannixon/aya`
     * really is 19.50 mm and `vlkv/chocofly` really is 17.50, both at high self-agreement
     * — snapping them to Mx and Choc would state a keycap compatibility neither board has.
     */
    @Test
    fun `an unusual pitch keeps its own numbers rather than snapping to a standard`() {
        val aya = KeyPitch.Custom(Hundredths(1950))

        assertEquals(CapFootprint.Unusual(Hundredths(1950)), aya.caps)
        assertTrue(aya.caps != CapFootprint.Full, "19.50 mm is not 19.05 mm")
    }

    @Test
    fun `a pitch reads back in millimetres`() {
        assertEquals(19.05, Hundredths(1905).mm)
        assertEquals("17.00 mm", Hundredths(1700).toString())
    }

    /**
     * Zero and negative spacings are not small keyboards, they are parse failures — the
     * measurement reads coordinates off a file and a bug there should not become a
     * design that claims its keys are on top of each other.
     */
    @Test
    fun `a pitch that is not a distance cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> { Hundredths(0) }
        assertFailsWith<IllegalArgumentException> { Hundredths(-1905) }
        assertFailsWith<IllegalArgumentException> { Hundredths(50_000) }
    }

    /**
     * `Unknown` is a state the corpus really occupies — 6 of 80 sampled boards are
     * macropads with too few keys to measure a spacing from — so it must be
     * representable without inventing a pitch.
     */
    @Test
    fun `a design whose pitch was never measured is Unknown, not a default`() {
        val unmeasured: Recorded<KeyPitch> = Recorded.Unknown

        assertEquals(Recorded.Unknown, unmeasured)
        assertEquals(null, unmeasured.knownOrNull())
    }
}
