package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.connectivityFor
import net.keyboardbuilders.keyboardbuilders.domain.usbPortFor
import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.FirmwareCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.Facets
import net.keyboardbuilders.keyboardbuilders.domain.Selection
import net.keyboardbuilders.keyboardbuilders.domain.Sort
import net.keyboardbuilders.keyboardbuilders.domain.UnreadableQuery
import net.keyboardbuilders.keyboardbuilders.domain.ShorthandQuery
import net.keyboardbuilders.keyboardbuilders.domain.parseShorthandQuery
import net.keyboardbuilders.keyboardbuilders.domain.forShorthand
import net.keyboardbuilders.keyboardbuilders.domain.readingFor
import net.keyboardbuilders.keyboardbuilders.persistence.DesignQueries
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.util.MultiValueMap
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Controller
class DesignsController(
    private val catalog: DesignCatalog,
    private val silhouettes: Silhouettes,
    private val activity: RepoActivity,
    private val queries: DesignQueries,
    private val site: SiteUrl,
    private val renames: SlugRenames,
    private val controllers: ControllerCatalog,
    private val renders: Renders,
    private val photos: Photos,
    private val related: RelatedDesigns,
    private val hearts: net.keyboardbuilders.keyboardbuilders.persistence.Hearts,
    /** For the `Connectivity` spec row -- the impossibility rules and each firmware's own
     *  wireless support, as data since 2026-09-10 (`seed/firmwares.toml`). */
    private val firmwares: FirmwareCatalog,
    private val unpublished: net.keyboardbuilders.keyboardbuilders.persistence.Unpublished,
    private val photoSlots: net.keyboardbuilders.keyboardbuilders.community.PhotoSlots,
) {

    /**
     * `page` is an Int, so an unparseable one is a 400 before this method runs — the
     * same rule the facet parameters take (#4). A page that parses but does not exist
     * is a 404, because `ux.md` §2.7 makes the URL the truth of what is being shown.
     */
    @GetMapping("/designs")
    fun index(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam allParams: MultiValueMap<String, String>,
        model: Model,
    ): ModelAndView {
        /*
         * BEFORE PARSING, because a renamed value still PARSES -- that is the whole
         * hazard (`FacetValueRenames`). `?connectivity=bluetooth` is a legal token that
         * matches nothing since #98, so without this it would render 200-with-zero and
         * call that an answer. 301 rather than 302 for the same reason `show` does it:
         * the old value is not coming back.
         */
        FacetValueRenames.rewrite(allParams)?.let { query ->
            return ModelAndView(RedirectView("/designs?$query").also {
                it.setStatusCode(HttpStatus.MOVED_PERMANENTLY)
            })
        }

        // An unreadable query is a 400 with the reason, never a silent narrowing
        // (`contract.md` §1). The rule exists because it was broken once and failed
        // quietly: `?keys=65-,unknown` matched nothing for `65-` and returned the exact
        // complement of what the page claimed, on a bookmarkable URL. `Sort.of` is the
        // same refusal, extended to `?sort=` — backlog #37: a value this build does not
        // read used to come back 200 and silently order nothing.
        val facets = try {
            Facets.parse(allParams)
        } catch (unreadable: UnreadableQuery) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, unreadable.message)
        }
        val chosenSort = try {
            Sort.of(sort)
        } catch (unreadable: UnreadableQuery) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, unreadable.message)
        }

        // ux.md §2.2's shorthand grammar, SCOPED DOWN to a key-count guess rather than an
        // exact match on the stored (and 0%-populated) `Shorthand` fact — see
        // `ShorthandSearch.kt`'s own doc comment for why. `q` still holds the reader's own
        // "3x6+3" for the search box and the "search:" chip; only the QUERY gets the
        // substitution, via a `Facets` that never reaches the URL or the rail.
        val shorthand = q?.let(::parseShorthandQuery)
        val reading = shorthand?.let(facets::readingFor)
        val (queryFacets, queryText) = when (reading) {
            null -> facets to q
            else -> facets.forShorthand(reading) to null
        }
        model.addAttribute("shorthand", shorthand)
        model.addAttribute("shorthandReading", reading)

        // The FILTER is SQL; the RENDERING still comes from the seed file. Deliberately
        // a hybrid: `design_facet` is the only thing that can answer a facet query, and
        // swapping the whole read path to SQL is its own piece of work with its own risk.
        // When that lands, this line is what changes.
        //
        // THE ORDER IS SQL TOO, AND IT USED NOT TO BE. `slugsMatching` returns its slugs
        // already ranked; taking them as a `Set` threw that away and left the page in
        // `catalog.all()`'s order, which is alphabetical. Every query-layer test passed
        // while Sofle and Lily58 -- the two most-starred designs in the corpus -- sat on
        // page two under S and L, and the index opened on `3 W6`, `41 1350` and `45`.
        val matched = queries.slugsMatching(queryFacets, queryText, chosenSort)
        // Doubles as the membership test, so the rank and the filter cannot disagree.
        val rank = (matched ?: queries.allRanked(chosenSort)).withIndex()
            .associate { (position, slug) -> slug to position }
        val all = catalog.all()
            .let { designs ->
                if (matched == null) designs
                else designs.filter { rank.containsKey(it.core.slug.value) }
            }
            // SORTED, NOT REBUILT FROM `rank`. A design the projection missed would
            // vanish from a rebuild -- silently, and out of a total this page prints as
            // fact. Sorting sinks it to the bottom instead, where it is visible and the
            // count stays honest. `sortedBy` is stable, so the sunk ones keep the
            // catalogue's own order rather than an arbitrary one.
            .sortedBy { rank[it.core.slug.value] ?: Int.MAX_VALUE }

        // ANY filter, not only a facet. Gated on `facets` alone, a search that found
        // nothing skipped this branch entirely and the reader landed on a bare empty
        // page with nothing to click -- and a typo is the LIKELIEST way to get here,
        // because a facet is chosen from a list and this box is typed into.
        if (all.isEmpty() && (!facets.isEmpty || !q.isNullOrBlank())) {
            // NOT AN ERROR STATE. ux.md §2.6 Risk 1 makes this a main path, and the
            // corpus insists: at 234 designs, 38% of two-filter combinations return
            // fewer than three results. A page that treats emptiness as an exception
            // would be wrong most of the times it mattered.
            model.addAttribute("applied", chips(facets, q, sort))
            model.addAttribute("relaxations", offers(facets, q, sort))
            model.addAttribute("rows", emptyList<DesignRow>())
            model.addAttribute("total", 0)
            model.addAttribute("page", Page.of(1, total = 0))
            model.addAttribute("rail", railForQuery(shorthand, queries, facets, q, sort))
            model.addAttribute("q", q.orEmpty())
            model.addAttribute("carry", carried(facets))
            model.addAttribute("pageQuery", pageQuery(facets, q, sort))
            model.addAttribute(
                "head",
                indexHead(site, chips(facets, q, sort), Page.of(1, total = 0), chosenSort),
            )
            return ModelAndView("designs/index")
        }
        val shown = try {
            Page.of(page, total = all.size)
        } catch (refused: NoSuchPage) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, refused.message)
        }
        // `queryFacets`, not `facets`: a shorthand search's key count never reaches the
        // URL (`railForQuery`'s own doc comment), but it is exactly as much this design's
        // reason for being in the list, and the card should picture it the same way.
        val keysFilter = queryFacets.of(FacetKey.Keys)
        val onPage = all.slice(shown.range)
        val heartCounts = hearts.forDesigns(onPage)
        model.addAttribute("rows", onPage.map { it.toRow(silhouettes, activity, keysFilter, heartCounts[it.core.slug.value]) })
        // The photo cards the list is drawn as (see `DesignCard`), built from the same rows.
        model.addAttribute("cards", onPage.map { it.toCard(it.toRow(silhouettes, activity, keysFilter, heartCounts[it.core.slug.value]), photos, silhouettes) })
        // The count is of the RESULT SET, not of the page. It is the first thing faceted
        // search has to get right (ux.md §2.5), and a reader who has filtered to 236
        // designs has 236 designs however many are on screen.
        model.addAttribute("total", all.size)
        model.addAttribute("page", shown)
        model.addAttribute("applied", chips(facets, q, sort))
        model.addAttribute("relaxations", emptyList<Relaxed>())
        model.addAttribute("rail", railForQuery(shorthand, queries, facets, q, sort))
        model.addAttribute("q", q.orEmpty())
        model.addAttribute("carry", carried(facets))
        model.addAttribute("pageQuery", pageQuery(facets, q, sort))
        model.addAttribute("head", indexHead(site, chips(facets, q, sort), shown, chosenSort))
        return ModelAndView("designs/index")
    }

    /**
     * The rail, suppressed rather than wrong, while a shorthand search is active.
     *
     * `DesignQueries.rail`'s counts and `FacetRail.toggled`'s hrefs are BOTH built from
     * the same `(facets, text)` pair (`FacetRail.kt`'s own doc comment on `toggled`: "this
     * function is built from `facets` alone" was already a bug fixed once for exactly this
     * reason). There is no clean way today to hand the rail "count as if `keys` were
     * 21-or-42" while still linking as if the box still said `q=3x6+3` — the two would
     * need separating inside `FacetRail.kt`, which is a bigger, separately-reviewable
     * change than this feature earns on its own. Counting against the literal text
     * "3x6+3" would zero every option next to a non-empty result list, which is a worse
     * bug than no rail at all — so this cuts the corner honestly instead of quietly.
     */
    private fun railForQuery(
        shorthand: ShorthandQuery?,
        queries: DesignQueries,
        facets: Facets,
        q: String?,
        sort: String?,
    ): List<RailGroup> = when (shorthand) {
        null -> railFor(queries.rail(facets, q), facets, q, sort)
        else -> emptyList()
    }

    /**
     * What to drop when nothing matched.
     *
     * THE SEARCH IS OFFERED FIRST WHEN IT IS THE LIKELIER CULPRIT. A facet is chosen from a
     * list and is hard to get wrong; a search box is typed into, so `sofel` strands a reader
     * on an empty page with nothing to click unless dropping the text is one of the offers.
     */
    private fun offers(facets: Facets, q: String?, sort: String?): List<Relaxed> = buildList {
        if (!q.isNullOrBlank()) {
            // `null` back from `slugsMatching` means "nothing was asked", i.e. the whole
            // corpus -- which is exactly the count when the search was the only filter.
            val without = queries.slugsMatching(facets, null)?.size ?: queries.total()
            if (without > 0) {
                add(Relaxed("the search for \u201c$q\u201d", without, link(facets, q, sort, keepText = false)))
            }
        }
        queries.relaxations(facets, q).forEach {
            add(Relaxed(it.group.key.param.replace('_', ' '), it.count, link(facets, q, sort, without = it.group)))
        }
    }

    /**
     * A slug in the URL has THREE possible answers, not two, and the middle one is
     * Forgejo #38. Ticket 07 decision 8: *"slugs are mutable and old ones keep
     * resolving. A machine-guessed name is a first draft too, so a rename leaves a
     * redirect rather than a dead link."*
     *
     * `ModelAndView` RATHER THAN A VIEW NAME, because a redirect is not an error and
     * cannot come back through the error channel. `ResponseStatusException` carries a
     * status and a reason phrase; MVC resolves it via `response.sendError`, which
     * renders an error page and drops any header set on the exception -- so a 301
     * thrown that way arrives with no `Location` and is a status code pretending to be
     * a redirect. And `return "redirect:..."` would be a **302**: temporary, which is
     * the wrong claim about a rename. `RedirectView.setStatusCode` is the one place
     * that says permanent.
     *
     * The 404 still throws, because that one really is an error.
     */
    @GetMapping("/designs/{slug}")
    fun show(
        @PathVariable slug: String,
        @CookieValue(HeroFirst.COOKIE, required = false) hero: String?,
        @org.springframework.security.core.annotation.AuthenticationPrincipal
        user: org.springframework.security.oauth2.core.user.OAuth2User?,
    ): ModelAndView {
        catalog.bySlug(slug)?.let { design ->
            val page = design.toPage(
                silhouettes, activity, renders, catalog, related,
                connectivity = design.connectivityFor(controllers.all(), firmwares.rules),
                usbPort = design.usbPortFor(controllers.all()),
                photos = photos,
                hearts = hearts,
                devBoards = controllers.all().map { it.slug }.toSet(),
            )
            return ModelAndView(
                "designs/show",
                mapOf(
                    "design" to page,
                    "head" to designHead(site, page, silhouettes),
                    "hearts" to hearts.count(design.core.slug.value),
                    // /settings: the render leads only where there is one to lead with.
                    "renderLeads" to (HeroFirst.of(hero) == HeroFirst.Render && page.render != null),
                    "slot" to photoSlots.of(design, photos.of(design.core.slug.value), user?.getAttribute<String>("login")),
                ),
            )
        }
        renames.currentFor(slug)?.let { current ->
            return ModelAndView(
                RedirectView("/designs/$current", true)
                    .apply { setStatusCode(HttpStatus.MOVED_PERMANENTLY) }
            )
        }
        // 410, NOT 404 (#116): the page existed and its author took it down. A 404 reads as
        // a broken site, and 410 tells a search engine to drop the URL sooner.
        if (unpublished.contains(slug)) {
            throw ResponseStatusException(HttpStatus.GONE, "The designer took this page down.")
        }
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "no design with slug '$slug'")
    }

    /**
     * Door B, "what fits this board", was removed 2026-11-27 (owner: "I never use it
     * myself"). Its URL sends readers on to the design page, so an old link or bookmark
     * still lands somewhere. It was `noindex`, so no search result points here.
     */
    @GetMapping("/designs/{slug}/compatible")
    fun compatible(@PathVariable slug: String): RedirectView {
        val current = if (catalog.bySlug(slug) != null) slug else renames.currentFor(slug)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no design with slug '$slug'")
        return RedirectView("/designs/$current", true).apply { setStatusCode(HttpStatus.MOVED_PERMANENTLY) }
    }

    /**
     * Backlog #47 -- two designs compared: their layouts overlaid, then `ux.md`:576's
     * table. With no second design (or the same one twice) it is the chooser instead.
     *
     * A LITERAL PATH, so it wins over `/designs/{slug}` -- Spring prefers the more
     * specific pattern, and no design is slugged `compare`.
     *
     * `indexable = false` ON BOTH, and `robots.txt` disallows the path as well, from the
     * first deploy: 329 designs make ~54,000 pairs, every one a near-duplicate of two
     * design pages, and the facet flood of 2026-09-08 was a crawler walking exactly this
     * kind of space. The design pages are what should rank.
     *
     * A renamed slug on either side 301s to the current pair, as `show` does; an unknown
     * one is a 404, and no `a` at all is a 400 -- there is nothing to compare from.
     *
     * `al` and `bl` pick a layout by key count -- KLOR's Konrad is `al=40`, owner-reported
     * missing on the first version. A count the design does not have is a 400, the same
     * refusal an unreadable facet gets, and naming the default one 301s to the URL without
     * it, so each comparison has one URL (`ux.md` §1.1).
     */
    @GetMapping("/designs/compare")
    fun compare(
        @RequestParam(required = false) a: String?,
        @RequestParam(required = false) b: String?,
        @RequestParam(required = false) diff: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) al: Int?,
        @RequestParam(required = false) bl: Int?,
    ): ModelAndView {
        if (a.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "compare needs a design: /designs/compare?a=<slug>")
        }
        val secondSlug = b?.takeIf { it.isNotBlank() }
        val diffOnly = diff == "1"
        val first = catalog.bySlug(a)
        val second = secondSlug?.let(catalog::bySlug)
        if (first == null || (secondSlug != null && second == null)) {
            val currentA = first?.core?.slug?.value ?: current(a)
            val currentB = secondSlug?.let { second?.core?.slug?.value ?: current(it) }
            return moved(compareHref(currentA, currentB, diffOnly, al, bl))
        }
        refuseUnknownLayout(first, al)
        second?.let { refuseUnknownLayout(it, bl) }
        // The default layout is spelled by leaving it out; so is a `bl` with no `b`.
        val canonicalA = al?.takeIf { it != silhouettes.defaultLayout(first.core.slug.value) }
        val canonicalB = bl?.takeIf { second != null && it != silhouettes.defaultLayout(second.core.slug.value) }
        if (canonicalA != al || canonicalB != bl) {
            return moved(compareHref(first.core.slug.value, secondSlug, diffOnly, canonicalA, canonicalB))
        }
        if (second == null || second.core.slug == first.core.slug) {
            return chooser(first, al, q, sameTwice = second != null)
        }

        val aSlug = first.core.slug.value
        val bSlug = second.core.slug.value
        val aSide = compareSide(first, al, silhouettes.drawnLayouts(aSlug), silhouettes.defaultLayout(aSlug)) {
            compareHref(aSlug, bSlug, diffOnly, it, bl)
        }
        val bSide = compareSide(second, bl, silhouettes.drawnLayouts(bSlug), silhouettes.defaultLayout(bSlug)) {
            compareHref(aSlug, bSlug, diffOnly, al, it)
        }
        val page = comparePage(
            a = pageFor(first),
            b = pageFor(second),
            aSide = aSide,
            bSide = bSide,
            overlay = silhouettes.overlay(
                aSlug, bSlug, "${sideLabel(aSide)} filled, ${sideLabel(bSide)} outlined",
                firstKeys = al, secondKeys = bl,
            ),
            aDrawn = silhouettes.isDrawn(aSlug, al),
            bDrawn = silhouettes.isDrawn(bSlug, bl),
            aScaled = silhouettes.hasScale(aSlug, al),
            bScaled = silhouettes.hasScale(bSlug, bl),
            eitherSplit = first.core.body is Body.Split || second.core.body is Body.Split,
            diffOnly = diffOnly,
        )
        return ModelAndView(
            "designs/compare",
            mapOf(
                "page" to page,
                "head" to Head(title = "${page.a.name} vs ${page.b.name} — compared", indexable = false),
            ),
        )
    }

    private fun moved(to: String) = ModelAndView(
        RedirectView(to, true).apply { setStatusCode(HttpStatus.MOVED_PERMANENTLY) }
    )

    /** A layout by key count must be one [design] declares; anything else is unreadable. */
    private fun refuseUnknownLayout(design: Design, keys: Int?) {
        if (keys != null && design.core.layouts.none { it.keys.value == keys }) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST, "${design.core.name.value} has no $keys-key layout",
            )
        }
    }

    /** A side as the overlay's aria-label names it: the layout too, where there is a choice. */
    private fun sideLabel(side: CompareSide) = side.layout?.let { "${side.name} ($it)" } ?: side.name

    /** The slug [former] now goes by, or a 404 -- a pair with one unknown side is not a pair. */
    private fun current(former: String): String =
        renames.currentFor(former)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no design with slug '$former'")

    /** A design's facts exactly as its own page states them, Connectivity included. */
    private fun pageFor(design: Design): DesignPage = design.toPage(
        silhouettes, activity,
        connectivity = design.connectivityFor(controllers.all(), firmwares.rules),
        usbPort = design.usbPortFor(controllers.all()),
    )

    /**
     * Pick the second board. A name typed into [q] is matched in memory against the
     * catalogue's names and slugs: this is a name lookup, not a search, and 329 names
     * need no index. With nothing typed, "boards like this" (`ux.md` §3.6) is the
     * suggestion -- a similar board is the likeliest thing to be weighed against this one.
     */
    private fun chooser(first: Design, al: Int?, q: String?, sameTwice: Boolean): ModelAndView {
        val aSlug = first.core.slug.value
        val text = q?.trim().orEmpty()
        val candidates = if (text.isNotEmpty()) {
            val wanted = text.folded()
            catalog.all()
                .filter { it.core.slug != first.core.slug }
                .filter { wanted in it.core.name.value.folded() || wanted in it.core.slug.value.folded() }
                .take(CHOOSER_LIMIT)
                .let { found ->
                    val counts = hearts.forDesigns(found)
                    found.map { CompareCandidate(it.toCard(it.toRow(silhouettes, activity, hearts = counts[it.core.slug.value]), photos, silhouettes), compareHref(aSlug, it.core.slug.value, al = al), null) }
                }
        } else {
            val similar = related.similarTo(first, limit = 6)
            val counts = hearts.forDesigns(similar.map { it.design })
            similar.map {
                CompareCandidate(it.design.toCard(it.design.toRow(silhouettes, activity, hearts = counts[it.design.core.slug.value]), photos, silhouettes), compareHref(aSlug, it.design.core.slug.value, al = al), it.reason)
            }
        }
        return ModelAndView(
            "designs/compareChoose",
            mapOf(
                "chooser" to CompareChooser(CompareSide(first.core.name.value, aSlug, keys = al), text, candidates, sameTwice),
                "head" to Head(title = "Compare ${first.core.name.value} with…", indexable = false),
            ),
        )
    }
}

