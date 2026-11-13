package net.keyboardbuilders.keyboardbuilders.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Serves a design's render bytes from this origin. Backlog #60.
 *
 * SAME-ORIGIN IS NOT AN OPTIMISATION HERE, IT IS THE ONLY THING THAT WORKS. `model-viewer`
 * fetches the GLB with JavaScript, so a cross-origin fetch needs CORS headers, and the
 * object store sends none -- measured 2026-09-09: a GET with an `Origin` header returns
 * 200 with no `access-control-allow-origin` at all, and the preflight `OPTIONS` returns
 * **403**. So a page pointing `src` at the bucket would fail in the browser while every
 * `curl` check passed. Proxying through this route sidesteps the whole class.
 *
 * A REDIRECT WOULD NOT DO. Sending a 302 to the bucket puts the browser back on a
 * cross-origin fetch and back into the same failure.
 *
 * LOCAL DIRECTORY FIRST, THEN THE BUCKET, so a developer sees the real page without
 * needing cloud credentials -- the renders are already on the machine that made them.
 * Same route, same URLs, same bytes; only where they are read from differs.
 *
 * A BUCKET FETCH WRITES ITSELF TO DISK BEFORE RETURNING, which is what makes the second
 * request for any asset fast. In production `dir` starts empty -- only the jar is
 * deployed, never `build/renders` -- so every request used to be a live round trip to
 * Hetzner with no connection reuse: measured in production at **8-10 seconds per PNG**,
 * on every view, forever, because nothing was ever kept. Caching is safe without any
 * invalidation logic because the name IS the content: a key is a hash of the render's own
 * inputs, so the bytes behind it can never change, and a re-render gets a new key rather
 * than overwriting this one. Written to a temp file and renamed into place so a request
 * arriving mid-write reads either nothing (falls through to the bucket) or a complete
 * file, never a partial one.
 *
 * The key is a 32-hex content hash and is validated as one before it reaches a
 * filesystem or a URL. That is what makes this route safe to expose: no caller-supplied
 * string ever becomes a path segment.
 */
@Controller
class RenderAssetController(
    @Value("\${keyboardbuilders.renders.dir:build/renders}") private val dir: String,
    @Value("\${keyboardbuilders.renders.base-url:https://keyboardbuilders-renders.hel1.your-objectstorage.com/boards}")
    private val baseUrl: String,
) {

    @GetMapping("/renders/{name}")
    fun asset(@PathVariable name: String): ResponseEntity<ByteArray> {
        val match = KEY.matchEntire(name)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "not a render key")
        val (digest, ext) = match.destructured

        val bytes = fromDisk(digest, ext) ?: fromBucket(digest, ext)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no such render")

        return ResponseEntity.ok()
            // IMMUTABLE, and it is true rather than optimistic: the key is a hash of the
            // render's own inputs, so these bytes cannot change under this name. A
            // re-render is a different key.
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
            .contentType(if (ext == "glb") GLTF else MediaType.IMAGE_PNG)
            .body(bytes)
    }

    private fun fromDisk(digest: String, ext: String): ByteArray? {
        val path: Path = Path.of(dir, "$digest.$ext")
        return if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
    }

    private fun fromBucket(digest: String, ext: String): ByteArray? = runCatching {
        val connection = URI.create("$baseUrl/$digest.$ext").toURL().openConnection()
        connection.connectTimeout = TIMEOUT
        connection.readTimeout = TIMEOUT
        val bytes = connection.getInputStream().use { it.readBytes() }
        cacheToDisk(digest, ext, bytes)
        bytes
    }.getOrNull()

    /**
     * Best-effort. A cache write that fails (a read-only mount, a full disk) must not
     * turn a successful fetch into a 500 -- the bytes already made it to the response
     * either way, this only decides whether the NEXT request is fast.
     */
    private fun cacheToDisk(digest: String, ext: String, bytes: ByteArray) = runCatching {
        val target = Path.of(dir, "$digest.$ext")
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "$digest.", ".$ext.tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        /** 32 hex characters and one of two extensions. Nothing else is a render key. */
        val KEY = Regex("""([0-9a-f]{32})\.(glb|png)""")
        val GLTF: MediaType = MediaType.parseMediaType("model/gltf-binary")
        val TIMEOUT = Duration.ofSeconds(20).toMillis().toInt()
    }
}
