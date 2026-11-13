package net.keyboardbuilders.keyboardbuilders.web

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * Why the site exists, in the owner's own words. The words live in `about.kte` and
 * nowhere else, so editing the page is editing that one file.
 */
@Controller
class AboutController(private val site: SiteUrl) {

    @GetMapping("/about")
    fun about(model: Model): String {
        model.addAttribute("head", Head(
            title = "About",
            description = "Why Keyboard Builders exists, and who made it.",
            canonical = site.of("/about"),
            image = site.of("/og.png"),
        ))
        return "about"
    }
}
