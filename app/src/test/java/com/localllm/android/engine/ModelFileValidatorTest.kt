package com.localllm.android.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ModelFileValidatorTest {

    @Test
    fun `validates gguf magic bytes and rejects html error pages`() {
        val dir = Files.createTempDirectory("validator_test").toFile()
        try {
            val validGguf = dir.resolve("valid.gguf").apply {
                writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 3, 0, 0, 0))
            }
            val htmlError = dir.resolve("login.gguf").apply {
                writeText("<!DOCTYPE html><html><body>401 Unauthorized</body></html>")
            }
            assertTrue(ModelFileValidator.isGgufFile(validGguf))
            assertFalse(ModelFileValidator.isGgufFile(htmlError))

            val validLitert = dir.resolve("bundle.litertlm").apply {
                writeBytes(ByteArray(1024 * 1024 + 64) { 0x5A })
            }
            val tinyLitert = dir.resolve("tiny.litertlm").apply {
                writeBytes(ByteArray(512) { 0x5A })
            }
            assertTrue(ModelFileValidator.isLiteRtModelFile(validLitert))
            assertFalse(ModelFileValidator.isLiteRtModelFile(tinyLitert))
        } finally {
            dir.deleteRecursively()
        }
    }
}
