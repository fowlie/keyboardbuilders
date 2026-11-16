package net.keyboardbuilders.keyboardbuilders.persistence

import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRules
import net.keyboardbuilders.keyboardbuilders.catalog.FirmwareCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.ControllerBoard
import net.keyboardbuilders.keyboardbuilders.catalog.SeedDesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.completeness
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.facet.Catalogue
import net.keyboardbuilders.keyboardbuilders.facet.FirmwareFacet
import net.keyboardbuilders.keyboardbuilders.facet.facet
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * The seed becomes rows. Forgejo #15.
 *
 * `architecture.md` §3.3 makes the JSON DOCUMENT the truth and the flat tables a
 * projection of it, so this writes each design's facts once into `revision.payload` and
 * then derives `design_facet` and `design_search` from them. The derived tables are
 * disposable by design: throw them away, run this again, get the same site. That is what
 * makes adding a facet later a re-projection rather than a re-fetch of 300 repositories.
 *
 * A REBUILD, NOT A SYNC. Every run clears the tables and rewrites them. The deployed
 * database is a freshly built file every time, so "run it twice" is the normal case
 * rather than an edge one, and a differential importer would be machinery for a
 * situation that does not arise. `DELETE FROM design` is enough on its own: every other
 * table hangs off it with `ON DELETE CASCADE`.
 *
 * ONE WRITER. `architecture.md` §3.6 serialises writes structurally rather than by
 * locking — *"`FOR UPDATE` is a thing you can forget. A one-connection write pool is
 * not."* Its `sqlx` two-pool code does not transfer to a single Hikari pool, and this
 * class is the whole reason the discipline is not needed yet: it is the only thing in
 * the application that writes, and it writes inside one transaction. When a second
 * writer appears, that is the moment to re-express §3.6 rather than before.
 */
