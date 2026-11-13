package net.keyboardbuilders.keyboardbuilders.web

import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.TimeUnit

/**
 * Serves an approved photo's bytes from this origin. Backlog #101.
 *
 * SAME SHAPE AS [RenderAssetController], not because a photo needs the CORS this exists
 * for on a render -- a plain `<img>` needs none of that, the way `model-viewer`'s JS fetch
 * does -- but because the OTHER half of that route matters just as much here: a bucket
 * fetch with no connection reuse was measured at 8-10 seconds per asset the first time
 * anyone asked for it, and a photo is exactly the same shape of request. Pointing `<img
 * src>` straight at the bucket was the first version of this and was fine for one photo;
 * it stops being fine at the 100-150 this project is about to add.
 *
 * LOCAL DIRECTORY FIRST, THEN THE BUCKET, so a developer sees a real photo without cloud
 * credentials -- `scripts/add_photo.py` already wrote it to disk on the machine that
 * uploaded it. Same route, same URL, same bytes; only where they are read from differs.
 *
 * The key is a 32-hex content hash and is validated as one before it reaches [PhotoStore],
 * same guarantee [RenderAssetController] makes: no caller-supplied string ever becomes a
 * path segment.
 */
@Controller
class PhotoAssetController(private val store: PhotoStore) {

    @GetMapping("/photos/{name}")
    fun asset(@PathVariable name: String): ResponseEntity<ByteArray> {
        val match = KEY.matchEntire(name)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "not a photo key")
        val digest = match.groupValues[1]
        val card = match.groupValues[2].isNotEmpty()

        val bytes = (if (card) store.cardBytes(digest) else store.bytes(digest))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no such photo")

        return ResponseEntity.ok()
            // IMMUTABLE, and it is true rather than optimistic: the key is a hash of the
            // photo's own bytes (scripts/add_photo.py), so nothing can change under this
            // name. A re-approved or re-cropped photo gets a new key.
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
            .contentType(MediaType.IMAGE_JPEG)
            .body(bytes)
    }

    private companion object {
        /** 32 hex characters, always .jpg -- add_photo.py never writes another extension.
         *  `-card` asks for the card-width copy; still immutable, since it is derived
         *  from bytes that are themselves immutable under this key. */
        val KEY = Regex("""([0-9a-f]{32})(-card)?\.jpg""")
    }
}
