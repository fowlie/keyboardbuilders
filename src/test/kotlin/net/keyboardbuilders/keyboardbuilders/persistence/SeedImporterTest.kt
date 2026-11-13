package net.keyboardbuilders.keyboardbuilders.persistence

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.Facets
import net.keyboardbuilders.keyboardbuilders.domain.Extension
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import net.keyboardbuilders.keyboardbuilders.domain.hotswapParts
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Forgejo #15, against the real seed and the real schema.
 *
 * Real rather than a fixture, deliberately: the interesting failures are the ones the
 * corpus produces and a hand-picked design would not — a design with two switch
 * families, one with several layouts, one whose repository states no licence. Nothing
 * here asserts on a particular keyboard, because the seed is regenerated whenever a
 * source improves; it asserts on properties the importer must hold whatever the data
 * says.
 *
 * NEVER THE DEVELOPER'S DATABASE. The first version of this ran on the application's
 * configured datasource and wrote 136 designs into the developer's `app.db` as a side
 * effect of `./gradlew test`. `mode=memory&cache=shared` with a NAMED database is what
 * fixes it: unnamed `:memory:` would give every pooled connection a different database,
 * which is the same trap `SchemaTest` pins with a single-connection datasource, and the
 * shared cache is what lets a pool share one. The test config now sets that URL for
 * every class (`src/test/resources/config/application.properties`).
 */
@SpringBootTest
// Unused here, but the same annotations as the web tests put this class in their cached
// Spring context instead of starting a second one (#114).
@AutoConfigureMockMvc
class SeedImporterTest {

    @Autowired lateinit var importer: SeedImporter
    @Autowired lateinit var db: JdbcClient
    @Autowired lateinit var catalog: DesignCatalog

    private fun count(table: String, where: String = "1=1"): Int =
        db.sql("SELECT count(*) FROM $table WHERE $where").query(Int::class.java).single()

    // -- 1, 2. One design, one genesis revision, and the head closed -----------------

    @Test
    fun `every design becomes one design row and one import revision`() {
        importer.importAll()

        assertEquals(catalog.all().size, count("design"))
        assertEquals(catalog.all().size, count("revision"))
        assertEquals(
            catalog.all().size,
            count("revision", "seq = 1 AND parent_id IS NULL AND author_id IS NULL " +
                "AND origin = 'import'"),
            "every imported revision is a genesis revision with no author",
        )
    }

    @Test
    fun `every design points at its own revision as head`() {
        importer.importAll()

        assertEquals(0, count("design", "head_id IS NULL"), "a design with no head")
        assertEquals(
            0,
            db.sql(
                """SELECT count(*) FROM design d
                     JOIN revision r ON r.id = d.head_id
                    WHERE r.design_id <> d.id"""
            ).query(Int::class.java).single(),
            "a design whose head belongs to another design",
        )
    }

    /**
     * The document version is the DOCUMENT's, not the product's. Asserted because the
     * two share a field name and the first draft of `SchemaTest` conflated them.
     */
    @Test
    fun `every payload is valid json carrying the document version`() {
        importer.importAll()

        assertEquals(catalog.all().size, count("revision", "json_valid(payload)"))
        assertEquals(
            catalog.all().size,
            count("revision", "doc_version = '1'"),
            "doc_version is generated from the payload, so this also proves the stamp landed",
        )
    }

    // -- 3. A rebuild, not a sync ----------------------------------------------------

    /**
     * The assertion that matters most in practice. Facet backfill makes the deploy a
     * rebuild, so running twice is the normal case — and the failure mode of getting it
     * wrong is a doubled corpus rather than an error.
     */
    @Test
    fun `importing twice leaves the same rows, not double`() {
        val first = importer.importAll()
        val designs = count("design")
        val facets = count("design_facet")
        val search = count("design_search")

        val second = importer.importAll()

        assertEquals(first, second, "the two runs disagree about what they wrote")
        assertEquals(designs, count("design"))
        assertEquals(facets, count("design_facet"))
        assertEquals(search, count("design_search"))
    }

    /** Slugs are the public URL, so two designs on one address is a broken site. */
    @Test
    fun `slugs stay unique across a rebuild`() {
        importer.importAll()
        importer.importAll()

        assertEquals(
            count("design"),
            db.sql("SELECT count(DISTINCT slug) FROM design").query(Int::class.java).single(),
        )
    }

    // -- 4. The facet projection -----------------------------------------------------

    /**
     * Read from the DATABASE against the shared enum, not against a list written out
     * here. `facets()` is a product contract: a facet in the filter rail must be a key
     * the projection emits, and a test comparing one hard-coded copy to another would
     * pass happily while the two drifted — which is the failure it exists to catch.
     */
    // -- extensions: a row for what we found, and silence for what we did not --------

