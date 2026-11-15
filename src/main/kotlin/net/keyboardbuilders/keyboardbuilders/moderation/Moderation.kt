package net.keyboardbuilders.keyboardbuilders.moderation

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.File

/** One button: `reply` is what a reply on the old GitHub issue would have said. */
data class Choice(val reply: String, val label: String)

/** One of the intake bot's questions. `id` is the bot's own `kind:slug:fingerprint`. */
data class Question(
    val id: String,
    val kind: String,
    val slug: String,
    val title: String,
    val body: String,
    val choices: List<Choice>,
)

private data class QuestionsFile(val questions: List<Question> = emptyList())

/**
 * The bot's questions, backlog #117. The bot writes the file and this only reads it.
 *
 * READ ON EVERY REQUEST, never cached: the bot rewrites it while the app runs, and a
 * moderator must see tonight's questions without a restart. It is small (a day's capped
 * questions) and only moderators read it. A missing file is an empty queue, which is what
 * a fresh server and a dev machine have.
 */
@Component
class Questions(
    private val mapper: ObjectMapper,
    @Value("\${keyboardbuilders.moderation.questions:data/moderation/questions.json}") private val path: String,
) {
    fun all(): List<Question> {
        val file = File(path)
        if (!file.exists()) return emptyList()
        return mapper.readValue(file, QuestionsFile::class.java).questions
    }
}

/** Moderators' answers, backlog #117. The app writes them; the bot reads them. */
@Component
class Answers(private val db: JdbcClient) {

    fun answeredIds(): Set<String> =
        db.sql("SELECT question_id FROM moderation_answer").query { rs, _ -> rs.getString(1) }.set()

    fun record(questionId: String, reply: String, by: String) {
        db.sql("""
            INSERT INTO moderation_answer (question_id, reply, by_login) VALUES (?, ?, ?)
            ON CONFLICT (question_id) DO UPDATE SET reply = excluded.reply,
                by_login = excluded.by_login, at = strftime('%Y-%m-%dT%H:%M:%SZ')
        """.trimIndent()).params(questionId, reply, by).update()
    }
}

/** Who may answer: GitHub logins from config, `fowlie` to start. */
@Component
class Moderators(@Value("\${keyboardbuilders.moderators:fowlie}") logins: List<String>) {
    private val logins = logins.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    fun includes(login: String?) = login != null && login.lowercase() in logins
}

/**
 * Markdown to HTML with every raw HTML tag ESCAPED. The bodies quote README text and repo
 * descriptions written by strangers, so a `<script>` in one must show as text.
 */
@Component
class QuestionMarkdown {
    private val parser = Parser.builder().build()
    private val renderer = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build()
    fun html(markdown: String): String = renderer.render(parser.parse(markdown))
}
