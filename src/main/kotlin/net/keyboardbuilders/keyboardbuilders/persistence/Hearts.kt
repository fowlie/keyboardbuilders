package net.keyboardbuilders.keyboardbuilders.persistence

import net.keyboardbuilders.keyboardbuilders.domain.Design
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * The heart totals -- `V6__heart.sql` -- and a signed-in person's own hearts,
 * `V12__account_heart.sql`. Neither is touched by the seed import, which [HeartsTest] pins.
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
        // An account's hearts move too. OR IGNORE: someone who hearted both slugs keeps one.
        for ((former, current) in renames) {
            db.sql("UPDATE OR IGNORE account_heart SET slug = ? WHERE slug = ?").params(current, former).update()
            db.sql("DELETE FROM account_heart WHERE slug = ?").param(former).update()
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

    /** The designs [login] has hearted, in the order hearted. */
    fun of(login: String): List<String> =
        db.sql("SELECT slug FROM account_heart WHERE login = ? ORDER BY id")
            .param(login.lowercase()).query { rs, _ -> rs.getString("slug") }.list()

    /** A signed-in heart: counted once per person, however often it is pressed. The total. */
    fun addFor(login: String, slug: String): Int {
        val new = db.sql("INSERT OR IGNORE INTO account_heart (login, slug) VALUES (?, ?)")
            .params(login.lowercase(), slug).update()
        return if (new == 1) add(slug) else count(slug)
    }

    /** A signed-in heart taken back: the total drops only if this person had hearted it. */
    fun removeFor(login: String, slug: String): Int {
        val gone = db.sql("DELETE FROM account_heart WHERE login = ? AND slug = ?")
            .params(login.lowercase(), slug).update()
        return if (gone == 1) remove(slug) else count(slug)
    }

    /**
     * A browser's hearts joining its account on sign-in. NOT COUNTED AGAIN: each was added
     * to the total when it was pressed. Returns the account's whole list afterwards.
     */
    fun merge(login: String, slugs: List<String>): List<String> {
        for (slug in slugs) {
            db.sql("INSERT OR IGNORE INTO account_heart (login, slug) VALUES (?, ?)")
                .params(login.lowercase(), slug).update()
        }
        return of(login)
    }
}
