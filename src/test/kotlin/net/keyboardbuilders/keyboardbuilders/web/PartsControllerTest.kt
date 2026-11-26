package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.persistence.SeedImporter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ByteArrayResource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `/parts/dev-board/:slug` — ux.md §9 build-order item 6, added because Door B's
 * CONTROLLER list shows 20-odd bare board names and a bare name teaches a newcomer
 * nothing (owner-raised 2026-09-03).
 *
 * THE CONTROLLER CATALOGUE IS FIXED, THE DESIGN CORPUS IS NOT, and the split is the
 * design of this class rather than convenience. `seed/controllers.json` is regenerated
 * whenever the research improves, so asserting on its contents would go red on a good
 * day's data work — hence a fixture, deliberately shaped like the real thing (one
 * wireless ZMK-only board, one wired QMK-only board, one board whose firmware family
 * this build does not name), because a fixture where every board is alike cannot catch
 * what those rows differ on.
 *
 * The designs stay REAL because the test that matters most here is a cross-check
 * between two independent derivations of the same corpus fact, and a fixed catalog
 * makes the two sides count different worlds — the first version of this class asserted
 * exactly that and failed with "the page claims 153 designs; its own link lists 1".
 * That failure was the fixture's, not the code's, and the lesson is worth keeping: this
 * particular claim can only be checked against the corpus it claims to describe. No
 * number is written down below, so a reseed cannot break it.
 */
/**
 * THE SEED THE TEST CONTEXT IMPORTED AT STARTUP (`src/test/resources/config/`), in a
 * database of the context's own. It once read `./app.db` instead and passed only where
 * `bootRun` had filled that file; CI caught it on its first run, 2026-09-07. A test that
 * depends on a previous manual step is not a test, it is a reminder.
 */
// Parts off: the parts seed is checked against the real design and controller
// catalogues at startup, and this class swaps one of them for a fixture.
@SpringBootTest(properties = ["keyboardbuilders.parts.enabled=false"])
@AutoConfigureMockMvc
class PartsControllerTest {



    @TestConfiguration
    class Fixture {
        /**
         * `nice!nano v2` carries a primary source, `Elite-Pi` deliberately carries
         * none — `ControllerBoard.url` is nullable because the research doc cites no
         * clean link for some boards, and null is a real state rather than a gap to
         * fill with a guess.
         *
         * `BlueMicro840 v1` runs `bluemicro`, a family `firmwareLabel()` has no case
         * for. It is in the real catalogue, so the fallback it exercises is a fact this
         * build actually has to render, not a hypothetical.
         *
         * `Michi` carries `mcu_family: unknown`, which is a real state for five rows of
         * the live catalogue (`seed/controllers.toml`'s header names them and why). A
         * fixture where every board has a known chip could not catch a rule firing on a
         * chip nobody has read -- the failure that would quietly move designs out of
         * "shares the footprint" and into "cannot run".
         */
        @Bean
        @Primary
        fun fixedControllers(mapper: ObjectMapper) = ControllerCatalog(
            mapper,
            ByteArrayResource(
                """[{"name":"nice!nano v2","slug":"nice-nano-v2","footprint":"pro_micro","wireless":true,
                     "mcu_family":"nrf52","firmware":["zmk"],"url":"https://nicekeyboards.com/nice-nano"},
                    {"name":"Elite-Pi","slug":"elite-pi","footprint":"pro_micro","wireless":false,
                     "mcu_family":"rp2040","firmware":["qmk"]},
                    {"name":"BlueMicro840 v1","slug":"bluemicro840-v1","footprint":"pro_micro",
                     "wireless":true,"mcu_family":"nrf52","firmware":["bluemicro"]},
                    {"name":"Michi","slug":"michi","footprint":"pro_micro","wireless":false,
                     "mcu_family":"unknown","firmware":["qmk"]},
                    {"name":"Bluepill (STM32F103)","slug":"bluepill","footprint":"blue_pill",
                     "wireless":false,"mcu_family":"stm32","firmware":["qmk"]}]"""
                    .toByteArray()
            ),
        )
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun html(path: String) = mockMvc.get(path).andReturn().response.contentAsString

