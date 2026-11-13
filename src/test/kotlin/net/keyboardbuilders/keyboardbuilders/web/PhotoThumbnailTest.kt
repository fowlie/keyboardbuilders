package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The card-sized copy of a build photo. Photos are stored at up to 1600px and a card
 * shows them at ~320px, so without this a page of fifty cards is megabytes of pixels
 * nobody sees -- measured at ~1.7 MB for just eight, 2026-09-25.
 */
class PhotoThumbnailTest {

    @TempDir
    lateinit var dir: Path

    private val digest = "0123456789abcdef0123456789abcdef"

    private fun store() = PhotoStore(dir = dir.toString(), baseUrl = "https://example.invalid")

    private fun writePhoto(width: Int, height: Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, (x * 255 / width) shl 16 or (y * 255 / height))
        val bytes = ByteArrayOutputStream().use { out -> ImageIO.write(image, "jpg", out); out.toByteArray() }
        Files.write(dir.resolve("$digest.jpg"), bytes)
    }

    private fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

    @Test
    fun `the card copy is card-wide and keeps the photo's proportions`() {
        writePhoto(1600, 900)

        val card = decode(assertNotNull(store().cardBytes(digest)))

        assertEquals(PhotoStore.CARD_WIDTH, card.width)
        assertEquals(PhotoStore.CARD_WIDTH * 900 / 1600, card.height)
    }

    /** Enlarging a small photo adds bytes and no detail. */
    @Test
    fun `a photo already narrower than a card is not enlarged`() {
        writePhoto(400, 300)

        val card = decode(assertNotNull(store().cardBytes(digest)))

        assertEquals(400, card.width)
        assertEquals(300, card.height)
    }

    @Test
    fun `the card copy is cached beside the photo, so it is resized once`() {
        writePhoto(1600, 900)

        store().cardBytes(digest)

        assertTrue(Files.isRegularFile(dir.resolve("$digest-card.jpg")), "no cached card copy on disk")
    }

    @Test
    fun `a missing photo has no card copy, rather than an error`() {
        assertEquals(null, store().cardBytes(digest))
    }

    /**
     * The URL [Photos.cardUrl] writes into pages must be one the controller answers --
     * checked end to end, because the two are spelled in different files.
     */
    @Test
    fun `the card url is served, as a jpeg that never changes`() {
        writePhoto(1600, 900)
        val url = Photos.cardUrl(Photos.Photo(jpg = digest, width = 1600, height = 900, sourceUrl = "x"))
        val mvc = MockMvcBuilders.standaloneSetup(PhotoAssetController(store())).build()

        val response = mvc.get(url).andReturn().response

        assertEquals(200, response.status, "$url is not served")
        assertEquals("image/jpeg", response.contentType)
        assertTrue("immutable" in response.getHeader("Cache-Control")!!, response.getHeader("Cache-Control"))
        assertEquals(PhotoStore.CARD_WIDTH, decode(response.contentAsByteArray).width)
    }
}
