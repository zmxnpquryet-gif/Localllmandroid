package com.localllm.android

import com.localllm.android.data.ModelStorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Locks in the path-traversal guarantee for user/URL-supplied model file names:
 * the result is always a single path segment inside the models directory.
 */
class ModelFileNameTest {

    private fun sanitize(raw: String, fallback: String = "model.gguf") =
        ModelStorageManager.sanitizeFileName(raw, fallback)

    @Test
    fun `strip parent-directory traversal on both separators`() {
        assertEquals("passwd", sanitize("../../etc/passwd"))
        assertEquals("evil.gguf", sanitize("..\\..\\windows\\system32\\evil.gguf"))
        assertEquals("model.gguf", sanitize("../../../model.gguf"))
    }

    @Test
    fun `absolute paths lose their directory part`() {
        assertEquals("model.gguf", sanitize("/absolute/path/model.gguf"))
        assertEquals("model.gguf", sanitize("C:\\models\\model.gguf"))
    }

    @Test
    fun `path metacharacters and control characters are neutralised`() {
        assertEquals("a_b_c_.gguf", sanitize("a:b*c?.gguf"))
        assertEquals("_model.gguf", sanitize("\u0000model.gguf"))
    }

    @Test
    fun `blank or dot-only names fall back to the default`() {
        assertEquals("model.gguf", sanitize(""))
        assertEquals("fallback.gguf", sanitize("...", fallback = "fallback.gguf"))
        assertEquals("model.gguf", sanitize("   "))
    }

    @Test
    fun `result never contains a separator and is length-capped`() {
        val hostile = "../".repeat(50) + "x".repeat(400) + ".gguf"
        val result = sanitize(hostile)
        assertFalse("result must not contain '/': $result", result.contains('/'))
        assertFalse("result must not contain '\\': $result", result.contains('\\'))
        assertEquals(120, result.length)
    }
}
