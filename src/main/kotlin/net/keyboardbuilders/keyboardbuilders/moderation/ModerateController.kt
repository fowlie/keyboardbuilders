package net.keyboardbuilders.keyboardbuilders.moderation

import net.keyboardbuilders.keyboardbuilders.web.Head
import net.keyboardbuilders.keyboardbuilders.web.SiteUrl
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException

/** The question as the page shows it: the body already rendered, safely. */
data class QuestionView(
    val id: String, val title: String, val bodyHtml: String, val choices: List<Choice>,
    /** Photo questions also take a URL, for when none of the candidates is right. */
    val takesUrl: Boolean,
)

/** One row of the queue under the question: every open question links to itself. */
data class QueueItem(val title: String, val kind: String, val href: String, val current: Boolean)

private fun href(id: String) = "/moderation?id=$id"

private val HTTPS = Regex("""https://[^\s<>"`]+""")

/**
 * Backlog #117: moderators answer the intake bot's questions, one at a time.
 * Sign-in is enforced by `SecurityConfig`; being a moderator is checked here.
 */
@Controller
class ModerateController(
    private val questions: Questions,
    private val answers: Answers,
    private val moderators: Moderators,
    private val markdown: QuestionMarkdown,
    private val site: SiteUrl,
) {

    @GetMapping("/moderation")
    fun show(
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) id: String?,
        csrf: CsrfToken,
        model: Model,
    ): String {
        // NOT A MODERATOR: an invitation, not an error. The footer links here for everyone
        // (owner, 2026-11-15), and the queue itself is never shown.
        if (!moderators.includes(user.getAttribute<String>("login"))) {
            model.addAttribute("invited", false)
            model.addAttribute("question", null)
            model.addAttribute("prev", null); model.addAttribute("next", null)
            model.addAttribute("queue", emptyList<QueueItem>())
            model.addAttribute("remaining", 0)
            model.addAttribute("csrf", csrf)
            model.addAttribute("head", Head(title = "Moderation", indexable = false))
            model.addAttribute("site", site)
            return "moderation"
        }
        val open = open()
        // An id that is answered or gone falls back to the first open question.
        val at = open.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
        val current = open.getOrNull(at)
        model.addAttribute("question", current?.let {
            QuestionView(it.id, it.title, markdown.html(it.body), it.choices, takesUrl = it.kind == "photo")
        })
        model.addAttribute("prev", open.getOrNull(at - 1)?.let { href(it.id) })
        model.addAttribute("next", open.getOrNull(at + 1)?.let { href(it.id) })
        model.addAttribute("queue", open.map { QueueItem(it.title, it.kind, href(it.id), it.id == current?.id) })
        model.addAttribute("remaining", open.size)
        model.addAttribute("csrf", csrf)
        model.addAttribute("invited", true)
        model.addAttribute("head", Head(title = "Moderation", indexable = false))
        model.addAttribute("site", site)
        return "moderation"
    }

    @PostMapping("/moderation/answer")
    fun answer(
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam id: String,
        @RequestParam(required = false) reply: String?,
        @RequestParam(required = false) image: String?,
        @RequestParam(required = false) page: String?,
    ): String {
        val login = requireModerator(user)
        val question = questions.all().firstOrNull { it.id == id }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "no open question '$id'")
        // A typed photo URL, or one of the question's own buttons. Nothing else.
        val answer = if (!image.isNullOrBlank()) photoUrl(question, image, page)
            else reply?.takeIf { r -> question.choices.any { it.reply == r } }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'$reply' is not an answer to '$id'")
        val before = open().map { it.id }
        answers.record(id, answer, by = login)
        // ON TO THE ONE AFTER IT, wrapping to the first still open: a moderator working
        // down the list stays where they were rather than being sent back to the top.
        val after = open().map { it.id }
        val at = before.indexOf(id)
        // Already answered (a second tab): it wasn't in the open list, so start from the top.
        val next = if (at < 0) after.firstOrNull()
            else (before.drop(at + 1) + before.take(at)).firstOrNull { it in after }
        return "redirect:" + (next?.let { href(it) } ?: "/moderation")
    }

    /**
     * A photo given by URL, as the reply the bot's `parse_photo` reads: `IMAGE` or
     * `IMAGE PAGE`. Photo questions only, https only -- the bot downloads it, and a
     * moderator's typo should fail here, not on the server at the next poll.
     */
    private fun photoUrl(question: Question, image: String, page: String?): String {
        if (question.kind != "photo") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "only a photo question takes a URL")
        }
        val parts = listOfNotNull(image.trim(), page?.trim()?.ifEmpty { null })
        if (parts.any { !HTTPS.matches(it) }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "a photo and its credit page must be https:// addresses")
        }
        return parts.joinToString(" ")
    }

    private fun open(): List<Question> {
        val answered = answers.answeredIds()
        return questions.all().filter { it.id !in answered }
    }

    private fun requireModerator(user: OAuth2User): String {
        val login = user.getAttribute<String>("login")
        if (!moderators.includes(login)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Moderators only.")
        return login!!
    }
}
