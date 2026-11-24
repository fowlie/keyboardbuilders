package net.keyboardbuilders.keyboardbuilders.community

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The Hetzner bucket the seed's photos and renders already live in, written by the app for
 * the first time (2026-11-24): an approved build photo is copied there so it outlives the
 * server, and a removed one is deleted.
 *
 * THE SAME SIGNER AS `scripts/objectstorage.py`, which is checked against curl's own.
 * `ObjectStorageTest` pins this one to signatures that script produced, so the two cannot
 * drift apart. Hand-rolled for the script's reason: the AWS SDK is a large dependency for
 * two HTTP verbs.
 *
 * OFF WITHOUT KEYS, and only application-prod.properties reads them from the environment:
 * a dev machine or a test run must never write to the live bucket, even in a shell that
 * happens to export them. There an approved photo just stays on disk.
 */
/** Where approved build photos are kept off the server. [ObjectStorage] is the real one. */
interface Bucket {
    fun put(key: String, bytes: ByteArray, contentType: String)
    fun delete(key: String)
}

@Component
class ObjectStorage(
    @Value("\${keyboardbuilders.s3.access-key:}") private val access: String,
    @Value("\${keyboardbuilders.s3.secret-key:}") private val secret: String,
    @Value("\${keyboardbuilders.s3.bucket:keyboardbuilders-renders}") private val bucket: String,
    @Value("\${keyboardbuilders.s3.region:hel1}") private val region: String,
) : Bucket {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    val enabled: Boolean get() = access.isNotBlank() && secret.isNotBlank()

    private val host get() = "$bucket.$region.your-objectstorage.com"

    /** Throws if the store refuses: the caller must not record an approval the store lost. */
    override fun put(key: String, bytes: ByteArray, contentType: String) {
        if (!enabled) { log.warn("no bucket keys; {} stays on local disk only", key); return }
        send("PUT", key, bytes, contentType)
    }

    override fun delete(key: String) {
        if (!enabled) return
        send("DELETE", key, ByteArray(0), "image/jpeg")
    }

    private fun send(method: String, key: String, body: ByteArray, contentType: String) {
        val amzdate = ZonedDateTime.now(ZoneOffset.UTC).format(AMZDATE)
        val sha = sha256Hex(body)
        val request = HttpRequest.newBuilder(URI.create("https://$host/${key.trimStart('/')}"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", contentType)
            .header("x-amz-content-sha256", sha)
            .header("x-amz-date", amzdate)
            .header("Authorization", authorization(access, secret, region, host, key, contentType, amzdate, sha, method))
            .apply { if (method == "PUT") header("Cache-Control", "public, max-age=31536000, immutable") }
            .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("bucket $method $key: HTTP ${response.statusCode()} ${response.body().take(300)}")
        }
    }

    companion object {
        private val AMZDATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private const val SIGNED_HEADERS = "content-type;host;x-amz-content-sha256;x-amz-date"

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun hmac(key: ByteArray, msg: String): ByteArray =
            Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(msg.toByteArray()) }

        /** objectstorage.py's `authorization()`, line for line. */
        fun authorization(
            access: String, secret: String, region: String, host: String, key: String,
            contentType: String, amzdate: String, payloadSha: String, method: String,
        ): String {
            val canonical = listOf(
                method, "/" + key.trimStart('/'), "",
                "content-type:$contentType", "host:$host",
                "x-amz-content-sha256:$payloadSha", "x-amz-date:$amzdate", "",
                SIGNED_HEADERS, payloadSha,
            ).joinToString("\n")
            val datestamp = amzdate.take(8)
            val scope = "$datestamp/$region/s3/aws4_request"
            val toSign = listOf("AWS4-HMAC-SHA256", amzdate, scope, sha256Hex(canonical.toByteArray())).joinToString("\n")
            var k = hmac("AWS4$secret".toByteArray(), datestamp)
            for (part in listOf(region, "s3", "aws4_request")) k = hmac(k, part)
            val signature = hmac(k, toSign).joinToString("") { "%02x".format(it) }
            return "AWS4-HMAC-SHA256 Credential=$access/$scope, SignedHeaders=$SIGNED_HEADERS, Signature=$signature"
        }
    }
}