    /**
     * PRESENCE ONLY, and the "Not recorded" bucket it produces is honest — which it was
     * not for one afternoon on 2026-09-07, when a design whose boards matched no
     * footprint name projected `not_applicable` and the rail called it "No optional
     * hardware". The scan proves presence and cannot prove absence: 11 of those 223 are
     * contradicted by their own QMK record, `bastardkb/dilemma` among them.
     */
    @Test
    fun `extensions project what we found evidence of, and nothing else`() {
        importer.importAll()

        val withEvidence = catalog.all().count { it.extras.extensions is Recorded.Known }

        assertTrue(withEvidence > 0, "no design has an extension, so this proves nothing")
        assertTrue(withEvidence < catalog.all().size, "every design has one, so this proves nothing")
        assertEquals(
            withEvidence,
            db.sql("SELECT count(DISTINCT design_id) FROM design_facet WHERE key = 'ext'")
                .query(Int::class.java).single(),
            "a design we have no evidence for projected an extensions row",
        )
        assertEquals(
            0,
            count("design_facet", "key = 'ext' AND value = '${Facets.NOT_APPLICABLE}'"),
            "no evidence is ignorance, never an answer -- the scan cannot prove absence",
        )
    }

    /**
     * ALL SIX, not just the two the rail shows. #41's sparsity finding keeps speaker,
     * haptic, trackball and trackpad off the rail as checkboxes; they are named on the
     * remainder line and `?ext=trackball` is a legal URL.
     */
    @Test
    fun `every extension the domain knows is projected, whether or not the rail shows it`() {
        importer.importAll()

        val projected = db.sql("SELECT DISTINCT value FROM design_facet WHERE key = 'ext'")
            .query(String::class.java).list().filterNotNull().toSet()

        assertEquals(
            Extension.entries.map { it.name.lowercase() }.toSet(),
            projected,
            "the projection and the domain's own list of extensions disagree",
        )
    }

    // -- hotswap: the parts a board takes, plus `any`, or `no` -------------------------

    private fun hotswapRows(slug: String) = db.sql(
        "SELECT f.value FROM design_facet f JOIN design d ON d.id = f.design_id " +
            "WHERE d.slug = :slug AND f.key = 'hotswap'"
    ).param("slug", slug).query(String::class.java).list().toSet()

    /** Every board that takes a hotswap part is found by `any` too, and a solder-only
     *  board by `no`: measured off the pads, so it is a fact and not "Not recorded". */
    @Test
    fun `hotswap projects each part a board takes, any for all of them, and no for solder only`() {
        importer.importAll()

        catalog.all().forEach { design ->
            val mounts = design.extras.switchMounts.knownOrNull() ?: return@forEach
            val parts = mounts.hotswapParts().map { it.token }.toSet()
            val expected = if (parts.isEmpty()) setOf("no") else parts + "any"
            assertEquals(expected, hotswapRows(design.core.slug.value), design.core.slug.value)
        }
        assertTrue(catalog.all().any { SwitchMount.MillMax in (it.extras.switchMounts.knownOrNull() ?: emptySet()) },
                   "no design takes Mill-Max, so this proves less than it says")
    }

    // -- splay: a genuine yes/no, not evidence-only like `extensions`/`switch_socket` ---

    /**
     * Unlike `extensions` and `switch_socket` above, `no` is a real measured verdict
     * here (`measure_splay.py`), so this facet projects BOTH values rather than
     * silence-means-unknown — `DesignExtras.splay`'s own doc comment has why.
     */
    @Test
    fun `splay projects a measured yes or no, and stays silent only where nothing was measured`() {
        importer.importAll()

        val withVerdict = catalog.all().count { it.extras.splay is Recorded.Known }

        assertTrue(withVerdict > 0, "no design has a splay verdict, so this proves nothing")
        assertTrue(withVerdict < catalog.all().size, "every design has one, so this proves nothing")
        assertEquals(
            withVerdict,
            count("design_facet", "key = 'splay'"),
            "a design with no measured verdict projected a splay row, or a measured one did not",
        )
        assertEquals(
            setOf("yes", "no"),
            db.sql("SELECT DISTINCT value FROM design_facet WHERE key = 'splay'")
                .query(String::class.java).list().filterNotNull().toSet(),
            "splay must project only yes/no -- both should be live in the real seed",
        )
    }

    // -- stagger: same discipline as `splay` just above, a genuine three-way verdict ---

