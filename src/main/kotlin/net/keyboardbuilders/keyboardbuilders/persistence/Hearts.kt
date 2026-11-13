package net.keyboardbuilders.keyboardbuilders.persistence

import net.keyboardbuilders.keyboardbuilders.domain.Design
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * The heart totals -- `V6__heart.sql`. The only table besides Flyway's own that the seed
 * import never touches, which is the property [HeartsTest] pins.
 */
@Component
class Hearts(private val db: JdbcClient) {

    fun count(slug: String): Int =
        db.sql("SELECT count FROM heart WHERE slug = ?").param(slug)
            .query(Int::class.java).optional().orElse(0)

    /** Every slug asked for, the unhearted ones at zero, so a caller never guesses. */
    fun counts(slugs: Collection<String>): Map<String, Int> {
        if (slugs.isEmpty()) return emptyMap()
        val found = db.sql("SELECT slug, count FROM heart WHERE slug IN (:slugs)")
            .param("slugs", slugs)
            .query { rs, _ -> rs.getString("slug") to rs.getInt("count") }
            .list().toMap()
        return slugs.associateWith { found[it] ?: 0 }
    }

    /** [counts] for a page's designs, the one query a page of cards makes. */
    fun forDesigns(designs: Collection<Design>): Map<String, Int> =
        counts(designs.map { it.core.slug.value })

    /**
     * Move each former slug's hearts onto the slug it is now, and drop the old row.
     * Backlog #107's renames would otherwise strand a renamed design's hearts under a slug
     * nothing publishes. Runs on every boot, so it must be idempotent: once moved, the old
     * row is gone and a second run finds nothing to carry. Returns how many rows moved.
     */
    fun carry(renames: Map<String, String>): Int {
        var moved = 0
        for ((former, current) in renames) {
            val count = db.sql("DELETE FROM heart WHERE slug = ? RETURNING count").param(former)
                .query(Int::class.java).optional().orElse(null) ?: continue
            db.sql(
                """INSERT INTO heart (slug, count) VALUES (?, ?)
                   ON CONFLICT(slug) DO UPDATE SET count = count + excluded.count"""
            ).params(current, count).update()
            moved++
        }
        return moved
    }

    /** One more heart; the new total. One statement, so two at once cannot both read n. */
    fun add(slug: String): Int =
        db.sql(
            """INSERT INTO heart (slug, count) VALUES (?, 1)
               ON CONFLICT(slug) DO UPDATE SET count = count + 1
               RETURNING count"""
        ).param(slug).query(Int::class.java).single()

    /** One heart taken back, never below zero: a visitor who cleared their browser can
     *  un-heart something the server never counted for them. */
    fun remove(slug: String): Int =
        db.sql("UPDATE heart SET count = max(count - 1, 0) WHERE slug = ? RETURNING count")
            .param(slug).query(Int::class.java).optional().orElse(0)
}
