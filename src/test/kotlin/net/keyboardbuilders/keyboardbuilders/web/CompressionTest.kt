package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compression is the servlet container's, not the application's, so this is the one
 * test in the suite that needs a real port. `MockMvc` never runs Tomcat's filter and
 * would pass against a completely uncompressed server.
 *
 * `java.net.http.HttpClient` is deliberate too: it neither sends `Accept-Encoding` on
 * its own nor decompresses a response, so the header and the byte count on the wire
 * are both observable. `RestTemplate` and `TestRestTemplate` hide the first and undo
 * the second, which would make every assertion here vacuous.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompressionTest {

    @LocalServerPort
    private var port: Int = 0

    @Value("\${server.compression.min-response-size}")
    private var floor: Int = 0

    private val client: HttpClient = HttpClient.newHttpClient()

    private fun get(path: String, acceptGzip: Boolean): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        if (acceptGzip) request.header("Accept-Encoding", "gzip")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun HttpResponse<ByteArray>.encoding() =
        headers().firstValue("Content-Encoding").orElse(null)

    /**
     * The measured page is 85% inline SVG and the markup is the same handful of
     * attributes with different numbers, which is the shape gzip is best at. 19x on
     * this page is a measurement, not a rule of thumb.
     */
    @Test
    fun `the designs index is gzipped when the client asks for it`() {
        val response = get("/designs", acceptGzip = true)

        assertEquals(200, response.statusCode())
        assertEquals("gzip", response.encoding())

        val html = GZIPInputStream(response.body().inputStream()).readBytes().decodeToString()
        assertTrue(html.contains("<html"), "the compressed body must decompress to the page")
        assertTrue(
            response.body().size < 40 * 1024,
            "expected under 40 KB on the wire, got ${response.body().size} bytes",
        )
    }

    /**
     * The no-JS path, `curl`, and anything else that does not negotiate must be
     * unaffected. A server that only serves compressed bytes is broken for them.
     */
    @Test
    fun `a client that does not negotiate still gets valid uncompressed html`() {
        val response = get("/designs", acceptGzip = false)

        assertEquals(200, response.statusCode())
        assertNull(response.encoding(), "nothing asked for gzip, so nothing may be encoded")
        assertTrue(response.body().decodeToString().contains("<html"))
    }

    /**
     * `ux.md` §2.7 settles on htmx swapping fragments, and gzipping a small fragment
     * costs CPU to save nothing, which is what `min-response-size=1024` is for.
     *
     * THE MARGIN WAS SPENT, AND THIS TEST CHANGED SHAPE RATHER THAN BEING TRIMMED TO FIT.
     * The home page used to be the below-floor case: 591 bytes when this was written, ~990
     * once the SEO head landed, and 1702 once the header grew a logo and three icon links.
     * Nothing the site serves is under 1024 any more.
     *
     * The previous version asserted `/` was below the floor, and the honest options when it
     * went red were to find another below-floor response or to cut prose until the number
     * came back. There is no other below-floor response, and cutting page content to satisfy
     * a test is the tail wagging the dog -- so this now records the truth: the floor is
     * configured, and it is currently INERT because nothing falls under it.
     *
     * IT BECOMES LOAD-BEARING AGAIN WITH FORGEJO #8. That is the htmx work, and an htmx
     * fragment is a few hundred bytes -- exactly the case the floor exists for. Whoever
     * lands it should expect this test to start meaning something again, and should assert
     * the fragment goes over the wire uncompressed.
     */
    @Test
    fun `the floor is configured, and nothing the site serves currently falls under it`() {
        assertEquals(1024, floor, "the floor moved; this test's whole premise is that value")

        // THE FLOOR IS A RULE ABOUT THE UNCOMPRESSED SIZE, so it has to be measured on an
        // uncompressed response. Asking with gzip and measuring `body().size` reads the
        // bytes AFTER compression -- the home page is 1702 bytes and gzips to well under
        // 1024, so that reading makes an above-floor page look below-floor and this test
        // fail against a correctly configured server.
        val paths = listOf("/", "/designs/3w6")
        val raw = paths.map { get(it, acceptGzip = false) }
        raw.forEach { assertEquals(200, it.statusCode()) }

        // Not a tautology: it is a tripwire. The day a response drops back under the floor
        // -- an htmx fragment, an error page, a stub -- this fails and says so, and the
        // assertion above it becomes testable behaviour rather than configuration.
        val bytes = raw.map { it.body().size }
        assertTrue(
            bytes.all { it >= floor },
            "a response is below the floor again ($bytes) -- assert it is served uncompressed",
        )
        paths.forEach {
            assertEquals("gzip", get(it, acceptGzip = true).encoding(), "above the floor, gzip applies")
        }
    }
}
