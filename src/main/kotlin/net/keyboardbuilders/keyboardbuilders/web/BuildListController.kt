package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.parts.PartsModule
import net.keyboardbuilders.keyboardbuilders.parts.Region
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView

/**
 * A design's build list (#112): what it needs, and what a shop charges for it today.
 *
 * LOCAL ONLY. The route exists only where `keyboardbuilders.parts.enabled` is set, which is
 * `application-dev.properties` and the tests that ask for it; production has no such line,
 * so the URL is a 404 there. Noindex as well, and in no sitemap and no nav: nothing links
 * here until the owner says so.
 *
 * A renamed slug 301s to the current one, as `compatible` does, keeping the reader's picks.
 * The picks (#113) are read by [readPicks], which never throws: a bad one is a note.
 */
@Controller
@ConditionalOnProperty("keyboardbuilders.parts.enabled", havingValue = "true")
class BuildListController(
    private val catalog: DesignCatalog,
    private val renames: SlugRenames,
    private val parts: PartsModule,
) {

    @GetMapping("/designs/{slug}/build")
    fun build(
        @PathVariable slug: String,
        @RequestParam params: Map<String, String>,
    ): ModelAndView {
        catalog.bySlug(slug)?.let { design ->
            return ModelAndView(
                "build/list",
                mapOf(
                    "page" to parts.buildListFor(design, READER, readPicks(params)).toPage(design, parts.shops, READER),
                    "head" to Head(title = "${design.core.name.value} — parts to buy", indexable = false),
                ),
            )
        }
        renames.currentFor(slug)?.let { current ->
            return ModelAndView(
                // Appended after any URI-template expansion, so a "{" in a pick cannot break it.
                RedirectView("/designs/$current/build", true)
                    .apply { setStatusCode(HttpStatus.MOVED_PERMANENTLY); setPropagateQueryParams(true) }
            )
        }
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "no design with slug '$slug'")
    }

    private companion object {
        /** The slice prices for one reader, the owner, in Norway. */
        val READER = Region("NO")
    }
}
