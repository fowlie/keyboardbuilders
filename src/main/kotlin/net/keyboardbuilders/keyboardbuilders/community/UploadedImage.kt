package net.keyboardbuilders.keyboardbuilders.community

import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** The upload was not a JPEG or PNG the server could read. */
class NotAnImage : RuntimeException("not a JPEG or PNG")

/**
 * An uploaded photo, made into what the seed's photos already are: a JPEG at most 1600px
 * wide, stored under a hash of its own bytes.
 *
 * RE-ENCODED, NEVER STORED AS SENT. That drops the EXIF block, which on a phone photo
 * holds where it was taken, and it means the bytes served are bytes this code wrote.
 *
 * TURNED UPRIGHT FIRST. A phone stores the picture sideways and an EXIF tag saying how to
 * turn it; ImageIO ignores that tag, so without this a portrait photo shows on its side.
 */
class UploadedImage private constructor(val jpeg: ByteArray, val width: Int, val height: Int) {

    /** `scripts/add_photo.py`'s key: the first 32 hex characters of the bytes' SHA-256. */
    val digest: String = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }.take(32)

    companion object {
        const val MAX_WIDTH = 1600
        private const val QUALITY = 0.86f

        fun from(bytes: ByteArray): UploadedImage {
            val read = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: throw NotAnImage()
            val upright = rotate(read, exifOrientation(bytes))
            val scaled = shrink(upright, MAX_WIDTH)
            val jpeg = encode(scaled)
            return UploadedImage(jpeg, scaled.width, scaled.height)
        }

        private fun shrink(image: BufferedImage, maxWidth: Int): BufferedImage {
            val width = minOf(image.width, maxWidth)
            val height = (image.height.toLong() * width / image.width).toInt()
            // Always redrawn, even at full size: an RGB canvas also flattens a PNG's
            // transparency, which JPEG cannot hold.
            return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { target ->
                target.createGraphics().run {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    color = java.awt.Color.WHITE
                    fillRect(0, 0, width, height)
                    drawImage(image, 0, 0, width, height, null)
                    dispose()
                }
            }
        }

        private fun encode(image: BufferedImage): ByteArray {
            val writer = ImageIO.getImageWritersByFormatName("jpg").next()
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = QUALITY
            }
            return ByteArrayOutputStream().use { out ->
                ImageIO.createImageOutputStream(out).use { stream ->
                    writer.output = stream
                    writer.write(null, IIOImage(image, null, null), params)
                }
                writer.dispose()
                out.toByteArray()
            }
        }

        /** EXIF orientations 1-8; anything else is treated as 1, upright. */
        private fun rotate(image: BufferedImage, orientation: Int): BufferedImage {
            if (orientation !in 2..8) return image
            val w = image.width.toDouble()
            val h = image.height.toDouble()
            val swap = orientation >= 5
            val t = AffineTransform()
            when (orientation) {
                2 -> { t.translate(w, 0.0); t.scale(-1.0, 1.0) }
                3 -> { t.translate(w, h); t.rotate(Math.PI) }
                4 -> { t.translate(0.0, h); t.scale(1.0, -1.0) }
                5 -> { t.rotate(Math.PI / 2); t.scale(1.0, -1.0) }
                6 -> { t.translate(h, 0.0); t.rotate(Math.PI / 2) }
                7 -> { t.scale(-1.0, 1.0); t.translate(-h, 0.0); t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
                8 -> { t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
            }
            val out = BufferedImage(if (swap) image.height else image.width, if (swap) image.width else image.height, BufferedImage.TYPE_INT_RGB)
            out.createGraphics().run { drawImage(image, t, null); dispose() }
            return out
        }

        /**
         * The EXIF orientation tag (0x0112) of a JPEG, or 1. Reads only the APP1 segment's
         * first directory, which is where cameras put it.
         */
        internal fun exifOrientation(b: ByteArray): Int {
            if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return 1
            var i = 2
            while (i + 4 <= b.size && b[i] == 0xFF.toByte()) {
                val marker = b[i + 1].toInt() and 0xFF
                val length = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                if (marker == 0xDA) return 1 // image data starts; no EXIF before it
                if (marker == 0xE1 && i + 10 <= b.size && String(b, i + 4, 4, Charsets.ISO_8859_1) == "Exif") {
                    return runCatching { tiffOrientation(b, i + 10, minOf(b.size, i + 2 + length)) }.getOrDefault(1)
                }
                i += 2 + length
            }
            return 1
        }

        private fun tiffOrientation(b: ByteArray, start: Int, end: Int): Int {
            val little = b[start] == 'I'.code.toByte()
            fun u16(at: Int): Int {
                val x = b[at].toInt() and 0xFF; val y = b[at + 1].toInt() and 0xFF
                return if (little) x or (y shl 8) else (x shl 8) or y
            }
            fun u32(at: Int): Int = if (little) u16(at) or (u16(at + 2) shl 16) else (u16(at) shl 16) or u16(at + 2)
            val ifd = start + u32(start + 4)
            val entries = u16(ifd)
            for (n in 0 until entries) {
                val entry = ifd + 2 + n * 12
                if (entry + 12 > end) break
                if (u16(entry) == 0x0112) return u16(entry + 8)
            }
            return 1
        }
    }
}
