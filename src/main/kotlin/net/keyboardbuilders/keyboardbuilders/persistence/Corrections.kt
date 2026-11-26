package net.keyboardbuilders.keyboardbuilders.persistence

import net.keyboardbuilders.keyboardbuilders.domain.AppliedCorrection
import net.keyboardbuilders.keyboardbuilders.domain.CorrectableFact
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * Corrections to measured facts -- `V11__correction.sql`. Whether the person may make one,
 * and whether it applies at once or waits, is the caller's question.
 */
@Component
class Corrections(private val db: JdbcClient) {

    /** Every applied correction, oldest first, so applying them in order leaves the latest. */
    fun applied(): List<AppliedCorrection> =
        db.sql("SELECT slug, fact, value FROM correction WHERE status = 'applied' ORDER BY id")
            .query { rs, _ ->
                AppliedCorrection(rs.getString(1), CorrectableFact.of(rs.getString(2))!!, rs.getString(3))
            }.list()

    fun apply(slug: String, fact: CorrectableFact, value: String, by: String) {
        db.sql("INSERT INTO correction (slug, fact, value, login, status) VALUES (?, ?, ?, ?, 'applied')")
            .params(slug, fact.token, value, by.lowercase()).update()
    }
}
