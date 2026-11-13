package net.keyboardbuilders.keyboardbuilders.web

import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Puts `site` on every model, so the footer does not have to be remembered per controller.
 *
 * The footer is on EVERY page and not just the home page, deliberately: a reader notices a
 * wrong switch footprint while looking at that design, not while looking at the index. A
 * correction route only reachable from the front page is a route nobody uses.
 */
@ControllerAdvice
class SiteAdvice(private val site: SiteUrl) {
    @ModelAttribute("site")
    fun site(): SiteUrl = site
}
