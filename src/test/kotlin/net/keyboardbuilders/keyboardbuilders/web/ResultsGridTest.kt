package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.core.io.ClassPathResource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `/designs` results grid.
 *
 * These strip comments before asserting, unlike `OffscreenRowsTest`'s regexes over the
 * raw text. A stray comment-close mid-comment voids the CSS that follows while leaving
 * it findable by any text search — which shipped a dead grid rule twice on 2026-09-10.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResultsGridTest {

    @Autowired lateinit var mockMvc: MockMvc

    private val css = ClassPathResource("static/app.css").inputStream
        .use { it.readBytes().decodeToString() }

    /** `app.css` with every comment removed, the way a parser sees it. */
    private val withoutComments = css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")

    /** The general form of the bug, rather than the one rule downstream of it. */
    @Test
    fun `every comment in the stylesheet is closed`() {
        assertEquals(
            Regex("""/\*""").findAll(css).count(),
            Regex("""\*/""").findAll(css).count(),
            "a comment is opened and not closed, which silently voids the CSS after it",
        )
        assertFalse(
            "/*" in withoutComments,
            "a comment survived stripping, so it is not terminated: " +
                withoutComments.substringAfter("/*").take(120),
        )
    }

    /** Asserted on the stripped stylesheet, so a commented-out rule fails here. */
    @Test
    fun `the search results are laid out as a responsive grid`() {
        val rule = Regex("""\.results-col ul\.results \{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
            .find(withoutComments)?.groupValues?.get(1)

        assertTrue(rule != null, "no `.results-col ul.results` rule survives parsing")
        assertTrue(
            Regex("""display:\s*grid""").containsMatchIn(rule!!),
            "the results list must be a grid, was: $rule",
        )
        // AUTO-FILL, not auto-fit (2026-10-01): auto-fit stretched a lone card across the
        // whole row -- a one-heart favourites page, a one-result search. auto-fill is just
        // as responsive and keeps a lone card the size it has in a full grid.
        assertTrue(
            Regex("""grid-template-columns:\s*repeat\(auto-fill,\s*minmax\(""").containsMatchIn(rule),
            "the columns must be responsive and keep a lone card's width (auto-fill + minmax), was: $rule",
        )
    }

    /**
     * "The same style on every page": the home page drew three columns while `/designs`
     * drew two, because each grid carried its own copy of the minimum. The home page's
     * `.answers` are excluded on purpose — four answers to a question, laid out in one
     * row, not cards for keyboards.
     */
    @Test
    fun `every grid of keyboard cards takes its minimum from one token`() {
        assertTrue(
            Regex("""--card-min:\s*[\d.]+rem""").containsMatchIn(withoutComments),
            "`--card-min` is not declared, so nothing defines how wide a card may get",
        )

        val cardGrids = listOf(".results-col ul.results", ".strip")
        cardGrids.forEach { selector ->
            val rule = Regex("""${Regex.escape(selector)} \{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
                .find(withoutComments)?.groupValues?.get(1)

            assertTrue(rule != null, "no `$selector` rule survives parsing")
            assertTrue(
                Regex("""minmax\(\s*var\(--card-min\)""").containsMatchIn(rule!!),
                "`$selector` sets its own card minimum instead of `--card-min`. Was: $rule",
            )
        }
    }

    /**
     * `width: 100%` reads as redundant beside `max-width` and is not: auto margins turn
     * off a flex item's stretch, and the grid inside then collapses to one column. Here
     * so that tidying it away fails loudly.
     */
    @Test
    fun `main states a width, or the auto margins collapse it to fit-content`() {
        val rule = Regex("""(?<![.\w])main \{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
            .find(withoutComments)?.groupValues?.get(1)

        assertTrue(rule != null, "no bare `main` rule survives parsing")
        assertTrue(
            Regex("""width:\s*100%""").containsMatchIn(rule!!),
            "`main` needs an explicit width, or its auto margins collapse it. Was: $rule",
        )
    }

    /** Per page type: a `wide` that leaked everywhere would be a site-wide restyle. */
    @Test
    fun `only the scanning surface asks for the wide measure`() {
        val designs = mockMvc.get("/designs").andReturn().response.contentAsString
        val onePage = mockMvc.get("/designs/corne-cherry-v4").andReturn().response.contentAsString

        assertTrue(
            Regex("""<main class="wide">""").containsMatchIn(designs),
            "/designs did not ask for the wide measure",
        )
        assertFalse(
            "class=\"wide\"" in onePage,
            "a design's own page took the wide measure; it is prose and 52rem is right for it",
        )
    }
}
