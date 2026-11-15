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
data class QuestionView(val id: String, val title: String, val bodyHtml: String, val choices: List<Choice>)

/** One row of the queue under the question: every open question links to itself. */
data class QueueItem(val title: String, val kind: String, val href: String, val current: Boolean)

private fun href(id: String) = "/moderate?id=$id"

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

    @GetMapping("/moderate")
    fun show(
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) id: String?,
        csrf: CsrfToken,
        model: Model,
    ): String {
        requireModerator(user)
        val open = open()
        // An id that is answered or gone falls back to the first open question.
        val at = open.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
        val current = open.getOrNull(at)
        model.addAttribute("question", current?.let {
            QuestionView(it.id, it.title, markdown.html(it.body), it.choices)
        })
        model.addAttribute("prev", open.getOrNull(at - 1)?.let { href(it.id) })
        model.addAttribute("next", open.getOrNull(at + 1)?.let { href(it.id) })
        model.addAttribute("queue", open.map { QueueItem(it.title, it.kind, href(it.id), it.id == current?.id) })
        model.addAttribute("remaining", open.size)
        model.addAttribute("csrf", csrf)
        model.addAttribute("head", Head(title = "Moderate", indexable = false))
        model.addAttribute("site", site)
        return "moderate"
    }

    @PostMapping("/moderate/answer")
    fun answer(
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam id: String,
        @RequestParam reply: String,
    ): String {
        val login = requireModerator(user)
        val question = questions.all().firstOrNull { it.id == id }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "no open question '$id'")
        if (question.choices.none { it.reply == reply }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'$reply' is not an answer to '$id'")
        }
        val before = open().map { it.id }
        answers.record(id, reply, by = login)
        // ON TO THE ONE AFTER IT, wrapping to the first still open: a moderator working
        // down the list stays where they were rather than being sent back to the top.
        val after = open().map { it.id }
        val at = before.indexOf(id)
        val next = (before.drop(at + 1) + before.take(at)).firstOrNull { it in after }
        return "redirect:" + (next?.let { href(it) } ?: "/moderate")
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
