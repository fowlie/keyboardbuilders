package net.keyboardbuilders.keyboardbuilders.persistence

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.Facets
import net.keyboardbuilders.keyboardbuilders.domain.Sort
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Forgejo #5, against the hand-written fixture the issue specifies. Six designs, written
 * as inserts, because that decouples the query layer from the importer — and because six
 * rows chosen to sit on the boundaries prove more than 234 real ones that happen not to.
 *
 * THE FACET IS `pitch`, NOT `mcu`. The issue picks `mcu` deliberately and gives its
 * reason — `switch_footprint` is the one value list still moving, and `mcu` "exercises
 * `Recorded<T>` properly, since two of the six rows are NULL". That reason now points at
 * `pitch`: ticket 50 took the MCU off socketed designs, 59% of the corpus, so no `mcu`
 * facet is projected, while `pitch` is stable and genuinely unrecorded for 9 designs.
 * Same shape, same NULL behaviour, a key that exists.
 */
class DesignQueriesTest {

    private lateinit var source: SingleConnectionDataSource
    private lateinit var db: JdbcClient
    private lateinit var queries: DesignQueries

    @BeforeEach
    fun fixture() {
        source = SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=on", true)
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        db = JdbcClient.create(source)
        queries = DesignQueries(db)

        //  2 choc | 1 mx | 2 with no pitch at all | 1 choc but archived
        seed("choc-one", "choc", stars = 500)
        seed("choc-two", "choc", stars = 400)
        seed("mx-one", "mx", stars = 300)
        seed("quiet-one", null, stars = 200)
        seed("quiet-two", null, stars = 100)
        seed("choc-archived", "choc", stars = 9_000, archived = true)
    }

    /** External-content FTS5 is not self-maintaining -- the importer rebuilds it, so must this. */
    private fun indexText() =
        db.sql("INSERT INTO design_fts(design_fts) VALUES('rebuild')").update()

    @AfterEach
    fun close() = source.destroy()

    private fun seed(
        slug: String,
        pitch: String?,
        stars: Int?,
        archived: Boolean = false,
        keys: Int = 42,
        completeness: Int = 0,
    ) {
        val id = db.sql("INSERT INTO design (slug) VALUES (?) RETURNING id")
            .param(slug).query(Long::class.java).single()
        val rev = db.sql(
            """INSERT INTO revision (design_id, seq, parent_id, author_id, origin, summary, payload)
               VALUES (?, 1, NULL, NULL, 'import', 'fixture', '{"v":"1"}') RETURNING id"""
        ).param(id).query(Long::class.java).single()
        db.sql("UPDATE design SET head_id = ? WHERE id = ?").params(rev, id).update()
        db.sql(
            """INSERT INTO design_search
                 (design_id, revision_id, name, slug, hay_a, hay_b, hay_c, facets, keys,
                  stars, archived, completeness)
               VALUES (?, ?, ?, ?, ?, '', '', '{}', ?, ?, ?, ?)"""
        ).params(listOf(id, rev, slug, slug, slug, keys, stars, if (archived) 1 else 0, completeness)).update()
        // A design with no pitch gets NO ROW, which is what makes `unknown` an anti-join
        // rather than a magic value. Writing "unknown" into the table would make the
        // absence a presence and the whole distinction would collapse.
        if (pitch != null) {
            db.sql("INSERT INTO design_facet (design_id, key, value) VALUES (?, 'pitch', ?)")
                .params(id, pitch).update()
        }
        // `body` is uniform ACROSS THE FIXTURE ON PURPOSE — it stands in for the real
        // corpus's `build_method`, where all 234 designs are `pcb`. A group with one
        // reachable value must not reach the rail.
        db.sql("INSERT INTO design_facet (design_id, key, value) VALUES (?, 'body', 'split')")
            .param(id).update()
        // `firmware` varies, so there is still a group the other filters can be seen to
        // narrow. Without one, "another group's filter narrows this one" has no subject.
        db.sql("INSERT INTO design_facet (design_id, key, value) VALUES (?, 'firmware', ?)")
            .params(id, if (pitch == null) "zmk" else "qmk").update()
    }

