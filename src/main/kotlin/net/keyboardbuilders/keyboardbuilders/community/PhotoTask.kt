package net.keyboardbuilders.keyboardbuilders.community

import net.keyboardbuilders.keyboardbuilders.account.isOwnedBy
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.web.Photos
import net.keyboardbuilders.keyboardbuilders.web.RepoActivity
import org.springframework.stereotype.Component

/**
 * The one task the account page offers someone signed in (owner, 2026-11-25): add a build
 * photo to a design that has none. Sign-in lands there, so it is the first thing a new
 * contributor sees.
 *
 * WHICH DESIGN: one they are likely to have built. Their own designs first -- a designer
 * has surely built theirs -- then by GitHub stars, most first. Stars are not a count that
 * a gap in our data pushes up, so they are fair to rank by. A design they already sent a
 * photo for is skipped, accepted or not. [n] steps down the list, for "not built that one".
 */
@Component
class PhotoTask(
    private val catalog: DesignCatalog,
    private val photos: Photos,
    private val uploads: PhotoUploads,
    private val activity: RepoActivity,
) {

    /** The [n]th design (1-based) this person could add a photo to, or null past the end. */
    fun suggest(login: String, n: Int = 1): Design? {
        val taken = uploads.slugsWithPhotoOrWaiting()
        val tried = uploads.byLogin(login).map { it.slug }.toSet()
        return catalog.all().asSequence()
            .filter { val slug = it.core.slug.value; photos.of(slug) == null && slug !in taken && slug !in tried }
            .sortedWith(compareBy<Design>({ !it.isOwnedBy(login) }, { -(activity.starsFor(it) ?: -1) }, { it.core.slug.value }))
            .elementAtOrNull(n.coerceAtLeast(1) - 1)
    }
}
