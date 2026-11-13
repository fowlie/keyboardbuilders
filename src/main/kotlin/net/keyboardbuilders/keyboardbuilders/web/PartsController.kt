package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.FirmwareCatalog
import net.keyboardbuilders.keyboardbuilders.persistence.DesignQueries
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView

/**
 * `/parts/:kind/:slug` (`ux.md`'s sitemap). Only `dev-board` exists so far -- the
 * kinds `ux.md` also names (`mcu`, `switch`, `keycap-profile`, `extension`, `firmware`)
 * have no catalogue behind them yet, so their routes are not wired rather than 404ing
 * on a claim this build cannot back.
 */
@Controller
class PartsController(
    private val controllers: ControllerCatalog,
    private val queries: DesignQueries,
    private val site: SiteUrl,
    /** The corpus itself, for the three design lists. `DesignQueries` answers the
     *  footprint COUNT off the facet tables; the tiers need each design's firmware refs
     *  and target chip, which live on the record rather than in a facet column. */
    private val catalog: DesignCatalog,
    private val silhouettes: Silhouettes,
    private val activity: RepoActivity,
    private val hearts: net.keyboardbuilders.keyboardbuilders.persistence.Hearts,
    /** The impossibility rules and each firmware's wireless support, as data --
     *  `seed/firmwares.toml` replaced the `FirmwareBar` enum on 2026-09-10. */
    private val firmwares: FirmwareCatalog,
) {
    /**
     * `ux.md`'s sitemap line 56, "Part index, grouped by kind". Built because the 34 board
     * pages had no indexable inbound link -- `SeoController.sitemap` says so itself -- and
     * because a reader could only reach one by already knowing its slug, which inverts
     * Door B's whole premise about starting from the part you already own.
     */
    @GetMapping("/parts")
    fun index(): ModelAndView {
        val page = partsIndex(catalog, controllers.all(), firmwares.rules)
        return ModelAndView("parts/index", mapOf("page" to page, "head" to partsIndexHead(site)))
    }

    @GetMapping("/parts/dev-board/{slug}")
    fun devBoard(@PathVariable slug: String): ModelAndView {
        val board = controllers.bySlug(slug)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no dev board with slug '$slug'")
        val page = board.toDevBoardPage(queries, catalog, silhouettes, activity, firmwares.rules, hearts)
        return ModelAndView("parts/devBoard", mapOf("page" to page, "head" to partHead(site, page)))
    }
}
