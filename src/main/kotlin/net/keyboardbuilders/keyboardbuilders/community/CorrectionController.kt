package net.keyboardbuilders.keyboardbuilders.community

import net.keyboardbuilders.keyboardbuilders.account.isOwnedBy
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.CorrectableFact
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.persistence.Corrections
import net.keyboardbuilders.keyboardbuilders.persistence.SeedImporter
import net.keyboardbuilders.keyboardbuilders.web.Head
import net.keyboardbuilders.keyboardbuilders.web.SiteUrl
import net.keyboardbuilders.keyboardbuilders.web.Silhouettes
import net.keyboardbuilders.keyboardbuilders.web.rowSilhouette
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException

/** One tile on the form: [icon] is trusted SVG drawn by [FactIcons], or null for none. */
data class CorrectionChoice(val value: String, val label: String, val detail: String?, val icon: String?, val checked: Boolean)

/** One fact's card on the form. */
data class CorrectionCard(val name: String, val heading: String, val question: String, val many: Boolean, val choices: List<CorrectionChoice>)

/**
 * Correcting a design's measured facts (owner, 2026-11-26): one page, every correctable
 * fact on it, one Save (2026-11-27: a link per spec line was too much to read past).
 * Signed in with GitHub (SecurityConfig). The design's own designer -- their GitHub account
 * holds its repo -- changes it at once and is never reviewed: the changed facts are stored,
 * the import re-runs, and the page, the filters and the counts all show it.
 */
@Controller
class CorrectionController(
    private val catalog: DesignCatalog,
    private val corrections: Corrections,
    private val importer: SeedImporter,
    private val silhouettes: Silhouettes,
    private val site: SiteUrl,
) {

    @GetMapping("/designs/{slug}/correct")
    fun form(@AuthenticationPrincipal user: OAuth2User, @PathVariable slug: String, csrf: CsrfToken, model: Model): String {
        val design = design(slug)
        model.addAttribute("slug", slug)
        model.addAttribute("name", design.core.name.value)
        model.addAttribute("silhouette", design.rowSilhouette(silhouettes))
        model.addAttribute("cards", CorrectableFact.entries.map { card(it, design) })
        model.addAttribute("designer", design.isOwnedBy(user.login()))
        model.addAttribute("csrf", csrf)
        model.addAttribute("head", Head(title = "Correct ${design.core.name.value}", indexable = false))
        model.addAttribute("site", site)
        return "correct"
    }

    @PostMapping("/designs/{slug}/correct")
    fun save(
        @AuthenticationPrincipal user: OAuth2User,
        @PathVariable slug: String,
        @RequestParam fields: MultiValueMap<String, String>,
    ): String {
        val design = design(slug)
        val login = user.login()
        if (!design.isOwnedBy(login)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the designer can correct ${design.core.name.value} for now.")
        }
        // Every answer is checked before any is stored, so a bad one stores nothing.
        val changed = CorrectableFact.entries.mapNotNull { fact ->
            val sent = fields[fact.token] ?: return@mapNotNull null
            val value = fact.encode(sent)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick one of the listed answers for ${fact.label}.")
            (fact to value).takeIf { value.split(",").toSet() != fact.current(design) }
        }
        if (changed.isNotEmpty()) {
            changed.forEach { (fact, value) -> corrections.apply(slug, fact, value, by = login) }
            importer.importAll()
        }
        return "redirect:/designs/$slug"
    }

    private fun design(slug: String): Design =
        catalog.bySlug(slug) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no design with slug '$slug'")

    private fun card(fact: CorrectableFact, design: Design): CorrectionCard {
        val now = fact.current(design)
        val choices = fact.choices.map { CorrectionChoice(it, label(fact, it), detail(fact, it), FactIcons.of(fact, it), it in now) }
        return when (fact) {
            CorrectableFact.Splay -> CorrectionCard(fact.token, "Splay", "Are the columns fanned out, or parallel?", false, choices)
            CorrectableFact.Stagger -> CorrectionCard(fact.token, "Stagger", "How do the keys line up?", false, choices)
            CorrectableFact.SwitchMounts -> CorrectionCard(fact.token, "Switches", "How do the switches go on? Pick every way the board takes.", true, choices)
        }
    }

    /** The same words the design page and the rail use. */
    private fun label(fact: CorrectableFact, value: String): String = when (fact) {
        CorrectableFact.Splay -> if (value == "yes") "Splayed" else "Not splayed"
        CorrectableFact.Stagger -> when (value) {
            "ortho" -> "Ortholinear"
            "row" -> "Row-staggered"
            else -> "Column-staggered"
        }
        CorrectableFact.SwitchMounts -> when (value) {
            "solder" -> "Soldered"
            "mx_socket" -> "MX hotswap"
            "choc_socket" -> "Choc hotswap"
            else -> "Mill-Max"
        }
    }

    private fun detail(fact: CorrectableFact, value: String): String? = when (fact) {
        CorrectableFact.Splay -> if (value == "yes") "Columns angle outwards" else "Columns run straight up"
        CorrectableFact.Stagger -> when (value) {
            "ortho" -> "A straight grid"
            "row" -> "Rows shifted, like a typewriter"
            else -> "Columns shifted to fit the fingers"
        }
        CorrectableFact.SwitchMounts -> when (value) {
            "solder" -> "Switches solder to the board"
            "mx_socket" -> "Kailh or Gateron MX sockets"
            "choc_socket" -> "Kailh Choc sockets"
            else -> "Mill-Max pin sockets"
        }
    }
}
