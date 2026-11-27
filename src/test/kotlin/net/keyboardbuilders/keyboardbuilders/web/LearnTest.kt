package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.KeyPitch
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Stagger
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import net.keyboardbuilders.keyboardbuilders.domain.HotswapPart
import net.keyboardbuilders.keyboardbuilders.domain.hotswapParts
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `/learn` and `/learn/:topic` -- backlog #108, the owner's "explanations of technical terms
 * with illustrations". One evergreen page per term a spec line uses, each ending in a LIVE
 * list read from the corpus at request time.
 *
 * THE DESIGNS ARE REAL, for the reason `PartsControllerTest` gives: a page that counts the
 * corpus can only be checked against the corpus it counts. So no count is written down here;
 * each is derived from [DesignCatalog] beside the page that claims it, and a reseed cannot
 * break the test unless it breaks the page.
 *
 * Reads the seed the test context imports at startup, because the live lists are ranked
 * by `DesignQueries.allRanked`, the order the rest of the site uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LearnTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var catalog: DesignCatalog


    private fun html(path: String) = mockMvc.get(path).andReturn().response.contentAsString
    private fun title(page: String) = Regex("<title>(.*?)</title>").find(page)?.groupValues?.get(1)
    private fun ld(page: String) =
        Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(page).map { jacksonObjectMapper().readTree(it.groupValues[1]) }.toList()

    /** One live list as drawn: its heading's count, its cards' slugs and markup, and what
     *  follows the cards up to the next list (the see-all line). */
    private data class Drawn(val heading: String, val total: Int, val cards: Map<String, String>, val after: String)

    private fun lists(page: String): List<Drawn> =
        page.split("""<h3 class="learn-list-head">""").drop(1).map { chunk ->
            val head = assertNotNull(Regex("""^(.*?) <span class="learn-count">(\d+)</span>""").find(chunk), chunk.take(200))
            val strip = chunk.substringBefore("</ul>")
            Drawn(
                head.groupValues[1],
                head.groupValues[2].toInt(),
                Regex("""<a class="strip-card card" href="/designs/([^"]+)".*?</a>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(strip).associate { it.groupValues[1] to it.value },
                if ("</ul>" in chunk) chunk.substringAfter("</ul>").substringBefore("</article>") else chunk.substringBefore("</article>"),
            )
        }

    private fun list(page: String, heading: String) = lists(page).first { it.heading == heading }

    @Test
    fun `the index renders and links every topic`() {
        val response = mockMvc.get("/learn").andReturn().response
        assertEquals(200, response.status)
        val page = response.contentAsString

        assertEquals("Learn · Keyboard Builders", title(page))
        assertTrue("<h1>" in page, "no heading on /learn")
        assertTrue("""<link rel="canonical" href="https://keyboardbuilders.net/learn">""" in page)
        for (topic in LearnTopic.entries) {
            assertTrue("""href="/learn/${topic.slug}"""" in page, "/learn does not link ${topic.slug}")
        }
    }

    /** The bar every topic page meets, asserted over the catalogue so a new topic is held to
     *  it the moment it is added. */
    @Test
    fun `every topic page is indexable, self-canonical and titled after its topic`() {
        for (topic in LearnTopic.entries) {
            val response = mockMvc.get("/learn/${topic.slug}").andReturn().response
            assertEquals(200, response.status, topic.slug)
            val page = response.contentAsString

            assertEquals("${topic.title} · Keyboard Builders", title(page), topic.slug)
            assertTrue(Regex("<h1>.+</h1>").containsMatchIn(page), "${topic.slug}: no h1")
            assertTrue("""<link rel="canonical" href="https://keyboardbuilders.net/learn/${topic.slug}">""" in page, topic.slug)
            assertFalse("noindex" in page, "${topic.slug} carries noindex")
            assertTrue("""<p class="crumb"><a href="/learn">""" in page, "${topic.slug}: no crumb back to /learn")
            assertTrue("""<figure class="learn-figure">""" in page, "${topic.slug}: no illustration")
            assertTrue(Regex("""<svg[^>]+role="img"[^>]+aria-label="[^"]+"""").containsMatchIn(page),
                "${topic.slug}: the diagram is not labelled for a screen reader")
        }
    }

    /** Strict JSON, for the reason `SearchPresenceTest` gives, and the two blocks a crawler
     *  reads off an explainer: what the article is, and where it sits. */
    @Test
    fun `every topic page carries a TechArticle and a breadcrumb trail`() {
        for (topic in LearnTopic.entries) {
            val blocks = ld(html("/learn/${topic.slug}"))
            val article = assertNotNull(blocks.firstOrNull { it["@type"].asString() == "TechArticle" }, topic.slug)
            assertEquals("https://keyboardbuilders.net/learn/${topic.slug}", article["url"].asString())
            assertEquals(topic.title, article["headline"].asString())

            val crumbs = assertNotNull(blocks.firstOrNull { it["@type"].asString() == "BreadcrumbList" }, topic.slug)
            val list = crumbs["itemListElement"]
            val items = (0 until list.size()).map { list[it]["item"].asString() }
            assertEquals(listOf("https://keyboardbuilders.net/learn", "https://keyboardbuilders.net/learn/${topic.slug}"), items)
        }
    }

    @Test
    fun `an unknown topic is a 404`() {
        assertEquals(404, mockMvc.get("/learn/no-such-term").andReturn().response.status)
    }

    @Test
    fun `the index and every topic are in the sitemap`() {
        val sitemap = html("/sitemap.xml")

        assertTrue("<loc>https://keyboardbuilders.net/learn</loc>" in sitemap)
        for (topic in LearnTopic.entries) {
            assertTrue("<loc>https://keyboardbuilders.net/learn/${topic.slug}</loc>" in sitemap, topic.slug)
        }
    }

    @Test
    fun `the footer links to learn`() {
        val footer = html("/").substringAfter("<footer").substringBefore("</footer>")

        assertTrue("""href="/learn"""" in footer, footer)
    }

    /** Every "see them all" link is a filter the catalogue can read: a typo in a facet token
     *  would 400 there, and nothing on the learn page itself would show it. */
    @Test
    fun `every see-all link is a filter the catalogue answers`() {
        for (topic in LearnTopic.entries) {
            val hrefs = Regex("""<a class="learn-all" href="([^"]+)"""").findAll(html("/learn/${topic.slug}"))
                .map { it.groupValues[1].replace("&amp;", "&") }.toList()
            for (href in hrefs) {
                assertEquals(200, mockMvc.get(href).andReturn().response.status, "${topic.slug}: $href")
            }
        }
    }

    @Test
    fun `key spacing shows designs at a non-standard spacing, with the number`() {
        val page = html("/learn/key-spacing")
        val custom = catalog.all().filter { (it.core.pitch as? Recorded.Known)?.value is KeyPitch.Custom }
            .associateBy { it.core.slug.value }

        assertTrue(custom.isNotEmpty(), "the corpus has no non-standard spacing to list")
        val drawn = list(page, "Designs at a non-standard spacing")
        assertEquals(custom.size, drawn.total)
        assertTrue(drawn.cards.isNotEmpty(), "no card drawn")
        for ((slug, card) in drawn.cards) {
            assertTrue(slug in custom, "$slug is not at a non-standard spacing")
            assertTrue(Regex("""<span class="related-reason">keys .+ apart</span>""").containsMatchIn(card), card)
        }
        val unrecorded = catalog.all().count { it.core.pitch is Recorded.Unknown }
        assertTrue("$unrecorded designs" in page, "the unrecorded count ($unrecorded) is not stated")
        for (pitch in listOf("mx", "choc", "other")) {
            assertTrue("""href="/designs?pitch=$pitch"""" in page, "no link to pitch=$pitch")
        }
    }

    /**
     * "Usually 18 x 17" holds for boards made only for Choc switches, and the corpus is held
     * to it here; counting every board that also takes Choc (dual MX/Choc footprints, mostly
     * at MX spacing) it is a coin flip, so the page and its search snippet say "only".
     */
    @Test
    fun `key spacing's usually-18-by-17 claim is about Choc-only boards, and the corpus bears it out`() {
        val choc = setOf(SwitchFootprint.ChocV1, SwitchFootprint.ChocV2, SwitchFootprint.ChocMini)
        val pitches = catalog.all()
            .filter { it.core.switches.isNotEmpty() && choc.containsAll(it.core.switches) }
            .mapNotNull { (it.core.pitch as? Recorded.Known)?.value }
        assertTrue(pitches.count { it == KeyPitch.Choc } * 2 > pitches.size,
            "Choc spacing is not most Choc-only boards' spacing: ${pitches.groupingBy { it::class.simpleName }.eachCount()}")

        val page = html("/learn/key-spacing").replace(Regex("\\s+"), " ")
        assertTrue("Boards made only for Kailh Choc switches usually space their keys" in page)
        assertTrue("Boards made only for Choc switches usually use 18 × 17 mm" in LearnTopic.KeySpacing.description)
    }

    /** 18 mm pitch less a 17.5 mm cap is the gap between two caps, not a margin around each. */
    @Test
    fun `key spacing says the half millimeter is between neighboring caps`() {
        val page = html("/learn/key-spacing").replace(Regex("\\s+"), " ")

        assertTrue("half a millimeter between neighboring caps" in page)
        assertFalse("half a millimeter around each" in page)
    }

    /**
     * A Choc v2 board at Choc spacing needs a cap that is Choc-sized AND cross-stemmed, and
     * the usual Choc-spaced caps are neither-nor: their stem is Choc v1's. The claim about
     * which caps are both is beekeeb's, a shop that sells them, so the page cites it and
     * says whose claim it is. The Choc page points to the answer rather than repeating it.
     */
    @Test
    fun `key spacing says the usual Choc-spaced caps do not fit Choc v2, and cites who says which do`() {
        val page = html("/learn/key-spacing").replace(Regex("\\s+"), " ")

        assertTrue("have Choc v1 stems, so they do not fit Choc v2" in page, "no Choc v2 caveat")
        assertTrue("""href="https://showcase.beekeeb.com/choc-spaced-chocv2-keycaps/"""" in page, "beekeeb's guide is not cited")
        assertTrue("Tai-Hao's MT 165 and THCS" in page)
        assertTrue("a shop that sells them" in page, "the claim is not marked as the shop's own")

        val choc = html("/learn/choc-switches").replace(Regex("\\s+"), " ")
        assertTrue("""<a href="/learn/key-spacing">""" in choc, "the Choc page does not point to the keycap answer")
    }

    /** "Not MX" leaves the newcomer's question open; the page says which caps it does take. */
    @Test
    fun `the choc page says which keycaps a Mini Choc takes`() {
        val page = html("/learn/choc-switches").replace(Regex("\\s+"), " ")

        assertTrue("its keycap mount as Kailh Choc, the same as Choc v1" in page)
    }

    /** Each footprint's list draws only boards drilled for it, and states how many there
     *  are in all. */
    @Test
    fun `the choc page's lists each draw only their own footprint`() {
        val page = html("/learn/choc-switches")
        val bySlug = catalog.all().associateBy { it.core.slug.value }

        for ((footprint, heading) in listOf(
            SwitchFootprint.ChocV1 to "Choc v1 designs", SwitchFootprint.ChocV2 to "Choc v2 designs",
            SwitchFootprint.ChocMini to "Mini Choc designs", SwitchFootprint.KailhX to "Kailh X designs",
        )) {
            val drawn = list(page, heading)
            assertEquals(catalog.all().count { footprint in it.core.switches }, drawn.total, heading)
            assertTrue(drawn.cards.isNotEmpty(), "no $footprint card")
            for (slug in drawn.cards.keys) {
                assertTrue(footprint in bySlug.getValue(slug).core.switches, "$slug is under $heading")
            }
        }
        val v1 = catalog.all().count { SwitchFootprint.ChocV1 in it.core.switches }
        assertTrue("""<span class="learn-count">$v1</span>""" in page, "the Choc v1 total ($v1) is not stated")
        assertTrue("""href="/designs?switch_footprint=choc_v1"""" in page)
    }

    /**
     * The hole diagram is the view from the TOP of the board, the side a builder holds up
     * against a PCB. KiCad footprints are drawn that way (ergogen's choc.js: pins at (0, -5.9)
     * and (5, -3.8), y down; ceoloide's v1/v2 footprint puts the v2 slot at (-5, 5.15)).
     * Turned a quarter turn so the first pin is on the left, the second pin is up-left of
     * center and the v2 slot down-right. Plotting Kailh's y-up numbers with y down drew the
     * mirror image, the view from below.
     */
    @Test
    fun `the Choc hole diagram is the view from the top of the board`() {
        val svg = html("/learn/choc-switches").substringAfter("""<figure class="learn-figure">""").substringBefore("</svg>")
        fun at(cx: Double): List<Double> =
            Regex("""<circle class="lf-mark" cx="([\d.]+)" cy="([\d.]+)"""").findAll(svg)
                .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
                .filter { kotlin.math.abs(it.first - cx) < 0.01 }.map { it.second }.toList()
        // Centers at y 22; the second pin is 3.8 mm left of center on each switch.
        assertEquals(listOf(17.0), at(22 - 3.8), "Choc v1's second pin is not up-left of center")
        assertEquals(listOf(17.0), at(72 - 3.8), "Choc v2's second pin is not up-left of center")
        val slot = assertNotNull(Regex("""<ellipse class="lf-hole" cx="([\d.]+)" cy="([\d.]+)"""").find(svg))
        assertEquals("27", slot.groupValues[2], "Choc v2's slot is not down-right of center")
    }

    /** Unknown must never read as "no": the page lists both recorded answers and says, in
     *  numbers, how many splits nobody has recorded. */
    @Test
    fun `the reversible page lists both recorded answers and counts the unrecorded splits`() {
        val page = html("/learn/reversible-pcb")
        val splits = catalog.all().mapNotNull { d -> (d.core.body as? Body.Split)?.let { d.core.slug.value to it.reversible } }

        for ((answer, heading) in listOf(true to "Recorded as one reversible PCB", false to "Recorded as two different PCBs")) {
            val recorded = splits.filter { it.second == Recorded.Known(answer) }.map { it.first }.toSet()
            assertTrue(recorded.isNotEmpty(), "no split recorded as $answer to list")
            val drawn = list(page, heading)
            assertEquals(recorded.size, drawn.total, heading)
            assertTrue(drawn.cards.isNotEmpty() && recorded.containsAll(drawn.cards.keys), "$heading: ${drawn.cards.keys}")
        }
        val unknown = splits.count { it.second is Recorded.Unknown }
        assertTrue("$unknown of the ${splits.size} split designs" in page, "the unrecorded count is not stated")
    }

    /** The Cradio Z lp is named, from its own board file, as a reversible board; while the
     *  corpus has no answer for it, it is missing from the "recorded" list, and the page
     *  says why rather than leave a reader to wonder. */
    @Test
    fun `the reversible page says why its Cradio Z lp example is missing from the lists`() {
        val page = html("/learn/reversible-pcb").replace(Regex("\\s+"), " ")
        val body = catalog.all().first { it.core.slug.value == "cradio-z-lp" }.core.body as Body.Split
        val clause = "It isn't recorded in the catalog yet, so it's not in the lists below"

        assertTrue("""href="/designs/cradio-z-lp"""" in page)
        assertEquals(body.reversible is Recorded.Unknown, clause in page, "reversible is ${body.reversible}")
    }

    /** Stagger is read off each board's own key positions, so all three answers are real
     *  ones; the designs nobody has measured are a number of their own, never "ortho". */
    @Test
    fun `the stagger page counts each kind, links each filter and states the unmeasured count`() {
        val page = html("/learn/stagger")
        val all = catalog.all()

        for (kind in Stagger.entries) {
            val n = all.count { it.extras.stagger == Recorded.Known(kind) }
            assertTrue(n > 0, "no $kind design to count")
            val param = kind.name.lowercase()
            assertTrue(Regex("""href="/designs\?stagger=$param"[^>]*>$n """).containsMatchIn(page), "$kind: count $n not linked to stagger=$param")
        }
        val unmeasured = all.count { it.extras.stagger is Recorded.Unknown }
        assertTrue("$unmeasured of the ${all.size} designs" in page, "the unmeasured count ($unmeasured) is not stated")

        val ortho = all.filter { it.extras.stagger == Recorded.Known(Stagger.Ortho) }
        assertTrue(ortho.any { """href="/designs/${it.core.slug.value}"""" in page }, "no ortholinear design listed")
    }

    /** Every way `measure_stagger.py` leaves a design without a verdict: no board file, no
     *  file matching a published layout, too few keys to judge (`stagger_kind`'s under-six
     *  rule), files or hands that disagree, and the owner's "unknown" override. */
    @Test
    fun `the stagger page names every reason a design is unrecorded`() {
        val page = html("/learn/stagger").replace(Regex("\\s+"), " ")

        for (reason in listOf("no board file", "matches none of its published layouts",
                              "fewer than six keys", "disagree", "fits none of the three")) {
            assertTrue(reason in page, "the page does not give \"$reason\" as a reason")
        }
    }

    /** Like Key spacing, the label is itself the term a newcomer asks about, so it links
     *  whatever the value: the page also says what "not recorded" means here. */
    @Test
    fun `a stagger spec line's label leads to the stagger page, measured or not`() {
        fun dt(slug: String): String =
            assertNotNull(Regex("""<dt>((?:<a [^>]+>)?Stagger(?:</a>)?)</dt>""").find(html("/designs/$slug")), slug).groupValues[1]
        assertEquals("""<a href="/learn/stagger">Stagger</a>""", dt("corne-cherry-v4"))
        val unmeasured = catalog.all().first { it.extras.stagger is Recorded.Unknown }.core.slug.value
        assertEquals("""<a href="/learn/stagger">Stagger</a>""", dt(unmeasured), unmeasured)
    }

    /**
     * A design page's spec labels lead to the learn page that explains them (#108: "Key
     * spacing", "one reversible PCB" and "Kailh Mini Choc" become links a newcomer can
     * follow). The LABEL is the link, so the value links to the catalog filters stay as
     * they are; and a label links only where its page explains what the line says -- an
     * MX-only board's switch line is not a Choc question.
     */
    @Test
    fun `spec labels link to the learn page that explains them, where one does`() {
        fun dt(slug: String, label: String): String {
            val page = html("/designs/$slug")
            return assertNotNull(Regex("""<dt>((?:<a [^>]+>)?$label(?:</a>)?)</dt>""").find(page), "$slug: no $label line")
                .groupValues[1]
        }
        assertEquals("""<a href="/learn/key-spacing">Key spacing</a>""", dt("aya", "Key spacing"))
        assertEquals("""<a href="/learn/hotswap">Hotswap</a>""", dt("corne-cherry-v4", "Hotswap"))

        assertEquals("""<a href="/learn/choc-switches">Switch footprint</a>""", dt("cradio-pg1232", "Switch footprint"))
        assertEquals("Switch footprint", dt("corne-cherry-v4", "Switch footprint"), "an MX-only board links to the Choc page")

        // Reversible is printed on the Body line only where it is recorded, and only then
        // does the line say anything the reversible page explains.
        assertEquals("""<a href="/learn/reversible-pcb">Body</a>""", dt("architeuthis-dux", "Body"))
        assertEquals("""<a href="/learn/reversible-pcb">Body</a>""", dt("ferris-0-2-mini", "Body"))
        assertEquals("Body", dt("3w6", "Body"), "a split with no reversible record links to the reversible page")
    }

    /** No corpus field records an IO expander, so the list is hand-kept from the notes in
     *  `seed/bodies.toml` -- and every entry must still be a design the site serves, or the
     *  page would link a 404. */
    @Test
    fun `the IO expander page lists its hand-kept examples, each a live design, and says it is hand-kept`() {
        val page = html("/learn/io-expander")
        val slugs = catalog.all().map { it.core.slug.value }.toSet()

        assertTrue(IO_EXPANDER_EXAMPLES.isNotEmpty())
        for ((slug, _) in IO_EXPANDER_EXAMPLES) {
            assertTrue(slug in slugs, "$slug is not a design the catalog serves")
        }
        val drawn = list(page, "Designs known to use one")
        assertEquals(IO_EXPANDER_EXAMPLES.size, drawn.total)
        val notes = IO_EXPANDER_EXAMPLES.toMap()
        for ((slug, card) in drawn.cards) {
            assertTrue("""<span class="related-reason">${notes.getValue(slug)}</span>""" in card, card)
        }
        // No catalog filter holds the hand-kept list, so the count is stated, not linked.
        assertTrue("${drawn.cards.size} of ${drawn.total} shown" in drawn.after, drawn.after)
        assertTrue("kept by hand" in page, "the list does not say it is hand-kept")
        assertTrue("""href="/learn/reversible-pcb"""" in page, "no link to the reversible page")
    }

    /** Counts per part, each the catalog's own filter for it, plus the designs whose pads
     *  nobody has read -- which are neither hotswap nor solder. */
    @Test
    fun `the hotswap page counts each part and the unread designs, and links each filter`() {
        val page = html("/learn/hotswap")
        // Owner, 2026-10-04: the page named Keebio's guide without linking it.
        assertTrue("""<a href="https://blog.keeb.io/how-to-make-your-keyboard-hotswappable-with-mill-max-sockets/">Keebio's guide</a>""" in page)
        val mounts = catalog.all().map { it.extras.switchMounts }

        for (part in HotswapPart.entries) {
            val n = mounts.count { m -> (m as? Recorded.Known)?.value?.hotswapParts()?.contains(part) == true }
            assertTrue("""href="/designs?hotswap=${part.token}">$n """ in page, "${part.token}: count $n not linked")
        }
        val solderOnly = mounts.count { m -> (m as? Recorded.Known)?.value?.hotswapParts()?.isEmpty() == true }
        assertTrue("""href="/designs?hotswap=no">$solderOnly """ in page, "solder-only count $solderOnly not linked")
        val unread = mounts.count { it is Recorded.Unknown }
        assertTrue("For $unread designs" in page, "the unread count ($unread) is not stated")

        // Most boards carry one socket shape, but some carry pads for both: the page counts
        // them rather than saying every board has one or the other.
        val both = mounts.count { m ->
            (m as? Recorded.Known)?.value?.containsAll(setOf(SwitchMount.MxSocket, SwitchMount.ChocSocket)) == true
        }
        assertTrue(both > 0, "no design with pads for both socket shapes")
        assertTrue("$both designs have pads for both" in page.replace(Regex("\\s+"), " "), "the dual-socket count ($both) is not stated")

        // The Mill-Max list draws designs, and only designs whose pads take Mill-Max.
        val section = page.substringAfter("Takes Mill-Max sockets").substringBefore("learn-list-head")
        val listed = Regex("""href="/designs/([^"/?]+)"""").findAll(section).map { it.groupValues[1] }.toList()
        assertTrue(listed.isNotEmpty(), "no live list of Mill-Max designs")
        val bySlug = catalog.all().associateBy { it.core.slug.value }
        for (slug in listed) {
            val m = (bySlug.getValue(slug).extras.switchMounts as? Recorded.Known)?.value.orEmpty()
            assertTrue(SwitchMount.MillMax in m, "$slug is listed under Mill-Max but its pads say $m")
        }
    }

    /**
     * The owner, 2026-10-04: "the listing of keyboards that has X is way too long! [...] like
     * 3 designs for each filter would be enough." Every live list draws at most three of the
     * site's own cards, and the whole set is one link away with its size in the link -- or,
     * where the catalog has no filter for it, stated.
     */
    @Test
    fun `every live list draws at most three design cards, then links or states the whole set`() {
        for (topic in LearnTopic.entries) {
            val drawn = lists(html("/learn/${topic.slug}"))
            assertTrue(drawn.isNotEmpty(), "${topic.slug} has no live list")
            for (list in drawn) {
                val where = "${topic.slug} / ${list.heading}"
                assertTrue(list.cards.size <= 3, "$where draws ${list.cards.size} cards")
                assertEquals(minOf(3, list.total), list.cards.size, where)
                val text = if (list.total == 1) "See it in the catalog" else "See all ${list.total} in the catalog"
                val linked = Regex("""<a class="learn-all" href="/designs\?[^"]+">$text""").containsMatchIn(list.after)
                assertTrue(linked || "${list.cards.size} of ${list.total} shown" in list.after || list.total <= list.cards.size,
                    "$where: neither a see-all link nor a count -- ${list.after}")
            }
        }
        // Where a filter exists the link is there: hotswap's three lists all have one.
        val hotswap = lists(html("/learn/hotswap"))
        assertEquals(3, hotswap.size)
        assertTrue(hotswap.all { "See all ${it.total} in the catalog" in it.after }, hotswap.map { it.after }.toString())
    }

    /** The picture is /settings' choice here too, exactly as on a family page. */
    @Test
    fun `with render first chosen, the cards show PCB renders`() {
        val renderFirst = jakarta.servlet.http.Cookie(HeroFirst.COOKIE, "render")
        for (slug in listOf("hotswap", "choc-switches")) {
            val page = mockMvc.get("/learn/$slug") { cookie(renderFirst) }.andReturn().response.contentAsString
            val cards = lists(page).flatMap { it.cards.values }
            assertTrue(cards.isNotEmpty(), slug)
            assertTrue(cards.any { Regex("""<img src="/renders/[0-9a-f]+\.png" alt="[^"]*, PCB render"""").containsMatchIn(it) },
                "$slug: no render on any card")
        }
    }

    @Test
    fun `without the setting, the cards show build photos and no renders`() {
        for (slug in listOf("hotswap", "choc-switches")) {
            val cards = lists(html("/learn/$slug")).flatMap { it.cards.values }
            assertTrue(cards.isNotEmpty(), slug)
            assertTrue(cards.none { "/renders/" in it }, "$slug shows a render unasked")
            assertTrue(cards.any { """, built"""" in it }, "$slug: no build photo on any card")
        }
    }
}
