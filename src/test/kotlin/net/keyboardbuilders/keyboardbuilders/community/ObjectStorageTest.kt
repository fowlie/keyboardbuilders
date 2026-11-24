package net.keyboardbuilders.keyboardbuilders.community

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The expected values were produced by `scripts/objectstorage.py`'s `authorization()`,
 * whose signer is checked against curl's (`objectstorage.py --selftest`), on 2026-11-24.
 */
class ObjectStorageTest {

    private val host = "keyboardbuilders-renders.hel1.your-objectstorage.com"
    private fun sign(method: String, sha: String) = ObjectStorage.authorization(
        "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "hel1", host,
        "photos/abc.jpg", "image/jpeg", "20261124T120000Z", sha, method,
    )

    @Test
    fun `a PUT is signed exactly as the Python script signs it`() {
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261124/hel1/s3/aws4_request, " +
                "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, " +
                "Signature=be39801e5dcbdcd94d67eb5cc6a529ab8d55879e964302a4b16813c8cc51e476",
            sign("PUT", "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"),
        )
    }

    @Test
    fun `a DELETE is signed exactly as the Python script signs it`() {
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261124/hel1/s3/aws4_request, " +
                "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, " +
                "Signature=667258f6e05e1bbdfc116d9f06fbce7c78e186b01adbec93d638ce9cf4a75e96",
            sign("DELETE", ObjectStorage.sha256Hex(ByteArray(0))),
        )
    }

    @Test
    fun `without keys the store is off`() {
        assertEquals(false, ObjectStorage("", "", "b", "hel1").enabled)
    }
}
