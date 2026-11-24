package net.keyboardbuilders.keyboardbuilders.web

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken

/**
 * The GitHub login of whoever is signed in on this request, or null. For the footer, which
 * every page draws through `layout.kte` and which therefore has no model of its own to be
 * given it. Templates render on the request's own thread, so the security context is there.
 *
 * SAFE ONLY WHILE NO HTML IS CACHED: nothing in the app or the Caddyfile caches a page
 * (checked 2026-11-24), so one person's name never reaches another.
 */
object SignedIn {
    fun login(): String? =
        (SecurityContextHolder.getContext().authentication as? OAuth2AuthenticationToken)
            ?.principal?.getAttribute<String>("login")
}
