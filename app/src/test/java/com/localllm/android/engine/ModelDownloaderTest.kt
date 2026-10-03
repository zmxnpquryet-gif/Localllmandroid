package com.localllm.android.engine

import com.localllm.android.model.LlmModel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDownloaderTest {

    private class FakeInterceptor(
        private val handler: (Request) -> Response
    ) : Interceptor {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests.add(request)
            return handler(request)
        }
    }

    @Before
    fun setUp() {
        System.setProperty("robolectric.logging", "stdout")
    }

    private fun tempDir(): File = Files.createTempDirectory("fdmtest").toFile()

    private fun model(url: String) = LlmModel(
        id = "test-model",
        name = "Test Model",
        repoId = "",
        fileName = "model.gguf",
        sizeBytes = 6 * 1024 * 1024L,
        mainModelUrl = url
    )

    @Test
    fun `segmented download verifies content-range and merges parts exactly`() = runBlocking {
        val payload = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
        payload[0] = 'G'.code.toByte()
        payload[1] = 'G'.code.toByte()
        payload[2] = 'U'.code.toByte()
        payload[3] = 'F'.code.toByte()
        val interceptor = FakeInterceptor({ request ->
            val range = request.header("Range")
            val responseBuilder = Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
            if (range == null) {
                if (request.method == "HEAD") {
                    return@FakeInterceptor responseBuilder.code(200)
                        .message("OK")
                        .header("Content-Length", payload.size.toString())
                        .header("Accept-Ranges", "bytes")
                        .build()
                }
                return@FakeInterceptor responseBuilder.code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes 0-0/${payload.size}")
                    .body(ByteArray(1).toResponseBody())
                    .build()
            }
            val dash = range.removePrefix("bytes=").split("-")
            val start = dash[0].toLong()
            val end = dash[1].toLong()
            val slice = payload.copyOfRange(start.toInt(), (end + 1).toInt())
            responseBuilder.code(206)
                .message("Partial Content")
                .header("Content-Range", "bytes $start-$end/${payload.size}")
                .body(slice.toResponseBody())
            responseBuilder.build()
        })
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val downloader = ModelDownloader(client)
        val dir = tempDir()
        val statuses = downloader.downloadUnifiedBundle(model("http://fake.invalid/x"), dir).toList()
        val target = File(dir, "model.gguf")
        assertEquals(payload.size.toLong(), target.length())
        assertArrayEquals(payload, target.readBytes())
        assertTrue(statuses.last().isCompleted)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `hf token is attached only to exact huggingface hosts over https`() {
        fun authFor(url: String): String? =
            ModelDownloader.withHfAuth(Request.Builder().url("https://huggingface.co/x"), url, "hf_secret")
                .build().header("Authorization")

        // Legitimate API hosts receive the token.
        assertEquals("Bearer hf_secret", authFor("https://huggingface.co/gemma/model/resolve/main/model.gguf"))
        assertEquals("Bearer hf_secret", authFor("https://cas-bridge.xethub.huggingface.co/xet-bridge/file"))
        assertEquals("Bearer hf_secret", authFor("https://huggingface.co:443/model.gguf"))

        // Attacker host embedding the brand string in path/query must NOT receive it.
        assertNull(authFor("https://evil.example/download?next=huggingface.co/model.gguf"))
        assertNull(authFor("https://evil.example/huggingface.co/model.gguf"))
        assertNull(authFor("https://huggingface.co.evil.example/model.gguf"))
        assertNull(authFor("https://not-huggingface.co/model.gguf"))
        assertNull(authFor("https://evil-huggingface.co.evil.example/x"))

        // Plain HTTP and CDN pre-signed targets must NOT receive it.
        assertNull(authFor("http://huggingface.co/model.gguf"))
        assertNull(authFor("https://cdn-lfs.huggingface.co/large-file"))
        assertNull(authFor("https://d1234.cloudfront.net/signed-file"))
        assertNull(authFor("https://bucket.s3.amazonaws.com/signed-file"))

        // Blank token / malformed URL never attach anything.
        assertNull(
            ModelDownloader.withHfAuth(Request.Builder().url("https://huggingface.co/x"), "https://huggingface.co/x", "  ")
                .build().header("Authorization")
        )
        assertFalse(ModelDownloader.isHuggingFaceApiHost("not a url"))
    }

    private fun ggufPayload(): ByteArray {
        val payload = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
        payload[0] = 'G'.code.toByte()
        payload[1] = 'G'.code.toByte()
        payload[2] = 'U'.code.toByte()
        payload[3] = 'F'.code.toByte()
        return payload
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Range-capable server that advertises [linkedEtag] as X-Linked-ETag (like HF LFS). */
    private fun lfsInterceptor(payload: ByteArray, linkedEtag: String?) = FakeInterceptor({ request ->
        val builder = Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
        if (linkedEtag != null) builder.header("X-Linked-ETag", "\"$linkedEtag\"")
        val range = request.header("Range")
        if (range == null) {
            return@FakeInterceptor builder.code(200).message("OK")
                .header("Content-Length", payload.size.toString())
                .header("Accept-Ranges", "bytes")
                .build()
        }
        val dash = range.removePrefix("bytes=").split("-")
        val start = dash[0].toLong()
        val end = dash[1].toLong()
        builder.code(206).message("Partial Content")
            .header("Content-Range", "bytes $start-$end/${payload.size}")
            .body(payload.copyOfRange(start.toInt(), (end + 1).toInt()).toResponseBody())
            .build()
    })

    @Test
    fun `download whose sha256 matches the published hash is marked verified`() = runBlocking {
        val payload = ggufPayload()
        val client = OkHttpClient.Builder().addInterceptor(lfsInterceptor(payload, sha256(payload))).build()
        val dir = tempDir()
        val statuses = ModelDownloader(client)
            .downloadUnifiedBundle(model("http://fake.invalid/x"), dir).toList()

        assertTrue(statuses.last().isCompleted)
        assertTrue(statuses.last().components.single().isSha256Verified)
        assertEquals(IntegrityNote.VERIFIED, statuses.last().integrityNote)
        assertArrayEquals(payload, File(dir, "model.gguf").readBytes())
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `download whose sha256 differs is rejected and deleted`() = runBlocking {
        val payload = ggufPayload()
        val wrong = sha256(payload.copyOf().also { it[100] = (it[100] + 1).toByte() })
        val client = OkHttpClient.Builder().addInterceptor(lfsInterceptor(payload, wrong)).build()
        val dir = tempDir()
        val statuses = ModelDownloader(client)
            .downloadUnifiedBundle(model("http://fake.invalid/x"), dir).toList()

        assertFalse(statuses.any { it.isCompleted })
        val error = statuses.last().errorMessage
        assertTrue("expected an integrity error, got $error", error?.contains("SHA-256") == true)
        assertFalse(File(dir, "model.gguf").exists())
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `download without a published hash completes but is not claimed verified`() = runBlocking {
        val payload = ggufPayload()
        val client = OkHttpClient.Builder().addInterceptor(lfsInterceptor(payload, null)).build()
        val dir = tempDir()
        val statuses = ModelDownloader(client)
            .downloadUnifiedBundle(model("http://fake.invalid/x"), dir).toList()

        assertTrue(statuses.last().isCompleted)
        assertFalse(statuses.last().components.single().isSha256Verified)
        assertEquals(IntegrityNote.NO_HASH_PUBLISHED, statuses.last().integrityNote)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `existing file with a matching published hash is accepted as verified`() = runBlocking {
        val payload = ggufPayload()
        val interceptor = lfsInterceptor(payload, sha256(payload))
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val dir = tempDir()
        File(dir, "model.gguf").writeBytes(payload)

        val statuses = ModelDownloader(client)
            .downloadUnifiedBundle(model("http://fake.invalid/x"), dir).toList()

        assertTrue(statuses.last().isCompleted)
        assertTrue(statuses.last().components.single().isSha256Verified)
        assertEquals(IntegrityNote.VERIFIED, statuses.last().integrityNote)
        // No segment traffic: only the lightweight probes ran.
        assertTrue(interceptor.requests.isNotEmpty())
        assertTrue(interceptor.requests.all { it.header("Range") in listOf(null, "bytes=0-0") })
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `existing file with a mismatching hash is re-downloaded, not trusted`() = runBlocking {
        val payload = ggufPayload()
        val stale = payload.copyOf().also {
            it[0] = 'G'.code.toByte()
            it[1] = 'G'.code.toByte()
            it[2] = 'U'.code.toByte()
            it[3] = 'F'.code.toByte()
            it[1000] = (it[1000] + 7).toByte()
        }
        val interceptor = lfsInterceptor(payload, sha256(payload))
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val dir = tempDir()
        File(dir, "model.gguf").writeBytes(stale)

        val statuses = ModelDownloader(client)
            .downloadUnifiedBundle(model("http://fake.invalid/x"), dir).toList()

        assertTrue(statuses.last().isCompleted)
        assertTrue(statuses.last().components.single().isSha256Verified)
        assertArrayEquals(payload, File(dir, "model.gguf").readBytes())
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `linked hash is read from the redirect chain, not just the final response`() {
        val hash = "a".repeat(64)
        fun req() = Request.Builder().url("https://huggingface.co/x").build()
        val prior = okhttp3.Response.Builder()
            .request(req())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(302)
            .message("Found")
            .header("X-Linked-ETag", "\"$hash\"")
            .build()
        val final = okhttp3.Response.Builder()
            .request(req())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .priorResponse(prior)
            .body(ByteArray(0).toResponseBody())
            .build()

        assertEquals(hash, ModelDownloader().linkedSha256(final))
        // No header anywhere: no hash.
        val bare = okhttp3.Response.Builder()
            .request(req())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ByteArray(0).toResponseBody())
            .build()
        assertNull(ModelDownloader().linkedSha256(bare))
    }

    @Test
    fun `linked etag parsing accepts only a 64 char sha256`() {
        val hex = "a".repeat(64)
        assertEquals(hex, ModelDownloader.parseLinkedSha256("\"$hex\""))
        assertEquals(hex, ModelDownloader.parseLinkedSha256("W/\"${hex.uppercase()}\""))
        assertNull(ModelDownloader.parseLinkedSha256("\"" + "b".repeat(40) + "\"")) // git SHA-1
        assertNull(ModelDownloader.parseLinkedSha256("not-a-hash"))
        assertNull(ModelDownloader.parseLinkedSha256(null))
    }

    @Test
    fun `segment ignores 200 response and does not corrupt parts`() = runBlocking {
        val payload = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
        payload[0] = 'G'.code.toByte()
        payload[1] = 'G'.code.toByte()
        payload[2] = 'U'.code.toByte()
        payload[3] = 'F'.code.toByte()
        val interceptor = FakeInterceptor({ request ->
            val responseBuilder = Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
            if (request.method == "HEAD") {
                return@FakeInterceptor responseBuilder.code(200)
                    .message("OK")
                    .header("Content-Length", payload.size.toString())
                    .header("Accept-Ranges", "bytes")
                    .build()
            }
            responseBuilder.code(200)
                .message("OK")
                .header("Content-Length", payload.size.toString())
                .body(payload.toResponseBody())
                .build()
        })
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val downloader = ModelDownloader(client)
        val dir = tempDir()
        val statuses = downloader.downloadUnifiedBundle(model("http://fake.invalid/x"), dir).toList()
        val target = File(dir, "model.gguf")
        assertTrue(target.isFile && target.length() == payload.size.toLong())
        assertTrue(statuses.last().isCompleted)
        assertArrayEquals(payload, target.readBytes())
        dir.deleteRecursively()
        Unit
    }
}