    /**
     * Same shape as the `splay` test just above, for `stagger`: `Recorded.Known` and a
     * projected row must agree in count, and all three [Stagger] values must be live in
     * the real seed, not just one or two.
     */
    @Test
    fun `stagger projects a measured kind, and stays silent only where nothing was measured`() {
        importer.importAll()

        val withVerdict = catalog.all().count { it.extras.stagger is Recorded.Known }

        assertTrue(withVerdict > 0, "no design has a stagger verdict, so this proves nothing")
        assertTrue(withVerdict < catalog.all().size, "every design has one, so this proves nothing")
        assertEquals(
            withVerdict,
            count("design_facet", "key = 'stagger'"),
            "a design with no measured verdict projected a stagger row, or a measured one did not",
        )
        assertEquals(
            setOf("ortho", "row", "column"),
            db.sql("SELECT DISTINCT value FROM design_facet WHERE key = 'stagger'")
                .query(String::class.java).list().filterNotNull().toSet(),
            "stagger must project all three kinds -- all should be live in the real seed",
        )
    }

    // -- split link: every design answers, so "Not recorded" means only an unread split --

    /**
     * Backlog #97. A design with no link to read is a one-piece board, and it projects
     * `one_piece` rather than landing in "Not recorded" beside the splits nobody read --
     * a unibody's link is not unknown, it does not exist. A split projects one row per
     * connector it offers, and nothing where the scan named none -- that split is
     * unread, because a name-matching scan's silence is not an absence (2026-09-29).
     */
    @Test
    fun `split link projects every connector a split offers, and one_piece for the rest`() {
        importer.importAll()

        val unread = catalog.all().count { (it.core.body as? Body.Split)?.links is Recorded.Unknown }
        assertEquals(
            catalog.all().size - unread,
            db.sql("SELECT count(DISTINCT design_id) FROM design_facet WHERE key = 'link'")
                .query(Int::class.java).single(),
            "a design with a readable link projected no row, or an unread one projected one",
        )
        assertEquals(
            setOf("trrs", "usb_c", "ethernet", "mini_din", "one_piece"),
            db.sql("SELECT DISTINCT value FROM design_facet WHERE key = 'link'")
                .query(String::class.java).list().filterNotNull().toSet(),
            "every link value should be live in the real seed",
        )
        val twoLinks = db.sql(
            """SELECT count(*) FROM (SELECT design_id FROM design_facet WHERE key = 'link'
                 GROUP BY design_id HAVING count(*) > 1)"""
        ).query(Int::class.java).single()
        assertTrue(twoLinks > 0, "no design projected two links, so a TRRS-or-USB-C board is lost to one filter")
    }

    @Test
    fun `every key written is one the query binder accepts`() {
        importer.importAll()

        val written = db.sql("SELECT DISTINCT key FROM design_facet")
            .query(String::class.java).list().filterNotNull().toSet()
        val known = FacetKey.entries.map { it.param }.toSet()

        assertTrue(written.isNotEmpty(), "nothing was projected, so this proves nothing")
        assertTrue(
            written.all { it in known },
            "projected but unreadable by a query: ${written - known}",
        )
    }

    @Test
    fun `every design gets the facets that make the sidebar work`() {
        importer.importAll()

        listOf(FacetKey.Body, FacetKey.Keys, FacetKey.SwitchFootprint,
            FacetKey.ControllerFootprint, FacetKey.BuildMethod, FacetKey.Firmware,
            FacetKey.License).map { it.param }.forEach { key ->
            assertTrue(
                count("design_facet", "key = '$key'") > 0,
                "no design projected a `$key` facet",
            )
        }
        assertEquals(
            catalog.all().size,
            db.sql("SELECT count(DISTINCT design_id) FROM design_facet WHERE key = 'body'")
                .query(Int::class.java).single(),
            "body is total: every design is a split, a unibody or a one-hander",
        )
    }

    /**
     * `num` is what makes a key count range-queryable as well as exactly selectable, and
     * it must be NULL for facets that are not numbers — a partial index depends on it.
     */
    @Test
    fun `numeric facets carry their number and others do not`() {
        importer.importAll()

        assertEquals(
            0, count("design_facet", "key = 'keys' AND num IS NULL"),
            "a key count with no number is not range-queryable",
        )
        assertEquals(
            0, count("design_facet", "key = 'keys' AND CAST(value AS INTEGER) <> num"),
            "`value` and `num` must hold the same number",
        )
        assertEquals(
            0, count("design_facet", "key IN ('switch_footprint','license','firmware') " +
                "AND num IS NOT NULL"),
            "a non-numeric facet must leave `num` NULL",
        )
    }

    /**
     * A design offering two switch families is two rows and both select it. Guards the
     * long-and-thin shape: the tempting alternative is one row holding "mx,choc_v1",
     * which no index can seek and no count can group.
     */
    @Test
    fun `a design with two switch families gets a row for each`() {
        importer.importAll()

        val multi = catalog.all().count { it.core.switches.size > 1 }
        assertTrue(multi > 0, "the corpus has no multi-switch design, so this proves nothing")

        assertEquals(
            multi,
            db.sql(
                """SELECT count(*) FROM (
                     SELECT design_id FROM design_facet WHERE key = 'switch_footprint'
                      GROUP BY design_id HAVING count(*) > 1)"""
            ).query(Int::class.java).single(),
        )
    }

