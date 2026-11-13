package net.keyboardbuilders.keyboardbuilders.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShorthandSearchTest {

    @Test
    fun `the Corne's own notation derives its own key count either way round`() {
        val corne = parseShorthandQuery("3x6+3")!!
        assertEquals(42, corne.ifSplit)
        assertEquals(21, corne.ifUnibody)

        // Row-column order is not disambiguated here, deliberately — see the file doc
        // comment. Both spellings of the Corne must derive the identical count.
        val flipped = parseShorthandQuery("6x3+3")!!
        assertEquals(corne.perHand, flipped.perHand)
    }

    @Test
    fun `a pasted link that decoded a plus to a space still parses`() {
        // application/x-www-form-urlencoded's own trap, ux.md 1.3.
        assertEquals(42, parseShorthandQuery("3x6 3")!!.ifSplit)
    }

    @Test
    fun `an extended thumb cluster adds its own rectangle, not a flat count`() {
        val sweep = parseShorthandQuery("5x6_2x3")!!
        assertEquals(36, sweep.perHand) // (5*6) + (2*3)
    }

    @Test
    fun `whitespace around the grammar is tolerated, mixed case x is not required`() {
        assertEquals(42, parseShorthandQuery("  3X6+3  ")!!.ifSplit)
    }

    @Test
    fun `a design name or free text is not this grammar`() {
        assertNull(parseShorthandQuery("corne"))
        assertNull(parseShorthandQuery("nice!nano"))
        assertNull(parseShorthandQuery("a small wireless split"))
    }

    @Test
    fun `shorthand embedded in a longer sentence does not hijack free text`() {
        // The grammar is one of four readings of the WHOLE box (ux.md 2.2), not a
        // substring scan — a search that happens to contain "3x6" is still free text.
        assertNull(parseShorthandQuery("is 3x6+3 a real board"))
    }

    @Test
    fun `asTyped carries the reader's own spelling, not a re-derived one`() {
        assertEquals("3X6+3", parseShorthandQuery("3X6+3")!!.asTyped)
    }

    // -- The bug a live search caught: keys alone is not enough ------------------------

    @Test
    fun `with no body filter of its own, a request reads the split count`() {
        val sweep = parseShorthandQuery("3x5+3")!!
        val reading = Facets.none.readingFor(sweep)
        assertEquals(36, reading.keys)
        assertEquals("split", reading.body)
    }

    @Test
    fun `a reader who already ticked Unibody gets the unibody count instead`() {
        val sweep = parseShorthandQuery("3x5+3")!!
        val facets = Facets.parse(mapOf("body" to listOf("unibody")))
        val reading = facets.readingFor(sweep)
        assertEquals(18, reading.keys)
        assertEquals("unibody", reading.body)
    }

    @Test
    fun `ticking both bodies keeps the split default`() {
        val sweep = parseShorthandQuery("3x5+3")!!
        val facets = Facets.parse(mapOf("body" to listOf("split", "unibody")))
        assertEquals("split", facets.readingFor(sweep).body)
    }

    @Test
    fun `forShorthand asks for keys AND body together, never keys alone`() {
        // This is the defect itself: "3x5+3" used to OR keycount 18 and 36 with no body
        // check, which is exactly how keypads and small unibody boards matched a search
        // for a small SPLIT board.
        val sweep = parseShorthandQuery("3x5+3")!!
        val facets = Facets.none.forShorthand(Facets.none.readingFor(sweep))

        assertEquals(setOf(FacetValue("36")), facets.of(FacetKey.Keys).map { (it as Term.Exact).value }.toSet())
        assertEquals(setOf(FacetValue("split")), facets.of(FacetKey.Body).map { (it as Term.Exact).value }.toSet())
    }

    @Test
    fun `forShorthand merges into a facet the reader already narrowed by hand`() {
        // `?keys=36-48&q=3x5+3` should not lose the reader's own band -- the derived
        // exact value joins it as an OR, the same rule any other repeated value follows.
        val facets = Facets.parse(mapOf("keys" to listOf("37-49")))
        val merged = facets.forShorthand(ShorthandReading(36, "split"))
        val tokens = merged.of(FacetKey.Keys).map { it.token }.toSet()
        assertEquals(setOf("37-49", "36"), tokens)
    }
}