    private fun parse(vararg pairs: Pair<String, String>) =
        Facets.parse(pairs.groupBy({ it.first }, { it.second }))

    // -- The three assertions ----------------------------------------------------------

    @Test
    fun `a value selects the designs that have it`() {
        assertEquals(
            listOf("choc-one", "choc-two", "choc-archived"),
            queries.slugsMatching(parse("pitch" to "choc")),
        )
    }

    /** Plain OR within the group. `unknown` needs no mode and no second parameter. */
    @Test
    fun `unknown ORs with the values beside it`() {
        val hits = queries.slugsMatching(parse("pitch" to "choc,unknown"))!!

        assertEquals(5, hits.size)
        assertTrue("quiet-one" in hits && "quiet-two" in hits, "the unrecorded ones are missing")
        assertTrue("mx-one" !in hits, "a recorded non-match must not arrive")
    }

    /**
     * Sunk, NOT excluded. `choc-archived` has 9,000 stars and would top the list on stars
     * alone; it sorts last because `archived ASC` is the first key. A repo nobody has
     * touched is still a keyboard you can build, and dropping it answers a question the
     * reader did not ask.
     */
    @Test
    fun `an archived design sinks to the bottom rather than disappearing`() {
        val hits = queries.slugsMatching(parse("pitch" to "choc"))!!

        assertTrue("choc-archived" in hits, "archived was excluded, not sunk")
        assertEquals("choc-archived", hits.last(), "archived must sort last despite 9,000 stars")
    }

    @Test
    fun `no query selects nothing in particular, which is not the same as selecting none`() {
        assertEquals(null, queries.slugsMatching(Facets.none))
        assertEquals(emptyList(), queries.slugsMatching(parse("pitch" to "nonexistent")))
    }

    @Test
    fun `two groups AND together`() {
        assertEquals(3, queries.slugsMatching(parse("pitch" to "choc", "body" to "split"))!!.size)
        assertEquals(0, queries.slugsMatching(parse("pitch" to "choc", "body" to "unibody"))!!.size)
    }

    // -- Free-text search, Forgejo #25 -------------------------------------------------

    @Test
    fun `text narrows to the designs whose name or slug matches`() {
        indexText()

        assertEquals(listOf("choc-one", "choc-two", "choc-archived"),
            queries.slugsMatching(Facets.none, "choc"))
    }

    /** A prefix, because a reader types as far as they have got. */
    @Test
    fun `a partial word matches`() {
        indexText()

        assertEquals(2, queries.slugsMatching(Facets.none, "qui")!!.size)
    }

    /** Several words AND: "choc one" is narrower than "choc", never wider. */
    @Test
    fun `every word must match`() {
        indexText()

        assertEquals(listOf("choc-one"), queries.slugsMatching(Facets.none, "choc one"))
    }

    @Test
    fun `text ANDs with the facets rather than replacing them`() {
        indexText()

        assertEquals(listOf("mx-one"), queries.slugsMatching(parse("pitch" to "mx"), "one"))
        assertEquals(emptyList(), queries.slugsMatching(parse("pitch" to "choc"), "mx"))
    }

    /**
     * NOTHING TO SEARCH FOR IS NOT A SEARCH THAT FINDS NOTHING. A blank box, or one
     * holding only punctuation, is the unfiltered page -- the same distinction
     * `slugsMatching` already draws between "no filter" and "no match".
     */
    @Test
    fun `an empty or unusable query is not a filter`() {
        indexText()

        assertEquals(null, queries.slugsMatching(Facets.none, ""))
        assertEquals(null, queries.slugsMatching(Facets.none, "   "))
        assertEquals(null, queries.slugsMatching(Facets.none, "!!! ***"))
    }

