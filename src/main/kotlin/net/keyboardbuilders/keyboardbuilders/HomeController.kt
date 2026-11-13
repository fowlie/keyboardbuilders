package net.keyboardbuilders.keyboardbuilders

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.Facets
import net.keyboardbuilders.keyboardbuilders.persistence.DesignQueries
import net.keyboardbuilders.keyboardbuilders.web.DesignCard
import net.keyboardbuilders.keyboardbuilders.web.Head
import net.keyboardbuilders.keyboardbuilders.web.JsonLd
import net.keyboardbuilders.keyboardbuilders.web.Photos
import net.keyboardbuilders.keyboardbuilders.web.RepoActivity
import net.keyboardbuilders.keyboardbuilders.web.Silhouettes
import net.keyboardbuilders.keyboardbuilders.web.SiteUrl
import net.keyboardbuilders.keyboardbuilders.web.describe
import net.keyboardbuilders.keyboardbuilders.web.toCard
import net.keyboardbuilders.keyboardbuilders.web.toRow
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.LocalDate

/**
 * One answer to the page's question: words a newcomer already has, and the facet query
 * they stand for. [params] rather than a finished URL so the same pairs build the link
 * AND the count, and the two cannot drift apart.
 */
data class Answer(
    val label: String,
    val blurb: String,
    val params: List<Pair<String, String>>,
    /** Drawn instead of a board, for an answer no board outline can show. */
    val icon: AnswerIcon? = null,
) {
    val href: String get() = "/designs?" + params.joinToString("&") { (k, v) -> "$k=$v" }
}

/**
 * WHY SOME ANSWERS GET AN ICON: a silhouette is a board seen from above. Seen from
 * above, a wireless board is any board, and a slim switch looks exactly like a tall
 * one -- so a drawing on those tiles shows nothing about the answer.
 */
enum class AnswerIcon { Wireless, LowProfile }

/** An [Answer] as drawn: how many designs it leads to, and one of them pictured. */
data class AnswerTile(val answer: Answer, val count: Int, val silhouette: String?)

