package net.keyboardbuilders.keyboardbuilders.community

import net.keyboardbuilders.keyboardbuilders.web.PhotoStore
import org.springframework.stereotype.Component

/**
 * Where an uploaded photo's bytes live at each step (owner, 2026-11-24):
 *
 *   waiting   on the server's disk only, where reviewers see it
 *   yes       copied to the bucket too, so it outlives the server
 *   no        deleted from disk; the bucket never had it
 *   removed   deleted from the bucket and the disk
 *
 * Nothing unreviewed ever reaches the public bucket. Who may do each is the caller's
 * question. SYNCHRONIZED so two reviewers deciding one photo at once can't both act.
 */
@Component
class PhotoReview(
    private val uploads: PhotoUploads,
    private val store: PhotoStore,
    private val bucket: Bucket,
) {
    /** Bucket first: if it refuses, the photo stays waiting rather than approved and lost. */
    @Synchronized
    fun approve(id: Long, by: String): Boolean {
        val upload = uploads.get(id)?.takeIf { it.status == Upload.Status.WAITING } ?: return false
        val bytes = store.bytes(upload.jpg) ?: error("photo ${upload.jpg} is missing from disk")
        bucket.put(key(upload.jpg), bytes, "image/jpeg")
        return uploads.approve(id, by)
    }

    @Synchronized
    fun reject(id: Long, by: String, note: String?): Boolean {
        val upload = uploads.get(id) ?: return false
        if (!uploads.reject(id, by, note)) return false
        if (!uploads.inUse(upload.jpg)) store.delete(upload.jpg)
        return true
    }

    @Synchronized
    fun remove(id: Long) {
        val upload = uploads.get(id) ?: return
        uploads.remove(id)
        if (uploads.inUse(upload.jpg)) return
        if (upload.status == Upload.Status.APPROVED) bucket.delete(key(upload.jpg))
        store.delete(upload.jpg)
    }

    private fun key(jpg: String) = "photos/$jpg.jpg"
}
