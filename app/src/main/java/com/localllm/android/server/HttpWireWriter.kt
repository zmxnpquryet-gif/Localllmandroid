package com.localllm.android.server

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Low-level HTTP/1.1 response, chunked transfer, and loopback-validated CORS header writer.
 */
internal object HttpWireWriter {

    fun validatedCorsOrigin(origin: String?): String? {
        if (origin.isNullOrBlank()) return null
        return if (OllamaApiServer.isAllowedCorsOrigin(origin)) origin else null
    }

    fun sendCorsPreflight(output: OutputStream, origin: String?) {
        val allowedOrigin = validatedCorsOrigin(origin)
        val header = buildString {
            append("HTTP/1.1 204 No Content\r\n")
            appendCorsHeaders(allowedOrigin)
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    fun sendStreamHeaders(output: OutputStream, contentType: String, origin: String?) {
        val allowedOrigin = validatedCorsOrigin(origin)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Connection: close\r\n")
            appendCorsHeaders(allowedOrigin)
            append("\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    fun sendChunk(output: OutputStream, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty()) return
        output.write(Integer.toHexString(bytes.size).toByteArray(StandardCharsets.US_ASCII))
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    fun endChunk(output: OutputStream) {
        output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        content: String,
        origin: String?
    ) {
        val contentBytes = content.toByteArray(StandardCharsets.UTF_8)
        val allowedOrigin = validatedCorsOrigin(origin)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${contentBytes.size}\r\n")
            append("Connection: close\r\n")
            appendCorsHeaders(allowedOrigin)
            append("\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        if (contentBytes.isNotEmpty()) {
            output.write(contentBytes)
        }
        output.flush()
    }

    fun isoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun StringBuilder.appendCorsHeaders(allowedOrigin: String?) {
        if (allowedOrigin != null) {
            append("Access-Control-Allow-Origin: $allowedOrigin\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization, X-API-Key\r\n")
        }
    }
}