    // -- 5. The search row and the FTS index -----------------------------------------

    @Test
    fun `every design gets a search row keyed to its head revision`() {
        importer.importAll()

        assertEquals(catalog.all().size, count("design_search"))
        assertEquals(
            0,
            db.sql(
                """SELECT count(*) FROM design_search s
                     JOIN design d ON d.id = s.design_id
                    WHERE s.revision_id <> d.head_id"""
            ).query(Int::class.java).single(),
            "a search row pointing at something other than the design's head",
        )
    }

    /**
     * FTS5 external content tables are not self-maintaining: `architecture.md` §3.5 says
     * so, and the failure is silent — the table exists, queries run, and they return
     * nothing. So this searches for a name the corpus definitely has.
     */
    @Test
    fun `the full text index actually finds a design that exists`() {
        importer.importAll()
        val wanted = catalog.all().first().core

        val hits = db.sql("SELECT count(*) FROM design_fts WHERE design_fts MATCH ?")
            .param(wanted.slug.value.substringBefore('-'))
            .query(Int::class.java).single()

        assertTrue(hits > 0, "the index returned nothing for `${wanted.slug.value}`")
    }

    @Test
    fun `the trigram index finds a design by part of its name`() {
        importer.importAll()
        // A name with no space or punctuation: the query is passed to FTS5 verbatim, so
        // "34 Keys Cradio Z" would be parsed as a multi-token expression rather than the
        // substring this test is about. Picking the first long name found one of those
        // the moment the corpus grew, which is the test being fragile rather than the
        // index being broken.
        val wanted = catalog.all()
            .first { it.core.name.value.length >= 6 && it.core.name.value.all(Char::isLetter) }
            .core

        val hits = db.sql("SELECT count(*) FROM design_name_trgm WHERE name MATCH ?")
            .param(wanted.name.value.take(5).lowercase())
            .query(Int::class.java).single()

        assertTrue(hits > 0, "the trigram index returned nothing for `${wanted.name.value}`")
    }

    // -- 6. Nothing is invented -------------------------------------------------------

    /**
     * The schema says NULL means "we have no star count", NOT zero stars. So the failure
     * this pins is not "no data" but "invented data": a design whose repo the fetch could
     * not answer for must stay absent rather than read as a repo with zero stars, which
     * would then sort as if it were the least popular board in the corpus instead of an
     * unranked one.
     */
    @Test
    fun `a design whose repo was not fetched has no star count rather than zero`() {
        importer.importAll()

        val withStars = count("design_search", "stars IS NOT NULL")
        assertTrue(withStars > 0, "nothing has a star count, so this proves nothing")
        assertEquals(
            0,
            count("design_search", "stars = 0 AND last_commit IS NULL"),
            "a design with no fetched aliveness must not read as zero stars",
        )
        // Both come from the same fetch, so a row with one and not the other means the
        // importer took them from different places.
        assertEquals(
            0,
            count("design_search", "(stars IS NULL) <> (last_commit IS NULL)"),
            "stars and last_commit must be absent or present together",
        )
    }

    /**
     * `design_search_rank` orders on `archived ASC, stars DESC, last_commit DESC`, and
     * SQLite sorts NULLs last under DESC. That band — unknown counts BELOW every known
     * one rather than as if they were zero — is what the schema calls load-bearing and
     * non-obvious, so it gets the test it asks for.
     */
    @Test
    fun `designs with no star count rank below every design that has one`() {
        importer.importAll()

        // WITHIN each archived group, not across the whole list. `archived ASC` is the
        // FIRST sort key, so an archived repo with 78 stars legitimately sorts below an
        // unarchived one with no count at all — the first draft of this asserted over the
        // whole list and only failed once the corpus contained both, which is the data
        // catching a test that was reasoning about one sort key instead of three.
        listOf(0, 1).forEach { archived ->
            val order = db.sql(
                """SELECT stars FROM design_search WHERE archived = ?
                    ORDER BY stars DESC, last_commit DESC"""
            ).param(archived).query(Int::class.java).list()

            val firstNull = order.indexOfFirst { it == null }
            if (firstNull >= 0) {
                assertTrue(
                    order.drop(firstNull).all { it == null },
                    "a known star count sorted below an unknown one (archived=$archived)",
                )
            }
        }
    }

    /** v0.5 has no users, so an import must not conjure a principal to author itself. */
    @Test
    fun `the import creates no users`() {
        importer.importAll()

        assertEquals(0, count("user"))
        assertEquals(0, count("revision", "author_id IS NOT NULL"))
    }
}
