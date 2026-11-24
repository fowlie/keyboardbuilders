package net.keyboardbuilders.keyboardbuilders.community

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.web.Head
import net.keyboardbuilders.keyboardbuilders.web.PhotoStore
import net.keyboardbuilders.keyboardbuilders.web.Photos
import net.keyboardbuilders.keyboardbuilders.web.SiteUrl
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import jakarta.servlet.http.HttpServletResponse

/**
 * Adding a build photo to a design that has none, and taking one down (owner, 2026-11-24).
 * Signed in with GitHub (SecurityConfig). The photo waits for review at /moderation.
 */
@Controller
class PhotoUploadController(
    private val catalog: DesignCatalog,
    private val photos: Photos,
    private val uploads: PhotoUploads,
    private val slots: PhotoSlots,
    private val store: PhotoStore,
    private val review: PhotoReview,
    private val site: SiteUrl,
) {

    @GetMapping("/designs/{slug}/photo")
    fun form(@AuthenticationPrincipal user: OAuth2User, @PathVariable slug: String, csrf: CsrfToken, model: Model): String {
        val design = design(slug)
        val login = user.login()
        val approved = uploads.approvedFor(slug)
        // Someone who may take the photo down gets the remove button; anyone else, once a
        // page has a photo, has nothing to do here.
        if (approved != null && slots.mayRemove(design, approved, login)) {
            return page(model, design, csrf, state = "remove", photo = photos.url(photos.of(slug)!!), uploadId = approved.id)
        }
        if (photos.of(slug) != null) return "redirect:/designs/$slug"
        if (uploads.current(slug) != null) return page(model, design, csrf, state = "waiting")
        return page(model, design, csrf, state = "add")
    }

    @PostMapping("/designs/{slug}/photo")
    fun upload(
        @AuthenticationPrincipal user: OAuth2User,
        @PathVariable slug: String,
        @RequestParam("photo", required = false) file: MultipartFile?,
        csrf: CsrfToken,
        model: Model,
        response: HttpServletResponse,
    ): String {
        val design = design(slug)
        val login = user.login()
        if (photos.of(slug) != null) return "redirect:/designs/$slug"
        if (file == null || file.isEmpty) {
            response.status = 400
            return page(model, design, csrf, state = "add", error = "Choose a photo first.")
        }
        val image = try {
            UploadedImage.from(file.bytes)
        } catch (e: NotAnImage) {
            response.status = 400
            return page(model, design, csrf, state = "add", error = "That file isn't a photo we can read. Send a JPEG or PNG.")
        }
        store.save(image.digest, image.jpeg)
        try {
            uploads.submit(slug, login, image.digest, image.width, image.height)
        } catch (e: PhotoTaken) {
            response.status = 409
            return page(model, design, csrf, state = "waiting")
        }
        return "redirect:/builders/${login.lowercase()}"
    }

    @PostMapping("/designs/{slug}/photo/remove")
    fun remove(@AuthenticationPrincipal user: OAuth2User, @PathVariable slug: String, @RequestParam id: Long): String {
        val design = design(slug)
        val upload = uploads.get(id)?.takeIf { it.slug == slug }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no such photo")
        if (!slots.mayRemove(design, upload, user.login())) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only whoever added it, the designer or a moderator can remove a photo.")
        }
        review.remove(id)
        return "redirect:/designs/$slug"
    }

    private fun page(
        model: Model, design: Design, csrf: CsrfToken, state: String,
        error: String? = null, photo: String? = null, uploadId: Long? = null,
    ): String {
        model.addAttribute("slug", design.core.slug.value)
        model.addAttribute("name", design.core.name.value)
        model.addAttribute("state", state)
        model.addAttribute("error", error)
        model.addAttribute("photo", photo)
        model.addAttribute("uploadId", uploadId)
        model.addAttribute("csrf", csrf)
        model.addAttribute("head", Head(title = "Add a build photo", indexable = false))
        model.addAttribute("site", site)
        return "photoUpload"
    }

    private fun design(slug: String): Design =
        catalog.bySlug(slug) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no design with slug '$slug'")
}

internal fun OAuth2User.login(): String =
    getAttribute<String>("login") ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "no GitHub login")
