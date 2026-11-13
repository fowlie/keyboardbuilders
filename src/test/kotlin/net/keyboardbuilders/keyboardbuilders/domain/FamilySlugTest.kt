package net.keyboardbuilders.keyboardbuilders.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * A family's address, `/designs/families/{slug}`. Backlog #107 made "Corne" a name of its own,
 * and that name is what people type into a search engine ("corne keyboard", "lily58"),
 * so the URL is the name, lowercased and hyphenated -- the same shape a design slug has.
 */
class FamilySlugTest {

    @Test
    fun `a one-word name is its own slug, lowercased`() {
        assertEquals(Slug("corne"), FamilyName("Corne").slug)
        assertEquals(Slug("lily58"), FamilyName("Lily58").slug)
    }

    @Test
    fun `spaces and punctuation become single hyphens, never leading or trailing`() {
        assertEquals(Slug("bad-wings"), FamilyName("Bad Wings").slug)
        assertEquals(Slug("ergo-travel"), FamilyName("Ergo Travel").slug)
        assertEquals(Slug("dilemma-max"), FamilyName(" Dilemma  MAX! ").slug)
    }

    /** Not dropped: "Jörne" losing its o would be a different word. */
    @Test
    fun `an accented letter keeps its base letter`() {
        assertEquals(Slug("jorne"), FamilyName("Jörne").slug)
    }

    /** A name with nothing to spell a URL from is a seed error, not a page at "/designs/families/". */
    @Test
    fun `a name with no letters or digits has no slug`() {
        assertThrows<IllegalArgumentException> { FamilyName("!!").slug }
    }
}
