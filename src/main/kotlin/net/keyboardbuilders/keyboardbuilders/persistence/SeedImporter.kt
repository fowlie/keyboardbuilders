package net.keyboardbuilders.keyboardbuilders.persistence

import net.keyboardbuilders.keyboardbuilders.domain.SplitLink
import net.keyboardbuilders.keyboardbuilders.domain.facetValue
import net.keyboardbuilders.keyboardbuilders.domain.connectivityFor
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRules
import net.keyboardbuilders.keyboardbuilders.catalog.FirmwareCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.ControllerBoard
import net.keyboardbuilders.keyboardbuilders.catalog.SeedDesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.completeness
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.Facets
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.License
import net.keyboardbuilders.keyboardbuilders.domain.KeyPitch
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import net.keyboardbuilders.keyboardbuilders.domain.smdRequired
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.hotswapParts
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
        val core = design.core
        val rows = mutableListOf<Triple<String, String, Int?>>()

        rows += when (val body = core.body) {
            is Body.Unibody -> Triple(FacetKey.Body.param, "unibody", null)
            is Body.Split -> Triple(FacetKey.Body.param, "split", body.halves.value)
            // `num` carries the half count for a split, so "three-piece" is queryable
            // without a second key. A one-hander has one half by definition, and which
            // hand is procurement rather than a property of the design, so it is not
            // folded into `split` — somebody filtering for a split does not want one.
            is Body.OneHanded -> Triple(FacetKey.Body.param, "one_handed", 1)
        }
        // Every design answers -- see `FacetKey.Link`. A split whose link nobody could
        // name projects nothing and lands in "Not recorded", which is honest about it.
        when (val body = core.body) {
            is Body.Split -> body.links.knownOrNull()?.forEach {
                rows += Triple(FacetKey.Link.param, it.token(), null)
            }
            else -> rows += Triple(FacetKey.Link.param, "one_piece", null)
        }
        core.layouts.forEach { rows += Triple(FacetKey.Keys.param, it.keys.value.toString(), it.keys.value) }
        core.switches.forEach {
            rows += Triple(
                FacetKey.SwitchFootprint.param,
                when (it) {
                    is SwitchFootprint.Mx -> "mx"
                    is SwitchFootprint.ChocV1 -> "choc_v1"
                    is SwitchFootprint.ChocV2 -> "choc_v2"
                    is SwitchFootprint.Alps -> "alps"
                    is SwitchFootprint.ChocMini -> "choc_mini"
                    is SwitchFootprint.KailhX -> "kailh_x"
                    // "Assigned by a human, never by the importer" — so the seed cannot
                    // produce one today. Projected rather than refused, because the day a
                    // human assigns one it must be selectable, and a `when` that threw
                    // here would make that a code change instead of a data change.
                    is SwitchFootprint.Bespoke -> "bespoke"
                },
                null,
            )
        }
        rows += when (val controller = core.controller) {
            // One row per footprint (#99): a PCB taking a Pro Micro or a Feather answers
            // both filters, the way a design on two firmwares answers both.
            is Controller.Socket -> controller.footprints.map {
                Triple(FacetKey.ControllerFootprint.param, it.name.snake(), null)
            }
            // NOT_APPLICABLE, not "soldered". This facet asks WHICH DEV-BOARD FOOTPRINT
            // the design takes, and a design with the chip on its own PCB does not take
            // one — the question does not apply rather than having "soldered" as an
            // answer. `contract.md` §3 makes that a first-class value above the divider,
            // rendered in the facet's own words: "MCU soldered on".
            //
            // The distinction is not pedantry. `not_applicable` is a HARD non-match
            // against every other value in its group, where a value called "soldered"
            // reads as one option among the sockets and would invite a rail that offers
            // "Pro Micro or soldered" as if those answered the same question.
            is Controller.Soldered ->
                listOf(Triple(FacetKey.ControllerFootprint.param, Facets.NOT_APPLICABLE, null))
        }
        rows += when (core.buildMethod) {
            is BuildMethod.Pcb -> Triple(FacetKey.BuildMethod.param, "pcb", null)
            is BuildMethod.Handwire -> Triple(FacetKey.BuildMethod.param, "handwire", null)
        }
        // Projected only when known, same discipline as `Pitch` below: a board the
        // scanner found nothing solderable on is ordinary ignorance and belongs in the
        // rail's "Not recorded" gap, not a fabricated `false`.
        core.buildMethod.smdRequired.knownOrNull()?.let {
            rows += Triple(FacetKey.SmdRequired.param, if (it) "yes" else "no", null)
        }
        // Projected only when measured. A design whose pitch could not be read is absent
        // from this key rather than carrying an "unknown" value: the sidebar counts a
        // "Not recorded" bucket from the gap between the facet's rows and the corpus,
        // so an explicit unknown would be counted twice.
        core.pitch.knownOrNull()?.let { measured ->
            rows += Triple(FacetKey.Pitch.param, when (measured) {
                KeyPitch.Mx -> "mx"
                KeyPitch.Choc -> "choc"
                is KeyPitch.Custom -> "other"
            }, null)
        }
        // Projected only when known: no record is the rail's "Not recorded" gap (#106).
        core.firmware.knownOrNull()?.forEach { rows += Triple(FacetKey.Firmware.param, it.familySlug(), null) }
        // PRESENCE ONLY, and the "Not recorded" bucket that falls out of it is HONEST
        // here in a way it briefly was not. This projected a `not_applicable` row for
        // designs whose boards matched no footprint name, on the reading that they had
        // been measured and found bare. They had not: the scan is a regex over footprint
        // NAMES and proves presence only — 11 of those 223 designs are contradicted by
        // their own QMK record, `bastardkb/dilemma` among them, which declares a Cirque
        // trackpad our board scan cannot see because the module hangs off a generic
        // pin header. So no evidence is ordinary ignorance and belongs in the gap.
        design.extras.extensions.knownOrNull()?.forEach {
            rows += Triple(FacetKey.Ext.param, it.name.lowercase(), null)
        }
        // THE PARTS THAT FIT, plus `any` for each board that takes one, and `no` for a
        // solder-only board: the pads prove that, so it is a value and not the gap (#107).
        design.extras.switchMounts.knownOrNull()?.hotswapParts()?.let { parts ->
            val tokens = if (parts.isEmpty()) listOf("no") else parts.map { it.token } + "any"
            tokens.forEach { rows += Triple(FacetKey.Hotswap.param, it, null) }
        }
        // Projected only when measured, same discipline as `SmdRequired` above -- but
        // unlike `Ext` just above, `no` here is a genuine measured
        // verdict rather than an absence of evidence, so it is written as an ordinary
        // value rather than folded into the "Not recorded" gap. See
        // `DesignExtras.splay`'s own doc comment for why the two facets differ.
        design.extras.splay.knownOrNull()?.let {
            rows += Triple(FacetKey.Splay.param, if (it) "yes" else "no", null)
        }
        // Same discipline as `splay` just above: a genuine measured verdict, not
        // evidence-only, so it is written as an ordinary value rather than folded into
        // the "Not recorded" gap. See `DesignExtras.stagger`'s own doc comment.
        design.extras.stagger.knownOrNull()?.let {
            rows += Triple(FacetKey.Stagger.param, it.name.lowercase(), null)
        }
        // Same discipline as `splay`: `no` is measured, so it is an ordinary value.
        design.extras.exposedComponents.knownOrNull()?.let {
            rows += Triple(FacetKey.ExposedComponents.param, if (it) "yes" else "no", null)
        }
        // Same discipline again: `measure_power_switch.py`'s `no` is measured.
        design.extras.powerSwitch.knownOrNull()?.let {
            rows += Triple(FacetKey.PowerSwitch.param, if (it) "yes" else "no", null)
        }
        rows += when (val licensing = core.licensing.hardware) {
            is LicenseState.Unstated ->
                // "all rights reserved" is a fact about the repo, so it is selectable
                // rather than absent. An absent licence and an unreadable one are
                // different, and only the first reaches here.
                listOf(Triple(FacetKey.License.param, "unstated", null))
            is LicenseState.Stated -> licensing.licenses.map {
                Triple(FacetKey.License.param, it.slug(), null)
            }
        }

        /*
         * THE ONE FACET THAT IS A JOIN, and the first in this method not read off a field
         * of `design`.
         *
         * "Can this keyboard be wireless" is not a property of the design -- for a
         * socketed design it is a property of the (design, controller) pair, and the
         * builder picks the controller. `Design.kt` records that ticket 12's `RadioProof`
         * tried to make it a field, ticket 50 removed `Mcu` from socketed designs, and
         * "`Wireless` could never be a design-level value for the majority case". So it is
         * computed here, against the catalogue, and stored as an ordinary facet value --
         * which is what lets every existing rail, count, relaxation and URL mechanism work
         * on it unchanged.
         *
         * NO ROW WHERE THE ANSWER IS NOT ESTABLISHED, so this behaves like every other
         * facet: `Connectivity.Unrecorded` projects null and the rail's `unknown`
         * anti-join puts those designs below the divider.
         *
         * That is the 2026-09-10 correction. It first shipped storing a third `maybe`
         * value and claiming no "Not recorded" tier -- a special case that existed only
         * because the facet was named after a verdict ("can this be wireless") rather than
         * after the thing. Renamed to `connectivity`, "maybe" stopped being a plausible
         * value and the absence became the honest projection. Same shape as `ext` above.
         */
        design.connectivityFor(boards, rules).facetValue()?.let {
            rows += Triple(FacetKey.Connectivity.param, it, null)
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
                (core.firmware.knownOrNull().orEmpty().map { it.familySlug() } + core.sources.map { it.url })
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

        /** `ProMicro` -> `pro_micro`, so a facet value reads the way a URL should. */
        fun String.snake(): String =
            replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_").lowercase()

        /**
         * The FAMILY, not the reference. `qmk:sofle/rev1` and `qmk:crkbd/rev1` are the
         * same facet value: somebody filtering by firmware wants "runs QMK", and the
         * path is a fact about this design rather than something to select on.
         */
        fun FirmwareRef.familySlug(): String = when (this) {
            is FirmwareRef.Qmk -> "qmk"
            is FirmwareRef.Zmk -> "zmk"
            is FirmwareRef.Kmk -> "kmk"
            is FirmwareRef.Custom -> "custom"
        }

        /**
         * `Licence` is a sealed interface rather than an enum, so the tail variant has
         * to be spelled out: `Other` carries text nobody enumerated, and it is slugged
         * as given rather than collapsed to "other", which would make two unrelated
         * bespoke licences look like one facet value.
         */
        fun License.slug(): String = when (this) {
            is License.Other -> spdxOrName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            else -> this::class.simpleName!!.snake()
        }
    }
}

/** The URL's word for a link, the same token `designs.json` carries. */
private fun SplitLink.token(): String = when (this) {
    SplitLink.Trrs -> "trrs"
    SplitLink.UsbC -> "usb_c"
    SplitLink.Ethernet -> "ethernet"
    SplitLink.MiniDin -> "mini_din"
    SplitLink.Bridge -> "bridge"
}
