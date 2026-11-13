package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The top bar: the wordmark left, its keys (Designs, Favorites, Settings) right -- owner,
 * 2026-10-04. The phone menu already sat right; the wide bar's links now do too. The
 * header is a flex row, so an auto left margin on each is what pushes it to the far end.
 */
class TopBarTest {

    private val css = ClassPathResource("static/app.css").inputStream.use { it.readBytes().decodeToString() }

    private fun rule(selector: String): String = assertNotNull(
        Regex(Regex.escape(selector) + """\s*\{([^}]*)\}""").find(css)?.groupValues?.get(1),
        "no rule for $selector in app.css",
    )

    @Test
    fun `the bar's links sit right on a wide screen, and the phone menu stays right`() {
        assertTrue(Regex("""margin-left:\s*auto""").containsMatchIn(rule("header.site .bar-links")), rule("header.site .bar-links"))
        assertTrue(Regex("""margin-left:\s*auto""").containsMatchIn(rule("header.site .menu")), rule("header.site .menu"))
    }
}
