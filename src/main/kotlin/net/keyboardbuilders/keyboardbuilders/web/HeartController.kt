package net.keyboardbuilders.keyboardbuilders.web

import jakarta.servlet.http.HttpServletRequest
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.persistence.Hearts
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Hearting a design: `PUT` adds one, `DELETE` takes it back, and both answer with the new
 * total. Signed out, the visitor's own hearts are in their browser's localStorage and this
 * only keeps the sum (`V6__heart.sql`). Signed in, they are kept with the account too
 * (`V12__account_heart.sql`), once per person; `PUT /hearts` merges the browser's list in
 * on sign-in, and `GET /hearts` reads it back (owner, 2026-11-27, `static/hearts.js`).
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
    fun heart(@PathVariable slug: String, request: HttpServletRequest): Total {
        val checked = checked(slug, request)
        return Total(SignedIn.login()?.let { hearts.addFor(it, checked) } ?: hearts.add(checked))
    }

    @DeleteMapping("/designs/{slug}/heart")
    fun unheart(@PathVariable slug: String, request: HttpServletRequest): Total {
        val checked = checked(slug, request)
        return Total(SignedIn.login()?.let { hearts.removeFor(it, checked) } ?: hearts.remove(checked))
    }

    /** The signed-in person's hearts, in the order hearted. */
    @GetMapping("/hearts")
    fun mine(): List<String> = hearts.of(signedIn())

    /** A browser's hearts joining the account, once, on sign-in. Slugs nothing publishes
     *  are dropped (a stranger chooses them); the account's whole list comes back. */
    @PutMapping("/hearts")
    fun merge(@RequestBody slugs: List<String>): List<String> =
        hearts.merge(signedIn(), slugs.take(MAX_MERGE).filter { catalog.bySlug(it) != null })

    private fun signedIn(): String =
        SignedIn.login() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in first")

    /** A design the catalogue publishes, from a visitor under their rate. `remoteAddr` is
     *  the real client in production: `server.forward-headers-strategy=native`. */
    private fun checked(slug: String, request: HttpServletRequest): String {
        catalog.bySlug(slug) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no design '$slug'")
        if (!rate.allow(request.remoteAddr)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "slow down a little")
        }
        return slug
    }

    private companion object {
        /** More than anyone hearts by hand; the cap only bounds a hand-made request. */
        const val MAX_MERGE = 500
    }
}
