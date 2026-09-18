package com.localllm.android.engine

import com.localllm.android.model.LlmModel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
                    .body(okhttp3.ResponseBody.create(null, ByteArray(1)))
                    .build()
            }
            val dash = range.removePrefix("bytes=").split("-")
            val start = dash[0].toLong()
            val end = dash[1].toLong()
            val slice = payload.copyOfRange(start.toInt(), (end + 1).toInt())
            responseBuilder.code(206)
                .message("Partial Content")
                .header("Content-Range", "bytes $start-$end/${payload.size}")
                .body(okhttp3.ResponseBody.create(null, slice))
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
                .body(okhttp3.ResponseBody.create(null, payload))
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