    /**
     * FTS5 MATCH is a QUERY LANGUAGE, and a reader typing into a search box is not
     * writing one. `"` opens a phrase, `*` is a prefix operator, `NEAR` and `OR` are
     * keywords, and a malformed expression does not return nothing -- it THROWS, which
     * is a 500 on a page whose whole job is to be typed into.
     */
    @Test
    fun `punctuation and FTS operators are text to search for, not syntax`() {
        indexText()

        for (hostile in listOf("choc\"", "choc*", "choc OR mx", "NEAR(choc", "choc)", "^choc")) {
            // COMPLETING is the assertion. Finding nothing is a fine answer -- `choc OR
            // mx` ANDs three literal words and no design has one starting "or" -- but
            // THROWING is a 500 on the one control built to be typed into.
            assertTrue(runCatching { queries.slugsMatching(Facets.none, hostile) }.isSuccess,
                "`$hostile` threw instead of searching for it literally")
        }
        // And the operator characters are simply not part of a word.
        assertEquals(queries.slugsMatching(Facets.none, "choc"),
            queries.slugsMatching(Facets.none, "choc*"))
    }

    @Test
    fun `text results keep the site ranking order`() {
        indexText()
        val hits = queries.slugsMatching(Facets.none, "choc")!!

        assertEquals("choc-archived", hits.last(), "archived must still sink")
    }

    // -- Zero-recovery -----------------------------------------------------------------

    /**
     * `ux.md` §2.6 Risk 1 makes an empty result a main path, and the corpus agrees: at 234
     * designs, 38% of two-filter combinations return fewer than three results. So the
     * question "what should I drop" has to be answered by the page, not by the reader.
     */
    @Test
    fun `an empty result offers the filter to drop, with the count it would give`() {
        val query = parse("pitch" to "choc", "body" to "unibody")
        assertEquals(0, queries.slugsMatching(query)!!.size)

        val offers = queries.relaxations(query)

        // The offer names the group to DROP. Dropping `body` leaves `pitch=choc`, which
        // is 3. Dropping `pitch` leaves `body=unibody`, which is still 0 in this fixture
        // and is therefore not offered at all.
        assertEquals(1, offers.size, "only one of the two groups leads anywhere: $offers")
        assertEquals(FacetKey.Body, offers.single().group.key)
        assertEquals(3, offers.single().count, "dropping body leaves the three choc designs")
    }

    /**
     * An offer that leads to another empty page is worse than no offer: it spends the
     * reader's click to tell them nothing.
     */
    @Test
    fun `a relaxation that would still find nothing is not offered`() {
        val offers = queries.relaxations(parse("pitch" to "nonexistent", "body" to "unibody"))

        assertTrue(offers.isEmpty(), "offered a dead end: $offers")
    }

    @Test
    fun `relaxations are ordered by how much they open up`() {
        val offers = queries.relaxations(parse("pitch" to "mx", "body" to "split"))

        assertEquals(
            offers.map { it.count }.sortedDescending(), offers.map { it.count },
            "the most fruitful suggestion must come first",
        )
    }

    // -- #6: the rail's self-excluded counts -------------------------------------------

    private fun rail(facets: Facets) = queries.rail(facets).associateBy { it.key }

    /**
     * The assertion the whole issue turns on. With `pitch=choc` selected, the PITCH group
     * still shows `mx` at its unfiltered count — otherwise every unselected option reads
     * 0, and the group becomes a one-way ratchet: the reader can never switch from choc
     * to mx, only clear everything and start again.
     */
    @Test
    fun `a group's own filter is removed from its own counts`() {
        val groups = rail(parse("pitch" to "choc"))
        val pitch = groups[FacetKey.Pitch]!!.options.associate { it.value to it.count }

        assertEquals(3, pitch["choc"], "the selected value keeps its true count")
        assertEquals(1, pitch["mx"], "an unselected sibling must not read 0")
    }

    /**
     * And a group that is NOT selected is still narrowed by the others.
     *
     * Asked of `firmware` rather than `body`: `body` is uniform in this fixture and is
     * therefore no longer offered at all, which is its own assertion below.
     */
    @Test
    fun `another group's filter still narrows this one`() {
        val all = rail(Facets.none)[FacetKey.Firmware]!!.options.single { it.value == "qmk" }
        val filtered = rail(parse("pitch" to "choc"))[FacetKey.Firmware]!!
            .options.single { it.value == "qmk" }

        assertEquals(4, all.count, "unfiltered, the four designs with a pitch are qmk")
        assertEquals(3, filtered.count, "with pitch=choc applied, only the choc ones count")
    }

