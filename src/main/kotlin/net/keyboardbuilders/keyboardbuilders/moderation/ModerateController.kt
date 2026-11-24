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
import java.time.Duration
import java.time.Instant

/** The question as the page shows it: the body already rendered, safely. */
data class QuestionView(
    val id: String, val title: String, val bodyHtml: String, val choices: List<Choice>,
    /** Photo questions also take a URL, for when none of the candidates is right. */
    val takesUrl: Boolean,
)

/** A build photo waiting for a yes or no (owner, 2026-11-24). [mine] is the reviewer's own: they can't decide it. */
data class WaitingPhoto(
    val id: Long, val slug: String, val name: String, val url: String,
    val width: Int, val height: Int, val by: String, val mine: Boolean,
)

/** One row of the queue under the question: every open question links to itself. */
data class QueueItem(val title: String, val kind: String, val href: String, val current: Boolean)

/** An answer the bot hasn't read yet, so it can still be undone. */
data class PendingAnswer(val id: String, val title: String, val said: String)

private fun href(id: String) = "/moderation?id=$id"

/** deploy/intake/intake-answer.timer: OnUnitActiveSec=10min. */
private val CHECK_EVERY: Duration = Duration.ofMinutes(10)

/**
 * When the bot next looks, in words. The timer counts from the end of the last run and
 * skips a run while the nightly holds the lock, so this is "about", and a late check is
 * said plainly rather than counted down past zero.
 */
internal fun checkNote(last: Instant?, now: Instant): String? {
    if (last == null) return null
    val left = Duration.between(now, last + CHECK_EVERY)
    return when {
        left > Duration.ZERO -> "Next check in about ${(left.toSeconds() + 59) / 60} min."
        left > Duration.ofMinutes(-2) -> "Next check any moment now."
        else -> "The bot last checked ${Duration.between(last, now).toMinutes()} min ago."
    }
}

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
    private val lastCheck: LastCheck,
    private val uploads: net.keyboardbuilders.keyboardbuilders.community.PhotoUploads,
    private val contributors: net.keyboardbuilders.keyboardbuilders.community.Contributors,
    private val catalog: net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog,
    private val review: net.keyboardbuilders.keyboardbuilders.community.PhotoReview,
) {

    @GetMapping("/moderation")
    fun show(
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) id: String?,
        csrf: CsrfToken,
        model: Model,
    ): String {
        model.addAttribute("csrf", csrf)
        model.addAttribute("head", Head(title = "Moderation", indexable = false))
        model.addAttribute("site", site)
        val login = user.getAttribute<String>("login")
        // BUILD PHOTOS: moderators, and anyone with 50 points, review them here.
        val reviewer = contributors.canReview(login)
        model.addAttribute("reviewer", reviewer)
        model.addAttribute("photos", if (reviewer) waitingPhotos(login!!) else emptyList<WaitingPhoto>())
        // NOT A MODERATOR: an invitation, not an error. The footer links here for everyone
        // (owner, 2026-11-15), and the queue itself is never shown. A reviewer who isn't a
        // moderator sees the photos and nothing of the bot's questions.
        if (!moderators.includes(login)) {
            model.addAttribute("invited", false)
            model.addAttribute("question", null)
            model.addAttribute("prev", null); model.addAttribute("next", null)
            model.addAttribute("queue", emptyList<QueueItem>())
            model.addAttribute("remaining", 0)
            model.addAttribute("pending", emptyList<PendingAnswer>())
            model.addAttribute("checkNote", null)
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
        model.addAttribute("pending", pending())
        model.addAttribute("checkNote", checkNote(lastCheck.at(), Instant.now()))
        model.addAttribute("invited", true)
        return "moderation"
    }

    /** Yes or no to a waiting build photo. Nobody decides their own. */
    @PostMapping("/moderation/photos/{id}")
    fun decidePhoto(
        @AuthenticationPrincipal user: OAuth2User,
        @org.springframework.web.bind.annotation.PathVariable id: Long,
        @RequestParam decision: String,
        @RequestParam(required = false) note: String?,
    ): String {
        val login = user.getAttribute<String>("login")
        if (!contributors.canReview(login)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Reviewers only.")
        val upload = uploads.get(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no such photo")
        if (upload.login.equals(login, ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Someone else has to review your own photo.")
        }
        when (decision) {
            // A yes copies the photo to the bucket; a no deletes it and keeps the note.
            "yes" -> review.approve(id, login!!)
            "no" -> review.reject(id, login!!, note)
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'$decision' is not yes or no")
        }
        return "redirect:/moderation#photos"
    }

    private fun waitingPhotos(login: String): List<WaitingPhoto> = uploads.waiting().map {
        WaitingPhoto(
            it.id, it.slug, catalog.bySlug(it.slug)?.core?.name?.value ?: it.slug, "/photos/${it.jpg}.jpg",
            it.width, it.height, it.login, mine = it.login.equals(login, ignoreCase = true),
        )
    }

    /**
     * Forget an answer the bot hasn't read, and open its question again. One it HAS read
     * is refused: it may already be committed, and deleting the row would only make the
     * page lie about it.
     */
    @PostMapping("/moderation/undo")
    fun undo(@AuthenticationPrincipal user: OAuth2User, @RequestParam id: String): String {
        requireModerator(user)
        if (pending().none { it.id == id }) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "the bot has already read the answer to '$id'")
        }
        answers.delete(id)
        return "redirect:" + href(id)
    }

    /**
     * Answers given after the bot's last check, oldest first. Before its first check
     * there is no stamp, and every answer counts. An answer to a question no longer in
     * the file is left out: the bot ignores those too.
     */
    private fun pending(): List<PendingAnswer> {
        val check = lastCheck.at()
        val byId = questions.all().associateBy { it.id }
        return answers.all()
            .filter { check == null || it.at.isAfter(check) }
            .mapNotNull { a ->
                byId[a.questionId]?.let { q ->
                    PendingAnswer(q.id, q.title, q.choices.firstOrNull { it.reply == a.reply }?.label ?: a.reply)
                }
            }
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