@Controller
class HomeController(
    private val catalog: DesignCatalog,
    private val queries: DesignQueries,
    private val silhouettes: Silhouettes,
    private val activity: RepoActivity,
    private val site: SiteUrl,
    private val photos: Photos,
    private val hearts: net.keyboardbuilders.keyboardbuilders.persistence.Hearts,
) {

    /**
     * ONE QUESTION, TWO WAYS TO ANSWER IT. "What kind of keyboard are you after?", a search
     * box for the reader who knows a name, and four tiles for the reader who does not.
     * Chosen 2026-09-25 from five prototypes on branch `prototype/front-page`: a search box
     * alone was clean but left a newcomer nothing to type, and a photo-led page read cheap.
     *
     * `ux.md` §2.10's three doors are still two short -- Door C (`/build-with`) is out of
     * scope by map ticket 29 and the contribute door waits on the report surface -- and a
     * dead link is still worse than a missing one.
     *
     * THE DAILY STRIP STAYS, BELOW THE FOLD, for the crawler: this is the site's
     * highest-authority page, and before the strip it linked to exactly one other page.
     * The owner kept it when choosing the new layout rather than drop those eight links.
     */
    @GetMapping("/")
    fun index(model: Model): String {
        val total = catalog.all().size
        model.addAttribute("total", total)
        model.addAttribute("answers", answerTiles())
        model.addAttribute("featured", featured())
        model.addAttribute("websiteLd", JsonLd.of(mapOf(
            "@context" to "https://schema.org",
            "@type" to "WebSite",
            "name" to "Keyboard Builders",
            "url" to site.of("/"),
            "description" to "A searchable catalog of open-source keyboard designs, read from their own files.",
        )))
        model.addAttribute("head", Head(
            // What the site IS, in the words a search uses (2026-10-02). "Home" told a
            // search engine nothing, and an Android install took it as the app's name.
            title = "Open-source keyboard designs you can build",
            description = describe(
                "$total open-source keyboard designs, read from their own board files:",
                "key count, switch footprint, controller, firmware and license for every one.",
            ),
            canonical = site.of("/"),
            // The site's own lockup, not one of the eight designs below. Which eight
            // those are is reshuffled daily, so a card drawn from one would picture a
            // different keyboard depending on the day a link happened to be pasted.
            // No `thumbnail`: Google's own guidance names a site logo as the generic
            // image a `primaryImageOfPage` should avoid, and this page's only picture
            // is exactly that logo.
            image = site.of("/og.png"),
        ))
        return "index"
    }

    /**
     * Each answer with its live count and a picture of one design it leads to.
     *
     * THE COUNT IS LIVE, where the old entry points deliberately printed none: those were
     * written-in words, and a number written in beside them would have gone stale on the
     * next import. This one is computed from the same query the link runs, per request.
     *
     * NO TWO TILES PICTURE THE SAME REPO. Stars-first ranking puts the same boards on top
     * of most answers -- a Sofle is split, low-profile AND wireless -- so taking each
     * answer's first drawn design gave three tiles of one keyboard. Tiles claim repos in
     * order, and a later tile takes its next-best design instead.
     */
    private fun answerTiles(): List<AnswerTile> {
        val bySlug = catalog.all().associateBy { it.core.slug.value }
        val pictured = mutableSetOf<String>()
        return ANSWERS.map { answer ->
            val params = answer.params.groupBy({ it.first }, { it.second })
            val slugs = queries.slugsMatching(Facets.parse(params)) ?: emptyList()
            val picture = if (answer.icon != null) null else slugs.asSequence()
                .mapNotNull { bySlug[it] }
                .firstOrNull { silhouettes.isDrawn(it.core.slug.value) && it.repo() !in pictured }
                ?.also { pictured.add(it.repo()) }
            AnswerTile(answer, slugs.size, picture?.toRow(silhouettes)?.silhouette)
        }
    }

    /**
     * Six designs, at most one per repository, each with a build photo and at least
     * [MIN_FEATURED_STARS] stars.
     *
     * PHOTOS AND A STAR FLOOR, owner's calls of 2026-09-26, when the strip became photo
     * cards. A design with no photo is skipped rather than drawn, and an UNRECORDED star
     * count is skipped too -- unknown does not show ten. Measured that day: about 77
     * repos qualify, so six a day repeat roughly every two weeks.
     *
     * ONE PER REPO IS THE WHOLE FUNCTION. The default order is `design_search_rank`, which
     * is stars-first, and a star count belongs to a REPO rather than to a design — so
     * same-repo siblings arrive as a block. Taken raw the first eight are four Sofles, two
     * Lily58s and two others, a strip that says the catalogue holds about three keyboards.
     *
     * Ticket 23 settled the same clustering for the RESULT LIST and ruled it correct there,
     * because a list is exhaustive and a reader is scanning it for one thing. A shop window
     * is neither, so the rule does not carry across.
     *
     * A MACROPAD IS NOT EXCLUDED FROM THE CATALOGUE, only from these eight. Reported
     * directly: `scotto-modules`, 6 keys, was one of the shop window's first impressions
     * of the whole site. Stars-first ranking has no reason to prefer a full keyboard over
     * a macropad, so nothing before this stopped one leading the window. `MIN_FEATURED_KEYS`
     * draws the line at "a macropad" rather than at any particular size class, matching
     * where the corpus itself splits: 40 of 325 designs sit under 16 keys, then usage
     * climbs straight into ordinary keyboard territory.
     *
     * RESHUFFLED DAILY, same eight all day: `queries.allRankedShuffled` seeds on the
     * calendar day rather than the request, so the window changes date to date but not
     * request to request.
     */
    private fun featured(): List<DesignCard> {
        val bySlug = catalog.all().associateBy { it.core.slug.value }
        val seen = mutableSetOf<String>()
        // AND ONE PER NAME, 2026-09-26: `sofle-v1` and `sofle-pico` are two repos, so one
        // per repo let both into the same day's strip -- two cards reading "Sofle", which
        // is the near-duplicate this rule exists to stop. HomeTest asserts it by the slug
        // stem because that is what a reader sees, and the rule now checks the same thing.
        val names = mutableSetOf<String>()
        return queries.allRankedShuffled(LocalDate.now().dayOfYear)
            .mapNotNull { bySlug[it] }
            .filter { it.core.layouts.maxOf { l -> l.keys.value } >= MIN_FEATURED_KEYS }
            .filter { photos.of(it.core.slug.value) != null }
            .filter { (activity.starsFor(it) ?: 0) >= MIN_FEATURED_STARS }
            .filter { design ->
                val name = design.core.slug.value.substringBefore('-')
                design.repo() !in seen && name !in names &&
                    seen.add(design.repo()) && names.add(name)
            }
            .take(FEATURED)
            .let { featured ->
                val counts = hearts.forDesigns(featured)
                featured.map { it.toCard(it.toRow(silhouettes, activity, hearts = counts[it.core.slug.value]), photos, silhouettes) }
            }
    }

    /** The repo a design belongs to, or its own slug where it cites none. */
    private fun Design.repo(): String =
        core.sources.firstOrNull()?.url ?: core.slug.value

    private companion object {
        /** Two rows of three on a laptop, one column on a phone. */
        const val FEATURED = 6

        /** The owner's floor for the shop window: "they should have at least 10 GH stars". */
        const val MIN_FEATURED_STARS = 10

        /** See `featured()`'s doc comment: the corpus's own gap between macropad and
         * keyboard sits at 16 keys, not at a number chosen to hit a target count. */
        const val MIN_FEATURED_KEYS = 16

        /**
         * The answers to "what kind of keyboard are you after?", replacing both the row of
         * entry-point words and backlog #79's starting points.
         *
         * #79'S LESSON CARRIES OVER: a label built from facet names ("Pro Micro, PCB
         * assembly") is recall, and only works for a reader who already knows the terms.
         * These are shape words a newcomer has, and each blurb states what the query
         * narrows by rather than who the board suits -- this catalogue records no fact
         * about difficulty or suitability (#18, open).
         *
         * THE BLURB NAMES THE NARROWING where the label alone would overclaim: "One piece"
         * is 60-90 keys, 73 of the 205 unibody designs -- the ordinary-sized board a
         * newcomer means, not every macropad that happens to be one piece. The blurb says
         * 60 to 90 so the tile does not claim all 205.
         *
         * FOUR, NOT SIX (owner's call, 2026-09-26): "Tiny" and "Full-size" were dropped.
         * Both were size answers, and size is one click away in the rail.
         *
         * BOTH SIDES OF THE CORPUS, split and unibody, so a reader who does not want an
         * ergonomic split has as direct an answer as one who does.
         *
         * NONE IS EMPTY, and `HomeTest` follows each link to hold that: #41 found over a
         * third of two-facet combinations match nothing.
         *
         * WHAT WAS DROPPED: the "3x5+3" doorway, which taught the shorthand grammar
         * (`ux.md` §2.2). It still works in the search box; it just has no place to be
         * seen on this page now.
         */
        val ANSWERS = listOf(
            Answer("Split in two", "One half per hand, shoulders open", listOf("body" to "split")),
            Answer("One piece", "A single board of 60 to 90 keys, like most keyboards",
                listOf("body" to "unibody", "keys" to "60-90")),
            Answer("Low-profile", "Kailh Choc switches, a slimmer board",
                listOf("switch_footprint" to "choc_v1"), AnswerIcon.LowProfile),
            Answer("Wireless", "No cable between you and the computer",
                listOf("connectivity" to "wireless"), AnswerIcon.Wireless),
        )
    }
}
