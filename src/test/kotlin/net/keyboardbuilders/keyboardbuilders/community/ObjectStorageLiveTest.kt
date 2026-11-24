package net.keyboardbuilders.keyboardbuilders.community

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

/**
 * ONE REAL ROUND TRIP to the live bucket: put a small file, read it back, delete it, see
 * it gone. Off unless KB_S3_LIVE=1 and both keys are in the environment -- the Kotlin twin
 * of `objectstorage.py --selftest`. Run it after touching ObjectStorage.
 */
@EnabledIfEnvironmentVariable(named = "KB_S3_LIVE", matches = "1")
class ObjectStorageLiveTest {

    @Test
    fun `put, read back, delete`() {
        val store = ObjectStorage(System.getenv("KB_S3_ACCESS_KEY"), System.getenv("KB_S3_SECRET_KEY"), "keyboardbuilders-renders", "hel1")
        val key = "selftest/kotlin-signer.txt"
        val url = URI.create("https://keyboardbuilders-renders.hel1.your-objectstorage.com/$key")
        val http = HttpClient.newHttpClient()
        fun status() = http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString())

        store.put(key, "hello".toByteArray(), "text/plain")
        status().let { assertEquals(200, it.statusCode()); assertEquals("hello", it.body()) }

        store.delete(key)
        // 403, not 404: this bucket answers 403 for any key it doesn't have (checked
        // 2026-11-24 against a key that never existed), since listing it isn't public.
        assertEquals(403, status().statusCode())
    }
}