@Component
class SeedImporter(
    private val db: JdbcClient,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
    @Value("classpath:seed/designs.json") private val seed: Resource,
    @Value("classpath:seed/activity.json") private val activity: Resource,
    /**
     * THE FILES, NOT THE BEANS, and that distinction is load-bearing here for exactly the
     * reason the `designs` field below records. `ControllerCatalog` is substituted with a
     * fixture in five `@SpringBootTest` classes; injecting the bean would make the
     * importer derive the `wireless` facet from whichever fixture a test happens to
     * install, and the facet table would then describe a catalogue the site does not
     * serve. Reading the file makes this importer's input the seed, full stop.
     *
     * Both are also why the facet cannot go stale: the two catalogues are built by one
     * pipeline into one jar and imported together, so `wireless` is a projection of the
     * seed rather than of an independently-clocked component.
     */
    @Value("classpath:seed/controllers.json") private val controllers: Resource,
    @Value("classpath:seed/firmwares.json") private val firmwares: Resource,
    private val unpublished: Unpublished,
) {

    /**
     * Parsed from the FILE, not taken from the `DesignCatalog` bean.
     *
     * The first version injected the bean, and it was wrong in a way only a test caught:
     * a `@SpringBootTest` that substitutes a fixture catalogue made the importer look for
     * seed documents under slugs that are not in the file, and it aborted the whole
     * application context. The importer's input is the seed; that the running site
     * happens to serve the same file today is a coincidence of wiring, not a dependency.
     */
    private val designs by lazy { SeedDesignCatalog(mapper, seed).all() }

    /** The controller catalogue, for the one facet that is a JOIN rather than a field. */
    private val boards by lazy { ControllerCatalog(mapper, controllers).all() }

    /** Which firmware runs on which chip family, and which does wireless. */
    private val rules by lazy { FirmwareCatalog(mapper, firmwares).rules }

    /** How many rows of each kind the last run wrote. Returned so a caller can log it. */
    data class Imported(val designs: Int, val facets: Int)

    fun importAll(): Imported = transactions.execute { _ ->
        val payloads = payloadsBySlug()
        val alive = aliveness()
        db.sql("DELETE FROM design").update()

        // LEFT OUT, NOT MARKED HIDDEN (#116). `design.status` could say 'hidden', but no
        // query filters on it -- DesignQueries reads `design_facet` as "live rows only" --
        // so a hidden row would still count on the rail. A row that is not here cannot.
        val withdrawn = unpublished.all()
        val published = designs.filter { it.core.slug.value !in withdrawn }

        var facets = 0
        for (design in published) {
            val slug = design.core.slug.value
            val payload = payloads[slug]
                ?: error("no seed document for `$slug`; the catalog and the file disagree")

            val designId = db.sql("INSERT INTO design (slug) VALUES (?) RETURNING id")
                .param(slug).query(Long::class.java).single()

            // seq = 1 and parent_id = NULL are written honestly rather than as constants
            // that happen to satisfy a CHECK: they stop being decorative the moment a
            // correction exists, and a rebuild must not be what clobbers them.
            val revisionId = db.sql(
                """INSERT INTO revision
                     (design_id, seq, parent_id, author_id, origin, summary, payload)
                   VALUES (?, 1, NULL, NULL, 'import', ?, ?) RETURNING id"""
            ).params(listOf(designId, "Imported from the seed", payload))
                .query(Long::class.java).single()

            db.sql("UPDATE design SET head_id = ? WHERE id = ?")
                .params(revisionId, designId).update()

            facets += writeFacets(designId, design, boards, rules)
            writeSearchRow(designId, revisionId, design, alive[design.core.sources
                .firstNotNullOfOrNull { repoOf(it.url) }])
        }

        // FTS5 external content tables are NOT self-maintaining, and `architecture.md`
        // §3.5 says keeping them in sync is the caller's job. `rebuild` is that job done
        // in one statement: it regenerates the index from the content table, so the index
        // cannot disagree with `design_search` the way hand-written inserts can.
        db.sql("INSERT INTO design_fts(design_fts) VALUES('rebuild')").update()
        db.sql("INSERT INTO design_name_trgm(design_name_trgm) VALUES('rebuild')").update()

        Imported(published.size, facets)
    } ?: error("the import transaction returned nothing, which should be impossible")

    /**
     * The seed file's own records, keyed by slug, each stamped with the document version.
     *
     * `v` is the DOCUMENT format's version and not the product's — the two share a field
     * name and nothing else. A v0.5 site writing `v: "1"` is correct; it means "the first
     * shape of this document", and it leaves room to bump the shape without implying the
     * product moved. `architecture.md` §3.4 tags it the same way, and the `doc_version`
     * generated column turns "how many rows are still on the old shape" into one query.
     */
    private fun payloadsBySlug(): Map<String, String> =
        seed.inputStream.use { mapper.readTree(it) }
            .associate { node ->
                val stamped = (node as ObjectNode).deepCopy().put("v", DOC_VERSION)
                node.get("slug").asString() to mapper.writeValueAsString(stamped)
            }

    /**
     * Repo aliveness, keyed by `owner/name` exactly as the seed cites it. Written by
     * `scripts/fetch_activity.py`, refreshed on its own clock rather than with the corpus.
     *
     * A repo the fetch could not answer for is simply absent from the map, and an absent
     * entry stays absent all the way to the column. That is the point: the schema says
     * NULL means "we have no star count", NOT zero stars.
     */
    private fun aliveness(): Map<String, Aliveness> =
        activity.inputStream.use { mapper.readTree(it) }.get("repos")
            .properties().associate { (repo, facts) ->
                repo to Aliveness(
                    stars = facts.get("stars").asInt(),
                    lastPush = facts.get("lastPush").asString(),
                    archived = facts.get("archived").asBoolean(),
                )
            }

    private data class Aliveness(val stars: Int, val lastPush: String, val archived: Boolean)

    /**
     * The filter and count path. Long and thin: one row per queryable value, so a design
     * offering two switch families is two rows and both are selectable.
     *
     * THE KEYS COME FROM `FacetKey`, which the query binder also reads. That shared list
     * is the `facets()` contract: a facet in the filter rail must be a key the projection
     * emits, and this project has already had the failure where they drift -- 11 facets
     * offered and never projected.
     *
     * THE VOCABULARY IS DELIBERATELY SMALL, and it is derived from the seed's own fields
     * rather than from `architecture.md`'s query examples — those query `mcu`, and ticket
     * 50 removed the MCU from socketed designs, which is 59% of the corpus. Getting this
     * set wrong is cheap and recoverable: the table is a projection, so widening it later
     * is a re-import rather than a migration.
     *
     * `num` carries the integer for numeric facets so a key count is both exactly
     * selectable ("34-key boards") and range-queryable, with `value` holding the same
     * number as text.
     */
    private fun writeFacets(
        designId: Long,
        design: Design,
        boards: List<ControllerBoard>,
        rules: FirmwareRules,
    ): Int {
        // Each facet projects its own rows -- `facet/`, one file per facet, has what
        // each writes and why. `FacetKey.entries` is every facet, exhaustively.
        val catalogue = Catalogue(boards, rules)
        val rows = FacetKey.entries.flatMap { key ->
            key.facet.project(design, catalogue).map { Triple(key.param, it.token, it.num) }
        }

        // Distinct because the PRIMARY KEY is (design_id, key, value): two layouts of the
        // same size, or a repeated licence, are one fact stated twice and not two rows.
        val distinct = rows.distinctBy { it.first to it.second }
        distinct.forEach { (key, value, num) ->
            db.sql("INSERT INTO design_facet (design_id, key, value, num) VALUES (?, ?, ?, ?)")
                .params(listOf(designId, key, value, num)).update()
        }
        return distinct.size
    }

    /**
     * The text-search row. `hay_a`/`hay_b`/`hay_c` are the weighted haystacks bm25 ranks
     * over — strongest to weakest.
     *
     * `stars` and `last_commit` are NULL when the fetch had no answer for the design's
     * repo — NULL means "we have no star count", NOT zero stars, and writing 0 would be
     * exactly the default-fill this project refuses. `archived` is different and is NOT
     * NULL in the schema: false is a real claim, and an unfetched repo is not archived as
     * far as anything here knows.
     */
    private fun writeSearchRow(
        designId: Long,
        revisionId: Long,
        design: Design,
        alive: Aliveness?,
    ) {
        val core = design.core
        db.sql(
            """INSERT INTO design_search
                 (design_id, revision_id, name, slug, hay_a, hay_b, hay_c, facets, keys,
                  stars, archived, last_commit, completeness)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).params(
            listOf(
                designId, revisionId, core.name.value, core.slug.value,
                "${core.name.value} ${core.slug.value}",
                // The summary, where the seed carries one. Empty stays the truthful value
                // for the 163 designs that have none: the column is NOT NULL because
                // every design has a summary SLOT, not because every design has a summary,
                // and an empty haystack contributes nothing to bm25 rather than matching
                // everything.
                design.extras.summary.knownOrNull().orEmpty(),
                (core.firmware.knownOrNull().orEmpty().map { FirmwareFacet.familySlug(it) } + core.sources.map { it.url })
                    .joinToString(" "),
                mapper.writeValueAsString(mapOf("keys" to core.layouts.map { it.keys.value })),
                core.layouts.maxOf { it.keys.value },
                alive?.stars,
                if (alive?.archived == true) 1 else 0,
                alive?.lastPush,
                // Sort-only -- see the V2 migration's own comment. `toRow()` recomputes
                // this independently for display, the same relationship `stars` has to
                // `RepoActivity`.
                design.completeness().percent,
            )
        ).update()
    }

    private companion object {
        /** The document shape's version. Not the product's — see [payloadsBySlug]. */
        const val DOC_VERSION = "1"

        private val GITHUB = Regex("""https://github\.com/([^/]+/[^/#?]+)""")

        /** `owner/name` from a source URL, matching how the fetch keys its output. */
        fun repoOf(url: String): String? =
            GITHUB.find(url)?.groupValues?.get(1)?.removeSuffix(".git")
    }
}

