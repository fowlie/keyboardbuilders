package net.keyboardbuilders.keyboardbuilders.community

import net.keyboardbuilders.keyboardbuilders.moderation.Moderators
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * Points and review rights (owner, 2026-11-24). One number per person, worked out from
 * what they did rather than stored, so it can never disagree with their photos.
 *
 *   photo approved   +10
 *   photo rejected     0
 *   reviewing          0
 *   review rights at  50
 *
 * Moderators (the configured list, the owner to start) can always review, and can take
 * anyone else's review rights away.
 */
@Component
class Contributors(
    private val uploads: PhotoUploads,
    private val moderators: Moderators,
    private val db: JdbcClient,
) {
    fun points(login: String): Int = uploads.approvedCount(login) * PHOTO_POINTS

    fun isModerator(login: String?): Boolean = moderators.includes(login)

    fun isRevoked(login: String): Boolean =
        db.sql("SELECT 1 FROM review_revoked WHERE login = ?").param(login.lowercase())
            .query(Int::class.java).optional().isPresent

    fun canReview(login: String?): Boolean = when {
        login == null -> false
        moderators.includes(login) -> true
        else -> points(login) >= REVIEW_AT && !isRevoked(login)
    }

    fun revoke(login: String, by: String) {
        db.sql("INSERT INTO review_revoked (login, by_login) VALUES (?, ?) ON CONFLICT(login) DO NOTHING")
            .params(login.lowercase(), by.lowercase()).update()
    }

    fun restore(login: String) {
        db.sql("DELETE FROM review_revoked WHERE login = ?").param(login.lowercase()).update()
    }

    companion object {
        const val PHOTO_POINTS = 10
        const val REVIEW_AT = 50
    }
}
