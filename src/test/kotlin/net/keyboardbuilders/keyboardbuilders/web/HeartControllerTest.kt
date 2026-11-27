package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PUT` hearts a design and `DELETE` takes the heart back, the shape the owner described
 * for ticket 53: the browser remembers the visitor's own hearts, and the server only
 * keeps the total. PUT and DELETE rather than POST on purpose: a cross-site page cannot
 * send either without a CORS preflight this site never answers, so a third-party page
 * cannot quietly heart a design on its visitors' behalf.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HeartControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var db: JdbcClient
    @Autowired lateinit var rate: HeartRate

    /** After as well as before: the context is shared (#114), and hearts or a spent rate
     *  left behind would show up in another class. */
    @BeforeEach @AfterEach fun clear() {
        db.sql("DELETE FROM heart").update()
        db.sql("DELETE FROM account_heart").update()
        rate.reset()
    }

    @Test
    fun `a heart returns the new total, and taking it back returns it again`() {
        val added = mockMvc.put("/designs/sweep-2/heart").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertEquals("""{"count":1}""", added)

        val removed = mockMvc.delete("/designs/sweep-2/heart").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertEquals("""{"count":0}""", removed)
    }

    /** No row for a design that does not exist: a slug is something a stranger types. */
    @Test
    fun `a design that does not exist cannot be hearted`() {
        mockMvc.put("/designs/no-such-board/heart").andExpect { status { isNotFound() } }
        assertEquals(0, db.sql("SELECT count(*) FROM heart").query(Int::class.java).single())
    }

    /** Unauthenticated writes, so the only defence is a per-visitor rate, kept in memory
     *  and never written down -- the line `V3__report.sql` drew for the reader reports. */
    @Test
    fun `one visitor hearting too fast is turned away`() {
        repeat(5) { mockMvc.put("/designs/sweep-2/heart").andExpect { status { isOk() } } }
        mockMvc.put("/designs/sweep-2/heart").andExpect { status { isTooManyRequests() } }
    }

    @Test
    fun `the design page shows the heart count`() {
        mockMvc.put("/designs/sweep-2/heart")
        val html = mockMvc.get("/designs/sweep-2").andReturn().response.contentAsString
        val button = Regex("<button[^>]*class=\"heart\"[^>]*>.*?</button>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value.orEmpty()

        assertTrue("data-slug=\"sweep-2\"" in button, "no heart button for this design")
        assertTrue(">1<" in button, "the count is not on the button: $button")
    }

    // -- the count wherever the stars are (owner, 2026-10-01) ------------------------

    /** The card markup for one design, from wherever cards are drawn. */
    private fun card(html: String, slug: String) =
        Regex("""<div class="card-wrap">\s*<a class="result-card[^"]*" href="/designs/$slug".*?</div>\s*</li>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value.orEmpty()

    @Test
    fun `a hearted design's card shows its hearts beside its stars`() {
        repeat(3) { mockMvc.put("/designs/sweep-2/heart") }

        for (page in listOf("/designs?q=sweep", "/favorites?d=sweep-2")) {
            val card = card(mockMvc.get(page).andReturn().response.contentAsString, "sweep-2")
            assertTrue(card.isNotEmpty(), "$page has no Sweep 2 card")
            assertTrue(Regex("""class="heart-count">3<""").containsMatchIn(card),
                "$page does not show Sweep 2's 3 hearts: $card")
        }
        // The dev-board page draws its own row (parts/fitList.kte), not the card.
        val row = Regex("""<a class="result-card" href="/designs/sweep-2".*?</a>""", RegexOption.DOT_MATCHES_ALL)
            .find(mockMvc.get("/parts/dev-board/nice-nano-v2").andReturn().response.contentAsString)?.value.orEmpty()
        assertTrue(Regex("""title="Hearts">3&#9829;<""").containsMatchIn(row), "the dev-board page lost Sweep 2's 3 hearts: $row")
    }

    /**
     * ZERO SHOWS TOO, owner 2026-10-01: *"that will quickly change. since the reddit posting,
     * I've had 500 visitors in 3 days."* A first version drew nothing below one heart, to
     * keep "♥ 0" off hundreds of cards; with that many visitors the counts fill in fast,
     * and a zero says the design can be hearted.
     */
    @Test
    fun `a card for a design nobody has hearted shows zero hearts`() {
        val card = card(mockMvc.get("/designs?q=sweep").andReturn().response.contentAsString, "sweep-high")
        assertTrue(card.isNotEmpty())
        assertTrue(Regex("""class="heart-count">0<""").containsMatchIn(card),
            "an unhearted card hides its heart count: $card")
    }

    /**
     * A SHORTLIST IS A URL. The visitor's hearts live in their browser, and `/favourites`
     * turns them into `?d=...` so the server renders ordinary cards -- and the link can be
     * sent to someone. Order is the visitor's, and a slug that no longer publishes is
     * skipped rather than an error: browsers keep hearts across renames we cannot see.
     */
    @Test
    fun `the favorites page renders the designs it is given, in that order`() {
        val html = mockMvc.get("/favorites?d=sweep-bling-lp&d=no-such-board&d=sweep-2")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        val linked = Regex("""class="result-card card" href="/designs/([a-z0-9-]+)"""")
            .findAll(html).map { it.groupValues[1] }.toList()

        assertEquals(listOf("sweep-bling-lp", "sweep-2"), linked)
    }

    /**
     * A browser keeps the slug it hearted, and slugs move: `sweepv2` is `sweep-2` now. The
     * favorite must still show, under its new name, and the page tells the browser so its
     * own list -- and the heart button on the renamed page -- catch up.
     */
    @Test
    fun `a favorite that was renamed still shows, and the browser is told its new slug`() {
        val html = mockMvc.get("/favorites?d=sweepv2").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val linked = Regex("""class="result-card card" href="/designs/([a-z0-9-]+)"""")
            .findAll(html).map { it.groupValues[1] }.toList()

        assertEquals(listOf("sweep-2"), linked)
        assertTrue("""data-from="sweepv2" data-to="sweep-2"""" in html,
            "the browser is not told that sweepv2 is sweep-2 now")
    }

    @Test
    fun `the favorites page with nothing in it says how to add some`() {
        val html = mockMvc.get("/favorites").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertTrue("favorites-empty" in html, "an empty shortlist rendered nothing to read")
    }

    // -- signed in (owner, 2026-11-27: "both, merged on login") ------------------------

    private val alice = oauth2Login().attributes { it["login"] = "alice" }

    @Test
    fun `a signed-in heart is kept with the account and counted once`() {
        mockMvc.put("/designs/sweep-2/heart") { with(alice) }.andExpect { content { json("""{"count":1}""") } }
        mockMvc.put("/designs/sweep-2/heart") { with(alice) }.andExpect { content { json("""{"count":1}""") } }

        mockMvc.get("/hearts") { with(alice) }.andExpect { content { json("""["sweep-2"]""") } }

        mockMvc.delete("/designs/sweep-2/heart") { with(alice) }.andExpect { content { json("""{"count":0}""") } }
        mockMvc.get("/hearts") { with(alice) }.andExpect { content { json("[]") } }
    }

    /** The browser's list joins the account on sign-in; slugs nothing publishes are dropped. */
    @Test
    fun `a browser's hearts merge into the account without being counted again`() {
        mockMvc.put("/designs/lily58/heart")                     // anonymous, counted now
        mockMvc.put("/hearts") {
            with(alice)
            contentType = MediaType.APPLICATION_JSON
            content = """["lily58", "no-such-board"]"""
        }.andExpect { status { isOk() }; content { json("""["lily58"]""") } }

        assertEquals(1, db.sql("SELECT count FROM heart WHERE slug = 'lily58'").query(Int::class.java).single())
    }

    @Test
    fun `nobody signed in has no account hearts to read or merge`() {
        mockMvc.get("/hearts").andExpect { status { isUnauthorized() } }
        mockMvc.put("/hearts") { contentType = MediaType.APPLICATION_JSON; content = "[]" }
            .andExpect { status { isUnauthorized() } }
    }

    /** Signed in, bare /favorites is the account's list, drawn by the server. */
    @Test
    fun `a signed-in person's favorites are their account's hearts`() {
        mockMvc.put("/designs/sweep-2/heart") { with(alice) }
        mockMvc.put("/designs/lily58/heart") { with(alice) }

        val html = mockMvc.get("/favorites") { with(alice) }.andReturn().response.contentAsString
        val linked = Regex("""class="result-card card" href="/designs/([a-z0-9-]+)"""")
            .findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(listOf("sweep-2", "lily58"), linked)
    }

    /** Every card carries a heart button, which is what lets a list of designs be hearted. */
    @Test
    fun `every card has a heart button for its design`() {
        val html = mockMvc.get("/designs?q=sweep").andReturn().response.contentAsString
        assertTrue(Regex("""<button[^>]*class="heart card-heart"[^>]*data-slug="sweep-2"""").containsMatchIn(html),
            "the Sweep 2 card has no heart button")
    }
}
