package net.keyboardbuilders.keyboardbuilders.account

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.AccessDeniedHandlerImpl
import org.springframework.security.web.csrf.CsrfException
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher

/**
 * Sign in with GitHub (backlog #116). EVERYTHING STAYS PUBLIC except `/account`: the site
 * is a reference anyone reads without an account, and only a designer managing their own
 * designs needs to say who they are.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        val account = PathPatternRequestMatcher.withDefaults().matcher("/account/**")
        val accountRoot = PathPatternRequestMatcher.withDefaults().matcher("/account")
        val logout = PathPatternRequestMatcher.withDefaults().matcher("/logout")
        // Backlog #117: the moderator queue. Signed in here; being a moderator is checked
        // in ModerateController against the configured list.
        val moderate = PathPatternRequestMatcher.withDefaults().matcher("/moderation")
        val moderateAny = PathPatternRequestMatcher.withDefaults().matcher("/moderation/**")
        // Build photos (2026-11-24): adding or removing one needs a GitHub sign-in, and a
        // moderator's review-rights switch posts from someone's page.
        val photo = PathPatternRequestMatcher.withDefaults().matcher("/designs/{slug}/photo")
        val photoAny = PathPatternRequestMatcher.withDefaults().matcher("/designs/{slug}/photo/**")
        val reviewRights = PathPatternRequestMatcher.withDefaults().matcher("/builders/{login}/review-rights")
        http
            .authorizeHttpRequests {
                it.requestMatchers(accountRoot, account, moderate, moderateAny, photo, photoAny, reviewRights).authenticated()
                it.anyRequest().permitAll()
            }
            // Straight to GitHub, not Spring's own "choose a provider" page: there is one.
            .oauth2Login { it.loginPage("/oauth2/authorization/github") }
            .logout { it.logoutSuccessUrl("/") }
            // Back from GitHub to /account, not /account?continue: the default marks the
            // saved URL with that parameter, and it then sits in the address bar.
            .requestCache { it.requestCache(PlainRequestCache()) }
            // CSRF ONLY WHERE A SESSION MAKES A REQUEST POWERFUL. The heart buttons and the
            // /settings form are anonymous and write nothing tied to a person, and they post
            // without a token today; checking them would break them for no protection.
            .csrf { it.requireCsrfProtectionMatcher { r -> r.method == "POST" && (account.matches(r) || logout.matches(r) || moderateAny.matches(r) ||
                photo.matches(r) || photoAny.matches(r) || reviewRights.matches(r)) } }
            // NO `no-store` ON EVERY RESPONSE. Spring Security's default would stop browsers
            // keeping /app.css, which is served without Cache-Control on purpose (Head.kt),
            // and it would override nothing the asset controllers set themselves.
            .headers { it.cacheControl { c -> c.disable() } }
            .exceptionHandling { it.accessDeniedHandler(ExpiredSessionsMoveOn(logout)) }
        return http.build()
    }
}

/**
 * A REFUSED TOKEN FROM A SESSION THAT NO LONGER EXISTS IS AN EXPIRED SIGN-IN, NOT AN
 * ATTACK. Sessions live in memory and every deploy restarts the app, so a page left open
 * across one posts a token nobody can match. Sign out goes to the front page (they are
 * signed out already); an account button goes back to /account, which signs them in again.
 * A bad token inside a live session is still the plain 403.
 */
private class ExpiredSessionsMoveOn(private val logout: RequestMatcher) : AccessDeniedHandler {
    private val refuse = AccessDeniedHandlerImpl()

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, denied: AccessDeniedException) {
        val expired = denied is CsrfException && request.requestedSessionId != null && !request.isRequestedSessionIdValid
        when {
            !expired -> refuse.handle(request, response, denied)
            logout.matches(request) -> response.sendRedirect("/")
            else -> response.sendRedirect("/account")
        }
    }
}
