package net.keyboardbuilders.keyboardbuilders.account

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * DEV MACHINE ONLY: `/dev/sign-in-as?login=davidphilipbarr` signs you in as that GitHub
 * login without asking GitHub (#116). The owner's own account owns no design, so this is
 * the only way to try the author's screens in a real browser.
 *
 * TWO LOCKS. The switch is set only in application-dev.properties, and the bean also
 * refuses the `prod` profile, so a stray property on the VPS still cannot turn it on.
 * `DevSignInOffTest` pins the default: the URL does not exist.
 *
 * The user it makes has the same shape GitHub's does, `login` among the attributes, so
 * everything after sign-in runs the code a real designer runs.
 */
@Controller
@Profile("!prod")
@ConditionalOnProperty("keyboardbuilders.dev-sign-in", havingValue = "true")
class DevSignInController {

    private val contexts = HttpSessionSecurityContextRepository()

    @GetMapping("/dev/sign-in-as")
    fun signInAs(
        @RequestParam login: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): String {
        val user = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("OAUTH2_USER")),
            mapOf("login" to login, "id" to login),
            "login",
        )
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = OAuth2AuthenticationToken(user, user.authorities, "github")
        SecurityContextHolder.setContext(context)
        contexts.saveContext(context, request, response)
        return "redirect:/account"
    }
}
