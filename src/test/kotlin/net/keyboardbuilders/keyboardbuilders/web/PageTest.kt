package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The arithmetic of paging, separated from the page that renders it.
 *
 * It is separated because every bug pagination has ever had is an off-by-one at a
 * boundary — the last page holding one row, an empty corpus, a page past the end —
 * and those are cheap to state here and expensive to state through HTTP.
 */
class PageTest {

    @Test
    fun `the first page holds the first slice and knows there is more`() {
        val page = Page.of(number = 1, size = 100, total = 300)

        assertEquals(0 until 100, page.range)
        assertEquals(1, page.first)
        assertEquals(100, page.last)
        assertEquals(3, page.count)
        assertFalse(page.hasPrevious)
        assertTrue(page.hasNext)
    }

    @Test
    fun `the last page is short and knows there is no more`() {
        val page = Page.of(number = 3, size = 100, total = 236)

        assertEquals(200 until 236, page.range)
        assertEquals(201, page.first)
        assertEquals(236, page.last)
        assertTrue(page.hasPrevious)
        assertFalse(page.hasNext)
    }

    /** 300 designs at 100 a page is 3 pages, not 4. The classic off-by-one. */
    @Test
    fun `an exact multiple does not invent a trailing empty page`() {
        assertEquals(3, Page.of(1, size = 100, total = 300).count)
        assertEquals(4, Page.of(1, size = 100, total = 301).count)
    }

    /**
     * An empty corpus still has one page, and it is page 1. Zero pages would make
     * `/designs` itself a 404 the moment the seed is empty, which is a worse answer
     * than an empty list.
     */
    @Test
    fun `an empty corpus is one empty page, not zero pages`() {
        val page = Page.of(number = 1, size = 100, total = 0)

        assertEquals(IntRange.EMPTY, page.range)
        assertEquals(1, page.count)
        assertFalse(page.hasPrevious)
        assertFalse(page.hasNext)
    }

    /**
     * The URL is truth (`ux.md` §2.7), so a page that does not exist is not quietly
     * turned into one that does. Clamping would serve page 2 at `?page=99` and make
     * the address bar a lie.
     */
    @Test
    fun `a page past the end is refused rather than clamped`() {
        assertFailsWith<NoSuchPage> { Page.of(number = 4, size = 100, total = 300) }
        assertFailsWith<NoSuchPage> { Page.of(number = 2, size = 100, total = 0) }
    }

    @Test
    fun `a page number below one is refused`() {
        assertFailsWith<NoSuchPage> { Page.of(number = 0, size = 100, total = 300) }
        assertFailsWith<NoSuchPage> { Page.of(number = -1, size = 100, total = 300) }
    }
}
