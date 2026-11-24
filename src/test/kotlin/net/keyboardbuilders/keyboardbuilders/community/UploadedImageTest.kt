package net.keyboardbuilders.keyboardbuilders.community

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UploadedImageTest {

    /** 40x30, black, with a red 8x8 block in the top-left corner. */
    private fun marked(): BufferedImage = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().run { color = Color.RED; fillRect(0, 0, 8, 8); dispose() }
    }

    private fun jpeg(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()

    /** A JPEG with an EXIF block saying how to turn it, as a phone writes one. */
    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, // big-endian, first IFD at 8
            0, 1,                                                    // one entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, // Orientation, SHORT, 1
            0, 0, 0, 0,                                              // no next IFD
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun isRed(image: BufferedImage, x: Int, y: Int): Boolean {
        val c = Color(image.getRGB(x, y))
        return c.red > 180 && c.green < 80 && c.blue < 80
    }

    private fun read(u: UploadedImage) = ImageIO.read(ByteArrayInputStream(u.jpeg))

    @Test
    fun `reads the orientation a phone writes`() {
        assertEquals(6, UploadedImage.exifOrientation(withOrientation(jpeg(marked()), 6)))
        assertEquals(1, UploadedImage.exifOrientation(jpeg(marked())))
    }

    @Test
    fun `an upright photo stays as it is`() {
        val out = UploadedImage.from(jpeg(marked()))
        assertEquals(40 to 30, out.width to out.height)
        assertTrue(isRed(read(out), 2, 2))
    }

    /** 6 means "turn 90 degrees clockwise to view": the top-left block ends up top-right. */
    @Test
    fun `a photo taken upright on a phone is turned upright`() {
        val out = UploadedImage.from(withOrientation(jpeg(marked()), 6))
        assertEquals(30 to 40, out.width to out.height)
        assertTrue(isRed(read(out), 27, 2), "the corner block should be top-right")
    }

    /** 8 means "turn 90 degrees anticlockwise": the top-left block ends up bottom-left. */
    @Test
    fun `orientation 8 turns the other way`() {
        val out = UploadedImage.from(withOrientation(jpeg(marked()), 8))
        assertEquals(30 to 40, out.width to out.height)
        assertTrue(isRed(read(out), 2, 37), "the corner block should be bottom-left")
    }

    @Test
    fun `upside down is turned over`() {
        val out = UploadedImage.from(withOrientation(jpeg(marked()), 3))
        assertTrue(isRed(read(out), 37, 27), "the corner block should be bottom-right")
    }

    @Test
    fun `a wide photo is shrunk to 1600 pixels wide`() {
        val out = UploadedImage.from(jpeg(BufferedImage(3200, 2400, BufferedImage.TYPE_INT_RGB)))
        assertEquals(1600 to 1200, out.width to out.height)
    }

    @Test
    fun `the digest is a 32-character hex key, the shape photo URLs take`() {
        assertTrue(Regex("[0-9a-f]{32}").matches(UploadedImage.from(jpeg(marked())).digest))
    }

    @Test
    fun `something that is not an image is refused`() {
        assertThrows<NotAnImage> { UploadedImage.from("not a photo".toByteArray()) }
    }
}
