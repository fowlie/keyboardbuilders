package net.keyboardbuilders.keyboardbuilders.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * The designs their author took off the site -- `V8__unpublished.sql`, backlog #116.
 * Whether the person asking may do it is the caller's question, not this class's.
 */
@Component
class Unpublished(private val db: JdbcClient) {

    fun contains(slug: String): Boolean =
        db.sql("SELECT 1 FROM unpublished WHERE slug = ?").param(slug)
            .query(Int::class.java).optional().isPresent

    fun all(): Set<String> =
        db.sql("SELECT slug FROM unpublished").query(String::class.java).list().filterNotNull().toSet()

    /** Idempotent: a second click keeps the first record's login and time. */
    fun add(slug: String, by: String) {
        db.sql("INSERT INTO unpublished (slug, by_login) VALUES (?, ?) ON CONFLICT(slug) DO NOTHING")
            .params(slug, by).update()
    }

    fun remove(slug: String) {
        db.sql("DELETE FROM unpublished WHERE slug = ?").param(slug).update()
    }
}