/** How many name matches the compare chooser lists: enough to scan, not a second index. */
private const val CHOOSER_LIMIT = 24

/** Letters and digits only, lowercased, so "reviung 41" finds `Reviung41`. */
private fun String.folded() = lowercase().filter { it.isLetterOrDigit() }

/**
 * The head for one design: the page this site actually wants ranked.
 *
 * The title carries the shape because a bare name loses the query. Someone searching
 * "36 key split keyboard" is looking at a result list, and `Corne · Keyboard Builders`
 * says nothing that matches them; `Corne — 42-key split keyboard` does.
 *
 * AND STOPS AT "split": the brief shape, not the page's. Owner-reported 2026-09-26 from a
 * link preview -- "KLOR — 42 / 40 / 38 / 36 keys, split, 2 halves, TRRS cable between
 * halves" is a spec sheet, not a title. Halves and cable stay in the page's Spec table.
 *
 * AND SAYS "keyboard" since 2026-10-03, because "corne keyboard" is the query and the
 * title had the shape without the noun. The wording is `DesignPage.headline`'s.
 */
internal fun designHead(site: SiteUrl, page: DesignPage, silhouettes: Silhouettes) = Head(
    title = page.headline.ifBlank { null }?.let { "${page.name} — $it" } ?: page.name,
    // The summary when there is one, and the facts when there is not. Neither is padded
    // out to length: a description that repeats itself reads worse than a short one.
    // The facts are one sentence in the site's own voice since 2026-10-03, so a snippet
    // never passes our words off as the author's -- see `DesignPage.facts`.
    description = describe(page.summary ?: page.facts),
    canonical = site.of("/designs/${page.slug}"),
    image = site.of("/designs/${page.slug}/og.png"),
    // Null where nothing was drawn: a design with neither a photo nor a silhouette gets
    // no thumbnail rather than a blank square. `keysFor` is the same check
    // `thumbnail.png` makes for the silhouette case; a photo justifies the square on its
    // own, backlog #101.
    thumbnail = (page.photo != null || silhouettes.keysFor(page.slug)?.isNotEmpty() == true)
        .takeIf { it }?.let { site.of("/designs/${page.slug}/thumbnail.png") },
)

