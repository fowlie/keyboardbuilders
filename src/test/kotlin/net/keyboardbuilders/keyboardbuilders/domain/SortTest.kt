package net.keyboardbuilders.keyboardbuilders.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Backlog #37. `?sort=` used to be a 200 that silently ordered nothing. */
class SortTest {

    @Test
    fun `absent or blank defaults to hearts, then stars (owner, 2026-11-27)`() {
        assertEquals(Sort.Hearts, Sort.of(null))
        assertEquals(Sort.Hearts, Sort.of(""))
        assertEquals(Sort.Hearts, Sort.of("   "))
    }

    @Test
    fun `every value this build documents parses back to itself`() {
        assertEquals(Sort.Hearts, Sort.of("hearts"))
        assertEquals(Sort.Stars, Sort.of("stars"))
        assertEquals(Sort.Name, Sort.of("name"))
        assertEquals(Sort.Keys, Sort.of("keys"))
        assertEquals(Sort.Updated, Sort.of("updated"))
        assertEquals(Sort.Complete, Sort.of("complete"))
    }

    /**
     * REFUSED, NOT SILENTLY DEFAULTED — the exact "tolerated, not honoured" bug this
     * type exists to close, wearing a different value. `?sort=stars2` looking like it
     * worked and quietly falling back to the default would be no improvement on `?sort=`
     * itself being ignored.
     */
    @Test
    fun `an unrecognised value is refused rather than silently defaulted`() {
        val refused = assertFailsWith<UnreadableQuery> { Sort.of("stars2") }
        assertEquals(
            "`sort=stars2` is not a value this build reads; sort is one of " +
                "hearts, stars, name, keys, updated, complete",
            refused.message,
        )
    }
}
