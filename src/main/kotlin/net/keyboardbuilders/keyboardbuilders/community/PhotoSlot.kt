package net.keyboardbuilders.keyboardbuilders.community

import net.keyboardbuilders.keyboardbuilders.account.isOwnedBy
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.web.Photos
import org.springframework.stereotype.Component

/**
 * What a design page says about its build photo, beyond the photo itself:
 * who added it, or that one is waiting, or the invitation to add one.
 * [removeId] is set only for someone allowed to take the photo down: whoever added it,
 * a moderator, or the board's designer.
 */
data class PhotoSlot(
    val addedBy: String? = null,
    val waiting: Boolean = false,
    val canAdd: Boolean = false,
    val removeId: Long? = null,
)

@Component
class PhotoSlots(private val uploads: PhotoUploads, private val contributors: Contributors) {

    fun of(design: Design, photo: Photos.Photo?, viewer: String?): PhotoSlot {
        val slug = design.core.slug.value
        if (photo == null) {
            val waiting = uploads.current(slug)?.status == Upload.Status.WAITING
            return PhotoSlot(waiting = waiting, canAdd = !waiting)
        }
        val by = photo.addedBy ?: return PhotoSlot()
        val upload = uploads.approvedFor(slug)
        return PhotoSlot(
            addedBy = by,
            removeId = upload?.id?.takeIf { viewer != null && mayRemove(design, upload, viewer) },
        )
    }

    fun mayRemove(design: Design, upload: Upload, login: String): Boolean =
        upload.login.equals(login, ignoreCase = true) || contributors.isModerator(login) || design.isOwnedBy(login)
}