/**
 * The head for a result list, and the one decision on this page that a crawler acts on.
 *
 * FILTERED MEANS NOINDEX; PAGED DOES NOT. A facet narrows one set — 151,000 addressable
 * combinations over 234 designs, all near-duplicates of each other and of the unfiltered
 * list. A page is a different hundred designs, indexable on its own and self-canonical:
 * folding `?page=2` into `/designs` is the standard version of this mistake and it hides
 * everything past row 100 from discovery.
 */
internal fun indexHead(
    site: SiteUrl,
    applied: List<AppliedFilter>,
    page: Page,
    sort: Sort = Sort.DEFAULT,
): Head {
    // Backlog #37. `/designs?sort=name` carries no facet chip, so before this it was
    // indexable AND canonical to itself -- a byte-for-byte duplicate of `/designs`
    // reachable through a parameter nobody had classified as a facet. A sort is not a
    // narrowing, but it is exactly the same kind of near-duplicate a facet produces.
    val filtered = applied.isNotEmpty() || sort != Sort.DEFAULT
    val where = applied.joinToString("; ") { it.label }
    val which = if (page.number > 1) "page ${page.number} of ${page.count}" else null
    return Head(
        // "Open-source keyboard designs", not "Designs": the title is what a search engine
        // weighs most, and the bare word matched nothing anyone types (2026-10-02).
        title = (listOf("Open-source keyboard designs") + listOfNotNull(where.ifBlank { null }, which))
            .joinToString(" — "),
        // Sized to fit: at 158 characters the previous wording was elided by `describe`
        // into "controller, firmware and…", which is worse than a shorter sentence.
        description = if (filtered) null else describe(
            "${page.total} open-source keyboard designs, read from their own board files",
            "and filterable by key count, switch footprint, controller, firmware and license.",
        ),
        // Self-canonical, and only when the page may be kept at all.
        canonical = if (filtered) null
        else site.of(if (page.number > 1) "/designs?page=${page.number}" else "/designs"),
        indexable = !filtered,
        // The site card, on every variant including the filtered ones: a paste into
        // Discord previews the same whether or not the link carries facets, and a crawler
        // is not the only thing that reads a URL.
        // No `thumbnail`: Google's own guidance names a site logo as the generic image a
        // `primaryImageOfPage` should avoid, and this page's only picture is exactly that
        // logo.
        image = site.of("/og.png"),
    )
}

