package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a crawler is allowed to keep, and what it is only allowed to walk through.
 *
 * THE MEASUREMENT THIS EXISTS TO HOLD DOWN. The facet grammar is strict-AND and every
 * combination answers 200, so the rail's seven groups -- licence 15, controller 6,
 * keys 5, switch 4, pitch 4, firmware 2, body 2 -- address roughly **151,000 URLs over
 * 234 designs**, every one of which used to render the same `<title>Designs</title>`.
 * That is the faceted-navigation crawl trap in its textbook form: a crawler spends its
 * budget on near-duplicates and never settles on the pages worth ranking.
 *
 * THE FIX IS `noindex, follow`, NOT `Disallow`, and the order matters. A `robots.txt`
 * ban stops the fetch, so the directive inside the page is never read -- and a URL that
 * cannot be fetched can still be indexed from the links pointing at it, title and all.
 * The page has to be crawlable for `noindex` to be obeyed. `follow` keeps the 234 design
 * links live, so the rail stays a discovery path while none of its own pages are kept.
 *
 * PAGINATION IS NOT A FACET. `?page=2` is a hundred different designs, not a narrowing
 * of the same set, so it is indexable and self-canonical. Pointing it at page 1 is the
 * classic version of this mistake and it hides 134 designs from discovery.
 */
