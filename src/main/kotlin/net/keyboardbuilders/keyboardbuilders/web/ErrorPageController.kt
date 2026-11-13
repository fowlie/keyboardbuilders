package net.keyboardbuilders.keyboardbuilders.web

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.RequestMapping

/**
 * An error a person can read, instead of Spring's JSON error object.
 *
 * WHAT WAS ACTUALLY WRONG, because it was not what it looked like. A mistyped slug
 * returned `{"timestamp":…,"status":404,"error":"Not Found","path":"/designs/nope"}` —
 * which leaks nothing (no stack trace, no framework versions, no server detail; Boot
 * suppresses those by default and this checked) but is JSON served to a human. It matters
 * more now the site is indexed: any URL that later changes serves that to a visitor
 * arriving from a search result.
 *
 * THE REASON IS SHOWN ON A 4xx AND NEVER ON A 5xx. `contract.md` §1 says an unreadable
 * query is "a 400 with the reason, never a silent narrowing", and `DesignsController`
 * dutifully throws `ResponseStatusException(BAD_REQUEST, unreadable.message)` — which
 * `server.error.include-message=never` then threw away, so the reason has never reached
 * anyone. A 4xx message is ours, written for the reader. A 5xx message is whatever the
 * JVM said, and that IS the class of thing that leaks.
 */
@Controller
class ErrorPageController(private val site: SiteUrl) : ErrorController {

    @RequestMapping("/error")
    fun render(request: HttpServletRequest, model: Model): String {
        val code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int ?: 500
        val status = HttpStatus.resolve(code) ?: HttpStatus.INTERNAL_SERVER_ERROR
        val reason = (request.getAttribute(RequestDispatcher.ERROR_MESSAGE) as? String)
            ?.takeIf { it.isNotBlank() && status.is4xxClientError }

        model.addAttribute("status", code)
        model.addAttribute("headline", headline(status))
        model.addAttribute("reason", reason)
        model.addAttribute("head", Head(
            title = "$code",
            // NOINDEX, and it is the reason this page needs a Head at all: an error page
            // that a crawler files away is a URL competing with the real ones.
            indexable = false,
        ))
        model.addAttribute("site", site)
        return "error"
    }

    /** Plain words. A reader who typed a URL wrong does not need the RFC's phrasing. */
    private fun headline(status: HttpStatus): String = when (status) {
        HttpStatus.NOT_FOUND -> "There is no page here"
        HttpStatus.BAD_REQUEST -> "That address could not be read"
        HttpStatus.FORBIDDEN -> "That is not something you can look at"
        HttpStatus.GONE -> "This design is no longer listed"
        else -> if (status.is5xxServerError) "Something went wrong at our end"
                else "That request could not be answered"
    }
}
