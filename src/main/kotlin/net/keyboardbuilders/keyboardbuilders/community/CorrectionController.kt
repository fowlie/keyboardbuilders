package net.keyboardbuilders.keyboardbuilders.community

import net.keyboardbuilders.keyboardbuilders.account.isOwnedBy
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.CorrectableFact
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.persistence.Corrections
import net.keyboardbuilders.keyboardbuilders.persistence.SeedImporter
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

/** One radio or checkbox on the form. */
data class CorrectionChoice(val value: String, val label: String, val checked: Boolean)

/**
 * Correcting a measured fact on a design's page (owner, 2026-11-26). Signed in with GitHub
 * (SecurityConfig). The design's own designer -- their GitHub account holds its repo --
 * changes it at once and is never reviewed: the correction is stored, the import re-runs,
 * and the page, the filters and the counts all show it.
 */
@Controller
class CorrectionController(
    private val catalog: DesignCatalog,
    private val corrections: Corrections,
    private val importer: SeedImporter,
    private val site: SiteUrl,
) {

    @GetMapping("/designs/{slug}/correct/{fact}")
    fun form(
        @AuthenticationPrincipal user: OAuth2User,
        @PathVariable slug: String,
        @PathVariable("fact") token: String,
        csrf: CsrfToken,
        model: Model,
    ): String {
        val design = design(slug)
        val fact = fact(token)
        val now = fact.current(design)
        model.addAttribute("slug", slug)
        model.addAttribute("name", design.core.name.value)
        model.addAttribute("fact", fact.token)
        model.addAttribute("label", fact.label)
        model.addAttribute("many", fact.many)
        model.addAttribute("choices", fact.choices.map { CorrectionChoice(it, word(fact, it), it in now) })
        model.addAttribute("designer", design.isOwnedBy(user.login()))
        model.addAttribute("csrf", csrf)
        model.addAttribute("head", Head(title = "Correct ${fact.label.lowercase()}", indexable = false))
        model.addAttribute("site", site)
        return "correct"
    }

    @PostMapping("/designs/{slug}/correct/{fact}")
    fun correct(
        @AuthenticationPrincipal user: OAuth2User,
        @PathVariable slug: String,
        @PathVariable("fact") token: String,
        @RequestParam("value", required = false) values: List<String>?,
    ): String {
        val design = design(slug)
        val fact = fact(token)
        val value = fact.encode(values.orEmpty())
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick one of the listed answers.")
        val login = user.login()
        if (!design.isOwnedBy(login)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the designer can correct ${design.core.name.value} for now.")
        }
        corrections.apply(slug, fact, value, by = login)
        importer.importAll()
        return "redirect:/designs/$slug"
    }

    private fun design(slug: String): Design =
        catalog.bySlug(slug) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no design with slug '$slug'")

    private fun fact(token: String): CorrectableFact =
        CorrectableFact.of(token) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "'$token' is not a fact you can correct")

    /** The reader's words for a value; the same ones the design page and the rail use. */
    private fun word(fact: CorrectableFact, value: String): String = when (fact) {
        CorrectableFact.Splay -> if (value == "yes") "Splayed" else "Not splayed"
        CorrectableFact.Stagger -> when (value) {
            "ortho" -> "Ortholinear"
            "row" -> "Row-staggered"
            else -> "Column-staggered"
        }
        CorrectableFact.SwitchMounts -> when (value) {
            "solder" -> "Switches solder in"
            "mx_socket" -> "MX hotswap sockets"
            "choc_socket" -> "Choc hotswap sockets"
            else -> "Mill-Max sockets"
        }
    }
}
