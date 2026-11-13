package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.KeyPitch
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Stagger
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.HotswapPart
import net.keyboardbuilders.keyboardbuilders.domain.hotswapParts
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import net.keyboardbuilders.keyboardbuilders.persistence.DesignQueries
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView

/*
 * LEARN PAGES -- backlog #108, the owner's idea (2026-10-02): *"wouldn't it be cool to have
 * this kind of explanations of technical terms on a help/guide/learn page or something?
 * with illustrations?"*
 *
 * One evergreen page per term a spec line uses, written for the newcomer who typed the
 * question into a search engine. Each one does three things, and the split between Kotlin
 * and template follows them:
 *
 *  1. EXPLAINS, in prose and an inline diagram. That lives in the topic's own template and
 *     nowhere else, the rule `about.kte` set: editing the page is editing that one file.
 *  2. LISTS THE DESIGNS THAT HAVE THE PROPERTY, computed from the corpus at request time,
 *     so the page grows with the catalogue instead of going stale beside it. That is
 *     [LearnPages], and it is Kotlin because every `when` over a domain type lives in the
 *     web layer's Kotlin, never in a template (`DesignPresentation.kt`'s rule).
 *  3. SAYS HOW MUCH IT DOES NOT KNOW. Where a property is recorded for only part of the
 *     corpus, the page states how many designs are unrecorded. A list of the known cases
 *     with no remainder would read as the whole answer, and Unknown must never read as no.
 */

/**
 * The catalogue of topics: the one list the index, the 404 and the sitemap all read, so a
 * topic cannot be linked from one and missing from another.
 *
 * [title] is the page title AND the article headline, phrased the way people search for
 * it. [description] is the search snippet, written to answer the question on its own.
 * [template] is the jte file under `learn/`, camelCase because jte names a class after it.
 */
enum class LearnTopic(val slug: String, val title: String, val description: String, val template: String) {
    KeySpacing(
        "key-spacing",
        "Key spacing: 19.05 mm MX and 18 × 17 mm Choc",
        "Key spacing is the distance from the center of one key to the center of the next. MX boards use 19.05 mm " +
            "(0.75 in, called 1u). Boards made only for Choc switches usually use 18 × 17 mm.",
        "keySpacing",
    ),
    ChocSwitches(
        "choc-switches",
        "Choc v1, Choc v2 and Mini Choc switches",
        "Kailh's low-profile switches: Choc v1 (PG1350), Choc v2 (PG1353) with a cross stem " +
            "like MX, and the smaller Mini Choc (PG1232). A board takes only the one it was drilled for.",
        "chocSwitches",
    ),
    ReversiblePcb(
        "reversible-pcb",
        "Reversible PCBs for split keyboards",
        "A reversible PCB is one board design that makes both halves of a split keyboard: " +
            "build one half, then flip the next board over for the other.",
        "reversiblePcb",
    ),
    Hotswap(
        "hotswap",
        "Hotswap sockets, Mill-Max or solder",
        "A hotswap board lets you swap switches without soldering, using Kailh hotswap " +
            "sockets or Mill-Max sockets. Without either, every switch is soldered in.",
        "hotswap",
    ),
    IoExpander(
        "io-expander",
        "IO expanders in split keyboards",
        "An IO expander is a chip that lets one controller read both halves of a split " +
            "keyboard over I2C, so the other half needs no controller of its own.",
        "ioExpander",
    ),
    Stagger(
        "stagger",
        "Ortholinear, row stagger and column stagger",
        "Stagger is how a keyboard's keys are shifted against their neighbors: rows on an " +
            "ordinary keyboard, columns on many ergonomic boards, nothing on an ortholinear grid.",
        "stagger",
    ),
    ;