    /**
     * `contract.md` §3, and the trap the issue exists to catch: self-exclusion and
     * "render what has a count" interact to DELETE the user's own checkbox. The option
     * returns 0, is never emitted, and the control representing their filter vanishes —
     * which looks like a rendering bug and is an arithmetic one.
     */
    @Test
    fun `a selected value is emitted and checked even when nothing has it`() {
        val pitch = rail(parse("pitch" to "nonexistent"))[FacetKey.Pitch]!!
        val chosen = pitch.options.singleOrNull { it.value == "nonexistent" }

        assertTrue(chosen != null, "the user's own filter vanished from the rail")
        assertEquals(0, chosen.count)
        assertTrue(chosen.selected, "it must render checked, or the page contradicts the URL")
    }

    @Test
    fun `every selected value is marked selected and nothing else is`() {
        val pitch = rail(parse("pitch" to "choc,unknown"))[FacetKey.Pitch]!!

        assertEquals(setOf("choc", "unknown"), pitch.options.filter { it.selected }
            .map { it.value }.toSet())
    }

    /**
     * §3's third rule. A design offering several alternate layouts emits several `keys`
     * rows, so the column does not partition the corpus — 20 of ZMK's 77 records have
     * more than one layout, so this is common rather than theoretical. The GROUP
     * deliberately carries no total, so a caller cannot render "21 of 234" from it.
     */
    /**
     * A GROUP MUST OFFER A CHOICE. Every one of the 234 real designs is `build_method=pcb`
     * -- one distinct value, corpus-wide -- so the group rendered a single checkbox that
     * took 234 designs to 234 designs. A control that provably cannot change the result
     * set is worse than an absent one: it looks like a filter, costs a click, and teaches
     * the reader that the rail does nothing.
     *
     * The old guard asked whether ANY option had a count, which `pcb` at 234 passes
     * easily. The question is whether TWO outcomes are reachable.
     */
    @Test
    fun `a facet every design shares is not offered as a filter`() {
        // Every fixture design is `body=split`, exactly as every real design is pcb.
        assertTrue(rail(Facets.none).keys.none { it == FacetKey.Body },
            "a group with one reachable value is a filter that cannot filter")
    }

    /** One real value AND some designs lacking it is still two outcomes, so it stays. */
    @Test
    fun `a facet only some designs have is still a filter`() {
        val pitch = rail(Facets.none)[FacetKey.Pitch]

        assertTrue(pitch != null, "mx-one against the two with no pitch is a real choice")
    }

    /** And the reader's own filter is never withdrawn from under them. */
    @Test
    fun `a selected group survives even when it no longer discriminates`() {
        val groups = rail(parse("body" to "split"))

        assertTrue(groups.keys.any { it == FacetKey.Body },
            "the control representing the reader's own filter vanished")
    }

    @Test
    fun `a group carries no total, because its counts do not partition the corpus`() {
        val fields = DesignQueries.Group::class.java.declaredFields.map { it.name }

        assertTrue(fields.none { it.equals("total", ignoreCase = true) },
            "a total beside the options invites arithmetic the data does not support")
    }

    // -- Forgejo #29: the rail's counts respect the search -----------------------------

    /**
     * The bug, at fixture scale. Search for `mx` and the rail must not go on promising
     * three choc designs -- clicking that checkbox delivers none of them.
     */
    @Test
    fun `the rail's counts are narrowed by the text`() {
        indexText()
        val pitch = queries.rail(Facets.none, "mx").single { it.key == FacetKey.Pitch }
            .options.associate { it.value to it.count }

        assertEquals(1, pitch["mx"], "the one design matching `mx` is the mx one")
        // `?: 0` RECORDS A SECOND DEFECT RATHER THAN HIDING IT. What the rail must never
        // do is promise choc designs this search excludes, and it no longer does. But it
        // achieves that by OMITTING the option, and `ux.md` §2.5 requires zero-count
        // options be "disabled, not hidden" with a reason. That gap predates this fix and
        // applies to facet-only filtering too, so it is not widened into here.
        assertEquals(0, pitch["choc"] ?: 0,
            "the rail promised choc designs the search excludes")
    }

