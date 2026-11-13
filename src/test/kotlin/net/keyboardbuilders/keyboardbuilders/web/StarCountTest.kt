package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ticket 54, whose three questions this pins as three groups of assertions.
 *
 * The scope question — whether showing the number turns the default list into the
 * leaderboard the product argues against — was closed by the owner in the feature's
 * favour, on the ground that `stars DESC` already sorts the list, so the leaderboard
 * exists whether or not the number is drawn and an unlabelled one is worse.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StarCountTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var activity: RepoActivity
    @Autowired lateinit var catalog: DesignCatalog

    private fun page(path: String) = mockMvc.get(path).andReturn().response.contentAsString

    // -- 1. Where it renders ---------------------------------------------------------

    @Test
    fun `a result row carries its star count`() {
        val html = page("/designs")

        assertTrue("class=\"card-stars\"" in html, "no star count rendered on any row")
        assertTrue("&#9733;" in html || "★" in html, "no star glyph")
    }

    @Test
    fun `the detail page states the count exactly`() {
        val design = catalog.all().first { activity.starsFor(it) != null }
        val exact = "%,d".format(activity.starsFor(design))

        assertTrue(
            exact in page("/designs/${design.core.slug.value}"),
            "the detail page does not state $exact",
        )
    }

    // -- 2. What an unknown renders as, and it is not zero ---------------------------

    /**
     * The row omits an unknown rather than writing "not recorded" into the scanning
     * surface. What it must never do is write `0`, which would claim GitHub told us
     * nobody had starred it — a wrong value wearing an absence's clothes.
     */
    @Test
    fun `a row never shows zero for a design whose count is unknown`() {
        val unknown = catalog.all().filter { activity.starsFor(it) == null }
        val html = page("/designs")

        unknown.forEach { design ->
            val row = html.substringAfter("/designs/${design.core.slug.value}\"", "")
                .substringBefore("</li>")
            assertFalse(
                "class=\"card-stars\"" in row,
                "${design.core.slug.value} has no known count but rendered one",
            )
        }
    }

    /**
     * The DETAIL page is the opposite: `ux.md` §5.3 says "not recorded" is a rendered
     * state and never a blank cell. So an unknown is stated, in the same style as every
     * other unrecorded field — and specifically not as `0`. It sits in the byline since
     * 2026-09-25, when the Spec table was grouped and stars were ruled not a spec of the
     * keyboard; the rule moved with it.
     */
    @Test
    fun `the detail page renders an unknown count as not recorded, never as zero`() {
        val design = catalog.all().firstOrNull { activity.starsFor(it) == null } ?: return
        val html = page("/designs/${design.core.slug.value}")
        val stars = html.substringAfter("class=\"byline-stars", "").substringBefore("</span>")

        assertTrue(NOT_RECORDED in stars, "expected `$NOT_RECORDED`, got: $stars")
        assertFalse("0 " in stars, "an unknown count must not read as zero")
    }

    // -- 3. Staleness ------------------------------------------------------------------

    /**
     * The build is rebuild-only on a cron, so every displayed count is as old as the last
     * rebuild. A bare number would look live. The detail page has room to say how old it
     * is, so it does.
     */
    @Test
    fun `the detail page says how old the number is`() {
        val design = catalog.all().first { activity.starsFor(it) != null }

        assertTrue(
            "checked" in page("/designs/${design.core.slug.value}"),
            "the count is stated without saying when it was checked",
        )
    }

    @Test
    fun `the age is phrased in units a reader can act on`() {
        val now = Instant.now()

        assertEquals("checked today", checkedPhrase(now, now))
        assertEquals("checked yesterday", checkedPhrase(now.minus(1, ChronoUnit.DAYS), now))
        assertEquals("checked 9 days ago", checkedPhrase(now.minus(9, ChronoUnit.DAYS), now))
        assertEquals("checked 3 months ago", checkedPhrase(now.minus(95, ChronoUnit.DAYS), now))
    }

    /**
     * The ROW's answer to staleness is the rounding itself: `2.5k` says "about this many",
     * which is the true claim for a number as old as the last cron run, where `2,529`
     * claims a precision it cannot have. Below a thousand there is nothing to round —
     * turning `78` into `80` would destroy information to signal an imprecision a
     * two-digit count does not have.
     */
    @Test
    fun `a row rounds above a thousand and does not below`() {
        assertEquals("0", formatStars(0))
        assertEquals("78", formatStars(78))
        assertEquals("999", formatStars(999))
        assertEquals("1k", formatStars(1_000))
        assertEquals("2.5k", formatStars(2_529))
        assertEquals("12k", formatStars(12_400))
    }
}
