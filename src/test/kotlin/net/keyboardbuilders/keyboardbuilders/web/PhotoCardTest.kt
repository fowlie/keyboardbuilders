package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.ComponentKind
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.DesignCore
import net.keyboardbuilders.keyboardbuilders.domain.DesignName
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.HardestComponent
import net.keyboardbuilders.keyboardbuilders.domain.KeyCount
import net.keyboardbuilders.keyboardbuilders.domain.Layout
import net.keyboardbuilders.keyboardbuilders.domain.License
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Licensing
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.SolderingDifficulty
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.ByteArrayResource
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * Backlog #101's follow-on: when a design has an approved photo, the share card and the
 * search thumbnail are cropped from it rather than drawn -- so this asserts on pixels
 * sampled from the two halves of a two-colour fixture photo, the only way to tell "the
 * real photo, cropped" from "the drawn silhouette, unaffected" from outside the code.
 *
 * The fixture photo is 40x20 -- red on its left half, blue on its right -- landing on
 * disk under a digest [PhotoStore] reads directly, exactly as `scripts/add_photo.py`
 * would have left it. No network: [PhotoStore]'s bucket fallback is never reached
 * because the disk read always succeeds first.
 */
class PhotoCardTest {

    @TempDir
    lateinit var photosDir: Path

    private val silhouettes = Silhouettes(
        jacksonObjectMapper(),
        ByteArrayResource("""{"iso-board":{"w":5.0,"h":2.0,"keys":[[0.0,0.0,1.0,1.0]]}}""".toByteArray()),
        ByteArrayResource("{}".toByteArray()),
    )

    private val boards = listOf(photographed, undrawn)

    private fun controller(digest: String) = OgImageController(
        catalog = object : DesignCatalog {
            override fun all() = boards
            override fun bySlug(slug: String) = boards.find { it.core.slug.value == slug }
        },
        silhouettes = silhouettes,
        renames = SlugRenames(jacksonObjectMapper(), ByteArrayResource("{}".toByteArray())),
        photos = Photos(
            jacksonObjectMapper(),
            ByteArrayResource(
                """{"photographed":{"jpg":"$digest","width":$FW,"height":$FH,"sourceUrl":"https://example.invalid/repo"}}"""
                    .toByteArray()
            ),
        ),
        photoStore = PhotoStore(dir = photosDir.toString(), baseUrl = "https://example.invalid"),
    )

    private fun writeFixturePhoto(digest: String) {
        val image = BufferedImage(FW, FH, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until FH) for (x in 0 until FW) image.setRGB(x, y, if (x < FW / 2) RED else BLUE)
        val bytes = ByteArrayOutputStream().use { out -> ImageIO.write(image, "jpg", out); out.toByteArray() }
        Files.write(photosDir.resolve("$digest.jpg"), bytes)
    }

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))

    /** A generous distance from pure red/blue: JPEG and the bilinear resample both drift the value a little. */
    private fun isRoughly(rgb: Int, r: Int, g: Int, b: Int) =
        Math.abs((rgb shr 16 and 0xff) - r) < 40 && Math.abs((rgb shr 8 and 0xff) - g) < 40 && Math.abs((rgb and 0xff) - b) < 40

    @Test
    fun `the card is cropped from the photo, not drawn`() {
        val digest = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        writeFixturePhoto(digest)
        val png = decode(controller(digest).card("photographed").body!!)

        assertTrue(png.width == 1200 && png.height == 630, "card is not 1200x630: ${png.width}x${png.height}")
        assertTrue(isRoughly(png.getRGB(100, 300), 255, 0, 0), "left of the card is not the photo's red half")
        assertTrue(isRoughly(png.getRGB(1100, 300), 0, 0, 255), "right of the card is not the photo's blue half")
    }

    @Test
    fun `the thumbnail is cropped from the photo, not drawn`() {
        val digest = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        writeFixturePhoto(digest)
        val png = decode(controller(digest).thumbnail("photographed").body!!)

        assertTrue(png.width == 630 && png.height == 630, "thumbnail is not square 630: ${png.width}x${png.height}")
        assertTrue(isRoughly(png.getRGB(100, 300), 255, 0, 0), "left of the thumbnail is not the photo's red half")
        assertTrue(isRoughly(png.getRGB(500, 300), 0, 0, 255), "right of the thumbnail is not the photo's blue half")
    }

    /**
     * The photo entry exists in `photos.json`, but the bytes are not on disk and the
     * bucket is unreachable -- `PhotoStore.bytes` returns null. That must read as "draw
     * the silhouette instead", never as a 500: `undrawn` has no silhouette either, so a
     * failed fetch falling through anywhere but the drawn path would 404 instead of
     * degrading.
     */
    @Test
    fun `a fetch failure falls back to the drawn card rather than erroring`() {
        val response = controller("cccccccccccccccccccccccccccccccc").card("photographed")
        val png = decode(response.body!!)
        assertTrue(png.width == 1200 && png.height == 630)
        // The drawn card's background, `PAPER`, wherever the photo would have been.
        assertTrue((png.getRGB(100, 300) and 0xffffff) == 0xfdfdfc, "did not fall back to the drawn card")
    }

    private companion object {
        const val FW = 40
        const val FH = 20
        const val RED = 0xff0000
        const val BLUE = 0x0000ff

        private val photographed = board("photographed")
        private val undrawn = board("undrawn")

        private fun board(slug: String) = Design(
            core = DesignCore(
                name = DesignName(slug),
                slug = Slug(slug),
                sources = setOf(SourceRef("https://example.invalid/$slug")),
                firmware = Recorded.Known(setOf(FirmwareRef.Qmk(slug))),
                layouts = setOf(Layout(KeyCount(3))),
                switches = setOf(SwitchFootprint.Mx),
                buildMethod = BuildMethod.Pcb(
                    net.keyboardbuilders.keyboardbuilders.domain.Recorded.Known(
                        HardestComponent(SolderingDifficulty.ThroughHole, setOf(ComponentKind.Diode))
                    )
                ),
                controller = Controller.Socket(ControllerFootprint.ProMicro),
                body = Body.Unibody,
                licensing = Licensing(
                    hardware = LicenseState.Stated(setOf(License.Mit)),
                    firmware = LicenseState.Stated(setOf(License.Gpl2Only)),
                    documentation = LicenseState.Unstated,
                ),
            ),
        )
    }
}
