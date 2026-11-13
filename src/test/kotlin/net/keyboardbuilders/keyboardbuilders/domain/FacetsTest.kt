package net.keyboardbuilders.keyboardbuilders.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Forgejo #4's four assertions. Two of them the issue reserved as judgement calls; both
 * are settled here as REFUSALS, and the reasoning is in `Facets.parse`.
 *
 * The issue's own example is `?mcu=rp2040`, and there is no `mcu` facet — ticket 50 took
 * the MCU off socketed designs, which is 59% of the corpus, and the projection emits
 * `controller_footprint` instead. The behaviour under test is the binder's, so it is
 * pinned on keys that exist.
 */
class FacetsTest {

    private fun exact(token: String) = Term.Exact(FacetValue(token))


    private fun parse(vararg pairs: Pair<String, String>) =
        Facets.parse(pairs.groupBy({ it.first }, { it.second }))

    // -- 1. One value ------------------------------------------------------------------

    @Test
    fun `one parameter binds to one group holding one value`() {
        val facets = parse("switch_footprint" to "choc_v1")

        assertEquals(1, facets.groups.size)
        assertEquals(FacetKey.SwitchFootprint, facets.groups.single().key)
        assertEquals(setOf(FacetValue("choc_v1")), facets.groups.single().values)
    }

    @Test
    fun `no query at all is every design, not an error`() {
        assertTrue(Facets.parse(emptyMap()).isEmpty)
        assertTrue(Facets.none.isEmpty)
    }

    // -- 2. `unknown` is an ordinary value ---------------------------------------------

    /**
     * `contract.md` §1: *"There is no `include_unknown` parameter."* The whole assertion
     * is that nothing here special-cases it — it arrives as a value, sits in the value
     * set, and ORs with its neighbours like any other.
     */
    @Test
    fun `unknown is a value in the list, not a mode`() {
        val values = parse("switch_footprint" to "choc_v1,unknown").of(FacetKey.SwitchFootprint)

        assertEquals(setOf(exact("choc_v1"), exact("unknown")), values)
    }

    @Test
    fun `unknown is a value on a numeric facet too`() {
        assertEquals(
            setOf(exact("34"), exact("unknown")),
            parse("keys" to "34,unknown").of(FacetKey.Keys),
        )
    }

    // -- 3. An unparseable token is an error, never a silent narrowing -----------------

    /**
     * The failure the contract records: `?keys=65-,unknown` once fell through to the value
     * list, matched nothing for `65-`, and returned the complement of what the chip bar
     * claimed — on a bookmarkable URL. It was then REFUSED, correctly, because a range was
     * real grammar that was not built.
     *
     * It is built now, so the same URL is a filter. The rule that governed both answers
     * never changed: a token means what it says or it is an error, and it is never
     * silently something else.
     */
    @Test
    fun `an open-ended range is read as a span, and ORs with unknown beside it`() {
        assertEquals(
            setOf(Term.Span(65, null), exact("unknown")),
            parse("keys" to "65-,unknown").of(FacetKey.Keys),
        )
    }

    @Test
    fun `a closed range and a bare count are both read, in one group`() {
        assertEquals(
            setOf(Term.Span(37, 49), exact("34")),
            parse("keys" to "37-49,34").of(FacetKey.Keys),
        )
        assertEquals(setOf(Term.Span(null, 36)), parse("keys" to "-36").of(FacetKey.Keys))
    }

    /** A URL is the truth of what is shown, so a span must survive the round trip. */
    @Test
    fun `every span spells itself back into the token it was read from`() {
        for (token in listOf("-36", "37-49", "90-")) {
            assertEquals(token, parse("keys" to token).of(FacetKey.Keys).single().token)
        }
    }

    /**
     * A BACKWARDS SPAN IS THE OLD BUG WEARING NEW CLOTHES. `48-37` parses, looks like a
     * filter, and matches nothing by construction — a chip bar claiming "48–37" over a
     * result set that is empty for a reason the reader cannot see. Refusing it is the
     * same rule that refused `65-` when ranges did not exist.
     */
    @Test
    fun `a backwards span is refused rather than matching nothing`() {
        assertFailsWith<UnreadableQuery> { parse("keys" to "48-37") }
    }

    @Test
    fun `a span open at both ends is not a filter and is refused`() {
        assertFailsWith<UnreadableQuery> { parse("keys" to "-") }
    }

    /** `-` is an ordinary character on a facet that is not numeric. */
    @Test
    fun `a hyphen in a non-numeric value is part of the value, not a range`() {
        assertEquals(setOf(exact("cc-by-sa4")), parse("license" to "cc-by-sa4").of(FacetKey.License))
    }

    @Test
    fun `a numeric facet refuses a word`() {
        assertFailsWith<UnreadableQuery> { parse("keys" to "many") }
    }

    @Test
    fun `a value that is not a token is refused`() {
        assertFailsWith<UnreadableQuery> { parse("switch_footprint" to "Choc V1") }
        assertFailsWith<UnreadableQuery> { parse("switch_footprint" to "choc_v1,") }
        assertFailsWith<UnreadableQuery> { parse("switch_footprint" to "") }
    }

    // -- 4. An unknown facet key is rejected, not ignored -------------------------------

    /**
     * Ignoring it is how the rail and the query drift apart: a rail could offer a filter
     * the query silently drops, and the page would answer a different question than the
     * one asked without saying so. The message names the keys this build does know,
     * because a 400 that does not say what would have worked is a dead end.
     */
    @Test
    fun `an unknown facet key is refused and the error says what exists`() {
        val refused = assertFailsWith<UnreadableQuery> { parse("frobnicate" to "1") }

        assertTrue("frobnicate" in refused.message!!)
        assertTrue("switch_footprint" in refused.message!!, "the error must list real keys")
    }

    /**
     * `sort` and `page` share the query string and belong to other binders. Skipping them
     * is not the silent-ignore this issue forbids: they ARE handled, just not here.
     */
    @Test
    fun `sort and page are not facets and are not errors`() {
        val facets = parse("sort" to "stars", "page" to "2", "body" to "split")

        assertEquals(1, facets.groups.size)
        assertEquals(FacetKey.Body, facets.groups.single().key)
    }

    // -- One URL per query -------------------------------------------------------------

    @Test
    fun `groups are ordered by the vocabulary, so one query has one URL`() {
        val a = parse("pitch" to "choc", "body" to "split").groups.map { it.key }
        val b = parse("body" to "split", "pitch" to "choc").groups.map { it.key }

        assertEquals(a, b, "parameter order must not change the parsed shape")
    }
}
