package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.Facets
import net.keyboardbuilders.keyboardbuilders.domain.Sort
import net.keyboardbuilders.keyboardbuilders.persistence.DesignQueries
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Forgejo #5 over HTTP: the query reaches the page, and an unreadable one is refused
 * there rather than narrowing silently.
 *
 * Against the test context's in-memory database, never the developer's `./app.db`.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FilteringTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var queries: DesignQueries


    private fun page(query: String) = mockMvc.get("/designs$query").andReturn().response
    private fun rows(html: String) =
        // Before the footer: its "Keyboard families" link is /designs/families, not a row.
        Regex("""href="/designs/[a-z0-9-]+"""").findAll(html.substringBefore("<footer")).count()

    /**
     * The size of the RESULT SET, from the count the page prints — not the number of rows
     * on screen.
     *
     * COUNTING ROWS IS ONLY A PROXY, and it silently stops being one when a result set is
     * bigger than a page: two different filters both fill page one and read as equal. That
     * is exactly what happened when `Page.DEFAULT_SIZE` went 100 to 50 — `?keys=-36` and
     * `?keys=-36,60` are genuinely different sizes and both capped at 50. Any assertion
     * that COMPARES two result sets must use this; `rows` is for assertions about what is
     * rendered.
     */
    private fun found(html: String) =
        Regex("""class="count">(\d+) design""").find(html)!!.groupValues[1].toInt()

    /** The result slugs IN THE ORDER THE PAGE PRINTS THEM.
     *
     * Matches `class="result-card"`, not `class="name"` -- the whole row became one
     * link (owner-reported: only the name text was clickable), so the href moved to
     * the row's own anchor and the name is now a plain span inside it. */
    private fun slugs(html: String) =
        Regex("""class="result-card card" href="/designs/([a-z0-9-]+)"""")
            .findAll(html).map { it.groupValues[1] }.toList()

    @Test
    fun `a facet narrows the result set`() {
        val all = found(page("").contentAsString)
        val choc = found(page("?pitch=choc").contentAsString)

        assertTrue(choc in 1 until all, "expected a narrowing, got $choc of $all")
    }

    /** Plain OR: the not-recorded ones join the list rather than replacing it. */
    @Test
    fun `unknown widens the same group`() {
        val choc = found(page("?pitch=choc").contentAsString)
        val both = found(page("?pitch=choc,unknown").contentAsString)

        assertTrue(both > choc, "unknown did not widen the group: $both vs $choc")
    }

    /**
     * The chip's remove link. `AppliedFilter.without` was computed from the day chips
     * existed and rendered by nothing until 2026-08-30 -- so the only way to drop one
     * filter was Clear all and rebuild the query. The URL was already tested (a band has
     * to survive it); what was missing was anything asserting a reader can reach it.
     */
    @Test
    fun `each chip carries a link that removes only that filter`() {
        val html = page("?pitch=choc&body=split").contentAsString
        val chips = html.substringAfter("class=\"chips\"").substringBefore("</p>")
        val drops = Regex("""class="chip-drop" href="([^"]+)"""").findAll(chips)
            .map { it.groupValues[1] }.toList()

        assertEquals(2, drops.size, "expected one remove link per chip: $chips")
        // Each drops ITS OWN filter and keeps the other. Dropping both is `Clear all`,
        // and a remove link that quietly cleared everything would look identical here
        // without this pair of assertions.
        assertTrue(drops.any { "body=split" in it && "pitch=choc" !in it }, drops.toString())
        assertTrue(drops.any { "pitch=choc" in it && "body=split" !in it }, drops.toString())
    }

    /** Following one actually narrows to the promised set rather than 404ing or clearing. */
    @Test
    fun `following a chip's remove link leaves the other filter applied`() {
        val html = page("?pitch=choc&body=split").contentAsString
        val chips = html.substringAfter("class=\"chips\"").substringBefore("</p>")
        val keepsPitch = Regex("""class="chip-drop" href="([^"]*pitch=choc[^"]*)"""")
            .find(chips)!!.groupValues[1]

        val after = mockMvc.get(keepsPitch).andReturn().response
        assertEquals(200, after.status)
        assertTrue("pitch: choc" in after.contentAsString, "the surviving filter was dropped too")
        assertTrue("body: split" !in after.contentAsString, "the removed filter came back")
    }

    @Test
    fun `the applied filters are shown so the page says what it is answering`() {
        val html = page("?pitch=choc").contentAsString

        assertTrue("class=\"chip\"" in html, "no chip rendered")
        assertTrue("choc" in html.substringAfter("class=\"chips\"").substringBefore("</p>"))
    }

    /**
     * A SPAN MUST SURVIVE EVERY LINK THE PAGE DRAWS. `Selection.values` is the exact half
     * of a group, so the chip and the rail's toggle URLs — both built from it — rendered
     * `keys:` with nothing after it and silently dropped the band from every href the
     * moment bands existed. That is the silent-narrowing failure the whole contract is
     * written against, arriving through the back door.
     */
    @Test
    fun `a band survives the chip and the links the rail draws`() {
        val html = page("?keys=-36&pitch=choc").contentAsString
        val chips = html.substringAfter("class=\"chips\"").substringBefore("</p>")

        assertTrue("-36" in chips, "the chip lost the band: $chips")
        // Scoped to ANOTHER group's fieldset: toggling a key-spacing option must carry
        // the band through untouched. The band's own un-toggle link drops it on purpose,
        // which is why this cannot be asked of every link on the page.
        val spacing = html.substringAfter("<summary>Key spacing</summary>").substringBefore("</details>")
        val links = Regex("""href="(/designs\?[^"]*)"""").findAll(spacing)
            .map { it.groupValues[1] }.toList()

        assertTrue(links.isNotEmpty(), "no key-spacing links to check")
        // Bound first: `assertTrue`'s message is an ordinary argument and is built even
        // when the assertion holds, so naming the offender inline throws on the happy path.
        val dropped = links.filterNot { "keys=-36" in it }

        assertTrue(dropped.isEmpty(), "another group's link dropped the band: $dropped")
    }

    /**
     * Backlog #9. A pager href used to be the literal string `/designs?page=N`, no
     * matter what filter was applied -- so paging forward from a filtered list
     * silently dropped it and the reader landed on page 2 of the WHOLE catalog
     * instead of page 2 of their own result. `body=split` is well past one page
     * (121 of 325 designs) at the default page size.
     */
    @Test
    fun `paging preserves the applied filter, not just the page number`() {
        val nav = page("?body=split&page=2").contentAsString
            .substringAfter("<nav class=\"pages\"").substringBefore("</nav>")

        assertFalse("""href="/designs?page=1"""" in nav, "dropped the filter going back: $nav")
        assertFalse("""href="/designs?page=3"""" in nav, "dropped the filter going forward: $nav")
        assertTrue("body=split" in nav && "page=1" in nav, "no carried previous link: $nav")
        assertTrue("body=split" in nav && "page=3" in nav, "no carried next link: $nav")
    }

    // -- Free-text search, Forgejo #25 ----------------------------------------------

    @Test
    fun `a search narrows the result set`() {
        val all = found(page("").contentAsString)
        val hits = found(page("?q=sofle").contentAsString)

        assertTrue(hits in 1 until all, "expected a narrowing, got $hits of $all")
    }

    @Test
    fun `the search ANDs with the facets rather than replacing them`() {
        val text = found(page("?q=sofle").contentAsString)
        val both = found(page("?q=sofle&pitch=mx").contentAsString)

        assertTrue(both in 1..text, "text and facets must intersect: $both vs $text")
    }

    /**
     * A GET FORM REPLACES THE WHOLE QUERY STRING. Without the facets carried as hidden
     * inputs, searching from a filtered page silently clears every filter the reader
     * set and returns MORE results than before -- a widening they did not ask for, from
     * a control that looks like it only adds a constraint.
     */
    @Test
    fun `the form carries the applied facets so submitting it does not clear them`() {
        val html = page("?pitch=choc&body=split").contentAsString
        val form = html.substringAfter("<form class=\"find\"").substringBefore("</form>")

        assertTrue("""name="pitch" value="choc"""" in form, "pitch was not carried: $form")
        assertTrue("""name="body" value="split"""" in form, "body was not carried: $form")
    }

    @Test
    fun `the search shows as a removable chip and the box keeps what was typed`() {
        val html = page("?q=sofle&pitch=choc").contentAsString

        assertTrue("search: sofle" in html, "no chip for the search")
        assertTrue("""value="sofle"""" in html, "the box did not keep the query")
    }

    /**
     * A typo is the likeliest way to reach an empty page, because a facet is chosen from
     * a list and a search box is typed into. So dropping the text has to be one of the
     * offers, or the reader is stranded with nothing to click.
     */
    @Test
    fun `a search that finds nothing offers to drop the search`() {
        val html = page("?q=zzzznotakeyboard").contentAsString

        assertEquals(0, rows(html))
        val recover = html.substringAfter("class=\"recover\"").substringBefore("</div>")
        assertTrue("the search" in recover, "no offer to drop the text: $recover")
        val offered = Regex("""href="(/designs[^"]*)"""").find(recover)!!.groupValues[1]
        assertTrue(rows(mockMvc.get(offered).andReturn().response.contentAsString) > 0,
            "the offer led to another empty page")
    }

    /** An empty box is not a filter, and must not round-trip as one. */
    @Test
    fun `an empty query is the unfiltered page`() {
        val bare = page("")
        val empty = page("?q=")
        assertEquals(200, empty.status, "?q= failed: " + empty.contentAsString.take(300))
        val body = empty.contentAsString
        assertTrue(rows(bare.contentAsString) == rows(body),
            "bare=" + rows(bare.contentAsString) + " empty=" + rows(body) +
            " len=" + body.length +
            " count=" + Regex("""class="count">[^<]*""").find(body)?.value +
            " recover=" + ("class=\"recover\"" in body) +
            " emptyMarker=" + ("class=\"empty\"" in body))
        // Spaces via the parameter API rather than percent-encoding: MockMvc treats the
        // string it is given as a URI TEMPLATE and does not decode it the way the
        // servlet container does, so `?q=%20%20` reaches the controller as the literal
        // text "%20%20" -- which tokenises to "20 20" and is a real search. That is a
        // fact about MockMvc, not about the page; the live server returns the whole
        // corpus for the same URL.
        val spaces = mockMvc.get("/designs") { param("q", "   ") }.andReturn().response
        assertEquals(rows(bare.contentAsString), rows(spaces.contentAsString))
    }

    /** FTS5 MATCH is a query language; a search box is not. Neither may 500. */
    @Test
    fun `a query full of FTS operators is searched for, not executed`() {
        for (hostile in listOf("corne%20(v2)", "%22corne", "corne%20OR%20sofle", "NEAR(x")) {
            assertEquals(200, page("?q=$hostile").status, "?q=$hostile did not return 200")
        }
    }

    /**
     * REPORTED FROM THE RUNNING SITE. Search "cantor", click "36 or fewer", and the
     * search chip vanished while the result set WIDENED -- from a control that looks
     * like it only narrows. `FacetRail.toggled` builds its URLs from `facets` alone and
     * was missed when the chips and the recovery offers were taught to carry `q`.
     */
    @Test
    fun `a rail link keeps the search`() {
        val html = page("?q=cantor").contentAsString
        val keys = html.substringAfter("<summary>Keys</summary>").substringBefore("</details>")
        val links = Regex("""href="(/designs\?[^"]*)"""").findAll(keys)
            .map { it.groupValues[1] }.toList()

        assertTrue(links.isNotEmpty(), "no rail links to check")
        val dropped = links.filterNot { "q=cantor" in it }
        assertTrue(dropped.isEmpty(), "a rail link dropped the search: $dropped")
    }

    // -- The refusals, at the boundary that matters --------------------------------

    @Test
    fun `an unknown facet key is a bad request, not a full page`() {
        assertEquals(400, page("?frobnicate=1").status)
    }

    /**
     * The failure this rule was written from: `?keys=65-` once fell through to the value
     * list, matched nothing, and returned the exact complement of what the page claimed.
     * It was then a 400, because a range was real grammar that was not built.
     *
     * It is built now, so the URL is a filter and must NARROW — the one thing it was
     * never allowed to do is come back looking like a result while meaning nothing.
     *
     * `found`, not `rows`, per the note on `found`: this compares two result sets, and
     * it went green-to-red on 2026-09-01 for no reason but the corpus growing. 65+ keys
     * reached 51 designs against a 50-row page, so both sides read 50 and the narrowing
     * vanished. The filter was never broken — the proxy was.
     */
    @Test
    fun `an open-ended range narrows rather than being refused or ignored`() {
        val big = found(page("?keys=65-").contentAsString)

        assertTrue(big in 1 until found(page("").contentAsString),
            "expected a narrowing, got $big")
    }

    /** A span that cannot match is still the old bug, so it is still a 400. */
    @Test
    fun `a backwards range is refused rather than quietly matching nothing`() {
        assertEquals(400, page("?keys=48-37").status)
    }

    /** Bands and exact counts are one group, OR-ed, not two questions. */
    @Test
    fun `a band ORs with an exact count beside it`() {
        val band = found(page("?keys=-36").contentAsString)
        val both = found(page("?keys=-36,60").contentAsString)

        assertTrue(both > band, "the exact count did not widen the group: $both vs $band")
    }

    @Test
    fun `sort and page are not facets and do not 400`() {
        assertEquals(200, page("?sort=stars").status)
        assertEquals(200, page("?page=2").status)
    }

    // -- Zero-recovery -------------------------------------------------------------

    /**
     * ux.md §2.6 Risk 1. Measured on this corpus, 38% of two-filter combinations return
     * fewer than three results, so an empty page is a place readers will land often and
     * it has to carry the way out.
     */
    @Test
    fun `an empty result offers a filter to drop and says how many that finds`() {
        val html = page("?pitch=choc&controller_footprint=blue_pill").contentAsString

        assertTrue("class=\"recover\"" in html, "no recovery block on an empty result")
        assertTrue("Dropping one filter" in html)
        assertTrue("""href="/designs?pitch=choc"""" in html, "the offer must be a working link")
        assertTrue(Regex("""\d+ designs?""").containsMatchIn(
            html.substringAfter("class=\"recover\"")), "an offer with no count is a guess")
    }

    // -- The ranking reaches the page ----------------------------------------------

    /**
     * `DesignQueries` orders by `archived ASC, stars DESC, last_commit DESC` and every
     * test of it passes — yet the page was alphabetical, because the controller took the
     * ranked list as a `Set` and used it only to filter a name-sorted catalog. The
     * order died on that line, and no test noticed: they all stopped at the query layer.
     *
     * The visible symptom was the whole product's first impression. Sofle and Lily58, the
     * two most-starred designs in the corpus, were on PAGE TWO, under S and L, while page
     * one opened on `3 W6`, `41 1350` and `45`.
     */
    @Test
    fun `the unfiltered index leads with the most-starred design`() {
        val expected = queries.allRanked().take(5)

        assertEquals(expected, slugs(page("").contentAsString).take(5))
    }

    @Test
    fun `a filtered page is in ranking order too, not alphabetical`() {
        val expected = queries.slugsMatching(Facets.parse(mapOf("pitch" to listOf("choc"))))!!

        assertEquals(expected, slugs(page("?pitch=choc").contentAsString))
    }

    /**
     * The ordering must not change WHICH designs are shown. Sorting by rank position is
     * deliberately a reordering of the catalog rather than a rebuild from the rank
     * list, so a design the projection missed sinks to the bottom instead of vanishing
     * from a total the page prints as fact.
     */
    @Test
    fun `ordering does not change the result set`() {
        val html = page("").contentAsString
        val total = Regex("""(\d+) designs""").find(html)!!.groupValues[1].toInt()

        assertEquals(total, queries.allRanked().size)
    }

    // -- Sort, backlog #37 -----------------------------------------------------------

    @Test
    fun `sort=name orders the page alphabetically instead of by stars`() {
        val expected = queries.allRanked(Sort.Name).take(5)

        assertEquals(expected, slugs(page("?sort=name").contentAsString).take(5))
    }

    /** The filtered path must not diverge from the unfiltered one under a chosen sort — the
     *  exact worry the ticket raised about ORDER not reaching `slugsMatching`. */
    @Test
    fun `a filtered AND sorted page agrees with the query layer, not just the default`() {
        val expected = queries.slugsMatching(
            Facets.parse(mapOf("body" to listOf("split"))), sort = Sort.Name,
        )!!

        assertEquals(expected.take(5), slugs(page("?body=split&sort=name").contentAsString).take(5))
    }

    @Test
    fun `an unrecognised sort is refused rather than silently ignored`() {
        assertEquals(400, page("?sort=stars2").status)
    }

    /**
     * Backlog #9's fix already threads the current query through every link that
     * rebuilds one; this just confirms `sort` rides along through all three, the same
     * way `body=split` already does.
     */
    @Test
    fun `sort survives the pager, the rail and a chip's remove link`() {
        val html = page("?body=split&sort=name&page=2").contentAsString

        val nav = html.substringAfter("<nav class=\"pages\"").substringBefore("</nav>")
        assertTrue("sort=name" in nav, "the pager dropped sort: $nav")

        val rail = html.substringAfter("<div class=\"search\">")
        val railLink = Regex("""href="(/designs\?[^"]*)"""").find(rail)!!.groupValues[1]
        assertTrue("sort=name" in railLink, "a rail link dropped sort: $railLink")

        val chips = html.substringAfter("class=\"chips\"").substringBefore("</p>")
        val dropLink = Regex("""class="chip-drop" href="([^"]*)"""").find(chips)!!.groupValues[1]
        assertTrue("sort=name" in dropLink, "the chip's remove link dropped sort: $dropLink")
    }

    /**
     * `/designs?sort=name` carries no facet chip, so before this it was indexable and
     * self-canonical — a byte-for-byte duplicate of `/designs` through a parameter
     * nobody had classified as a facet.
     */
    @Test
    fun `a sorted list is noindexed and not self-canonical, same as a filtered one`() {
        val head = page("?sort=name").contentAsString.substringAfter("<head>").substringBefore("</head>")

        assertTrue("""content="noindex, follow"""" in head, "a sorted list was left indexable: $head")
        assertFalse("""rel="canonical" href="https://keyboardbuilders.net/designs"""" in head,
            "a sorted list canonicalised to the plain, unsorted listing: $head")
    }

    /** The default sort is invisible in every way that matters: no ticket regresses. */
    @Test
    fun `sort=stars is the default and behaves exactly as an absent sort does`() {
        assertEquals(
            slugs(page("").contentAsString),
            slugs(page("?sort=stars").contentAsString),
        )
        val head = page("?sort=stars").contentAsString.substringAfter("<head>").substringBefore("</head>")
        assertTrue("""content="noindex""" !in head, "the default sort was noindexed: $head")
    }

    /** A working link: following the offer must actually find the promised designs. */
    @Test
    fun `the offered link leads somewhere with results`() {
        val html = page("?pitch=choc&controller_footprint=blue_pill").contentAsString
        // SCOPED to the recovery block. The first version searched the whole page and
        // started picking rail links the moment #7 rendered a rail — a rail link is also
        // a /designs? href, and some of them legitimately lead nowhere.
        val recover = html.substringAfter("class=\"recover\"").substringBefore("</div>")
        val offered = Regex("""href="(/designs\?[^"]+)"""").find(recover)!!.groupValues[1]

        assertTrue(rows(mockMvc.get(offered).andReturn().response.contentAsString) > 0,
            "the recovery link led to another empty page")
    }
}