/** One applied filter, as a chip: what it says and the URL that removes it. */
data class AppliedFilter(val label: String, val without: String)

/** One zero-recovery offer: drop this filter and find this many. */
data class Relaxed(val label: String, val count: Int, val href: String)

/**
 * The page's URL with one thing taken out of it.
 *
 * SPELLED ONCE, because the chip's remove link and the zero-recovery offer are the same
 * question asked from two places, and they were two expressions -- one of which was the
 * literal `"/designs"`, which does not remove a filter, it removes ALL of them.
 *
 * `terms` and not `values`: a group's spans belong in its URL as much as its exact
 * values do. And `q` travels with them, so removing a facet does not silently discard
 * the reader's search as well.
 */
private fun link(
    facets: Facets,
    q: String?,
    sort: String? = null,
    without: Selection? = null,
    keepText: Boolean = true,
): String {
    val parts = facets.groups.filter { it != without }
        .map { g -> "${g.key.param}=" + g.terms.joinToString(",") { it.token } }
        .toMutableList()
    if (keepText && !q.isNullOrBlank()) {
        parts += "q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
    }
    // Backlog #37. Carried whatever the reader's own request said, never re-derived
    // from the default -- a chip's remove link or a relaxation offer answers "with
    // this ONE thing dropped", not "and also reset how you wanted it ordered".
    if (!sort.isNullOrBlank()) parts += "sort=$sort"
    return if (parts.isEmpty()) "/designs" else "/designs?" + parts.joinToString("&")
}

/** The applied filters, as chips. The search is one of them and is removable like one. */
private fun chips(facets: Facets, q: String?, sort: String? = null): List<AppliedFilter> = buildList {
    if (!q.isNullOrBlank()) {
        add(AppliedFilter("search: $q", link(facets, q, sort, keepText = false)))
    }
    facets.groups.forEach { group ->
        add(AppliedFilter(
            // `terms`, NOT `values`: `values` is the exact half of the group, so a chip
            // built from it renders "keys:" with nothing after it once bands exist.
            label = "${group.key.param.replace('_', ' ')}: " +
                group.terms.joinToString(" or ") { it.token },
            without = link(facets, q, sort, without = group),
        ))
    }
}

/** The facets, as hidden inputs, so submitting the search form does not drop them. */
private fun carried(facets: Facets): Map<String, String> =
    facets.groups.associate { g -> g.key.param to g.terms.joinToString(",") { it.token } }