    private fun head(path: String) = html(path).substringAfter("<head>").substringBefore("</head>")

    private fun status(path: String) = mockMvc.get(path).andReturn().response.status

    // -- the page states the board's own facts ------------------------------------

    @Test
    fun `a dev board page states the footprint, the connectivity and the firmware`() {
        val page = html("/parts/dev-board/nice-nano-v2")

        assertEquals(200, status("/parts/dev-board/nice-nano-v2"))
        assertTrue("nice!nano v2" in page, page)
        assertTrue("Pro Micro" in page, "no footprint label")
        assertTrue("wireless" in page, "no connectivity")
        assertTrue("ZMK" in page, "no firmware family")
        assertTrue("https://nicekeyboards.com/nice-nano" in page, "no primary source link")
    }

    /** The wired half of the same fact, asserted separately because a template that
     *  printed "wireless" unconditionally would pass the test above. */
    @Test
    fun `a wired board says wired, not wireless`() {
        val page = html("/parts/dev-board/elite-pi")

        assertTrue("wired" in page, page)
        assertFalse("wireless" in page, "a wired board is described as wireless")
        assertTrue("QMK" in page)
    }

    /**
     * `firmwareLabel()`'s fallback. A real sourced fact must never vanish from the page
     * because it is not one of the three families this build spells specially — the
     * alternative a reflex would reach for (drop the unrecognised value) publishes a
     * board that appears to run no firmware at all.
     */
    @Test
    fun `a firmware family this build does not name is still rendered`() {
        val page = html("/parts/dev-board/bluemicro840-v1")

        assertTrue("Bluemicro" in page, "the unrecognised family was dropped rather than capitalised")
    }

    /** Null `url` is a real state (the research doc cites no clean link for some
     *  boards), so the section is absent rather than an empty or dead link. */
    @Test
    fun `a board with no cited source offers no project link`() {
        assertFalse("Project page" in html("/parts/dev-board/elite-pi"), "a link was rendered for a board with no url")
    }

