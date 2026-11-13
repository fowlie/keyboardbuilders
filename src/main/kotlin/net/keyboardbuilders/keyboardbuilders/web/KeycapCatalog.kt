package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.CapFootprint
import net.keyboardbuilders.keyboardbuilders.domain.KeycapFit
import net.keyboardbuilders.keyboardbuilders.domain.KeycapStem
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * Which keycap profiles fit which `KeycapFit`, for Door B's KEYCAPS section (`ux.md`
 * §2.9, backlog #61).
 *
 * BUILT FROM `docs/research/keycap-profiles.md` VIA `seed/keycaps.toml`, exactly as
 * `ControllerCatalog` is built from its own research doc: the doc carries a primary
 * source per fact, the TOML carries the rows the build reads. Independent of the design
 * corpus, so its own file rather than a field on `designs.json` -- a keycap profile does
 * not change when a design is added.
 *
 * NO ROW IS MX-STEM AT 19.05 MM, and that is a finding rather than an omission. For an
 * MX cross stem at 19.05 mm the answer is the entire MX keycap market, which
 * `keycapFacts()` states as one sentence -- 151 of 324 built designs answered with no rows
 * at all. A catalogue is only the useful answer where the market is small enough to name:
 * the Choc-stem sets, and the two MX-stem sets cut for choc spacing (Choc v2 boards).
 *
 * THE PAIR IS THE POINT. Lookup is on `(stem, maxFootprint)` together and never on stem
 * alone: `choc_v1` splits across both cap footprints, because Choc-stem caps cut for
 * 19.05 mm spacing are a real product (research doc §2.3) and 23 corpus designs need
 * them. A stem-only catalogue would publish choc-spaced sets for every one of those.
 */
@Component
class KeycapCatalog(
    mapper: ObjectMapper,
    @Value("classpath:seed/keycaps.json") resource: Resource,
) {
    private val profiles: List<KeycapProfile> =
        resource.inputStream.use { mapper.readValue<List<SeedKeycapProfile>>(it) }
            .map { KeycapProfile(it.name, it.slug, it.stem.toStem(), it.caps.toCapFootprint(), it.url) }

    /**
     * Every catalogued profile matching this exact fit. Empty is never a reason to
     * suppress the section, only to state that nothing is catalogued for it -- `Alps` is
     * legitimately empty today, for the reason the research doc's §5 gives.
     *
     * `CapFootprint.Unusual` matches nothing by construction, and that is correct rather
     * than a missing case: no commercial profile is cut for a board's one-off spacing, and
     * `CapFootprint`'s own doc comment says so -- "which caps fit is the designer's to
     * state".
     */
    fun fitting(fit: KeycapFit): List<KeycapProfile> =
        profiles.filter { it.stem == fit.stem && it.caps == fit.maxFootprint }

    /** Whether any profile at all is catalogued for a stem, regardless of spacing. This is
     *  what separates the section's two empty states: nothing known about Alps caps at all,
     *  versus four Choc v1 sets catalogued of which none is cut for this board's spacing. */
    fun knowsStem(stem: KeycapStem): Boolean = profiles.any { it.stem == stem }
}

data class KeycapProfile(
    val name: String,
    /** Explicit data, not derived at runtime -- the discipline `seed/controllers.toml`
     *  states for its own slugs: a URL is a promise. */
    val slug: String,
    val stem: KeycapStem,
    val caps: CapFootprint,
    /** The research doc's own primary-source citation. Never null in the seed today, but
     *  nullable for the same reason `ControllerBoard.url` is: a row with no clean source
     *  to send a reader to is a real state, and a guessed link is not an option. */
    val url: String?,
)

/** Exhaustive rather than derived from `.name`, matching `SeedDesignCatalog.toFootprint()`:
 *  a new member is a compile error, not a silently unmatched string. */
private fun String.toStem(): KeycapStem = when (this) {
    "mx_cross" -> KeycapStem.MxCross
    "choc_v1" -> KeycapStem.ChocV1
    "alps" -> KeycapStem.Alps
    else -> throw IllegalArgumentException("unknown keycap stem '$this'")
}

/** `Unusual` is deliberately not a wire value. It carries a board's measured spacing, so
 *  it is something a DESIGN has and never something a PROFILE targets -- a profile cut for
 *  one board's one-off pitch is not a catalogue entry. */
private fun String.toCapFootprint(): CapFootprint = when (this) {
    "full" -> CapFootprint.Full
    "reduced" -> CapFootprint.Reduced
    else -> throw IllegalArgumentException("unknown cap footprint '$this'")
}

private data class SeedKeycapProfile(
    val name: String,
    val slug: String,
    val stem: String,
    val caps: String,
    val url: String? = null,
)
