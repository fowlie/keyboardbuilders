package net.keyboardbuilders.keyboardbuilders.community

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Build photos from people (owner, 2026-11-24): add one where a design has none, a
 * reviewer says yes or no, +10 points a yes, review rights at 50.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BuildPhotosTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var db: JdbcClient
    @Autowired lateinit var uploads: PhotoUploads
    @Autowired lateinit var contributors: Contributors
    @org.springframework.beans.factory.annotation.Value("\${keyboardbuilders.photos.dir:build/photos}") lateinit var photosDir: String

    private fun as_(login: String) = oauth2Login().attributes { it["login"] = login }
    private val owner = as_("fowlie")
    private val alice = as_("alice")
    private val bob = as_("bob")

    /** No seed photo for any of these, measured 2026-11-24. */
    private val bare = listOf("abacus", "adalyn", "ajisai74", "4by3", "45", "67-e")
    /** 3w6 has a seed photo. */
    private val pictured = "3w6"

    @BeforeEach @AfterEach fun clear() {
        db.sql("DELETE FROM photo_upload").update()
        db.sql("DELETE FROM review_revoked").update()
        uploads.approve(-1, "nobody") // reloads the in-memory approved photos
    }

    private fun jpeg(w: Int = 40, h: Int = 30): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", it)
    }.toByteArray()

    private fun upload(slug: String, who: RequestPostProcessor, bytes: ByteArray = jpeg()) =
        mockMvc.multipart("/designs/$slug/photo") {
            file(MockMultipartFile("photo", "build.jpg", "image/jpeg", bytes)); with(who); with(csrf())
        }

    private fun decide(id: Long, who: RequestPostProcessor, decision: String, note: String? = null) =
        mockMvc.post("/moderation/photos/$id") {
            with(who); with(csrf()); param("decision", decision); note?.let { param("note", it) }
        }

    private fun html(url: String, who: RequestPostProcessor? = null) =
        mockMvc.get(url) { who?.let { with(it) } }.andReturn().response.contentAsString

    private fun approvedFor(login: String, n: Int) = bare.take(n).forEach {
        uploads.approve(uploads.submit(it, login, "0".repeat(32), 40, 30), "fowlie")
    }

    @Test
    fun `a design with no photo invites one, a design with one does not`() {
        assertTrue("Add yours" in html("/designs/${bare[0]}"))
        assertFalse("Add yours" in html("/designs/$pictured"))
    }

    @Test
    fun `adding a photo needs a GitHub sign-in`() {
        mockMvc.get("/designs/${bare[0]}/photo").andExpect { status { is3xxRedirection() } }
    }

    @Test
    fun `an uploaded photo waits for review and the page says so`() {
        upload(bare[0], alice).andExpect { status { is3xxRedirection() }; redirectedUrl("/builders/alice") }

        val page = html("/designs/${bare[0]}")
        assertTrue("waiting for review" in page)
        assertFalse("Add yours" in page)
        assertTrue("waiting for review" in html("/builders/alice", alice))
    }

    @Test
    fun `a second photo for the same design is refused while one waits`() {
        upload(bare[0], alice)
        upload(bare[0], bob).andExpect { status { isConflict() } }
        assertEquals(1, uploads.waiting().size)
    }

    @Test
    fun `a design that already has a photo takes no upload`() {
        upload(pictured, alice).andExpect { redirectedUrl("/designs/$pictured") }
        assertTrue(uploads.waiting().isEmpty())
    }

    @Test
    fun `a file that is not a photo is refused`() {
        upload(bare[0], alice, "hello".toByteArray()).andExpect { status { isBadRequest() } }
        assertTrue(uploads.waiting().isEmpty())
    }

    @Test
    fun `an approved photo shows on the page, uncredited, and earns 10 points`() {
        upload(bare[0], alice)
        decide(uploads.waiting().single().id, owner, "yes").andExpect { status { is3xxRedirection() } }

        val page = html("/designs/${bare[0]}")
        assertTrue("<figcaption>An assembled " in page, page)
        assertFalse("Photo added by" in page, page)
        assertFalse("@alice" in page, page)
        assertEquals(10, contributors.points("alice"))
    }

    @Test
    fun `a turned-down photo earns nothing, shows its note to the uploader, and frees the page`() {
        upload(bare[0], alice)
        decide(uploads.waiting().single().id, owner, "no", "this is a render")

        assertEquals(0, contributors.points("alice"))
        assertTrue("not accepted: \"this is a render\"" in html("/builders/alice", alice).replace("&#34;", "\""))
        assertFalse("this is a render" in html("/builders/alice", bob))
        assertTrue("Add yours" in html("/designs/${bare[0]}"))
    }

    @Test
    fun `review rights arrive at 50 points`() {
        approvedFor("alice", 4)
        assertFalse(contributors.canReview("alice"))
        approvedFor("bob", 0)
        uploads.approve(uploads.submit(bare[4], "alice", "1".repeat(32), 40, 30), "fowlie")
        assertEquals(50, contributors.points("alice"))
        assertTrue(contributors.canReview("alice"))
    }

    @Test
    fun `someone without review rights cannot decide a photo`() {
        upload(bare[0], alice)
        decide(uploads.waiting().single().id, bob, "yes").andExpect { status { isForbidden() } }
    }

    @Test
    fun `a reviewer sees the waiting photos at moderation but not the bot's questions`() {
        approvedFor("alice", 5)
        upload(bare[5], bob)
        val page = html("/moderation", alice)
        assertTrue("Build photos" in page)
        assertTrue("/moderation/photos/" in page)
        assertFalse("invite-only" in page)
    }

    @Test
    fun `a reviewer never approves their own photo, however many points`() {
        approvedFor("alice", 5)
        upload(bare[5], alice)
        assertTrue("Someone else reviews it" in html("/moderation", alice))
        decide(uploads.waiting().single().id, alice, "yes").andExpect { status { isForbidden() } }
    }

    @Test
    fun `a moderator can approve their own photo`() {
        upload(bare[0], owner)
        assertFalse("Someone else reviews it" in html("/moderation", owner))
        decide(uploads.waiting().single().id, owner, "yes").andExpect { status { is3xxRedirection() } }
        assertEquals("fowlie", uploads.approvedFor(bare[0])!!.login)
    }

    @Test
    fun `the owner can take review rights away, and give them back`() {
        approvedFor("alice", 5)
        mockMvc.post("/builders/alice/review-rights") { with(owner); with(csrf()); param("action", "revoke") }
        assertFalse(contributors.canReview("alice"))
        assertEquals(50, contributors.points("alice"))
        mockMvc.post("/builders/alice/review-rights") { with(owner); with(csrf()); param("action", "restore") }
        assertTrue(contributors.canReview("alice"))
    }

    @Test
    fun `only a moderator can take review rights away`() {
        approvedFor("alice", 5)
        mockMvc.post("/builders/alice/review-rights") { with(bob); with(csrf()); param("action", "revoke") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `whoever added a photo can remove it, and loses the points`() {
        approvedFor("alice", 1)
        val id = uploads.approvedFor(bare[0])!!.id
        assertTrue("/designs/${bare[0]}/photo\">Remove" in html("/designs/${bare[0]}", alice))
        mockMvc.post("/designs/${bare[0]}/photo/remove") { with(alice); with(csrf()); param("id", id.toString()) }
            .andExpect { redirectedUrl("/designs/${bare[0]}") }
        assertNull(uploads.approvedFor(bare[0]))
        assertEquals(0, contributors.points("alice"))
        assertTrue("Add yours" in html("/designs/${bare[0]}"))
    }

    @Test
    fun `a stranger cannot remove someone's photo, the owner can`() {
        approvedFor("alice", 1)
        val id = uploads.approvedFor(bare[0])!!.id
        assertFalse(">Remove<" in html("/designs/${bare[0]}", bob))
        mockMvc.post("/designs/${bare[0]}/photo/remove") { with(bob); with(csrf()); param("id", id.toString()) }
            .andExpect { status { isForbidden() } }
        mockMvc.post("/designs/${bare[0]}/photo/remove") { with(owner); with(csrf()); param("id", id.toString()) }
        assertNull(uploads.approvedFor(bare[0]))
    }

    /** sweep-2's repo is github.com/davidphilipbarr/sweep. */
    @Test
    fun `the board's designer can remove a photo of it`() {
        uploads.approve(uploads.submit("sweep-2", "alice", "0".repeat(32), 40, 30), "fowlie")
        val id = uploads.approvedFor("sweep-2")!!.id
        mockMvc.post("/designs/sweep-2/photo/remove") { with(as_("davidphilipbarr")); with(csrf()); param("id", id.toString()) }
        assertNull(uploads.approvedFor("sweep-2"))
    }

    @Test
    fun `a person's page shows their points and approved photos, and 404s for nobody`() {
        approvedFor("alice", 2)
        val page = html("/builders/alice")
        assertTrue("<strong>20</strong> points" in page)
        assertTrue("Photos (2)" in page)
        mockMvc.get("/builders/nobody-at-all").andExpect { status { isNotFound() } }
    }

    @Test
    fun `an approved upload wins over a seed photo that arrives later`() {
        uploads.approve(uploads.submit(pictured, "alice", "0".repeat(32), 40, 30), "fowlie")
        assertTrue("/photos/${"0".repeat(32)}.jpg" in html("/designs/$pictured"))
        assertNotNull(uploads.approvedFor(pictured))
    }

    /** Signing in lands on /account, so that is where a turned-down photo has to be found. */
    @Test
    fun `the account page tells the uploader what happened to their photos and links their page`() {
        upload(bare[0], alice)
        decide(uploads.waiting().single().id, owner, "no", "this is a render")
        upload(bare[1], alice)

        val page = html("/account", alice)
        assertTrue("1 not accepted" in page, page)
        assertTrue("1 waiting for review" in page)
        assertTrue("href=\"/builders/alice\"" in page)
    }

    @Test
    fun `a declined photo is deleted, and only its note is left`() {
        upload(bare[0], alice)
        val jpg = uploads.waiting().single().jpg
        val file = java.nio.file.Path.of(photosDir, "$jpg.jpg")
        assertTrue(java.nio.file.Files.exists(file))

        decide(uploads.waiting().single().id, owner, "no", "this is a render")

        assertFalse(java.nio.file.Files.exists(file))
        val page = html("/builders/alice", alice)
        assertTrue("this is a render" in page)
        assertFalse(jpg in page)
    }

    @Test
    fun `the top bar names whoever is signed in, and the footer invites everyone else to sign in`() {
        val signedIn = html("/about", alice)
        assertEquals(2, Regex("""<a href="/account">@alice</a>""").findAll(signedIn).count(), "top bar and phone menu")
        assertFalse("<a href=\"/account\">Sign in to add photos</a>" in signedIn)
        val anonymous = html("/about")
        assertTrue("<a href=\"/account\">Sign in to add photos</a>" in anonymous)
        assertFalse("@alice" in anonymous)
    }

    /** The one task the account page offers (owner, 2026-11-25): a photo where none is. */
    private fun suggested(page: String) =
        Regex("""<a class="task-go" href="/designs/([^/"]+)/photo">""").find(page)?.groupValues?.get(1)

    @Test
    fun `the account page offers one design that has no build photo`() {
        val slug = assertNotNull(suggested(html("/account", alice)))
        assertTrue("Add yours" in html("/designs/$slug"), "$slug has no photo")
    }

    @Test
    fun `a design whose photo waits for review is not offered`() {
        val first = assertNotNull(suggested(html("/account", alice)))
        upload(first, bob)
        assertNotEquals(first, suggested(html("/account", alice)))
    }

    @Test
    fun `a designer is offered their own design first`() {
        // abacus is nickolaij/Abacus_Rev2, with no seed photo.
        assertEquals("abacus", suggested(html("/account", as_("nickolaij"))))
    }

    @Test
    fun `not built that one shows another`() {
        val first = assertNotNull(suggested(html("/account", alice)))
        assertNotEquals(first, suggested(html("/account?task=2", alice)))
    }

    /** The footer link alone was hard for a new visitor to find (owner, 2026-11-25). */
    @Test
    fun `a signed-out visitor finds sign in in the top bar and the phone menu`() {
        val signIn = Regex("""<a href="/account">Sign in</a>""")
        assertEquals(2, signIn.findAll(html("/about")).count(), "top bar and phone menu")
        assertEquals(0, signIn.findAll(html("/about", alice)).count())
    }
}
