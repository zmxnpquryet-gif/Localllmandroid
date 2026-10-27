package com.localllm.android.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HfSource rewrites only the official huggingface.co prefix when the mirror is
 * on; CDN and third-party URLs must pass through untouched.
 */
class HfSourceTest {

    @After
    fun resetMirrorFlag() {
        // HfSource is a global singleton: restore the default for other tests.
        HfSource.useMirror = false
    }

    @Test
    fun `default keeps huggingface urls untouched`() {
        HfSource.useMirror = false
        val url = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/model.gguf"
        assertEquals(url, HfSource.rewrite(url))
    }

    @Test
    fun `default current host is official`() {
        HfSource.useMirror = false
        assertEquals("huggingface.co", HfSource.currentHost)
        assertEquals("https://huggingface.co", HfSource.OFFICIAL)
    }

    @Test
    fun `mirror mode rewrites official urls to hf-mirror`() {
        HfSource.useMirror = true
        assertEquals(
            "https://hf-mirror.com/Qwen/Qwen3-0.6B-GGUF/resolve/main/model.gguf",
            HfSource.rewrite("https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/model.gguf")
        )
        assertEquals("https://hf-mirror.com/", HfSource.rewrite("https://huggingface.co/"))
    }

    @Test
    fun `mirror mode keeps current host on the mirror`() {
        HfSource.useMirror = true
        assertEquals("hf-mirror.com", HfSource.currentHost)
        assertEquals("https://hf-mirror.com", HfSource.MIRROR)
    }

    @Test
    fun `cdn and third party urls are never rewritten`() {
        HfSource.useMirror = true
        val cdn = "https://cdn-lfs.huggingface.co/repos/ab/cd/model.gguf?download=true"
        // Only the bare OFFICIAL prefix is replaced; cdn-lfs subdomains stay.
        assertEquals(cdn, HfSource.rewrite(cdn))
        val github = "https://github.com/ggml-org/llama.cpp/releases/download/v1/model.zip"
        assertEquals(github, HfSource.rewrite(github))
        val cdnOther = "https://cdn.example.com/models/model.gguf"
        assertEquals(cdnOther, HfSource.rewrite(cdnOther))
        val mirrorUrl = "https://hf-mirror.com/Qwen/Qwen3-0.6B-GGUF/resolve/main/model.gguf"
        assertEquals(mirrorUrl, HfSource.rewrite(mirrorUrl))
    }

    @Test
    fun `mirror rewrite only replaces the first occurrence`() {
        HfSource.useMirror = true
        // A query string that echoes the official URL must not be rewritten twice.
        assertEquals(
            "https://hf-mirror.com/redirect?to=https://huggingface.co/a.gguf",
            HfSource.rewrite("https://huggingface.co/redirect?to=https://huggingface.co/a.gguf")
        )
    }

    @Test
    fun `non http url is returned as is`() {
        HfSource.useMirror = true
        assertEquals("/local/path/model.gguf", HfSource.rewrite("/local/path/model.gguf"))
        assertEquals("", HfSource.rewrite(""))
    }
}
