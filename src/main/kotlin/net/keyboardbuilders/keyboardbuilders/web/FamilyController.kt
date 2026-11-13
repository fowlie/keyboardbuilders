package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.FamilyCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.FamilyRecord
import net.keyboardbuilders.keyboardbuilders.catalog.FirmwareCatalog
import net.keyboardbuilders.keyboardbuilders.domain.connectivityFor
import net.keyboardbuilders.keyboardbuilders.domain.usbPortFor
import net.keyboardbuilders.keyboardbuilders.persistence.Hearts
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView

/**
 * `/designs/families/{family}` -- a page per confirmed family (backlog
 * #107), built for search: people look a keyboard up by its family ("corne keyboard",
 * "sofle", "lily58"), and no design is called by that name alone.
 *
 * UNDER `/designs`, because the path uses the site's noun. The owner, 2026-10-03, on the
 * first draft at `/keyboards`: "keyboards/ url for families? And designs/ for keyboards?
 * What a mess." "Keyboard" stays in the titles, where the search word belongs.
 *
 * PLURAL, `/designs/families`, as `/designs` is. The owner, the same day, on
 * `/designs/family`: "maybe make designs/family and rename to families". Neither earlier
 * path was ever deployed, so neither redirects; both are plain 404s.
 *
 * NO INDEX PAGE, AND NO LINK IN THE FOOTER OR ON THE FRONT PAGE. The owner, 2026-10-04:
 * "families should be linked to from a design page only. So remove the whole Families list
 * page." A family page is reached from its members' "from the X family" line,
 * and by search through the sitemap; `/designs/families` itself is a 404.
 *
 * UNDER `/designs/families`, NOT `/designs/{family}`, so a family slug can never shadow a
 * design slug: a future design named just "Sofle" would collide, and the two answer
 * different questions. The one word the two share is "families" itself -- the literal
 * route wins in Spring, and `SeedDesignCatalog` refuses a design slugged "families" so
 * the design could never be reached.
 */
@Controller
class FamilyController(
    private val families: FamilyCatalog,
    private val catalog: DesignCatalog,
    private val silhouettes: Silhouettes,
    private val activity: RepoActivity,
    private val photos: Photos,
    private val hearts: Hearts,
    private val controllers: ControllerCatalog,
    private val firmwares: FirmwareCatalog,
    private val site: SiteUrl,
    private val renders: Renders,
) {
    /**
     * `hero` is /settings' "which picture first". It reaches the cards here, unlike every
     * other list, because this is the page the setting was asked for: the owner chose it
     * after browsing a family's boards one after another on their renders (2026-10-02).
     */
    @GetMapping("/designs/families/{slug}")
    fun show(
        @PathVariable slug: String,
        @CookieValue(HeroFirst.COOKIE, required = false) hero: String?,
    ): ModelAndView {
        val record = families.bySlug(slug)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no design family with slug '$slug'")
        val page = pageFor(record, renderFirst = HeroFirst.of(hero) == HeroFirst.Render)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "design family '$slug' has fewer than two designs")
        return ModelAndView(
            "designs/families/show",
            mapOf(
                "family" to page,
                "head" to familyHead(site, page, photos, silhouettes),
                "itemListLd" to familyItemListLd(site, page),
            ),
        )
    }

    /** Each member's facts exactly as its own page states them, Connectivity included --
     *  the same call `DesignsController.pageFor` makes for the compare page. */
    private fun pageFor(record: FamilyRecord, renderFirst: Boolean): FamilyPage? {
        val designs = familyMembers(record.name, catalog)
        val counts = hearts.forDesigns(designs)
        return familyPage(record, designs.map { design ->
            FamilyMember(
                design = design,
                page = design.toPage(
                    silhouettes, activity,
                    connectivity = design.connectivityFor(controllers.all(), firmwares.rules),
                    usbPort = design.usbPortFor(controllers.all()),
                ),
                card = design.toCard(
                    design.toRow(silhouettes, activity, hearts = counts[design.core.slug.value]), photos, silhouettes,
                    render = if (renderFirst) renders.of(design.core.slug.value) else null,
                ),
            )
        })
    }
}

/**
 * ONE VOCABULARY (owner, 2026-10-04): the site's noun is "design", so these pages count
 * "designs", never "variants", and the index is "Design families". The title below is the
 * one place "keyboard" stays, and never in a URL.
 *
 * "Corne keyboard — 2 designs compared". "Keyboard" is in the title because it is in
 * the search: "corne keyboard" is what people type, and a bare "Corne" title would also
 * have to beat every other meaning of the word.
 *
 * THE OG IMAGE IS A MEMBER'S OWN CARD, the first (alphabetically) with a picture behind
 * it -- a build photo or a drawn layout -- since `/designs/{slug}/og.png` is drawn from
 * exactly those. With neither, the site's own card, as every non-design page uses.
 */
internal fun familyHead(site: SiteUrl, page: FamilyPage, photos: Photos, silhouettes: Silhouettes): Head {
    val slugs = page.variants.map { it.href.removePrefix("/designs/") }
    val pictured = slugs.firstOrNull { photos.of(it) != null || silhouettes.isDrawn(it) }
    return Head(
        title = "${page.name} keyboard — ${page.variants.size} designs compared",
        // "In the catalog": the count is the variants we carry, not every one the author
        // made. "Where recorded": a cell can be a gap (Pancake v2's firmware), so the
        // description must not promise every fact for every variant.
        description = describe(
            "The ${page.variants.size} ${page.name} designs in the catalog, side by side:",
            page.variants.map { it.name }.spokenList("and") + ".",
            "Keys, switches, controller, firmware and more, where recorded.",
        ),
        canonical = site.of(page.href),
        image = site.of(pictured?.let { "/designs/$it/og.png" } ?: "/og.png"),
    )
}

/**
 * schema.org `ItemList` of the family's boards, so a search engine reads the page as the
 * list of variants it is. Through `JsonLd.of`, never templated: jte escapes for JavaScript
 * inside a script, which is not JSON (the 2026-10-03 Search Console fix).
 */
internal fun familyItemListLd(site: SiteUrl, page: FamilyPage): String = JsonLd.of(
    mapOf(
        "@context" to "https://schema.org",
        "@type" to "ItemList",
        "name" to "${page.name} designs",
        "url" to site.of(page.href),
        "numberOfItems" to page.variants.size,
        "itemListElement" to page.variants.mapIndexed { i, v ->
            mapOf("@type" to "ListItem", "position" to i + 1, "name" to v.name, "url" to site.of(v.href))
        },
    )
)
