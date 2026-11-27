package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.persistence.Hearts
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * A visitor's shortlist. `product.md`: *"Success looks like: a shortlist of 3"*, and *"She
 * will never create an account"* -- so the hearts live in the browser, and this page is
 * where they become something to compare from.
 *
 * A SHORTLIST IS A URL. Bare `/favourites` reads localStorage in the page's own script and
 * replaces itself with `/favourites?d=a&d=b`, which the server renders as ordinary cards.
 * No fragment endpoint, nothing drawn in JavaScript, and the address is a link that can be
 * sent to someone.
 */
@Controller
class FavoritesController(
    private val catalog: DesignCatalog,
    private val silhouettes: Silhouettes,
    private val activity: RepoActivity,
    private val photos: Photos,
    private val site: SiteUrl,
    private val hearts: Hearts,
    private val renames: SlugRenames,
) {
    @GetMapping("/favorites")
    fun favorites(@RequestParam(name = "d", required = false) asked: List<String>?, model: Model): String {
        // SIGNED IN, BARE /favorites IS THE ACCOUNT'S LIST (2026-11-27): drawn here, so a
        // new device shows it before its browser has heard of any of it.
        val account = if (asked == null) SignedIn.login()?.let { hearts.of(it) } else null
        val slugs = asked ?: account
        // The visitor's order, duplicates dropped, and a slug that no longer publishes
        // skipped: a browser keeps hearts across renames and removals it cannot see.
        // A RENAMED SLUG IS FOLLOWED, the way `/designs/{slug}` 301s it: the browser keeps
        // the slug it hearted, and #107 renames designs. `renamed` tells the page's script
        // so the browser's own list catches up.
        val renamed = slugs.orEmpty().filter { catalog.bySlug(it) == null }
            .mapNotNull { former -> renames.currentFor(former)?.let { former to it } }.toMap()
        val designs = slugs.orEmpty().map { renamed[it] ?: it }.distinct().mapNotNull { catalog.bySlug(it) }
        model.addAttribute("renamed", renamed)
        val counts = hearts.forDesigns(designs)
        model.addAttribute("cards", designs.map { it.toCard(it.toRow(silhouettes, activity, hearts = counts[it.core.slug.value]), photos, silhouettes) })
        model.addAttribute("asked", slugs.orEmpty().isNotEmpty() || account != null)
        model.addAttribute("account", account != null)
        model.addAttribute("head", Head(
            title = "My favorites",
            description = "The keyboards you have hearted.",
            canonical = site.of("/favorites"),
            indexable = false,
        ))
        return "favorites"
    }
}
