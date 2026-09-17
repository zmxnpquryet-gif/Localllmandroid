package com.localllm.android

import com.localllm.android.server.OllamaApiServer
import java.io.ByteArrayInputStream
import java.io.EOFException
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpRequestBodyTest {
    @Test
    fun readsUtf8BodyAfterHeadersWithoutConsumingFollowingBytes() {
        val body = "{\"prompt\":\"안녕하세요 café\"}"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "POST /api/generate HTTP/1.1\r\nContent-Length: ${bytes.size}\r\n\r\n"
        val input = ByteArrayInputStream(headers.toByteArray(Charsets.US_ASCII) + bytes + byteArrayOf(42)).buffered()

        assertEquals("POST /api/generate HTTP/1.1", OllamaApiServer.readHttpLine(input))
        assertEquals("Content-Length: ${bytes.size}", OllamaApiServer.readHttpLine(input))
        assertEquals("", OllamaApiServer.readHttpLine(input))
        assertEquals(body, OllamaApiServer.readHttpBody(input, bytes.size))
        assertEquals(42, input.read())
    }

    @Test
    fun readsEmptyBody() {
        assertEquals("", OllamaApiServer.readHttpBody(ByteArrayInputStream(byteArrayOf()), 0))
    }

    @Test(expected = EOFException::class)
    fun rejectsTruncatedBody() {
        OllamaApiServer.readHttpBody(ByteArrayInputStream(byteArrayOf(65)), 2)
    }
}
