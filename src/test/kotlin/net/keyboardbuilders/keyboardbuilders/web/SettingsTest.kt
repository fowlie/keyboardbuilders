package net.keyboardbuilders.keyboardbuilders.web

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * /settings, the owner's ask of 2026-10-02: clear every favorite, and choose whether a
 * design page leads with the photo or the bare PCB render. *"when clicking on the family
 * links to compare e.g. all 8 sweep designs, it was REALLY nice to quickly browse through
 * the PCB renders, but when we added photos some of the PCB comparisons was lost."*
 *
 * THE PICTURE CHOICE IS A COOKIE, not localStorage like the hearts, so the server draws the
 * chosen hero and there is no flash of the other one first. A form POST sets it, so the
 * choice works without JavaScript; clearing favorites cannot, since they live in the
 * browser.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettingsTest {

    @Autowired lateinit var mockMvc: MockMvc

    private val renderFirst = Cookie("kb-hero", "render")

    private fun html(path: String, vararg cookies: Cookie) =
        mockMvc.get(path) { if (cookies.isNotEmpty()) cookie(*cookies) }.andReturn().response.contentAsString

    private fun radio(page: String, value: String) =
        Regex("""<input[^>]*name="hero"[^>]*value="$value"[^>]*>""").find(page)?.value

    @Test
    fun `the settings page offers both pictures, photo first by default`() {
        val response = mockMvc.get("/settings").andReturn().response
        assertEquals(200, response.status)

        val page = response.contentAsString
        assertTrue("checked" in assertNotNull(radio(page, "photo")), page)
        assertFalse("checked" in assertNotNull(radio(page, "render")))
        assertTrue("""<meta name="robots" content="noindex""" in page, "a preferences page is not for search")
    }

    @Test
    fun `choosing the render sets a year-long cookie and comes back to the page`() {
        val response = mockMvc.post("/settings") { param("hero", "render") }.andReturn().response

        assertEquals(303, response.status)
        assertEquals("/settings?saved", response.getHeader("Location"))
        val cookie = assertNotNull(response.getHeader("Set-Cookie"))
        assertTrue(cookie.startsWith("kb-hero=render"), cookie)
        assertTrue("Max-Age=31536000" in cookie, cookie)
        assertTrue("Path=/" in cookie, cookie)
        assertTrue("SameSite=Lax" in cookie, cookie)
    }

    @Test
    fun `choosing the photo again removes the cookie, since photo is the default`() {
        val response = mockMvc.post("/settings") { param("hero", "photo") }.andReturn().response

        val cookie = assertNotNull(response.getHeader("Set-Cookie"))
        assertTrue(cookie.startsWith("kb-hero="), cookie)
        assertTrue("Max-Age=0" in cookie, cookie)
    }

    @Test
    fun `the page shows the saved choice`() {
        val page = html("/settings", renderFirst)

        assertTrue("checked" in assertNotNull(radio(page, "render")), page)
        assertFalse("checked" in assertNotNull(radio(page, "photo")))
    }

    @Test
    fun `an unknown value is refused rather than stored`() {
        val response = mockMvc.post("/settings") { param("hero", "silhouette") }.andReturn().response

        assertEquals(400, response.status)
        assertEquals(null, response.getHeader("Set-Cookie"))
    }

    /** Each choice shows what it means, on a real design that has both pictures. */
    @Test
    fun `each choice is illustrated with a real design's photo and render`() {
        val page = html("/settings")
        val photo = Regex("""<label class="choice"[^>]*>\s*<input[^>]*value="photo"[\s\S]*?</label>""").find(page)?.value
        val render = Regex("""<label class="choice"[^>]*>\s*<input[^>]*value="render"[\s\S]*?</label>""").find(page)?.value

        assertTrue(Regex("""<img src="/photos/[0-9a-f]+-card\.jpg"""").containsMatchIn(assertNotNull(photo, page)), photo)
        assertTrue(Regex("""<img src="/renders/[0-9a-f]+\.png"""").containsMatchIn(assertNotNull(render)), render)
    }

    @Test
    fun `the settings page has a clear-favorites button`() {
        assertTrue("""id="clear-favorites"""" in html("/settings"))
    }

    // -- the design page honours it -----------------------------------------------------

    /** 3w6 has both an assembled photo and a PCB render. */
    @Test
    fun `a design page leads with the photo by default`() {
        val page = html("/designs/3w6")

        assertTrue("""data-hero="photo"""" in page, "no photo hero")
        assertFalse("""class="shot shot-photo"""" in page, "the photo is also in the rail")
    }

    @Test
    fun `with the render chosen it leads with the render, and the photo moves to the rail`() {
        val page = html("/designs/3w6", renderFirst)

        assertTrue("""data-hero="render"""" in page, "the render is not the hero")
        assertFalse("""data-hero="photo"""" in page)
        assertTrue(Regex("""<a class="shot shot-photo" href="[^"]+"""").containsMatchIn(page), "the photo vanished")
    }

    /** A design with no render keeps its photo: the choice is an order, not a filter.
     *  `junco` has a photo and no PCB render (backlog #102). */
    @Test
    fun `a design with no render keeps its photo whatever the choice`() {
        assertTrue("""data-hero="photo"""" in html("/designs/junco", renderFirst))
    }

    // -- the top bar --------------------------------------------------------------------

    @Test
    fun `every page links to settings, in the bar and in the small-screen menu`() {
        val header = html("/designs").substringAfter("<header").substringBefore("</header>")

        assertEquals(2, Regex("""href="/settings"""").findAll(header).count(), header)
        assertTrue("""<details class="menu">""" in header, header)
    }
}
