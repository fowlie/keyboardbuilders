package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.Extension
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.KeyPitch
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import org.springframework.stereotype.Component

/**
 * "Boards like this" — `ux.md` §3.6, backlog #24's sibling gap.
 *
 * DERIVED FROM FACET OVERLAP, NOT A CURATED RELATION — the doc is explicit that a
 * relation here would need editorial upkeep this project has repeatedly declined to
 * take on for a single-maintainer, ~5h/week catalogue. Everything this ranks is a fact
 * already recorded for an unrelated reason (switches, body, controller, pitch,
 * extensions), so the strip costs nothing to keep correct as the corpus changes.
 *
 * RARER FACETS WEIGH MORE, which `ux.md` states as a requirement and not a suggestion:
 * "rank by shared-facet count with rarer facets weighted higher." Weighting by a FIXED
 * per-facet-type number would get this backwards on this specific corpus — MX is the
 * overwhelming majority switch family, so "shares MX" is barely evidence of anything,
 * while "shares Choc" or "shares a bespoke switch" says a great deal. The weight is
 * therefore 1 / (how many designs carry that exact value), computed once against the
 * live corpus rather than guessed at — the same "measure before deciding" discipline
 * every other facet in this codebase already follows.
 *
 * AN UNEXPLAINED STRIP IS NOISE, ux.md's own words, so every match carries a `reason`:
 * the one or two highest-weighted facets that matched, named in words, plus a size
 * comparison when key counts differ. Nothing here says "wireless" or rates difficulty —
 * neither is a modelled fact yet (see backlog #18, #59), and inventing one to make a
 * punchier sentence would be exactly the wrong-value-that-looks-right failure this
 * project exists to avoid.
 */
@Component
class RelatedDesigns(private val catalog: DesignCatalog) {

    private val all: List<Design> = catalog.all()

    // How many designs carry each facet value, counted once at construction. A value
    // that appears on every design (or on only this one) contributes no distinguishing
    // weight either way, which `1 / count` already expresses without a special case.
    private val switchCounts: Map<SwitchFootprint, Int> =
        all.flatMap { it.core.switches }.groupingBy { it }.eachCount()
    private val bodyKindCounts: Map<String, Int> =
        all.map { it.core.body.kind() }.groupingBy { it }.eachCount()
    private val controllerCounts: Map<Any, Int> =
        all.flatMap { it.core.controller.footprints() }.groupingBy { it }.eachCount()
    private val pitchCounts: Map<KeyPitch, Int> =
        all.mapNotNull { it.core.pitch.knownOrNull() }.groupingBy { it }.eachCount()
    private val extensionCounts: Map<Extension, Int> =
        all.flatMap { it.extras.extensions.knownOrNull().orEmpty() }.groupingBy { it }.eachCount()

    /** Up to [limit] other designs, ranked by weighted facet overlap, richest match first.
     *  [exclude] drops designs the page already shows elsewhere ("Derived from X"), BEFORE
     *  the limit, so the strip stays full rather than losing a card to the duplicate. */
    // CANDIDATES ARE READ PER CALL, the weights once: an author can unpublish a design
    // while the app runs (#116), and a stale weight is harmless where a stale candidate
    // links to a page that answers 410.
    fun similarTo(design: Design, limit: Int = 3, exclude: Set<Slug> = emptySet()): List<RelatedDesign> =
        catalog.all().asSequence()
            .filter { it.core.slug != design.core.slug && it.core.slug !in exclude }
            .map { candidate -> candidate to score(design, candidate) }
            .filter { (_, scored) -> scored.weight > 0.0 }
            .sortedWith(
                compareByDescending<Pair<Design, Scored>> { it.second.weight }
                    .thenBy { it.first.core.name.value.lowercase() }
            )
            .take(limit)
            .map { (candidate, scored) -> RelatedDesign(candidate, scored.reason(design, candidate)) }
            .toList()

    private data class Scored(val weight: Double, val matches: List<Match>)
    private data class Match(val weight: Double, val label: String)

    private fun score(design: Design, candidate: Design): Scored {
        val matches = buildList {
            for (fp in design.core.switches.intersect(candidate.core.switches)) {
                add(Match(1.0 / (switchCounts[fp] ?: 1), "same switches"))
            }
            val kind = design.core.body.kind()
            if (kind == candidate.core.body.kind()) {
                add(Match(1.0 / (bodyKindCounts[kind] ?: 1), "same layout"))
            }
            // Per shared footprint, like switches above. Two PCBs that each offer a Pro
            // Micro or a Feather match twice, which they would earn.
            for (footprint in design.core.controller.footprints().intersect(candidate.core.controller.footprints())) {
                add(Match(1.0 / (controllerCounts[footprint] ?: 1), "same controller"))
            }
            val pitch = design.core.pitch.knownOrNull()
            if (pitch != null && pitch == candidate.core.pitch.knownOrNull()) {
                add(Match(1.0 / (pitchCounts[pitch] ?: 1), "same key spacing"))
            }
            val shared = design.extras.extensions.knownOrNull().orEmpty()
                .intersect(candidate.extras.extensions.knownOrNull().orEmpty())
            for (ext in shared) {
                add(Match(1.0 / (extensionCounts[ext] ?: 1), "both have ${ext.article()}"))
            }
        }
        return Scored(matches.sumOf { it.weight }, matches)
    }

    /**
     * The one or two facts that earned this match, best first, plus a size comparison
     * when it says something a shared facet does not already say. Capped at two scored
     * reasons — `ux.md`'s own mock never states more than that, and a longer list reads
     * as a report rather than a reason.
     */
    private fun Scored.reason(design: Design, candidate: Design): String {
        val top = matches.sortedByDescending { it.weight }.map { it.label }.distinct().take(2)
        val theirs = candidate.core.layouts.maxOf { it.keys.value }
        val mine = design.core.layouts.maxOf { it.keys.value }
        val size = when {
            theirs < mine -> "fewer keys"
            theirs > mine -> "more keys"
            else -> null
        }
        return (top + listOfNotNull(size)).joinToString(", ")
    }

    private fun Body.kind() = when (this) {
        Body.Unibody -> "unibody"
        is Body.Split -> "split"
        is Body.OneHanded -> "one-handed"
    }

    private fun Controller.footprints() = (this as? Controller.Socket)?.footprints.orEmpty()

    private fun Extension.article() = when (this) {
        Extension.Encoder -> "an encoder"
        Extension.Display -> "a display"
        Extension.Speaker -> "a speaker"
        Extension.Haptic -> "haptic feedback"
        Extension.Trackball -> "a trackball"
        Extension.Trackpad -> "a trackpad"
        Extension.Joystick -> "a joystick"
    }
}

data class RelatedDesign(val design: Design, val reason: String)
