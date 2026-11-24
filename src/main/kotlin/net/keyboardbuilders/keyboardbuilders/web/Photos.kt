package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.community.PhotoUploads
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * A real photo of a design's finished, assembled board, where one has been reviewed and
 * approved. Backlog #101, `ux.md` §3.5's "first-party photos... a post-v1 addition."
 *
 * DELIBERATELY THE SAME SHAPE AS [Renders] AND [Silhouettes]: a committed seed file read
 * once at startup, keyed by slug. `scripts/add_photo.py` is the whole contract between
 * that approval step and this page -- the app never fetches a photo from anywhere but its
 * own object store and never learns that a repo might have one.
 *
 * NEVER A HOTLINKED REPO URL, and that is the point of the seed file existing at all.
 * #101 sampled 15 designs and found a real one is not safe to grab automatically: a repo
 * can rename its branch, move the file, or disappear with nothing here to notice, and one
 * README embedded a photo of a completely different, commercial keyboard for comparison.
 * So every entry is bytes a human or an agent actually looked at, copied once.
 *
 * ABSENT IS THE COMMON CASE AND MEANS "NOT CURATED YET", never "no real photo could exist"
 * -- #101's own sample put the ceiling at maybe 40% of designs, and as of this file only
 * one entry exists. The page shows nothing extra, exactly as before this existed.
 */
@Component
class Photos(
    mapper: ObjectMapper,
    @Value("classpath:seed/photos.json") resource: Resource,
    private val uploads: PhotoUploads? = null,
) {
    /**
     * `sourceUrl` names the repo the photo was approved from, so the copy is auditable --
     * same reasoning as [Silhouettes.Geometry.from]. Nothing renders it as a byline: the
     * design's own author credit, already on the page, already names this repo.
     *
     * `credit` is set only for a photo from somewhere else -- kbd.news, a builder's post
     * -- and names the page it came from. The author credit does not cover that photo,
     * so the page credits it by itself.
     */
    data class Photo(
        val jpg: String,
        val width: Int,
        val height: Int,
        val sourceUrl: String,
        val credit: String? = null,
        /** The GitHub login of whoever added it on this site (2026-11-24); null for the seed's. */
        val addedBy: String? = null,
    )

    private val bySlug: Map<String, Photo> =
        resource.inputStream.use { mapper.readValue<Map<String, Photo>>(it) }

    /**
     * AN APPROVED UPLOAD WINS OVER THE SEED'S PHOTO (owner, 2026-11-24): uploads are only
     * taken where a page has no photo, so a seed photo beside one arrived later, from the
     * nightly job, and the person's photo stays.
     */
    fun of(slug: String): Photo? =
        uploads?.approvedFor(slug)?.let { Photo(it.jpg, it.width, it.height, sourceUrl = "", addedBy = it.login) }
            ?: bySlug[slug]

    /** The seed's photo alone, ignoring uploads. */
    fun fromSeed(slug: String): Photo? = bySlug[slug]

    /**
     * Same-origin, through [PhotoAssetController] -- not the object store directly. A
     * plain `<img>` needs none of the CORS a render's JS fetch does, so this could point
     * straight at the bucket, and the first version did; changed once the plan stopped
     * being "one photo" and started being "100-150 of them", because a cross-origin fetch
     * with no connection reuse was the exact cost `RenderAssetController`'s own cache was
     * built to avoid -- 8-10 seconds the first time anyone asks, forever, for nothing kept.
     */
    fun url(photo: Photo): String = "/photos/${photo.jpg}.jpg"

    /** How many designs have one. For the credits/coverage line and for tests. */
    val count: Int get() = bySlug.size

    companion object {
        /** The card-sized copy (`PhotoStore.cardBytes`), for anywhere a photo is shown small. */
        fun cardUrl(photo: Photo): String = "/photos/${photo.jpg}-card.jpg"
    }
}
