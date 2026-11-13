package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Which designs a learn page's list shows as cards (the owner, 2026-10-04: "like 3 designs
 * for each filter would be enough"). The input is already in the site's own order; the
 * choice only lifts the pictured ones ahead of the rest and stops at three.
 */
class LearnShownTest {

    @Test
    fun `pictured designs come first, each group in the site's order, at most three`() {
        val ranked = listOf("a", "b", "c", "d", "e")
        val pictured = setOf("b", "d")

        assertEquals(listOf("b", "d", "a"), learnShown(ranked) { it in pictured })
    }

    @Test
    fun `with every design pictured, it is simply the first three`() {
        assertEquals(listOf("a", "b", "c"), learnShown(listOf("a", "b", "c", "d")) { true })
    }

    @Test
    fun `fewer than three designs are all shown`() {
        assertEquals(listOf("y", "x"), learnShown(listOf("x", "y")) { it == "y" })
        assertEquals(emptyList(), learnShown(emptyList<String>()) { true })
    }
}