    companion object {
        fun of(slug: String): LearnTopic? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * The designs known to scan one half through an IO expander, with the chip and where that
 * was read. HAND-KEPT, and the one list on these pages that is: no corpus field records an
 * expander, and a name-matching scan for one would prove presence and never absence. Each
 * entry is a design whose `seed/bodies.toml` note names the chip from the design's own
 * source; add one only on the same footing. `LearnTest` holds every slug to a design the
 * catalog serves.
 */
val IO_EXPANDER_EXAMPLES: List<Pair<String, String>> = listOf(
    "pando" to "PCA9555, named in the design's own description",
    "3w6" to "TCA9555, named in its README",
) + listOf(
    "ferris-0-1-base", "ferris-0-1-compact", "ferris-0-1-high", "ferris-0-1-low",
    "ferris-0-2-bling", "ferris-0-2-compact", "ferris-0-2-high", "ferris-0-2-mini",
).map { it to "MCP23017, in QMK's Ferris firmware" }

/** How many cards a learn page's list draws. The owner, 2026-10-04: "the listing of
 *  keyboards that has X is way too long! [...] like 3 designs for each filter would be
 *  enough." The rest is one link away, in the catalog's own filter. */
const val LEARN_SHOWN = 3

/**
 * Which designs a list draws: the PICTURED ones first, then the rest, each group in
 * [ranked]'s order, and no more than [n].
 *
 * [ranked] is already the site's own order (`DesignQueries.allRanked`, what /designs opens
 * on), so this invents no ranking of its own. It only lifts a card with a picture over a
 * card with a drawing, because three cards are a shop window and a photo is what sells
 * the property. A boolean, not a count: a missing photo demotes a design, it never
 * promotes one, so an unrecorded field cannot climb the list.
 */
fun <T> learnShown(ranked: List<T>, n: Int = LEARN_SHOWN, pictured: (T) -> Boolean): List<T> =
    (ranked.filter(pictured) + ranked.filterNot(pictured)).take(n)

/** One card in a list, with the line saying why it is there where the list has one (a
 *  design's spacing, the chip an example uses) -- `card.kte`'s `reason`. */
data class LearnCard(val card: DesignCard, val note: String? = null)

/**
 * One live list on a learn page: a heading, the cards the page draws, how many designs the
 * list holds in all, and where that whole set lives in the catalogue's own filters, when a
 * filter for it exists.
 *
 * The cards are the site's own (`designs/card.kte`, "strip-card"), the home page's and a
 * family page's, so a learn page draws a design the way everything else does.
 */
data class LearnList(val heading: String, val cards: List<LearnCard>, val total: Int, val seeAll: String? = null)

/**
 * Everything one topic page prints beyond its prose. [lists] and [counts] are keyed by
 * names the topic's own template uses, and [list]/[count] fail loudly on a key the builder
 * never filled -- a typo there should break the test that renders the page, not print a
 * zero that reads like a finding.
 */
data class LearnPage(
    val topic: LearnTopic,
    val lists: Map<String, LearnList>,
    val counts: Map<String, Int>,
    /** schema.org TechArticle, strict JSON via [JsonLd]. */
    val articleLd: String,
    /** schema.org BreadcrumbList: Learn, then this topic. */
    val breadcrumbLd: String,
) {
    fun list(key: String): LearnList = lists.getValue(key)
    fun count(key: String): Int = counts.getValue(key)
}

/**
 * Builds each topic's live lists from the corpus. A component rather than a function on
 * the controller so the ranking, the silhouettes and the star counts are injected once.
 */
@Component
class LearnPages(
    private val catalog: DesignCatalog,
    private val queries: DesignQueries,
    private val silhouettes: Silhouettes,
    private val activity: RepoActivity,
    private val site: SiteUrl,
    private val photos: Photos,
    private val renders: Renders,
) {
    /** [renderFirst] is /settings' "which picture first", read as `FamilyController` reads
     *  it: a reader who chose renders gets each card's PCB render where it has one. */
    fun build(topic: LearnTopic, renderFirst: Boolean = false): LearnPage {
        val all = catalog.all()
        // THE SITE'S OWN ORDER, as `toDevBoardPage` uses it: the boards people actually
        // build come first, and a learn page cannot disagree with /designs about which
        // board is more prominent. A design the ranking does not hold sorts last by name.
        val rank = queries.allRanked().withIndex().associate { (at, slug) -> slug to at }
        val ranked = all.sortedWith(compareBy<Design>({ rank[it.core.slug.value] ?: Int.MAX_VALUE }, { it.core.name.value }))

        fun render(d: Design) = if (renderFirst) renders.of(d.core.slug.value) else null
        // The picture the card will actually show: the render where one was chosen and
        // exists, else the build photo. A drawing alone is not a picture here.
        fun pictured(d: Design) = render(d) != null || photos.of(d.core.slug.value) != null

        fun list(heading: String, designs: List<Design>, seeAll: String? = null, note: (Design) -> String? = { null }) =
            LearnList(
                heading,
                learnShown(designs, pictured = ::pictured).map { d ->
                    LearnCard(d.toCard(d.toRow(silhouettes, activity), photos, silhouettes, render = render(d)), note(d))
                },
                designs.size,
                seeAll,
            )

        val (lists, counts) = when (topic) {
            LearnTopic.KeySpacing -> {
                val pitch = { d: Design -> (d.core.pitch as? Recorded.Known)?.value }
                val custom = ranked.filter { pitch(it) is KeyPitch.Custom }
                mapOf(
                    // The number on each card is the point of the list, so it rides along.
                    "custom" to list("Designs at a non-standard spacing", custom, "/designs?pitch=other") { d ->
                        (pitch(d) as? KeyPitch.Custom)?.let { "keys ${it.spacing} apart" }
                    },
                ) to mapOf(
                    "mx" to all.count { pitch(it) == KeyPitch.Mx },
                    "choc" to all.count { pitch(it) == KeyPitch.Choc },
                    "custom" to custom.size,
                    "unrecorded" to all.count { it.core.pitch is Recorded.Unknown },
                    "total" to all.size,
                )
            }
            LearnTopic.ChocSwitches -> {
                fun having(footprint: SwitchFootprint) = ranked.filter { footprint in it.core.switches }
                val v1 = having(SwitchFootprint.ChocV1)
                val v2 = having(SwitchFootprint.ChocV2)
                val mini = having(SwitchFootprint.ChocMini)
                val x = having(SwitchFootprint.KailhX)
                mapOf(
                    "v1" to list("Choc v1 designs", v1, "/designs?switch_footprint=choc_v1"),
                    "v2" to list("Choc v2 designs", v2, "/designs?switch_footprint=choc_v2"),
                    "mini" to list("Mini Choc designs", mini, "/designs?switch_footprint=choc_mini"),
                    "x" to list("Kailh X designs", x, "/designs?switch_footprint=kailh_x"),
                ) to emptyMap()
            }
            LearnTopic.ReversiblePcb -> {
                // `Body.Split.reversible` is all the corpus records: yes, no, or nobody has
                // said. It does NOT record HOW a board is reversible, so the page names the
                // methods in prose with checked example boards and splits its lists by the
                // answer alone. Backlog #108 asks for a by-method list once the field can
                // carry one; until then, a list claiming a method would be a guess.
                val splits = ranked.mapNotNull { d -> (d.core.body as? Body.Split)?.let { d to it.reversible } }
                val yes = splits.filter { it.second == Recorded.Known(true) }.map { it.first }
                val no = splits.filter { it.second == Recorded.Known(false) }.map { it.first }
                mapOf(
                    // No catalog filter reads `reversible`, so these link nothing and the
                    // template states the count instead.
                    "yes" to list("Recorded as one reversible PCB", yes),
                    "no" to list("Recorded as two different PCBs", no),
                ) to mapOf(
                    "splits" to splits.size,
                    "unknown" to splits.count { it.second is Recorded.Unknown },
                    // The prose names the Cradio Z lp's mirrored controller holes, read off its
                    // board file; until the corpus records its answer it is in neither list,
                    // and the page says so. 1 once it is recorded.
                    "cradioZLpRecorded" to splits.count { (d, r) -> d.core.slug.value == "cradio-z-lp" && r is Recorded.Known },
                )
            }
            LearnTopic.Hotswap -> {
                // The parts a board takes, read off its PADS (`SwitchMount`), so a solder-only
                // board is a measured answer and the unread designs are a separate count --
                // the same three states the design page's Hotswap line prints.
                val parts = { d: Design -> d.extras.switchMounts.knownOrNull()?.hotswapParts() }
                fun taking(part: HotswapPart) = ranked.filter { parts(it)?.contains(part) == true }
                val solderOnly = ranked.filter { parts(it)?.isEmpty() == true }
                mapOf(
                    "kailh" to list("Takes Kailh hotswap sockets", taking(HotswapPart.KailhSocket), "/designs?hotswap=kailh"),
                    "millmax" to list("Takes Mill-Max sockets", taking(HotswapPart.MillMax), "/designs?hotswap=millmax"),
                    "solder" to list("Solder only", solderOnly, "/designs?hotswap=no"),
                ) to HotswapPart.entries.associate { it.token to taking(it).size } + mapOf(
                    "no" to solderOnly.size,
                    // Pads for both Kailh shapes, so "a board has one or the other" is not
                    // what the corpus records.
                    "bothSockets" to all.count {
                        it.extras.switchMounts.knownOrNull()?.containsAll(setOf(SwitchMount.MxSocket, SwitchMount.ChocSocket)) == true
                    },
                    "unread" to all.count { it.extras.switchMounts is Recorded.Unknown },
                    "total" to all.size,
                )
            }
            LearnTopic.Stagger -> {
                // Read off each board's own key positions (`DesignExtras.stagger`), so all
                // three are measured answers and Unknown is the designs nobody has measured:
                // a count of its own, never folded into "ortho".
                fun kind(k: Stagger) = ranked.filter { it.extras.stagger == Recorded.Known(k) }
                val lists = Stagger.entries.associate { k ->
                    val param = k.name.lowercase()
                    param to list(
                        when (k) {
                            Stagger.Column -> "Column-staggered designs"
                            Stagger.Row -> "Row-staggered designs"
                            Stagger.Ortho -> "Ortholinear designs"
                        },
                        kind(k),
                        "/designs?${FacetKey.Stagger.param}=$param",
                    )
                }
                lists to lists.mapValues { it.value.total } + mapOf(
                    "unmeasured" to all.count { it.extras.stagger is Recorded.Unknown },
                    "total" to all.size,
                )
            }
            LearnTopic.IoExpander -> {
                val note = IO_EXPANDER_EXAMPLES.toMap()
                val examples = ranked.filter { it.core.slug.value in note }
                mapOf(
                    "examples" to list("Designs known to use one", examples) { note[it.core.slug.value] },
                ) to emptyMap()
            }
        }
        return LearnPage(topic, lists, counts, articleLd(topic), breadcrumbLd(topic))
    }

    /**
     * TechArticle, the schema.org type for "how to understand a technical thing". No
     * `datePublished`: the page is evergreen and its lists change on every deploy, so the
     * only honest timestamp would be the jar's boot time -- the reason `SeoController`
     * gives for writing no `lastmod` either.
     */
    private fun articleLd(topic: LearnTopic) = JsonLd.of(
        mapOf(
            "@context" to "https://schema.org",
            "@type" to "TechArticle",
            "headline" to topic.title,
            "description" to topic.description,
            "url" to site.of("/learn/${topic.slug}"),
            "image" to site.of("/og.png"),
            "publisher" to mapOf("@type" to "Organization", "name" to "Keyboard Builders", "url" to site.of("/")),
        )
    )

    private fun breadcrumbLd(topic: LearnTopic) = JsonLd.of(
        mapOf(
            "@context" to "https://schema.org",
            "@type" to "BreadcrumbList",
            "itemListElement" to listOf(
                mapOf("@type" to "ListItem", "position" to 1, "name" to "Learn", "item" to site.of("/learn")),
                mapOf("@type" to "ListItem", "position" to 2, "name" to topic.title, "item" to site.of("/learn/${topic.slug}")),
            ),
        )
    )
}

/**
 * `/learn` and `/learn/:topic`. The 404 for an unknown topic is the same
 * `ResponseStatusException` `/parts/dev-board/:slug` throws, so it renders the site's own
 * noindex error page.
 */
@Controller
class LearnController(private val pages: LearnPages, private val site: SiteUrl) {

    @GetMapping("/learn")
    fun index(): ModelAndView = ModelAndView(
        "learn/index",
        mapOf(
            "topics" to LearnTopic.entries,
            "head" to Head(
                title = "Learn",
                description = describe(
                    "Short explainers on key spacing, Choc switches, hotswap sockets,",
                    "reversible PCBs and more, for building your own keyboard.",
                ),
                canonical = site.of("/learn"),
                image = site.of("/og.png"),
            ),
        ),
    )

    @GetMapping("/learn/{slug}")
    fun topic(
        @PathVariable slug: String,
        @CookieValue(HeroFirst.COOKIE, required = false) hero: String?,
    ): ModelAndView {
        val topic = LearnTopic.of(slug)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no learn page called '$slug'")
        return ModelAndView(
            "learn/${topic.template}",
            mapOf(
                "page" to pages.build(topic, renderFirst = HeroFirst.of(hero) == HeroFirst.Render),
                "head" to Head(
                    title = topic.title,
                    description = describe(topic.description),
                    canonical = site.of("/learn/${topic.slug}"),
                    // The site card: the topic's own diagram is inline SVG, and Open Graph
                    // wants a raster -- the same reason `partHead` gives for the lockup.
                    image = site.of("/og.png"),
                ),
            ),
        )
    }
}