// Parts off: the parts seed is checked against the real design and controller
// catalogues at startup, and this class swaps one of them for a fixture.
@SpringBootTest(properties = ["keyboardbuilders.parts.enabled=false"])
@AutoConfigureMockMvc
@Import(ManyDesignsFixture::class)
class SeoTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun html(path: String) = mockMvc.get(path).andReturn().response.contentAsString

    private fun head(path: String) = html(path).substringAfter("<head>").substringBefore("</head>")

    private fun canonicalOf(path: String): String? =
        Regex("""<link rel="canonical" href="([^"]*)"""").find(head(path))?.groupValues?.get(1)

    private fun robotsOf(path: String): String? =
        Regex("""<meta name="robots" content="([^"]*)"""").find(head(path))?.groupValues?.get(1)

    private fun descriptionOf(path: String): String? =
        Regex("""<meta name="description" content="([^"]*)"""").find(head(path))?.groupValues?.get(1)

    // -- what may be indexed ------------------------------------------------------

    @Test
    fun `a design page is indexable and points at itself`() {
        assertEquals(null, robotsOf("/designs/board-7"), "a design page must not be noindexed")
        assertEquals("https://keyboardbuilders.net/designs/board-7", canonicalOf("/designs/board-7"))
    }

    @Test
    fun `the unfiltered index is indexable and points at itself`() {
        assertEquals(null, robotsOf("/designs"))
        assertEquals("https://keyboardbuilders.net/designs", canonicalOf("/designs"))
    }

    @Test
    fun `the home page is indexable and points at itself`() {
        assertEquals(null, robotsOf("/"))
        assertEquals("https://keyboardbuilders.net/", canonicalOf("/"))
    }

    /** A page of results is a different hundred designs, not a narrowing of the same set. */
    @Test
    fun `a later page is indexable and canonicalises to itself, not to page one`() {
        assertEquals(null, robotsOf("/designs?page=2"))
        assertEquals("https://keyboardbuilders.net/designs?page=2", canonicalOf("/designs?page=2"))
    }

    // -- what may only be walked through ------------------------------------------

    @Test
    fun `a filtered index is noindex but still followed`() {
        assertEquals("noindex, follow", robotsOf("/designs?body=split"))
    }

    @Test
    fun `a search result is noindex`() {
        assertEquals("noindex, follow", robotsOf("/designs?q=board"))
    }

    @Test
    fun `a filtered page carries no canonical, because noindex already decides it`() {
        // Both at once is contradictory signalling -- `noindex` says drop this page and
        // `canonical` says merge it into another. Pick one; the directive is `noindex`.
        assertEquals(null, canonicalOf("/designs?body=split"))
    }

    /**
     * The `follow` half, asserted on its own because it is the half that is easy to lose.
     * Writing `noindex` alone leaves the default (`follow`), but writing `noindex,
     * nofollow` -- the reflex when a page is not wanted in the index -- would cut the
     * rail off as a discovery path, and the 134 designs past the first page have no
     * other route in.
     *
     * NOT ASSERTED BY COUNTING RENDERED ROWS, deliberately: this class overrides the
     * catalog but not `DesignQueries`, which reads the imported database, so a facet
     * filter returns the real corpus's slugs and none of them are `board-N`. The
     * directive is the contract; the rows are `FilteringTest`'s subject.
     */
    @Test
    fun `a filtered page is still followed, so the designs stay discoverable`() {
        val robots = robotsOf("/designs?body=split")

        assertTrue(robots != null && "follow" in robots, "no follow directive: $robots")
        assertFalse(robots!!.contains("nofollow"), "the rail is the discovery path: $robots")
    }

    // -- the icon beside the result -----------------------------------------------

    /**
     * Owner-reported 2026-09-02: a Google result for "reviung53" shows the generic
     * globe rather than the site's mark.
     *
     * NOT A PROVEN BREAK, and the first read of it was wrong. Everything is present and
     * served -- `favicon.ico` 200s with a 48px layer, `robots.txt` allows everything,
     * the page is in the sitemap -- and `rel="apple-touch-icon"` is BOTH a rel Google
     * documents and a format it decodes, so Google does have a usable icon. The likeliest
     * cause is simply that favicon indexing is a separate, slow pass and the site is four
     * days old.
     *
     * WHAT IS WORTH FIXING ANYWAY is that nothing depends on fallback we cannot observe.
     * Google's supported `rel` values are `icon`, `shortcut icon`, `apple-touch-icon`
     * and `apple-touch-icon-precomposed`; its supported favicon formats are BMP, GIF,
     * ICO, PNG, JPEG, PPM and TIFF -- **SVG is not among them.** So the primary
     * `rel="icon"` pointed at the one file Google cannot read, and whether it then tried
     * the apple icon is undocumented behaviour. A raster under plain `rel="icon"` costs
     * one line and removes the question.
     */
    @Test
    fun `the primary rel-icon is a format Google can decode`() {
        val icons = Regex("""<link rel="(icon|shortcut icon)"[^>]*href="([^"]*)"""")
            .findAll(head("/designs/board-7"))
            .map { it.groupValues[2] }
            .toList()

        val raster = setOf("bmp", "gif", "ico", "png", "jpg", "jpeg", "ppm", "tiff")
        assertTrue(icons.any { it.substringAfterLast('.').lowercase() in raster },
            "every rel=icon is a format Google's favicon crawler does not decode: $icons")
    }

    /** The SVG stays, because a browser prefers it and it follows the reader's theme. */
    @Test
    fun `the themed svg icon is still offered to browsers`() {
        assertTrue("""href="/favicon.svg" type="image/svg+xml"""" in head("/designs/board-7"))
    }

    // -- what a crawler is told about the site ------------------------------------

    @Test
    fun `robots allows the site and names the sitemap`() {
        val robots = mockMvc.get("/robots.txt").andReturn().response

        assertEquals(200, robots.status)
        assertTrue(robots.contentType!!.startsWith("text/plain"), robots.contentType!!)
        assertTrue("User-agent: *" in robots.contentAsString)
        assertTrue("Sitemap: https://keyboardbuilders.net/sitemap.xml" in robots.contentAsString)
        // A `Disallow` on the query strings would stop the fetch that reads `noindex`.
        assertFalse("Disallow: /*?" in robots.contentAsString)
    }

    /**
     * The family pages live under `/designs`, which robots partly closes. Prefix matching
     * runs over path and query, so neither `/designs?` nor `/designs/compare` may be a
     * prefix of a family address -- in any group, since a named crawler reads only its own.
     */
    @Test
    fun `no Disallow closes the family pages`() {
        val robots = mockMvc.get("/robots.txt").andReturn().response.contentAsString
        val disallowed = robots.lines().filter { it.startsWith("Disallow:") }.map { it.removePrefix("Disallow:").trim() }

        assertTrue(disallowed.isNotEmpty(), "no Disallow at all -- this check would pass on nothing")
        for (path in listOf("/designs/families/corne")) {
            val closing = disallowed.filter { path.startsWith(it) }
            assertTrue(closing.isEmpty(), "$path is closed by $closing")
        }
    }

    /**
     * PHASE TWO, and the precondition is the reason it may exist at all.
     *
     * The rule this class documents is `noindex, follow` FIRST and a `Disallow` only once
     * the filtered URLs have left the index -- because a disallowed URL is never fetched,
     * so its `noindex` is never read, and it can still be indexed from the anchor text
     * pointing at it. On 2026-09-07 Search Console reported 22,000 pages under "Excluded
     * by 'noindex'", the facet URLs among them. The pages are out; the `Disallow` now
     * saves crawl budget rather than pinning them in place.
     *
     * PAGINATION IS THE PART THAT MUST SURVIVE IT. Closing the facets closes the discovery
     * path the rail was providing, so `?page=` has to stay crawlable or everything past
     * the first page is reachable only through the sitemap. Google resolves competing
     * rules by longest match, which is what makes the pair below work -- and it works only
     * because `FacetRail.toggled` sorts parameters alphabetically and never emits `page`,
     * so a filtered URL never begins `?page=`. That coupling is invisible from here, which
     * is exactly why it is asserted.
     */
    @Test
    fun `the facets are closed to everyone but pagination stays open`() {
        val robots = mockMvc.get("/robots.txt").andReturn().response.contentAsString
        // The first blank-line-separated block, rather than a slice keyed off whichever
        // agent happens to be named first -- that ordering is presentational and has
        // already changed once.
        val wildcard = robots.split("\n\n").first()

        assertTrue("Disallow: /designs?" in wildcard, "the facet space must be closed")
        assertTrue(
            "Allow: /designs?page=" in wildcard,
            "without this the Disallow also closes pagination, hiding the catalog " +
                "past the first page from everything but the sitemap",
        )
        // The order the two appear in does not matter to a longest-match crawler, but the
        // Allow being STRICTLY LONGER than the Disallow does -- that is what makes it win.
        assertTrue("/designs?page=".length > "/designs?".length)
    }

    /**
     * The named groups are narrower than the wildcard one, deliberately.
     *
     * A crawler obeys the most specific group matching it and only that one, so an agent
     * named here never sees `Allow: /designs?page=` and loses the whole query space.
     * Paged lists are a discovery path a search engine needs; a retrieval crawler has the
     * sitemap instead. These were added while the flood was still mis-attributed to
     * ClaudeBot and were worth 15 requests out of 2533 -- kept, but not mistaken for a fix.
     */
    @Test
    fun `a named AI crawler loses the facets and keeps the catalog`() {
        val robots = mockMvc.get("/robots.txt").andReturn().response.contentAsString

        assertTrue("User-agent: ClaudeBot" in robots)
        assertTrue("Disallow: /designs?" in robots)

        // THE TRAILING `?` IS LOAD-BEARING. Prefix matching runs over path and query
        // together, so `Disallow: /designs` on its own would also close `/designs` and
        // every `/designs/:slug` -- turning a facet rule into a ban on the corpus.
        assertFalse(
            Regex("""Disallow: /designs\s*$""", RegexOption.MULTILINE).containsMatchIn(robots),
            "a bare `Disallow: /designs` would close the catalog as well as the facets",
        )
    }

    /**
     * GOOGLEOTHER IS NOT GOOGLEBOT, AND THAT DISTINCTION IS THE ENTIRE LICENSE FOR THIS.
     *
     * Measured 2026-09-08 05:00 from a live 60-second access-log window: **4961 of 5000
     * requests** were `...Nexus 5X... Mobile Safari/537.36 (compatible; GoogleOther)` from
     * `66.249.66.x`, against 24 from Googlebot, and every URI began `/designs?`. That is
     * the flood. The 2026-09-07 note calling it Googlebot read the Android/Mobile Safari
     * part of the string and not the product token in the trailing parenthesis, which is
     * the only part that names the crawler.
     *
     * Google documents GoogleOther as "the generic crawler that may be used by various
     * product teams for fetching publicly accessible content", and states that "crawling
     * preferences addressed to the `GoogleOther` user agent don't affect any specific
     * product". **It feeds no Search index**, so closing the facets to it costs this site
     * no search traffic — which is exactly what could NOT be said of Googlebot.
     *
     * WHICH IS WHY GOOGLEBOT MUST NEVER ACQUIRE A GROUP HERE. A named group is obeyed to
     * the exclusion of the wildcard one, so naming Googlebot would take `Allow:
     * /designs?page=` away from the one crawler whose discovery path v0.5 exists to gain.
     * The asymmetry is asserted rather than commented because the two names differ by
     * five characters and a future tidy-up would read them as the same thing.
     */
    @Test
    fun `GoogleOther is closed out of the facets and Googlebot is not`() {
        val robots = mockMvc.get("/robots.txt").andReturn().response.contentAsString

        assertTrue(
            "User-agent: GoogleOther" in robots,
            "the one agent measured flooding the facet space has no group of its own",
        )
        assertFalse(
            "User-agent: Googlebot" in robots,
            "a Googlebot group overrides the wildcard one and would cost the site the " +
                "pagination Allow, which is the discovery path the Disallow depends on",
        )
    }

    /**
     * THE ONE INVARIANT NOTHING CHECKED, AND IT WAS ONE FORGOTTEN EDIT FROM BREAKING.
     *
     * `robots.txt` is what this site TELLS a crawler; the Caddyfile's `@facetCrawler`
     * block is what it DOES to one. That file has asked in a comment, since the rule was
     * written, that its list stay identical to `FACET_CRAWLERS` — because two lists that
     * disagree are worse than either alone. Drift one way and the site 429s an agent it
     * publicly invited in, which is the kind of contradiction a crawler is entitled to
     * ignore; drift the other and it invites one it silently refuses.
     *
     * **A comment is not a check.** On 2026-09-08 `GoogleOther` had to be added to both
     * by hand and nothing would have failed had only one been edited. This reads the
     * SERVED output rather than the constant, so it also catches a group that quietly
     * stops being emitted, and it reads the same file the proxy is given.
     */
    @Test
    fun `the proxy refuses exactly the agents robots txt closes the facets to`() {
        val robots = mockMvc.get("/robots.txt").andReturn().response.contentAsString
        val told = Regex("""User-agent: (\S+)""").findAll(robots)
            .map { it.groupValues[1] }
            .filterNot { it == "*" }
            .toSet()

        val caddyfile = File("deploy/Caddyfile")
        assertTrue(caddyfile.isFile, "no proxy config at ${caddyfile.absolutePath}")
        val matcher = Regex("""header_regexp User-Agent \(\?i\)\(([^)]+)\)""")
            .find(caddyfile.readText())
        assertTrue(matcher != null, "no @facetCrawler User-Agent matcher in deploy/Caddyfile")
        val enforced = matcher!!.groupValues[1].split('|').map { it.trim() }.toSet()

        assertEquals(
            told, enforced,
            "robots.txt and the Caddyfile disagree about who is closed out of the " +
                "facets — named only in robots.txt: ${told - enforced}, " +
                "refused only by the proxy: ${enforced - told}",
        )
    }

    @Test
    fun `the sitemap lists every design exactly once`() {
        val xml = mockMvc.get("/sitemap.xml").andReturn().response.contentAsString
        val listed = Regex("""<loc>([^<]*)</loc>""").findAll(xml).map { it.groupValues[1] }.toList()

        assertEquals(listed.size, listed.toSet().size, "the sitemap repeats a URL")
        assertTrue("https://keyboardbuilders.net/designs/board-236" in listed)
        assertEquals(
            236 + 5 + 2 + LearnTopic.entries.size, listed.size,
            "236 designs, five flat pages (home, /designs, /parts, /about, /learn), two dev " +
                "boards and every learn topic",
        )
    }

    /**
     * `/parts/dev-board/:slug` is indexable and self-canonical (`PartPresentation`), and
     * by this method's own rule that is what earns a place here. It mattered more for
     * these than for the designs, because until 2026-09-10 a dev board's ONLY inbound
     * link was Door B's `/compatible`, which is `noindex` -- so the whole catalog hung
     * off a page a crawler has been told not to keep. `/parts` is now an indexable hub
     * linking all of them, which is the real fix; the sitemap entries stay because they
     * are still the shortest path to a page a crawler should have.
     */
    @Test
    fun `the sitemap lists every cataloged dev board`() {
        val xml = mockMvc.get("/sitemap.xml").andReturn().response.contentAsString
        val listed = Regex("""<loc>([^<]*)</loc>""").findAll(xml).map { it.groupValues[1] }.toList()

        assertTrue("https://keyboardbuilders.net/parts/dev-board/nice-nano-v2" in listed, "$listed")
        assertTrue("https://keyboardbuilders.net/parts/dev-board/elite-pi" in listed, "$listed")
    }

    @Test
    fun `the sitemap offers no filtered url, because none of them may be indexed`() {
        val xml = mockMvc.get("/sitemap.xml").andReturn().response.contentAsString
        val listed = Regex("""<loc>([^<]*)</loc>""").findAll(xml).map { it.groupValues[1] }.toList()

        // On the URLs, not on the document: `<?xml ... ?>` is a question mark too.
        assertTrue(listed.none { "?" in it }, "a sitemap URL carries a query string")
    }

    /**
     * Forgejo #42: an `<image:image>` entry only where a real silhouette exists. This
     * fixture's boards have none (their slugs match nothing in `silhouettes.json`), so
     * the honest sitemap declares the namespace and lists zero images -- never one
     * pointing at a thumbnail that would 404.
     */
    @Test
    fun `the sitemap declares the image namespace but lists no image for an undrawn board`() {
        val xml = mockMvc.get("/sitemap.xml").andReturn().response.contentAsString
        assertTrue("xmlns:image=" in xml, "the sitemap never declares the image namespace")
        assertFalse("<image:image>" in xml, "a design with no silhouette got an image entry")
    }

    // -- what the head says to a human --------------------------------------------

    /**
     * A description is not a ranking factor and it is the snippet under the link, which
     * is what decides the click. Absent, the engine writes its own from the page -- and
     * this page's body opens with an inline SVG and a chip list.
     */
    @Test
    fun `a design page describes itself with its own facts`() {
        val description = descriptionOf("/designs/board-7")

        assertTrue(description != null && description.isNotBlank(), "no description")
        assertTrue("42-key split keyboard" in description!!, description)
        assertTrue(description.startsWith("Board 7: a 42-key split keyboard"), description)
    }

    /**
     * Every filtered page used to be titled `Designs · Keyboard Builders`. They are
     * noindexed now, so this is not for the crawler -- it is for the reader with nine
     * tabs open and the person receiving a pasted link.
     */
    /**
     * THE TITLE STOPS AT "split". It is what a search result and a pasted link's preview
     * show, and owner-reported on 2026-09-26: "KLOR — 42 / 40 / 38 / 36 keys, split, 2
     * halves, TRRS cable between halves" was a spec sheet, not a title. The halves and
     * the cable stay on the page itself. Checked in og:title too, since that is where the
     * owner saw it.
     *
     * AND IT SAYS "keyboard" since 2026-10-03: "corne keyboard" is how people search, and
     * the title had the shape but never the noun.
     */
    @Test
    fun `a design's title names its shape in one word, with no halves or cable`() {
        val head = head("/designs/board-1")
        val title = Regex("<title>([^<]*)</title>").find(head)!!.groupValues[1]
        val og = Regex("""<meta property="og:title" content="([^"]*)"""").find(head)!!.groupValues[1]

        assertEquals("Board 1 — 42-key split keyboard · Keyboard Builders", title)
        assertEquals("Board 1 — 42-key split keyboard", og)
    }

    @Test
    fun `a filtered page says in its title what it is filtered to`() {
        val title = Regex("<title>([^<]*)</title>").find(head("/designs?body=split"))!!.groupValues[1]

        assertTrue("split" in title, title)
        assertFalse(title == "Designs · Keyboard Builders", "still the shared title")
    }
}
