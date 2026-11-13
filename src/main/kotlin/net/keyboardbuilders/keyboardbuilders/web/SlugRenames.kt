package net.keyboardbuilders.keyboardbuilders.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * What a slug used to name. Forgejo #38.
 *
 * A design's slug is drafted from its board's own directory and normalised by the
 * person confirming the row, so a rename is a normal event in this project rather than
 * an accident -- and ticket 07
 * decision 8 settled what it costs: *"slugs are mutable and old ones keep resolving. A
 * machine-guessed name is a first draft too, so a rename leaves a redirect rather than
 * a dead link."* Two comments in the repo claimed that was already implemented. None of
 * it was, and `/designs/3-w6` was a 404 for four days.
 *
 * `seed/renames.toml` is the person's record of each rename, with the commit that
 * caused it; `scripts/build_seed.py` checks that file and emits `seed/renames.json` as
 * a flat `was -> now` map. The checks are why this class can be a bare lookup: the
 * build refuses a destination that publishes nothing, refuses a former slug that is
 * live, and resolves chains, so **every value here is one hop and names a design that
 * exists.** A removal is recorded in the TOML as `now = false` and emitted to no map at
 * all -- those pages were wrong, and a 404 is the right answer rather than a redirect
 * to a guess.
 *
 * NOT PART OF `DesignCatalog`, deliberately. That interface exists as the seam SQLite
 * plugs into, and this data will never come from SQLite: v0.5 rebuilds the database
 * from the seed on every boot, so the seed is the source of truth whatever the schema
 * does. Keeping it out leaves the seam narrow -- at the price that each caller must
 * remember the redirect, which is why `OgImageController` is a live question rather
 * than an oversight.
 */
@Component
class SlugRenames(
    mapper: ObjectMapper,
    @Value("classpath:seed/renames.json") resource: Resource,
) {
    private val moved: Map<String, String> =
        resource.inputStream.use { mapper.readValue<Map<String, String>>(it) }

    fun currentFor(former: String): String? = moved[former]

    /** Every former slug and the one it is now, for the boot-time heart carry (#107). */
    fun all(): Map<String, String> = moved
}