    /**
     * THE SUBTLETY, and the reason this is not a one-line change. Self-exclusion drops a
     * group's OWN facet from its own base, so the reader can switch from choc to mx
     * instead of only clearing. The text is not any group's own filter, so it must
     * survive that exclusion and narrow the group being counted too.
     *
     * With `pitch=choc` selected and `mx-one` typed, the pitch group's base excludes the
     * pitch filter -- leaving the whole corpus -- and the text must then cut it to the
     * single design named `mx-one`. Excluding the text "by symmetry" reads `choc` as 3.
     */
    @Test
    fun `the text is not self-excluded from a group's own counts`() {
        indexText()
        val pitch = queries.rail(parse("pitch" to "choc"), "mx-one")
            .single { it.key == FacetKey.Pitch }.options.associate { it.value to it.count }

        assertEquals(0, pitch["choc"], "the selected value must respect the search too")
        assertEquals(1, pitch["mx"], "the switchable sibling is still offered at its real count")
    }

    /** `unknown` is an anti-join and takes the same base, so the text reaches it as well. */
    @Test
    fun `the unknown count is narrowed by the text`() {
        indexText()
        fun unknown(text: String?) = queries.rail(Facets.none, text)
            .single { it.key == FacetKey.Pitch }.options.single { it.value == Facets.UNKNOWN }.count

        assertEquals(2, unknown(null), "both pitchless designs, unsearched")
        assertEquals(1, unknown("quiet-one"), "the search names one of them")
    }

    /**
     * And what the text must NOT touch. `reachOfEachFacet` asks whether a facet can reach
     * two outcomes ACROSS THE CORPUS -- a property of the facet, not of one query. Fed
     * the text, every group would vanish the moment a search narrowed to one design,
     * taking the rail with it exactly when the reader most needs a way back out.
     */
    @Test
    fun `a search that matches one design does not withdraw the rail`() {
        indexText()
        val groups = queries.rail(Facets.none, "mx-one")

        assertTrue(groups.any { it.key == FacetKey.Pitch },
            "pitch still reaches two outcomes in the corpus, whatever this search found")
    }

    // -- Sort, backlog #37 ----------------------------------------------------------

    /**
     * `archived` is sunk on EVERY sort, [Sort] included — the ticket's own worry that a
     * sort might flow through one path and not the other applies just as much to a sort
     * silently un-sinking the one archived row in this fixture.
     */
    @Test
    fun `sorting by name still sinks the archived design last`() {
        assertEquals(
            listOf("choc-one", "choc-two", "mx-one", "quiet-one", "quiet-two", "choc-archived"),
            queries.allRanked(Sort.Name),
        )
    }

    @Test
    fun `the default sort is unchanged from before Sort existed`() {
        // Highest stars first, EXCEPT choc-archived: 9,000 stars and sunk to last anyway.
        assertEquals(
            listOf("choc-one", "choc-two", "mx-one", "quiet-one", "quiet-two", "choc-archived"),
            queries.allRanked(),
            "calling allRanked() with no argument must order exactly as it always did",
        )
    }

    @Test
    fun `sorting by keys ascends, smallest first, archived still sunk`() {
        seed("keys-small", "choc", stars = 1, keys = 20)
        seed("keys-big", "choc", stars = 1, keys = 80)

        val order = queries.allRanked(Sort.Keys)

        assertTrue(order.indexOf("keys-small") < order.indexOf("keys-big"),
            "20 keys did not sort before 80: $order")
        assertTrue(order.indexOf("keys-big") < order.indexOf("choc-archived"),
            "an archived design was not sunk last under a keys sort: $order")
    }

    @Test
    fun `sorting by complete descends, most-filled-in first, archived still sunk`() {
        seed("bare-record", "choc", stars = 1, completeness = 20)
        seed("full-record", "choc", stars = 1, completeness = 90)

        val order = queries.allRanked(Sort.Complete)

        assertTrue(order.indexOf("full-record") < order.indexOf("bare-record"),
            "90% did not sort before 20%: $order")
        assertTrue(order.indexOf("bare-record") < order.indexOf("choc-archived"),
            "an archived design was not sunk last under a complete sort: $order")
    }

}
