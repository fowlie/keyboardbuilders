package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ux.md` §2.10's home page, minus the two doors v0.5 cannot open.
 *
 * Run against the REAL seed rather than a synthetic catalog, deliberately and unlike
 * `PaginationTest`. Every assertion here is about a shape or a relationship — one card per
 * repo, at least one link out, the scale left alone — and none of them names a count or a
 * slug, so a good day's data work cannot turn this red.
 */
/**
 * THE SEED THE TEST CONTEXT IMPORTED AT STARTUP (`src/test/resources/config/`), in a
 * database of the context's own. It once read `./app.db` instead and passed only where
 * `bootRun` had filled that file; CI caught it on its first run, 2026-09-07. A test that
 * depends on a previous manual step is not a test, it is a reminder.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HomeTest {



    @Autowired
    lateinit var mockMvc: MockMvc

    private fun home() = mockMvc.get("/").andReturn().response.contentAsString

    private fun featured(html: String) =
        Regex("""class="strip-card card" href="/designs/([^"]+)"""").findAll(html)
            .map { it.groupValues[1] }.toList()

    /** Each daily card's markup, for the per-card rules below. */
    private fun featuredCards(html: String): List<String> =
        html.substringAfter("<ul class=\"strip\">", "").substringBefore("</ul>")
            .split("<a class=\"strip-card card\"").drop(1)

    /** Six, down from eight on the owner's ask ("a bit fewer daily picks"): three across,
     *  two rows, now that each is a photo rather than a line drawing. */
    @Test
    fun `the page shows a strip of designs, each linking to its own page`() {
        val shown = featured(home())

        assertEquals(6, shown.size, "expected six featured designs, got $shown")
        assertEquals(shown.size, shown.toSet().size, "a design is featured twice: $shown")
    }

    /**
     * THE STRIP IS PHOTOS. A daily pick without one is skipped rather than drawn, and the
     * picture is the card-width copy -- the full photo is up to 1600px, which on six cards
     * is most of a megabyte nobody sees.
     */
    @Test
    fun `every daily pick shows its build photo, at card size`() {
        val cards = featuredCards(home())

        assertTrue(cards.isNotEmpty(), "no daily cards, so nothing was checked")
        cards.forEach { card ->
            val src = Regex("""<img src="([^"]+)"""").find(card)?.groupValues?.get(1)
            assertTrue(src != null, "a daily pick has no photo: ${card.take(160)}")
            assertTrue(src.endsWith("-card.jpg"), "a daily pick loads the full-size photo: $src")
        }
    }

    /**
     * AT LEAST TEN STARS, the owner's floor for the shop window. Read off the card's own
     * star chip, which is what a reader sees -- and a card with no chip fails, because an
     * unrecorded count does not show ten.
     */
    @Test
    fun `every daily pick has at least ten stars`() {
        val cards = featuredCards(home())

        assertTrue(cards.isNotEmpty(), "no daily cards, so nothing was checked")
        cards.forEach { card ->
            val shown = Regex("""title="GitHub stars">&#9733; ([0-9.]+)(k?)<""").find(card)
            assertTrue(shown != null, "a daily pick shows no star count: ${card.take(160)}")
            val (number, k) = shown.destructured
            val stars = number.toDouble() * (if (k == "k") 1000 else 1)
            assertTrue(stars >= 10, "a daily pick has only $stars stars")
        }
    }

    @Test
    fun `every daily pick says who made it`() {
        val cards = featuredCards(home())

        assertTrue(cards.isNotEmpty(), "no daily cards, so nothing was checked")
        cards.forEach { card ->
            assertTrue(Regex("""class="card-by">by [^<]+<""") in card, "a daily pick has no author: ${card.take(200)}")
        }
    }

    /**
     * THE ONE RULE THIS PAGE ADDS. Default order is `design_search_rank`, which is
     * stars-first, and stars belong to a REPO — so same-repo siblings arrive as a block and
     * the raw first eight are four Sofles, two Lily58s and two others. That reads as a
     * catalog of three keyboards.
     *
     * Asserted through the SLUG STEMS rather than by re-querying sources, because that is
     * what a reader actually sees: four cards all beginning "sofle" is the bug, whatever
     * the join says.
     */
    @Test
    fun `no two featured designs are near-duplicates of each other`() {
        val stems = featured(home()).map { it.takeWhile { c -> c != '-' } }

        assertEquals(stems.size, stems.toSet().size, "same-repo siblings in the strip: $stems")
    }

    /**
     * The silhouettes are DRAWN at a fixed 8px per key unit so sizes compare at a glance
     * (`ux.md`:551), and this checks the drawings themselves were not scaled to fit.
     *
     * WHAT IT CANNOT SEE is CSS: it reads each drawing's own viewBox, so a stylesheet that
     * fit every drawing to one width would still pass. The one place that does so is the
     * photo card's corner badge, on purpose -- it is a label for the photo, not a size
     * comparison. The answer tiles and the no-photo index cards keep the common scale.
     */
    @Test
    fun `the featured silhouettes keep the catalog's common scale`() {
        val heights = Regex("""class="silhouette" viewBox="0 0 [0-9.]+ ([0-9.]+)"""")
            .findAll(home()).map { it.groupValues[1].toDouble() }.toList()

        assertTrue(heights.size >= 6, "expected a silhouette per card, got ${heights.size}")
        assertTrue(
            heights.distinct().size > 1,
            "every silhouette is the same height, so they have been scaled to fit: $heights",
        )
    }

    @Test
    fun `the search box posts to the designs index, and works with no javascript`() {
        // `substringAfter` returns the WHOLE page when the marker is missing, and the whole
        // page contains a `/designs` link somewhere -- so without this the test passes on a
        // page with no search box at all.
        val form = home().substringAfter("<form class=\"home-find\"", "").substringBefore("</form>")
        assertTrue(form.isNotEmpty(), "no search box on the page")

        assertTrue("""action="/designs"""" in form, form)
        assertTrue("""method="get"""" in form, form)
        assertTrue("""name="q"""" in form, form)
    }

    private data class Answer(val href: String, val count: Int, val pictured: String?)

    private fun answers(html: String): List<Answer> =
        html.substringAfter("<ul class=\"answers\">").substringBefore("</ul>")
            .split("<a class=\"answer\"").drop(1).map { tile ->
                Answer(
                    href = Regex("""href="([^"]+)"""").find(tile)!!.groupValues[1].replace("&amp;", "&"),
                    count = Regex("""class="answer-count">(\d+) design""").find(tile)!!.groupValues[1].toInt(),
                    pictured = Regex("""aria-label="([^,"]+)""").find(tile)?.groupValues?.get(1),
                )
            }

    /** Since 2026-10-02 the heading says what the site is (owner, for search) and the
     *  question is the line under it, still before any answer. */
    @Test
    fun `the page asks what the reader is after, before anything else`() {
        val html = home()

        assertTrue(Regex("""<h1[^>]*>Find an open-source keyboard to build</h1>""") in html, "no headline")
        val question = html.indexOf("""<p class="home-q">What kind of keyboard are you after?</p>""")
        assertTrue(question > html.indexOf("<h1"), "no question under the headline")
        assertTrue(question < html.indexOf("<ul class=\"answers\">"), "the answers came before the question")
    }

    /** Both sides of the corpus, so the answers are a way in and not a highlight reel. */
    @Test
    fun `the answers are real links into the facet grammar, split and unibody both`() {
        val links = answers(home()).map { it.href }

        assertTrue(links.size >= 4, "expected several answers, got $links")
        assertTrue(links.all { it.startsWith("/designs?") }, links.toString())
        assertTrue(links.any { "body=split" in it }, links.toString())
        assertTrue(links.any { "body=unibody" in it }, links.toString())
    }

    /**
     * THE COUNT ON A TILE IS A PROMISE ABOUT THE PAGE BEHIND IT. Checked by following each
     * link rather than by re-running the query here, because a count computed by a second
     * code path could agree with itself and still disagree with what a reader lands on.
     */
    @Test
    fun `each answer's count is what its link actually returns, and none is empty`() {
        val all = answers(home())
        assertTrue(all.isNotEmpty(), "no answers on the page, so nothing was checked")
        for (answer in all) {
            val landed = mockMvc.get(answer.href).andReturn().response.contentAsString
            val shown = Regex("""<p class="count">(\d+) design""").find(landed)!!.groupValues[1].toInt()

            assertEquals(shown, answer.count, "${answer.href} promised ${answer.count}, returned $shown")
            assertTrue(answer.count > 0, "${answer.href} leads to nothing")
        }
    }

    /**
     * Stars-first ranking puts the same few boards at the top of most answers -- a Sofle is
     * split AND low-profile AND wireless -- so taken raw, three tiles drew the same keyboard.
     * The picture is meant to show what an answer looks like, and six tiles of one board
     * say every answer looks the same.
     */
    @Test
    fun `no two answers picture the same keyboard`() {
        val stems = answers(home()).mapNotNull { it.pictured }.map { it.lowercase().substringBefore(' ') }

        assertTrue(stems.size >= 2, "expected a board drawn on the shape answers, got $stems")
        assertEquals(stems.size, stems.toSet().size, "two answers picture the same board: $stems")
    }

    /**
     * A tile with neither a board nor an icon is a blank square above a label. Wireless
     * and low-profile get an icon because no board drawing shows either: a wireless
     * board's outline is any board's outline, and a silhouette is drawn from above,
     * where a slim switch looks exactly like a tall one.
     */
    @Test
    fun `wireless and low-profile show an icon rather than a board`() {
        val tiles = home().substringAfter("<ul class=\"answers\">").substringBefore("</ul>")
            .split("<a class=\"answer\"").drop(1)

        for (param in listOf("connectivity=wireless", "switch_footprint=choc_v1")) {
            val tile = tiles.firstOrNull { param in it } ?: error("no answer links to $param")
            assertTrue("class=\"answer-icon\"" in tile, "$param draws no icon")
            assertTrue("class=\"silhouette\"" !in tile, "$param still draws a board")
        }
    }

    @Test
    fun `every answer shows a board or an icon`() {
        val tiles = home().substringAfter("<ul class=\"answers\">").substringBefore("</ul>")
            .split("<a class=\"answer\"").drop(1)

        assertTrue(tiles.isNotEmpty(), "no answers on the page, so nothing was checked")
        tiles.forEach { tile ->
            val pic = tile.substringAfter("class=\"answer-pic\"").substringBefore("</span>")
            assertTrue("<svg" in pic, "an answer has an empty picture: ${tile.take(200)}")
        }
    }

    /**
     * The reason this page changed at all, stated as a test. It used to link to exactly one
     * other page, which is a waste of the site's highest-authority page and gives a crawler
     * one route in.
     */
    @Test
    fun `the home page links deep into the site, not just to the index`() {
        val internal = Regex("""href="(/[^"]*)"""").findAll(home())
            .map { it.groupValues[1] }.filterNot { it.endsWith(".css") }.toSet()

        // Six since 2026-09-26: the owner traded two of the eight for bigger photo cards,
        // knowingly -- these are the page's only links to individual designs.
        assertTrue(internal.count { it.startsWith("/designs/") } >= 6, "$internal")
        assertTrue(internal.any { it == "/designs" }, "the index itself should still be linked")
    }

    /** The count is stated, and it is the catalog's real size rather than a written-in number. */
    @Test
    fun `the page counts the corpus`() {
        val html = home()
        val claimed = Regex("""<p class="home-sub">(\d+) open-source""").find(html)!!.groupValues[1].toInt()
        val linked = Regex("""show me all (\d+)""").find(html)!!.groupValues[1].toInt()

        assertEquals(claimed, linked, "the line under the search box and the link disagree about the size")
        assertTrue(claimed > 0)
    }
}
