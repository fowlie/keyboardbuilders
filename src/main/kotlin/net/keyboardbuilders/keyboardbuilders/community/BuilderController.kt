package net.keyboardbuilders.keyboardbuilders.community

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.web.Head
import net.keyboardbuilders.keyboardbuilders.web.SiteUrl
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException

/** One photo on someone's page. [note] is the reviewer's line on a turned-down photo. */
data class BuilderPhoto(val slug: String, val name: String, val thumb: String, val status: Upload.Status, val note: String?)

/**
 * A person's page, `/builders/alice` (owner, 2026-11-24): their points, whether they can
 * review, the photos they added and how many they reviewed. Waiting and turned-down photos
 * are shown only to the person themselves.
 */
@Controller
class BuilderController(
    private val uploads: PhotoUploads,
    private val contributors: Contributors,
    private val catalog: DesignCatalog,
    private val site: SiteUrl,
) {

    @GetMapping("/builders/{login}")
    fun show(
        @PathVariable login: String,
        @AuthenticationPrincipal user: OAuth2User?,
        request: jakarta.servlet.http.HttpServletRequest,
        model: Model,
    ): String {
        val who = login.lowercase()
        if (login != who) return "redirect:/builders/$who"
        val viewer = user?.getAttribute<String>("login")?.lowercase()
        val self = viewer == who
        val all = uploads.byLogin(who)
        val shown = if (self) all else all.filter { it.status == Upload.Status.APPROVED }
        val reviewed = uploads.reviewedCount(who)
        if (!self && shown.isEmpty() && reviewed == 0 && !contributors.isModerator(who)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "nobody called '$login' has added anything yet")
        }
        val points = contributors.points(who)
        model.addAttribute("login", who)
        model.addAttribute("self", self)
        model.addAttribute("points", points)
        model.addAttribute("canReview", contributors.canReview(who))
        model.addAttribute("revoked", contributors.isRevoked(who))
        model.addAttribute("photos", shown.map {
            BuilderPhoto(it.slug, catalog.bySlug(it.slug)?.core?.name?.value ?: it.slug, "/photos/${it.jpg}-card.jpg", it.status, it.note)
        })
        model.addAttribute("reviewed", reviewed)
        // The owner's switch: only a moderator sees it, and never on a moderator's own page.
        val manage = contributors.isModerator(viewer) && !contributors.isModerator(who) && points >= Contributors.REVIEW_AT
        model.addAttribute("manage", manage)
        // Asked for only when the form is drawn: resolving a token stores it in a session,
        // and an anonymous reader of this page should not be given one.
        model.addAttribute("csrf", if (manage) request.getAttribute(CsrfToken::class.java.name) as CsrfToken? else null)
        model.addAttribute("head", Head(title = "@$who", indexable = false))
        model.addAttribute("site", site)
        return "builder"
    }

    @PostMapping("/builders/{login}/review-rights")
    fun reviewRights(@AuthenticationPrincipal user: OAuth2User, @PathVariable login: String, @RequestParam action: String): String {
        val by = user.login()
        if (!contributors.isModerator(by)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Moderators only.")
        when (action) {
            "revoke" -> contributors.revoke(login, by)
            "restore" -> contributors.restore(login)
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'$action' is not revoke or restore")
        }
        return "redirect:/builders/${login.lowercase()}"
    }
}
