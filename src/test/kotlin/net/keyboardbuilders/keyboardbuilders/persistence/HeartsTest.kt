package net.keyboardbuilders.keyboardbuilders.persistence

import net.keyboardbuilders.keyboardbuilders.web.SlugRenames
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.DefaultApplicationArguments
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals

/**
 * The heart count -- the first number on the site that visitors write. Owner, 2026-10-01:
 * replace the GitHub star counts with our own hearts, kept centrally and in the visitor's
 * own browser.
 */
@SpringBootTest
// Unused here, but the same annotations as the web tests put this class in their cached
// Spring context instead of starting a second one (#114).
@AutoConfigureMockMvc
class HeartsTest {

    @Autowired lateinit var hearts: Hearts
    @Autowired lateinit var importer: SeedImporter
    @Autowired lateinit var db: JdbcClient
    @Autowired lateinit var renames: SlugRenames

    /** After as well as before: the context is shared (#114), and a heart left behind
     *  would show on another class's design page. */
    @BeforeEach @AfterEach fun clear() {
        db.sql("DELETE FROM heart").update()
        db.sql("DELETE FROM account_heart").update()
    }

    @Test
    fun `a design nobody has hearted counts zero`() {
        assertEquals(0, hearts.count("sweep-2"))
    }

    @Test
    fun `a heart adds one and taking it back removes one`() {
        assertEquals(1, hearts.add("sweep-2"))
        assertEquals(2, hearts.add("sweep-2"))
        assertEquals(1, hearts.remove("sweep-2"))
        assertEquals(1, hearts.count("sweep-2"))
    }

    /** A visitor who clears their browser and un-hearts again must not drive the count
     *  below zero, which no reader could make sense of. */
    @Test
    fun `the count never goes below zero`() {
        assertEquals(0, hearts.remove("sweep-2"))
        assertEquals(0, hearts.count("sweep-2"))
    }

    /**
     * THE WHOLE REASON THIS TABLE IS SHAPED THE WAY IT IS. Every boot runs the seed
     * import, which opens with `DELETE FROM design` and lets the cascade empty everything
     * hanging off it. Ticket 53 ruled the central count out on 2026-08-27 for exactly this:
     * a visitor-written number would be wiped by every deploy. So `heart` does not
     * reference `design`, and this pins it.
     */
    @Test
    fun `hearts survive the seed import that runs on every boot`() {
        hearts.add("sweep-2")
        hearts.add("sweep-2")

        importer.importAll()

        assertEquals(2, hearts.count("sweep-2"))
    }

    /**
     * HEARTS ARE KEYED BY SLUG, AND SLUGS MOVE. `sweepv2` became `sweep-2` this week, and
     * the families work (#107) has more renames queued. Without this a renamed design's page
     * reads "♥ 0" while its hearts sit under a slug nothing publishes.
     */
    @Test
    fun `a renamed design's hearts follow it to its new slug`() {
        repeat(2) { hearts.add("sweepv2") }
        hearts.add("sweep-2")

        hearts.carry(mapOf("sweepv2" to "sweep-2"))

        assertEquals(3, hearts.count("sweep-2"), "the old slug's hearts did not join the new one's")
        assertEquals(0, hearts.count("sweepv2"))
    }

    /** It runs on every boot, so a second run must not count the same hearts again. */
    @Test
    fun `carrying hearts across renames twice changes nothing the second time`() {
        repeat(2) { hearts.add("sweepv2") }

        hearts.carry(mapOf("sweepv2" to "sweep-2"))
        hearts.carry(mapOf("sweepv2" to "sweep-2"))

        assertEquals(2, hearts.count("sweep-2"))
    }

    /** The wiring, with the real `seed/renames.json`: boot imports, then carries. */
    @Test
    fun `the boot import carries hearts across the corpus's own renames`() {
        hearts.add("sweepv2")

        ImportOnStartup(importer, hearts, renames).run(DefaultApplicationArguments())

        assertEquals(1, hearts.count("sweep-2"))
        assertEquals(0, hearts.count("sweepv2"))
    }

    @Test
    fun `counts for many designs come back in one call, zeros included`() {
        hearts.add("sweep-2")
        assertEquals(mapOf("sweep-2" to 1, "lily58" to 0), hearts.counts(listOf("sweep-2", "lily58")))
    }

    // -- a signed-in person's hearts (owner, 2026-11-27: "both, merged on login") ------

    /** One person, one heart: pressing it twice from two devices still counts once. */
    @Test
    fun `a signed-in heart counts once per person`() {
        assertEquals(1, hearts.addFor("alice", "sweep-2"))
        assertEquals(1, hearts.addFor("alice", "sweep-2"))
        assertEquals(2, hearts.addFor("bob", "sweep-2"))
        assertEquals(listOf("sweep-2"), hearts.of("alice"))
    }

    @Test
    fun `a signed-in heart taken back removes one, and only if it was there`() {
        hearts.addFor("alice", "sweep-2")
        hearts.addFor("bob", "sweep-2")

        assertEquals(1, hearts.removeFor("alice", "sweep-2"))
        assertEquals(1, hearts.removeFor("alice", "sweep-2"))
        assertEquals(emptyList(), hearts.of("alice"))
    }

    /** GitHub logins are case-insensitive, and the session carries whatever case GitHub sent. */
    @Test
    fun `a login is the same person in any case`() {
        hearts.addFor("Alice", "sweep-2")
        assertEquals(1, hearts.addFor("alice", "sweep-2"))
        assertEquals(listOf("sweep-2"), hearts.of("ALICE"))
    }

    /**
     * THE MERGE ON SIGN-IN. The browser's hearts were counted when they were pressed, so
     * they join the account without being counted again; what the account already had stays.
     */
    @Test
    fun `merging a browser's hearts into an account adds them without counting them twice`() {
        hearts.add("lily58")              // pressed anonymously, already in the total
        hearts.addFor("alice", "sweep-2")

        assertEquals(listOf("sweep-2", "lily58"), hearts.merge("alice", listOf("lily58", "sweep-2")))

        assertEquals(1, hearts.count("lily58"))
        assertEquals(1, hearts.count("sweep-2"))
    }

    /** The account's list is in the order hearted, the order /favorites shows. */
    @Test
    fun `an account's hearts come back in the order they were hearted`() {
        hearts.addFor("alice", "lily58")
        hearts.addFor("alice", "sweep-2")
        hearts.merge("alice", listOf("corne"))
        assertEquals(listOf("lily58", "sweep-2", "corne"), hearts.of("alice"))
    }

    @Test
    fun `a renamed design stays in the accounts that hearted it`() {
        hearts.addFor("alice", "sweepv2")
        hearts.addFor("bob", "sweepv2")
        hearts.addFor("bob", "sweep-2")

        hearts.carry(mapOf("sweepv2" to "sweep-2"))

        assertEquals(listOf("sweep-2"), hearts.of("alice"))
        assertEquals(listOf("sweep-2"), hearts.of("bob"))
    }

    /** A merged heart was never counted by this server if the browser's list outlived the
     *  count (an old database, a hand-edited list); taking it back must still stop at zero. */
    @Test
    fun `taking back a merged heart never drives the count below zero`() {
        hearts.merge("alice", listOf("sweep-2"))
        assertEquals(0, hearts.removeFor("alice", "sweep-2"))
        assertEquals(0, hearts.count("sweep-2"))
        assertEquals(0, hearts.removeFor("alice", "sweep-2"))
    }
}
