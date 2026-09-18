package com.localllm.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class ExpertPagerTest {

    private fun key(layer: Int, expert: Int) = ExpertTileKey(layer, expert, ExpertTileKey.GATE)

    private fun memSource(vararg sizes: Pair<ExpertTileKey, Int>): MemoryExpertTileSource {
        val map = sizes.associate { (k, n) -> k to ByteArray(n) { (k.expert + 1).toByte() } }
        return MemoryExpertTileSource(map)
    }

    @Test
    fun `hits misses and lru eviction accounted`() {
        val src = memSource(key(0, 0) to 100, key(0, 1) to 100, key(0, 2) to 100)
        val pager = ExpertPager(src, maxResidentBytes = 200)
        pager.acquire(key(0, 0)) // miss
        pager.acquire(key(0, 1)) // miss
        pager.acquire(key(0, 0)) // hit
        pager.acquire(key(0, 2)) // miss + evict LRU (expert 1)
        assertEquals(1L, pager.stats.hits)
        assertEquals(3L, pager.stats.misses)
        assertEquals(1L, pager.stats.evictions)
        assertEquals(300L, pager.stats.bytesStreamed)
        assertEquals(2, pager.residentCount())
        assertEquals(200L, pager.residentBytes())
        // expert 1 was evicted -> miss again
        pager.acquire(key(0, 1))
        assertEquals(4L, pager.stats.misses)
    }

    @Test
    fun `oversize tiles stream without caching`() {
        val src = memSource(key(0, 0) to 1000)
        val pager = ExpertPager(src, maxResidentBytes = 100)
        val bytes = pager.acquire(key(0, 0))
        assertEquals(1000, bytes.size)
        assertEquals(0, pager.residentCount())
        assertEquals(1L, pager.stats.misses)
        assertEquals(1000L, pager.stats.bytesStreamed)
    }

    @Test
    fun `advise preloads without counting requests`() {
        val src = memSource(key(1, 3) to 50)
        val pager = ExpertPager(src, maxResidentBytes = 1000)
        pager.advise(listOf(key(1, 3)))
        assertEquals(1L, pager.stats.prefetches)
        assertEquals(0L, pager.stats.requests)
        pager.acquire(key(1, 3))
        assertEquals(1L, pager.stats.hits)
    }

    @Test
    fun `file source roundtrips positional ranges`() {
        val f = Files.createTempFile("tiles", ".bin").toFile()
        try {
            val payload = ByteArray(1024) { it.toByte() }
            f.writeBytes(payload)
            FileExpertTileSource(
                f, mapOf(key(2, 5) to Pair(100L, 64))
            ).use { src ->
                assertEquals(64L, src.tileBytes(key(2, 5)))
                assertArrayEquals(payload.copyOfRange(100, 164), src.readTile(key(2, 5)))
            }
        } finally {
            f.delete()
        }
    }

    @Test
    fun `hit rate math`() {
        val s = ExpertPager.Stats(hits = 3, misses = 1)
        assertEquals(0.75, s.hitRate, 1e-9)
        assertEquals(1.0, ExpertPager.Stats().hitRate, 0.0)
    }
}
