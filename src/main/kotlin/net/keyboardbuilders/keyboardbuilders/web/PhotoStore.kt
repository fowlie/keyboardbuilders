package net.keyboardbuilders.keyboardbuilders.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * A photo's bytes, from wherever they currently live: `build/photos` on the machine that
 * fetched them before, or the bucket, on a plain digest.
 *
 * PULLED OUT OF [PhotoAssetController] once [OgImageController] needed the same bytes for
 * its own reason -- a share card cropped from the real photo -- rather than a second copy
 * of the same disk-then-bucket-then-cache dance. The digest that reaches this class is
 * never a caller-supplied path segment: [PhotoAssetController] validates that shape before
 * calling in, and [OgImageController] only ever passes a digest it read out of
 * `photos.json` itself.
 */
@Component
class PhotoStore(
    @Value("\${keyboardbuilders.photos.dir:build/photos}") private val dir: String,
    @Value("\${keyboardbuilders.photos.base-url:https://keyboardbuilders-renders.hel1.your-objectstorage.com/photos}")
    private val baseUrl: String,
) {
    fun bytes(digest: String): ByteArray? = fromDisk(digest) ?: fromBucket(digest)

    private fun fromDisk(digest: String): ByteArray? {
        val path: Path = Path.of(dir, "$digest.jpg")
        return if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
    }

    private fun fromBucket(digest: String): ByteArray? = runCatching {
        val connection = URI.create("$baseUrl/$digest.jpg").toURL().openConnection()
        connection.connectTimeout = TIMEOUT
        connection.readTimeout = TIMEOUT
        val bytes = connection.getInputStream().use { it.readBytes() }
        cacheToDisk(digest, bytes)
        bytes
    }.getOrNull()

    /**
     * Best-effort, same reasoning as [RenderAssetController]'s own cache write: a write
     * that fails (a read-only mount, a full disk) must not turn a successful fetch into
     * an error -- the bytes already made it to the caller either way, this only decides
     * whether the next request is fast.
     */
    private fun cacheToDisk(digest: String, bytes: ByteArray) = runCatching {
        val target = Path.of(dir, "$digest.jpg")
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "$digest.", ".jpg.tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * The photo at card width, resized once and cached beside the original.
     *
     * WHY THE SERVER RESIZES rather than a build step writing a second file: the photos
     * already live in a bucket keyed by their own hash, and a card copy is a pure function
     * of those bytes -- so it can be derived on first request and stay correct forever,
     * with no second upload to forget. The disk cache makes every later request a read.
     *
     * Never enlarged: a photo narrower than a card comes back at its own size.
     */
    fun cardBytes(digest: String): ByteArray? {
        val cached = Path.of(dir, "$digest-card.jpg")
        if (Files.isRegularFile(cached)) return Files.readAllBytes(cached)
        val original = bytes(digest) ?: return null
        val card = runCatching { resize(original, CARD_WIDTH) }.getOrNull() ?: return original
        cacheToDisk("$digest-card", card)
        return card
    }

    private fun resize(jpeg: ByteArray, width: Int): ByteArray {
        val source = ImageIO.read(ByteArrayInputStream(jpeg))
        if (source.width <= width) return jpeg
        val height = (source.height.toLong() * width / source.width).toInt()
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        target.createGraphics().run {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(source, 0, 0, width, height, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = CARD_QUALITY
        }
        return ByteArrayOutputStream().use { out ->
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(target, null, null), params)
            }
            writer.dispose()
            out.toByteArray()
        }
    }

    companion object {
        /** Twice the widest a card shows a photo (~320px), for 2x screens. */
        const val CARD_WIDTH = 640
        private const val CARD_QUALITY = 0.82f
        private val TIMEOUT = Duration.ofSeconds(20).toMillis().toInt()
    }
}