    /**
     * Owner-reported: the project link is the only off-site link on this page and it was
     * the only one without the arrow-out-of-box glyph, so it looked like an internal one.
     *
     * THE GLYPH AND THE BEHAVIOUR ARE ONE ASSERTION, which is why this test refuses to
     * let them separate: `designs/show.kte` documents the icon as a promise that the link
     * opens in a new tab, and this link carried a plain right-arrow and no `target`. An
     * icon without the target would be a lie about where the click goes.
     */
    @Test
    fun `the off-site project link is marked as leaving the site`() {
        val page = html("/parts/dev-board/nice-nano-v2")
        val link = Regex("""<a class="external"[^>]*>Project page<svg[^>]*class="ext".*?</a>""",
            RegexOption.DOT_MATCHES_ALL).find(page)

        assertTrue(link != null, "the project link carries no external marker or glyph")
        assertTrue("""target="_blank"""" in link!!.value, "the glyph promises a new tab: $link")
        assertTrue("""rel="nofollow noopener"""" in link.value, "an off-site link needs rel")
        assertTrue("""title="Opens in a new tab"""" in link.value,
            "the text is words rather than a URL, so the title is what announces the tab")
        assertTrue("""aria-hidden="true"""" in link.value, "the glyph adds nothing to read out")
        assertFalse("&rarr;" in link.value, "the arrow said 'more this way', not 'leaving'")
    }

    @Test
    fun `an uncataloged slug is a 404, not a page about nothing`() {
        assertEquals(404, status("/parts/dev-board/no-such-board"))
    }

    // -- the claim the page makes about the corpus --------------------------------

    /**
     * THE TEST THIS FILE EXISTS FOR. `PartPresentation.facetValue()` hand-writes the six
     * `controller_footprint` strings that `SeedImporter` derives from an enum name via
     * `.snake()`. Nothing makes those two agree, and when they stop agreeing the page
     * does not throw: it renders "0 designs socket a Pro Micro-shaped controller" as a
     * finished sentence, linking to a page that shows every one of them.
     *
     * So the number is compared against the page its own link points at, and neither
     * side is written down here.
     */
    @Test
    fun `the design count agrees with the page its own link goes to`() {
        val page = html("/parts/dev-board/nice-nano-v2")

        val href = Regex("""href="(/designs\?[^"]*)"""").find(page)?.groupValues?.get(1)
        assertNotNull(href, "the count is not a link to anything")
        val claimed = Regex(""">(\d+) designs?</a>""").find(page)?.groupValues?.get(1)?.toInt()
        assertNotNull(claimed, "no design count on the page")

        val listed = Regex("""<p class="count">(\d+) designs?""")
            .find(html(href.replace("&amp;", "&")))
            ?.groupValues?.get(1)?.toInt()

        assertEquals(listed, claimed, "the page claims $claimed designs; its own link lists $listed")
        assertTrue(claimed > 0, "no design in the corpus sockets a Pro Micro, which cannot be right")
    }

    /**
     * The honesty rule `PartPresentation`'s own doc comment states, held down as a test
     * because it is a sentence anyone would "improve" into a claim we cannot support.
     * The corpus records which FOOTPRINT a design sockets, never which specific board
     * its author recommends (backlog #59's finding), so "153 designs use this board" is
     * a wrong number that looks right — the failure mode this project exists to avoid.
     */
    @Test
    fun `the page never claims a design uses this specific board`() {
        val body = html("/parts/dev-board/nice-nano-v2").substringAfter("<body")

        assertTrue("socket a Pro Micro-shaped controller" in body, "the honest claim is gone")
        for (overclaim in listOf("use this", "uses this", "designs use", "using this")) {
            assertFalse(overclaim in body, "the page overclaims: `$overclaim`")
        }
    }

    // -- the three tiers (owner's ruling, 2026-09-10) -----------------------------

    /**
     * Counts the rows in one of the page's three lists. Keyed off the heading rather than
     * a class, because the heading is what a reader navigates by and a list that lost its
     * heading is a list nobody can tell apart from the one above it.
     */
    private fun tier(page: String, heading: String): List<String> {
        val from = page.indexOf(">$heading<")
        if (from < 0) return emptyList()
        val section = page.substring(from).substringAfter("<ul class=\"results").substringBefore("</ul>")
        return Regex("""href="/designs/([a-z0-9-]+)"""").findAll(section).map { it.groupValues[1] }.toList()
    }

    /**
     * The designs a tier says it holds but does not draw. The tiers are capped because a
     * `DesignRow` costs ~2.7 kB of inline SVG and an uncapped nice!nano page measured
     * 556 kB against this site's own 187 kB norm -- so "drawn" and "held" are different
     * numbers by design, and the page states both.
     */
    private fun hidden(page: String, heading: String): Int {
        val from = page.indexOf(">$heading<")
        if (from < 0) return 0
        val section = page.substring(from).substringBefore("</ul>") +
            page.substring(from).substringAfter("</ul>").substringBefore("<h2")
        return Regex("""and (\d+) more, not drawn here""").find(section)?.groupValues?.get(1)?.toInt() ?: 0
    }

    private val runs = "You can build these with it"
    private val socketOnly = "Same socket, firmware not ready"
    private val ruledOut = "Fits the socket, cannot run"

    /**
     * THE PAGE THE OWNER ASKED FOR. A bare count and a link was the whole page before
     * this: a reader holding a nice!nano got a number and a dead end.
     *
     * NO ONE BOARD EXERCISES ALL THREE TIERS, and that is a property of the rules rather
     * than a gap in the fixture -- the first draft of this class assumed otherwise and
     * asserted the sum on a nice!nano alone, where `socketOnly` is EMPTY and a mutation
     * deleting the whole tier still passed. On an nRF52 board every Pro Micro design
     * either runs ZMK or is barred from QMK, so the middle tier has nobody to hold. It
     * takes a board with an unrecorded chip to populate it. Two boards, therefore, and
     * the reason each one is the right example is the point of the two tests.
     */
    @Test
    fun `a wireless ZMK board lists what it builds and what its chip cannot run`() {
        val page = html("/parts/dev-board/nice-nano-v2")

        assertTrue(tier(page, runs).isNotEmpty(), "no design is listed as buildable with a nice!nano")
        assertTrue(tier(page, ruledOut).isNotEmpty(), "no QMK-only design was ruled out on an nRF52 board")
        assertTrue(
            tier(page, socketOnly).isEmpty() && hidden(page, socketOnly) == 0,
            "an nRF52 board has no middle tier: every Pro Micro design runs ZMK or is barred from QMK",
        )
    }

    /**
     * THE MIDDLE TIER, on the only kind of board that has one. `Michi` runs QMK and its
     * chip is unrecorded, so a ZMK-only design shares its socket with no rule either way
     * -- which is precisely "designs that only share the same footprint", the tier the
     * owner named. It is also the tier a careless default-fill would empty, by inventing
     * a chip and promoting every row into a verdict.
     */
    @Test
    fun `a board with an unrecorded chip has designs that only share its socket`() {
        val page = html("/parts/dev-board/michi")

        assertTrue(tier(page, runs).isNotEmpty(), "no QMK design is buildable with a QMK board")
        assertTrue(
            tier(page, socketOnly).isNotEmpty(),
            "no design merely shares the socket, though ZMK-only Pro Micro designs exist",
        )
    }

    /**
     * THE CROSS-DERIVATION THIS FILE EXISTS FOR, extended to the lists. The count comes
     * from the facet tables (`DesignQueries`); the three lists come from filtering
     * `DesignCatalog.all()` on `Controller.Socket`. Nothing makes those agree, and when
     * they stop agreeing the page does not throw -- it renders a count and a set of lists
     * that describe different worlds, which is the shape of every bug this project keeps
     * finding. No number is written down, so a reseed cannot break it.
     *
     * RECONCILES DRAWN + UNDRAWN, not drawn alone -- the tiers are capped, so the rows on
     * screen are deliberately fewer than the count above them. This is the arithmetic a
     * reader does when they count 12 struck-through rows under a heading claiming 118,
     * and it is the reason that "and N more" line exists rather than a silent truncation.
     */
    @Test
    fun `every design in the footprint count appears in exactly one of the three lists`() {
        // BOTH BOARDS, because they populate different tiers and a one-board version of
        // this test cannot see a deleted tier that happens to be empty on that board.
        for (board in listOf("nice-nano-v2", "michi")) {
            val page = html("/parts/dev-board/$board")

            val claimed = Regex(""">(\d+) designs?</a>""").find(page)?.groupValues?.get(1)?.toInt()
            assertNotNull(claimed, "no design count on /parts/dev-board/$board")

            val listed = tier(page, runs) + tier(page, socketOnly) + tier(page, ruledOut)
            val undrawn = hidden(page, runs) + hidden(page, socketOnly) + hidden(page, ruledOut)

            assertEquals(
                claimed, listed.size + undrawn,
                "$board counts $claimed designs and accounts for ${listed.size} drawn + $undrawn undrawn",
            )
            assertEquals(listed.size, listed.toSet().size, "$board lists a design in more than one tier")
        }
    }

    /**
     * Ticket 16 decision 3 rule 1, applied to the reverse direction: a row a reader is
     * told not to buy is a claim about the world, so it names the documented rule. "This
     * is incompatible" is not checkable by a reader who doubts us; "nRF52 chips cannot
     * run QMK" is, and getting people out of having to trust us is the whole product.
     */
    @Test
    fun `a ruled-out row names the rule that ruled it out`() {
        val page = html("/parts/dev-board/nice-nano-v2")

        assertTrue(ruledOut in page, "the ruled-out section is missing entirely")
        assertTrue("nRF52 chips cannot run QMK" in page, "a ruled-out row states no reason")
    }

    /**
     * A board with no recorded chip can rule nothing out -- no rule fires without a
     * family, which is the correct outcome for a chip nobody has read. `Michi` is that
     * row in the fixture and five rows of the live catalog.
     *
     * THE FAILURE THIS GUARDS IS SILENT. A default-filled or mis-parsed family does not
     * blank the page; it moves designs from "shares the footprint" into "cannot run",
     * which reads as a working page and is the yal-tools failure `product.md` §0 cites.
     */
    @Test
    fun `a board with no recorded chip rules nothing out`() {
        val page = html("/parts/dev-board/michi")

        assertTrue(tier(page, ruledOut).isEmpty(), "a chipless board ruled designs out")
        assertFalse("Chip" in page.substringAfter("<dl class=\"spec\">").substringBefore("</dl>"),
            "an unrecorded chip was rendered as a spec line")
    }

    /** The chip is a stated fact where the research doc names one, and it is what the
     *  rule-outs below it are argued from -- a reader who cannot see the chip cannot
     *  check the rule. */
    @Test
    fun `a board with a recorded chip states it`() {
        val spec = html("/parts/dev-board/nice-nano-v2")
            .substringAfter("<dl class=\"spec\">").substringBefore("</dl>")

        assertTrue("nRF52" in spec, spec)
    }

    /**
     * Found by reading the rendered page rather than by a test: the Bluepill sockets
     * exactly one design and every sentence on its page read "1 designs". Small, and
     * exactly the kind of small that undermines a site whose entire pitch is that its
     * numbers are careful -- a reader who catches us miscounting one has no reason to
     * trust us about 118.
     *
     * ASSERTS THE ABSENCE ACROSS THE WHOLE BODY, not one sentence, because there are four
     * counted sentences on this page and the first fix only corrected the one that had
     * been looked at.
     */
    @Test
    fun `a board socketing one design says design, not designs`() {
        // The fixture's boards all sit on pro_micro, so the singular case needs a real
        // footprint only one design takes -- found from the corpus rather than fixed here.
        val single = mockMvc.get("/designs?controller_footprint=blue_pill").andReturn()
            .response.contentAsString
            .let { Regex("""<p class="count">(\d+) design""").find(it)?.groupValues?.get(1) }

        assertEquals("1", single, "blue_pill no longer has exactly one design; pick another footprint")

        val body = html("/parts/dev-board/bluepill").substringAfter("<body")

        assertFalse("1 designs" in body, "a plural on a single design")
        assertFalse("1 design s" in body, "the plural suffix detached from its word")
        assertTrue("1 design" in body, "the count vanished entirely")
    }

    // -- `/parts`, the hub (ux.md sitemap line 56) --------------------------------

    /**
     * THE LINK PROBLEM THIS PAGE WAS BUILT FOR. `SeoController.sitemap`'s own comment
     * names it: the board pages were "the only indexable pages whose sole inbound link
     * (Door B's `/compatible`) is itself `noindex`". So the assertion that matters is not
     * that `/parts` renders -- it is that every board reachable by slug is reachable by
     * clicking, with no slug written down here.
     */
    @Test
    fun `the parts index links to every cataloged board`() {
        val linked = Regex("""href="/parts/dev-board/([a-z0-9-]+)"""")
            .findAll(html("/parts")).map { it.groupValues[1] }.toSet()

        assertTrue(linked.isNotEmpty(), "the hub links no board at all")
        for (slug in linked) {
            assertEquals(200, status("/parts/dev-board/$slug"), "dead link on the hub: $slug")
        }
        // Both directions: a board the hub omits is a board only the sitemap can find.
        assertTrue("nice-nano-v2" in linked, linked.toString())
        assertTrue("bluepill" in linked, "a board on a minority footprint was dropped: $linked")
    }

    /**
     * The count on the hub is the board page's own top tier. Two derivations of one fact
     * on two pages is how they start disagreeing -- and this is the number a reader picks
     * a board by, so a drift here is a wrong value that looks right. Neither number is
     * written down.
     */
    @Test
    fun `the hub's buildable count agrees with the board's own page`() {
        val hub = html("/parts")
        val claimed = Regex(
            """href="/parts/dev-board/nice-nano-v2"[\s\S]*?builds (\d+) designs?"""
        ).find(hub)?.groupValues?.get(1)?.toInt()
        assertNotNull(claimed, "no buildable count for nice!nano on the hub")

        val onItsOwnPage = tier(html("/parts/dev-board/nice-nano-v2"), runs).size +
            hidden(html("/parts/dev-board/nice-nano-v2"), runs)

        assertEquals(onItsOwnPage, claimed, "the hub says $claimed, the board page says $onItsOwnPage")
    }

    /**
     * The same honesty rule as the board page, restated because this is a second page
     * that could break it. `ux.md` §114's own wording for this line is "used by 84
     * designs", and that is a claim nothing in the corpus supports -- so the wording that
     * shipped is deliberately not ux.md's.
     */
    @Test
    fun `the hub never claims a design uses a specific board`() {
        val body = html("/parts").substringAfter("<body")

        for (overclaim in listOf("use this", "uses this", "designs use", "using this", "used by")) {
            assertFalse(overclaim in body, "the hub overclaims: `$overclaim`")
        }
    }

    @Test
    fun `the hub is indexable, points at itself, and is in the sitemap`() {
        val head = head("/parts")

        assertFalse("noindex" in head, "the hub a crawler needs is noindexed")
        assertTrue("""<link rel="canonical" href="https://keyboardbuilders.net/parts"""" in head, head)
        assertTrue(
            "<loc>https://keyboardbuilders.net/parts</loc>" in html("/sitemap.xml"),
            "the hub is absent from the sitemap",
        )
    }

    /** Upward link, so the 34 pages are not one-way streets from the hub. */
    @Test
    fun `a board page links back up to the hub`() {
        assertTrue(
            """<a href="/parts">""" in html("/parts/dev-board/nice-nano-v2"),
            "no crumb back to the index",
        )
    }

    // -- the route as a crawlable page --------------------------------------------

    /**
     * Indexable, unlike Door B's `/compatible` — this page is complete for what it
     * claims rather than missing one of three decided sections. Paired with the sitemap
     * assertion in `SeoTest`: a page that is indexable and self-canonical but absent
     * from the sitemap hangs off one `noindex` page, which is how these 34 pages
     * shipped before this test existed.
     */
    @Test
    fun `a dev board page is indexable and points at itself`() {
        val head = head("/parts/dev-board/nice-nano-v2")

        assertFalse("noindex" in head, "the SEO front door is noindexed")
        assertTrue(
            """<link rel="canonical" href="https://keyboardbuilders.net/parts/dev-board/nice-nano-v2"""" in head,
            head,
        )
        assertTrue("<title>Keyboards you can build with a nice!nano v2 · Keyboard Builders</title>" in head, head)
    }

    /**
     * THE TITLE ASKS WHAT THE READER ASKS. "nice!nano v2 — dev board" named the part and
     * stopped; what people type is "keyboards for nice!nano" or "what can I build with a
     * nice nano". The page's first list is exactly that answer, so the title promises it
     * -- and only where that list has something in it.
     */
    @Test
    fun `a board that builds nothing is titled by the sockets it fits, not by what it builds`() {
        // FIXTURE-ONLY: this fixture's BlueMicro840 v1 runs only "bluemicro", which none of
        // its designs ships. The real one also runs ZMK and builds 38 designs; the live
        // boards with this title are the MechWild PillBug and the two Seeed XIAOs.
        // The board fits the keyboard's socket, not the other way round -- the page's
        // own words are "This board fits the socket these N designs take".
        val head = head("/parts/dev-board/bluemicro840-v1")

        assertTrue("<title>Keyboards with a socket for a BlueMicro840 v1 · Keyboard Builders</title>" in head, head)
    }

    @Test
    fun `the article is the one the board's name is said with`() {
        assertTrue("<title>Keyboards you can build with an Elite-Pi" in head("/parts/dev-board/elite-pi"))
    }

    @Test
    fun `the description leads with how many designs the board builds, the same number the page shows`() {
        val page = html("/parts/dev-board/nice-nano-v2")
        val description = Regex("""<meta name="description" content="([^"]*)"""").find(page)!!.groupValues[1]
        val stated = Regex("""^(\d+) keyboard designs? you can build with a nice!nano v2""").find(description)
        val shown = Regex("""these (\d+) designs take""").find(page)

        assertNotNull(stated, description)
        assertNotNull(shown, "the page states no count")
        assertEquals(shown.groupValues[1], stated.groupValues[1])
    }

    @Test
    fun `the description names the footprint and the firmware, for the snippet under the link`() {
        val description = Regex("""<meta name="description" content="([^"]*)"""")
            .find(head("/parts/dev-board/nice-nano-v2"))?.groupValues?.get(1)

        assertNotNull(description)
        assertTrue("Pro Micro" in description, description)
        assertTrue("ZMK" in description, description)
    }
}
