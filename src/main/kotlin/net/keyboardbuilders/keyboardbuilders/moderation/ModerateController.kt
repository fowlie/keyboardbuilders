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
    fun next(@AuthenticationPrincipal user: OAuth2User, csrf: CsrfToken, model: Model): String {
        requireModerator(user)
        val answered = answers.answeredIds()
        val open = questions.all().filter { it.id !in answered }
        model.addAttribute("question", open.firstOrNull()?.let {
            QuestionView(it.id, it.title, markdown.html(it.body), it.choices)
        })
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
        answers.record(id, reply, by = login)
        return "redirect:/moderate"
    }

    private fun requireModerator(user: OAuth2User): String {
        val login = user.getAttribute<String>("login")
        if (!moderators.includes(login)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Moderators only.")
        return login!!
    }
}
