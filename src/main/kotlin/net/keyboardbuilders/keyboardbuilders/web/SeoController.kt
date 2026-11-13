package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.FamilyCatalog
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The two files a crawler reads before it reads anything else.
 *
 * SERVED BY THE APP, NOT BY THE PROXY. The sitemap has to enumerate the corpus, and the
 * corpus is rebuilt on every deploy — a static file beside the jar would be a second
 * artefact that can disagree with the first, which is the failure `application-prod`
 * already refuses for the database. Robots joins it so the two live together.
 */
@RestController
class SeoController(
    private val catalog: DesignCatalog,
    private val site: SiteUrl,
    private val silhouettes: Silhouettes,
    private val controllers: ControllerCatalog,
    private val families: FamilyCatalog,
) {

    /**
     * THE FACETS ARE CLOSED NOW, AND THE WAIT IS WHY THAT IS SAFE.
     *
     * This file used to disallow nothing, and the omission was the decision. A `Disallow`
     * on every URL carrying a query string would have kept a crawler out of the ~151,000
     * addressable facet combinations and would have backfired: a disallowed URL is never
     * fetched, so the `noindex` inside it is never read — and a URL that cannot be fetched
     * can still be INDEXED from the links that point at it, with a title invented from the
     * anchor text. The page has to be crawlable for the directive inside it to be obeyed.
     *
     * So the order was: `noindex, follow` first, and a `Disallow` only once the filtered
     * URLs had actually dropped out of the index — weeks, not minutes. **On 2026-09-07
     * Search Console reported 22,000 pages under "Excluded by 'noindex' tag", with the
     * facet URLs among them and last-crawled dates on them.** The condition is met, the
     * pages are out, and the `Disallow` now saves crawl budget instead of pinning them in
     * place. It went in the same evening an agent was measured walking that space at 87
     * req/s — 2510 of 2533 requests in a 20-second access-log sample, forward-confirmed
     * rDNS — with both vCPUs of a two-core box pegged. **That agent was recorded as
     * Googlebot and the next morning's window says GoogleOther**, which is a different
     * crawler wearing the same smartphone-shaped UA; see `FACET_CRAWLERS`. The 2026-09-07
     * logs are gone (`roll_keep 3` holds minutes at that rate), so the re-reading is
     * likely rather than proven.
     *
     * **`Allow: /designs?page=` IS NOT A DETAIL; IT IS WHAT MAKES THE DISALLOW SURVIVABLE.**
     * Closing the facets closes the discovery path the rail was providing, so pagination
     * has to stay open or the catalogue past the first page is reachable only through the
     * sitemap. Google resolves competing rules by longest match, so the `Allow` wins for
     * `/designs?page=2` while `/designs?body=split` stays closed. This works only because
     * `FacetRail.toggled` sorts its parameters alphabetically and never emits `page` — a
     * filtered URL therefore never begins `?page=`, and a paged one never carries a facet
     * ahead of it. If that ordering ever changes, this rule silently starts leaking.
     *
     * THE NAMED GROUPS BELOW ARE NARROWER, and stay despite the wildcard rule covering
     * the same facets. A crawler obeys the most specific group matching it and only that
     * one, so an agent named here never sees `Allow: /designs?page=` — it loses the whole
     * query space, pagination included. That is deliberate: paged lists are kept open for
     * a search engine because they are a DISCOVERY path, and a retrieval crawler already
     * has `sitemap.xml`, which names every design. They were added earlier the same
     * evening while the flood was still mis-attributed to ClaudeBot on the strength of one
     * error-log line, and they turned out to be worth 15 requests out of 2533 — a tidy-up
     * rather than a fix, which is the honest description of them.
     */
    /**
     * Backlog #47's pairs, closed FROM THE FIRST DEPLOY -- unlike the facets above, whose
     * `Disallow` had to wait for their `noindex` to be read. These URLs have never been
     * crawled, so there is nothing in the index for a `Disallow` to pin there, and
     * `/designs/:slug` links them `nofollow` so they are not discovered by anchor text
     * either. ~54,000 pairs of 329 designs, each a near-duplicate of two design pages.
     */
    private val COMPARE = "Disallow: /designs/compare"

    @GetMapping("/robots.txt", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun robots(): String = buildString {
        appendLine("User-agent: *")
        appendLine("Allow: /")
        appendLine("Disallow: /designs?")
        appendLine("Allow: /designs?page=")
        appendLine(COMPARE)
        FACET_CRAWLERS.forEach {
            appendLine()
            appendLine("User-agent: $it")
            // THE TRAILING `?` IS THE WHOLE RULE. Matching is a prefix over path AND
            // query, so `/designs?` closes every filtered list while leaving `/designs`
            // and all 234 `/designs/:slug` pages open. Drop it and this becomes a ban on
            // the catalogue itself.
            appendLine("Disallow: /designs?")
            // Repeated in every group, because a named crawler reads ONLY its own group.
            appendLine(COMPARE)
        }
        appendLine()
        appendLine("Sitemap: ${site.of("/sitemap.xml")}")
    }

    /**
     * The name a home-screen install offers: "Keyboard Builders", where the front page's
     * title would give "Home · Keyboard Builders".
     *
     * INSTALLABLE SINCE 2026-10-02, or Chrome ignores the name: an Android install offered
     * "Home - Keyboard Builders" because, with only a 180px icon and `display: browser`,
     * Chrome made a shortcut, and a shortcut is named after the page title. An app needs a
     * 192 and a 512 icon and a real display mode; the icons are the mark on the bar's dark,
     * padded to sit inside a maskable circle.
     *
     * SERVED HERE, NOT FROM `static/`, for its media type. Neither Tomcat nor the static
     * handler knows `.webmanifest` and both sent it as `application/octet-stream`, which
     * the manifest spec lets a browser ignore. AboutTest holds the type.
     */
    @GetMapping("/site.webmanifest", produces = ["application/manifest+json"])
    fun manifest(): String = """
        {
          "name": "Keyboard Builders",
          "short_name": "Keyboard Builders",
          "start_url": "/",
          "display": "standalone",
          "background_color": "#1d1d22",
          "theme_color": "#1d1d22",
          "icons": [
            { "src": "/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any maskable" },
            { "src": "/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any maskable" },
            { "src": "/apple-touch-icon.png", "sizes": "180x180", "type": "image/png" }
          ]
        }
    """.trimIndent()

    /**
     * Every page this site is willing to have indexed, and nothing else.
     *
     * A sitemap is a statement about what is CANONICAL, so a URL that carries `noindex`
     * must not appear in one — listing it asks a crawler to keep a page the page itself
     * asks it to drop. That is why no filtered URL is here, and it is the same rule that
     * keeps `?page=2` OUT while keeping the design it links to IN: paged lists are
     * indexable but they are a route to the designs, not the destination.
     *
     * No `lastmod`, deliberately. Every deploy rebuilds the corpus from the seed, so the
     * only honest timestamp available is "when this jar booted" — the same value for all
     * 234 URLs, which tells a crawler nothing and would tell it something false the first
     * time one design changed and the others did not.
     *
     * A DESIGN URL CARRIES ITS `<image:image>` HERE, where one exists — Forgejo #42.
     * The page itself has no `<img>` for Google to index (its pictures are inline SVG),
     * so this is the other documented way a crawler discovers a page's image without
     * one: the sitemap extension namespace, one entry per silhouette.
     */
    @GetMapping("/sitemap.xml", produces = [MediaType.APPLICATION_XML_VALUE])
    fun sitemap(): String {
        // `/parts` joins the flat list rather than the loop below: it is one page, and it
        // is the hub that stops the 34 board pages depending on this file for discovery.
        // `/learn` likewise: one hub page, whose topics follow in their own loop below.
        val pages = listOf("/", "/designs", "/parts", "/about", "/learn") to catalog.all().sortedBy { it.core.slug.value }
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" """)
            append("""xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">""").append('\n')
            pages.first.forEach { append("  <url><loc>").append(site.of(it)).append("</loc></url>\n") }
            pages.second.forEach { design ->
                val slug = design.core.slug.value
                append("  <url><loc>").append(site.of("/designs/$slug")).append("</loc>")
                if (silhouettes.keysFor(slug)?.isNotEmpty() == true) {
                    append("<image:image><image:loc>")
                        .append(site.of("/designs/$slug/thumbnail.png"))
                        .append("</image:loc></image:image>")
                }
                append("</url>\n")
            }
            // EVERY CATALOGUED DEV BOARD. `partHead` makes `/parts/dev-board/:slug`
            // indexable and self-canonical, which by this method's own rule is exactly
            // what earns a place here. Until 2026-09-10 it was also the ONLY way to find
            // them: their sole inbound link was Door B's `/compatible`, which is
            // `noindex`. `/parts` is now an indexable hub linking all 34 and is the real
            // fix — these entries stay because a sitemap is still the shortest path, not
            // because nothing else points here. Sorted by slug for the same reason the
            // designs are: a diff between two deploys should show what changed, not that
            // the map iterated differently.
            controllers.all().sortedBy { it.slug }.forEach { board ->
                append("  <url><loc>")
                    .append(site.of("/parts/dev-board/${board.slug}"))
                    .append("</loc></url>\n")
            }
            // EVERY LEARN PAGE (#108): indexable and self-canonical, and written to be found
            // by search, which is the whole reason they exist. In `LearnTopic`'s own order,
            // which is fixed in code, so two deploys list them identically.
            LearnTopic.entries.forEach { topic ->
                append("  <url><loc>").append(site.of("/learn/${topic.slug}")).append("</loc></url>\n")
            }
            // EVERY FAMILY WITH A PAGE (backlog #107), and only those: a family whose
            // members are not in the corpus answers 404, and a sitemap URL that 404s is a
            // claim this method's own rule forbids. Same two-member test the page makes.
            families.all().filter { familyMembers(it.name, catalog).size >= 2 }.forEach { family ->
                append("  <url><loc>").append(site.of(familyHref(family.name))).append("</loc></url>\n")
            }
            append("</urlset>\n")
        }
    }

    private companion object {
        /**
         * Agents given a group of their own, closing the facet space to them.
         *
         * ONE OF THESE IS THE FLOOD AND THE REST ARE HOUSEKEEPING, which is a different
         * list from the one this comment described a day earlier. A 60-second access-log
         * window on 2026-09-08 was **4961 GoogleOther out of 5000 requests**, every URI a
         * filtered list; the seven below were worth 15 requests out of 2533 the evening
         * before. Revise from the access log and delete what never appears — a name kept
         * on suspicion is a claim the file cannot support.
         *
         * **GOOGLEBOT IS NOT HERE AND MUST NOT BE.** A crawler obeys the most specific
         * group matching it and only that one, so naming Googlebot would take
         * `Allow: /designs?page=` away from the single agent whose discovery path this
         * whole file is arranged around. GoogleOther is safe to name for the opposite
         * reason: Google documents it as a generic fetcher whose crawling preferences
         * "don't affect any specific product", so it feeds no index and owes the site
         * nothing. `SeoTest` asserts both halves, because the names differ by five
         * characters and read as the same thing to anyone tidying up.
         *
         * `Google-Extended` and `Applebot-Extended` are deliberately ABSENT. They are
         * training opt-out tokens rather than fetching agents: they control what may be
         * used, not what may be requested, so a `Disallow` under them removes no load
         * and would imply a measurement nobody made.
         */
        val FACET_CRAWLERS = listOf(
            "GoogleOther",
            "ClaudeBot",
            "GPTBot",
            "CCBot",
            "Bytespider",
            "Amazonbot",
            "meta-externalagent",
            // 2026-10-02: 14,267 of 51,275 requests in three hours, walking filter
            // combinations; Meta's link-preview indexer, a different token from the above.
            "meta-webindexer",
            "PerplexityBot",
        )
    }
}
