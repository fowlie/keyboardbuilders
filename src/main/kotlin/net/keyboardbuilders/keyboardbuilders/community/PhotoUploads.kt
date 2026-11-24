package net.keyboardbuilders.keyboardbuilders.community

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component
import java.sql.ResultSet

/** One build photo someone added (`V10__photo_upload.sql`). */
data class Upload(
    val id: Long,
    val slug: String,
    val login: String,
    val jpg: String,
    val width: Int,
    val height: Int,
    val status: Status,
    val note: String?,
    val reviewedBy: String?,
) {
    enum class Status { WAITING, APPROVED, REJECTED }
}

/** The design already has a photo, or one is waiting for review. */
class PhotoTaken(slug: String) : RuntimeException("'$slug' already has a photo or one waiting")

/**
 * Build photos people add, and their review. Who may do what is the caller's question.
 *
 * APPROVED PHOTOS ARE KEPT IN MEMORY, because every card on a list page asks for its
 * design's photo, and a query per card would be 30 queries a page for a table that
 * changes a few times a day. Every write here reloads it; nothing else writes the table.
 * Loaded on first use, not at construction, so Flyway has made the table by then.
 */
@Component
class PhotoUploads(private val db: JdbcClient) {

    @Volatile private var approved: Map<String, Upload>? = null

    fun approvedFor(slug: String): Upload? = (approved ?: reload())[slug]

    @Synchronized
    private fun reload(): Map<String, Upload> =
        db.sql("$SELECT WHERE status = 'approved'").query(::row).list()
            .associateBy { it.slug }.also { approved = it }

    /** The design's waiting or approved photo: while there is one, nobody else may add one. */
    fun current(slug: String): Upload? =
        db.sql("$SELECT WHERE slug = ? AND status IN ('waiting', 'approved')").param(slug)
            .query(::row).optional().orElse(null)

    fun get(id: Long): Upload? = db.sql("$SELECT WHERE id = ?").param(id).query(::row).optional().orElse(null)

    /** Oldest first, so the photo that has waited longest is reviewed first. */
    fun waiting(): List<Upload> = db.sql("$SELECT WHERE status = 'waiting' ORDER BY id").query(::row).list()

    /** Newest first: someone's own page leads with what they just sent. */
    fun byLogin(login: String): List<Upload> =
        db.sql("$SELECT WHERE login = ? ORDER BY id DESC").param(login.lowercase()).query(::row).list()

    /**
     * SYNCHRONIZED AND CHECKED FIRST: SQLite's driver reports the unique index as an
     * uncategorised error, so the index is the backstop and this check is the answer.
     */
    @Synchronized
    fun submit(slug: String, login: String, jpg: String, width: Int, height: Int): Long {
        if (current(slug) != null) throw PhotoTaken(slug)
        val keys = GeneratedKeyHolder()
        db.sql("INSERT INTO photo_upload (slug, login, jpg, width, height) VALUES (?, ?, ?, ?, ?)")
            .params(slug, login.lowercase(), jpg, width, height).update(keys)
        return keys.key!!.toLong()
    }

    /** Only a waiting photo can be decided; false if it was already decided or removed. */
    fun approve(id: Long, by: String): Boolean = decide(id, "approved", by, null)

    fun reject(id: Long, by: String, note: String?): Boolean =
        decide(id, "rejected", by, note?.trim()?.take(200)?.ifEmpty { null })

    private fun decide(id: Long, status: String, by: String, note: String?): Boolean {
        val changed = db.sql("""
            UPDATE photo_upload SET status = ?, note = ?, reviewed_by = ?, reviewed_at = strftime('%Y-%m-%dT%H:%M:%SZ')
            WHERE id = ? AND status = 'waiting'
        """.trimIndent()).param(status).param(note).param(by.lowercase()).param(id).update()
        reload()
        return changed == 1
    }

    /** True while any waiting or approved upload still uses these bytes. */
    fun inUse(jpg: String): Boolean =
        db.sql("SELECT 1 FROM photo_upload WHERE jpg = ? AND status IN ('waiting', 'approved') LIMIT 1")
            .param(jpg).query(Int::class.java).optional().isPresent

    /** Takes the photo off its page, and with it the 10 points it earned. */
    fun remove(id: Long) {
        db.sql("DELETE FROM photo_upload WHERE id = ?").param(id).update()
        reload()
    }

    fun approvedCount(login: String): Int =
        db.sql("SELECT count(*) FROM photo_upload WHERE login = ? AND status = 'approved'")
            .param(login.lowercase()).query(Int::class.java).single()

    /** Photos this person said yes or no to. Shown on their page; worth no points. */
    fun reviewedCount(login: String): Int =
        db.sql("SELECT count(*) FROM photo_upload WHERE reviewed_by = ?")
            .param(login.lowercase()).query(Int::class.java).single()

    private companion object {
        const val SELECT = "SELECT id, slug, login, jpg, width, height, status, note, reviewed_by FROM photo_upload"

        @Suppress("UNUSED_PARAMETER")
        fun row(rs: ResultSet, n: Int) = Upload(
            id = rs.getLong("id"), slug = rs.getString("slug"), login = rs.getString("login"),
            jpg = rs.getString("jpg"), width = rs.getInt("width"), height = rs.getInt("height"),
            status = Upload.Status.valueOf(rs.getString("status").uppercase()),
            note = rs.getString("note"), reviewedBy = rs.getString("reviewed_by"),
        )
    }
}
