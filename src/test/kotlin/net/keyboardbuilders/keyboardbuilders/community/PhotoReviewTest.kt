package net.keyboardbuilders.keyboardbuilders.community

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A bucket that remembers what it was given, and can be told to refuse. */
class FakeBucket : Bucket {
    val keys = mutableSetOf<String>()
    var refuse = false
    override fun put(key: String, bytes: ByteArray, contentType: String) {
        if (refuse) error("bucket said 503")
        keys += key
    }
    override fun delete(key: String) { keys -= key }
}

/**
 * Where a photo's bytes are at each step (owner, 2026-11-24): disk while waiting, the
 * bucket after a yes, nowhere after a no, and gone from both when removed.
 */
@SpringBootTest
@Import(PhotoReviewTest.Config::class)
class PhotoReviewTest {

    @TestConfiguration
    class Config { @Bean @Primary fun fakeBucket() = FakeBucket() }

    @Autowired lateinit var review: PhotoReview
    @Autowired lateinit var uploads: PhotoUploads
    @Autowired lateinit var bucket: FakeBucket
    @Autowired lateinit var store: net.keyboardbuilders.keyboardbuilders.web.PhotoStore
    @Autowired lateinit var db: JdbcClient
    @Value("\${keyboardbuilders.photos.dir:build/photos}") lateinit var dir: String

    @BeforeEach @AfterEach fun clear() {
        db.sql("DELETE FROM photo_upload").update()
        uploads.approve(-1, "nobody")
        bucket.keys.clear(); bucket.refuse = false
    }

    /** A waiting photo, saved to disk the way an upload saves it. */
    private fun waiting(): Upload {
        val image = UploadedImage.from(ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "jpg", it)
        }.toByteArray())
        store.save(image.digest, image.jpeg)
        return uploads.get(uploads.submit("abacus", "alice", image.digest, image.width, image.height))!!
    }

    private fun onDisk(u: Upload) = Files.exists(Path.of(dir, "${u.jpg}.jpg"))

    @Test
    fun `a waiting photo is on disk and not in the bucket`() {
        val u = waiting()
        assertTrue(onDisk(u))
        assertTrue(bucket.keys.isEmpty())
    }

    @Test
    fun `a yes copies it to the bucket`() {
        val u = waiting()
        assertTrue(review.approve(u.id, "fowlie"))
        assertEquals(setOf("photos/${u.jpg}.jpg"), bucket.keys)
    }

    @Test
    fun `if the bucket refuses, the photo stays waiting`() {
        val u = waiting()
        bucket.refuse = true
        assertThrows<IllegalStateException> { review.approve(u.id, "fowlie") }
        assertEquals(Upload.Status.WAITING, uploads.get(u.id)!!.status)
    }

    @Test
    fun `a no deletes it from disk and never touches the bucket`() {
        val u = waiting()
        review.reject(u.id, "fowlie", "a render")
        assertFalse(onDisk(u))
        assertTrue(bucket.keys.isEmpty())
    }

    @Test
    fun `removing an approved photo deletes it from the bucket and the disk`() {
        val u = waiting()
        review.approve(u.id, "fowlie")
        review.remove(u.id)
        assertTrue(bucket.keys.isEmpty())
        assertFalse(onDisk(u))
    }
}
