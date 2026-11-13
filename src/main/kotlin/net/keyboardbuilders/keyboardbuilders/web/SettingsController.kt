package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Duration

/**
 * Which picture leads a design page (owner, 2026-10-02): the assembled photo, the default
 * since 2026-09-17, or the bare PCB render. The owner's reason for the second: browsing a
 * family's boards one after another was "REALLY nice" on the renders, and the photos hid
 * the comparison.
 *
 * A COOKIE, not localStorage like the hearts, so the server draws the chosen hero and no
 * page flashes the other picture first. Only `render` is ever stored: the photo is the
 * default, so choosing it removes the cookie.
 */
enum class HeroFirst(val token: String) {
    Photo("photo"), Render("render");

    companion object {
        const val COOKIE = "kb-hero"

        fun of(token: String?): HeroFirst = entries.firstOrNull { it.token == token } ?: Photo
    }
}

/**
 * /settings: the hero choice, and clearing every favorite. The first is a plain form POST
 * so it works without JavaScript; the second needs script, since favorites live in the
 * browser's localStorage.
 */
/** One real design's two pictures, so each choice shows what it means. */
data class HeroExample(val name: String, val photo: String, val render: String)

@Controller
class SettingsController(
    private val site: SiteUrl,
    private val catalog: DesignCatalog,
    private val photos: Photos,
    private val renders: Renders,
) {
    /** The first design, in catalogue order, that has both. Per request, so an
     *  unpublished design (#116) stops being the example at once. */
    private fun example(): HeroExample? = catalog.all().firstNotNullOfOrNull { design ->
        val slug = design.core.slug.value
        val photo = photos.of(slug) ?: return@firstNotNullOfOrNull null
        val render = renders.of(slug) ?: return@firstNotNullOfOrNull null
        HeroExample(design.core.name.value, Photos.cardUrl(photo), "/renders/${render.png}.png")
    }

    @GetMapping("/settings")
    fun settings(
        @CookieValue(HeroFirst.COOKIE, required = false) hero: String?,
        @RequestParam(required = false) saved: String?,
        model: Model,
    ): String {
        model.addAttribute("hero", HeroFirst.of(hero))
        model.addAttribute("saved", saved != null)
        model.addAttribute("example", example())
        model.addAttribute("head", Head(
            title = "Settings",
            description = "Choose which picture a design page shows first, or clear your favorites.",
            canonical = site.of("/settings"),
            indexable = false,
        ))
        return "settings"
    }

    @PostMapping("/settings")
    fun save(@RequestParam hero: String): ResponseEntity<Void> {
        val choice = HeroFirst.entries.firstOrNull { it.token == hero }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "no such picture '$hero'")
        val cookie = ResponseCookie.from(HeroFirst.COOKIE, if (choice == HeroFirst.Render) choice.token else "")
            .path("/")
            .sameSite("Lax")
            .httpOnly(true)
            .maxAge(if (choice == HeroFirst.Render) Duration.ofDays(365) else Duration.ZERO)
            .build()
        // 303: the browser follows with a GET, so a reload does not resubmit the form.
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .location(URI.create("/settings?saved"))
            .build()
    }
}
