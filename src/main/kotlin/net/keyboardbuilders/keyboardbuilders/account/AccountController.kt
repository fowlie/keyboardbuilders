package net.keyboardbuilders.keyboardbuilders.account

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalogs
import net.keyboardbuilders.keyboardbuilders.persistence.Unpublished
import net.keyboardbuilders.keyboardbuilders.web.Head
import net.keyboardbuilders.keyboardbuilders.web.SiteUrl
import net.keyboardbuilders.keyboardbuilders.web.Silhouettes
import net.keyboardbuilders.keyboardbuilders.web.rowSilhouette
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.server.ResponseStatusException

/** One row of "Your designs"; [silhouette] is trusted SVG, drawn by [Silhouettes]. */
data class OwnedDesign(val slug: String, val name: String, val published: Boolean, val silhouette: String)

/**
 * "Your designs" (#116): the designs whose repo is under the signed-in GitHub account,
 * each with one button to take it off the site or put it back.
 *
 * READS THE WHOLE SEED, not the published view, because an unpublished design has to stay
 * here for its designer to publish again.
 */
@Controller
class AccountController(
    @Qualifier(DesignCatalogs.SEED) private val seed: DesignCatalog,
    private val unpublished: Unpublished,
    private val publishing: Publishing,
    private val site: SiteUrl,
    private val silhouettes: Silhouettes,
    private val uploads: net.keyboardbuilders.keyboardbuilders.community.PhotoUploads,
    private val contributors: net.keyboardbuilders.keyboardbuilders.community.Contributors,
) {

    @GetMapping("/account")
    fun account(@AuthenticationPrincipal user: OAuth2User, csrf: CsrfToken, model: Model): String {
        val login = user.login()
        val hidden = unpublished.all()
        model.addAttribute("login", login)
        model.addAttribute("designs", seed.all().filter { it.isOwnedBy(login) }.map {
            OwnedDesign(it.core.slug.value, it.core.name.value, it.core.slug.value !in hidden, it.rowSilhouette(silhouettes))
        })
        model.addAttribute("photos", photoSummary(login))
        model.addAttribute("csrf", csrf)
        model.addAttribute("head", Head(title = "Your account", indexable = false))
        model.addAttribute("site", site)
        return "account"
    }

    @PostMapping("/account/designs/{slug}/unpublish")
    fun unpublish(@AuthenticationPrincipal user: OAuth2User, @PathVariable slug: String): String {
        publishing.unpublish(owned(user, slug), by = user.login())
        return "redirect:/account"
    }

    @PostMapping("/account/designs/{slug}/publish")
    fun publish(@AuthenticationPrincipal user: OAuth2User, @PathVariable slug: String): String {
        publishing.publish(owned(user, slug))
        return "redirect:/account"
    }

    /** The slug, if the design exists and is the signed-in designer's. */
    private fun owned(user: OAuth2User, slug: String): String {
        val design = seed.bySlug(slug)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no design with slug '$slug'")
        if (!design.isOwnedBy(user.login())) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "${design.core.name.value} is not under your GitHub account.")
        }
        return slug
    }

    /** GitHub's username. `getName()` is the numeric id under Spring's GitHub defaults. */
    /**
     * Where the build photos someone added stand, in one line (2026-11-24). Sign-in lands
     * here, so a turned-down photo is found here, with a link to their page for the notes.
     * Null for someone who never added one.
     */
    private fun photoSummary(login: String): String? {
        val mine = uploads.byLogin(login)
        if (mine.isEmpty()) return null
        val by = mine.groupingBy { it.status }.eachCount()
        val parts = listOfNotNull(
            "${contributors.points(login)} points",
            by[net.keyboardbuilders.keyboardbuilders.community.Upload.Status.REJECTED]?.let { "$it not accepted" },
            by[net.keyboardbuilders.keyboardbuilders.community.Upload.Status.WAITING]?.let { "$it waiting for review" },
        )
        return parts.joinToString(", ") + "."
    }

    private fun OAuth2User.login(): String =
        getAttribute<String>("login") ?: error("GitHub sent no login")
}
