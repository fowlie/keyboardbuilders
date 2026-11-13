package net.keyboardbuilders.keyboardbuilders.web

import jakarta.servlet.http.HttpServletRequest
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.persistence.Hearts
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Hearting a design: `PUT` adds one, `DELETE` takes it back, and both answer with the new
 * total. The visitor's own hearts are in their browser's localStorage; this only keeps the
 * sum (`V6__heart.sql`).
 *
 * PUT AND DELETE, NOT POST. A form or a plain cross-site `fetch` can POST without asking;
 * neither PUT nor DELETE goes cross-origin without a CORS preflight, and this site answers
 * none, so another page cannot heart a design on its own visitors' behalf.
 */
@RestController
class HeartController(
    private val catalog: DesignCatalog,
    private val hearts: Hearts,
    private val rate: HeartRate,
) {
    data class Total(val count: Int)

    @PutMapping("/designs/{slug}/heart")
    fun heart(@PathVariable slug: String, request: HttpServletRequest): Total =
        Total(hearts.add(checked(slug, request)))

    @DeleteMapping("/designs/{slug}/heart")
    fun unheart(@PathVariable slug: String, request: HttpServletRequest): Total =
        Total(hearts.remove(checked(slug, request)))

    /** A design the catalogue publishes, from a visitor under their rate. `remoteAddr` is
     *  the real client in production: `server.forward-headers-strategy=native`. */
    private fun checked(slug: String, request: HttpServletRequest): String {
        catalog.bySlug(slug) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no design '$slug'")
        if (!rate.allow(request.remoteAddr)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "slow down a little")
        }
        return slug
    }
}
