// SDengine — TEST BUILD. EXPERIMENTAL. See SDEngine.ADVISORIES.
package com.localllm.engine

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** One expert weight tile in one layer. `kind` selects gate/up/down. */
data class ExpertTileKey(val layer: Int, val expert: Int, val kind: Int) {
    companion object {
        const val GATE = 0
        const val UP = 1
        const val DOWN = 2
    }
}

/** Blocking tile source: SSD in production, heap map in tests. */
interface ExpertTileSource {
    fun readTile(key: ExpertTileKey): ByteArray
    fun tileBytes(key: ExpertTileKey): Long
}

/** File-backed source reading absolute [offset, offset+length) ranges positionally. */
class FileExpertTileSource(
    file: File,
    private val layout: Map<ExpertTileKey, Pair<Long, Int>>
) : ExpertTileSource, AutoCloseable {
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
    private val fileSize = file.length()

    override fun readTile(key: ExpertTileKey): ByteArray {
        val (offset, length) = layout[key] ?: throw GgufException("No tile layout for $key")
        if (offset < 0 || offset + length > fileSize) throw GgufException("Tile $key out of bounds")
        val out = ByteArray(length)
        val buf = java.nio.ByteBuffer.wrap(out)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw GgufException("EOF reading tile $key")
            pos += n
        }
        return out
    }

    override fun tileBytes(key: ExpertTileKey): Long =
        layout[key]?.second?.toLong() ?: throw GgufException("No tile layout for $key")

    override fun close() {
        try {
            channel.close()
        } catch (_: Exception) {
        }
    }
}

class MemoryExpertTileSource(private val tiles: Map<ExpertTileKey, ByteArray>) : ExpertTileSource {
    override fun readTile(key: ExpertTileKey): ByteArray =
        tiles[key]?.copyOf() ?: throw GgufException("No tile for $key")

    override fun tileBytes(key: ExpertTileKey): Long =
        tiles[key]?.size?.toLong() ?: throw GgufException("No tile for $key")
}

/**
 * Edge0-style MoE residency manager: only routed experts live in RAM.
 *
 * LRU eviction under [maxResidentBytes]; [advise] preloads predicted experts
 * (next-layer same-index or router lookahead) without counting a use;
 * [Stats] exposes hit rate and SSD bytes for the UI benchmark chip.
 * Thread-confinement: call from the single inference thread.
 */
class ExpertPager(
    private val source: ExpertTileSource,
    private val maxResidentBytes: Long,
    private val prefetch: Boolean = true
) {
    data class Stats(
        var hits: Long = 0,
        var misses: Long = 0,
        var bytesStreamed: Long = 0,
        var evictions: Long = 0,
        var prefetches: Long = 0
    ) {
        val requests: Long get() = hits + misses
        val hitRate: Double get() = if (requests == 0L) 1.0 else hits.toDouble() / requests
    }

    val stats = Stats()

    private val resident = object : LinkedHashMap<ExpertTileKey, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<ExpertTileKey, ByteArray>?): Boolean = false
    }
    private var residentBytes: Long = 0
    /** Speculative keys, evicted before confirmed entries (adversarial finding). */
    private val speculative = LinkedHashSet<ExpertTileKey>()

    fun residentCount(): Int = resident.size

    fun residentBytes(): Long = residentBytes

    /** Returns a copy of the tile bytes; loads from SSD on miss (LRU-evicting first). */
    fun acquire(key: ExpertTileKey): ByteArray {
        resident[key]?.let {
            stats.hits++
            speculative.remove(key)
            return it.copyOf()
        }
        stats.misses++
        val bytes = source.readTile(key)
        stats.bytesStreamed += bytes.size
        if (bytes.size.toLong() <= maxResidentBytes) {
            makeRoom(bytes.size.toLong())
            resident[key] = bytes
            residentBytes += bytes.size
        }
        return bytes.copyOf()
    }

    /**
     * Zero-copy read for the fused matvec path: returns the resident array
     * itself (or the freshly loaded bytes) without copying. Callers must treat
     * it as read-only and must not retain it across [acquire]/[advise]/[evictAll]
     * calls — eviction may drop the entry afterwards. Single-threaded use only,
     * same confinement as the rest of this class (adversarial review finding:
     * `acquire` memcpys the whole tile 3x per expert per token).
     */
    fun borrow(key: ExpertTileKey): ByteArray {
        resident[key]?.let {
            stats.hits++
            speculative.remove(key)
            return it
        }
        stats.misses++
        val bytes = source.readTile(key)
        stats.bytesStreamed += bytes.size
        if (bytes.size.toLong() <= maxResidentBytes) {
            makeRoom(bytes.size.toLong())
            resident[key] = bytes
            residentBytes += bytes.size
            return bytes
        }
        return bytes
    }

    /**
     * Preloads predicted tiles without disturbing resident entries: only free
     * space is used, never eviction. A prefetch that evicts a tile the next
     * acquire immediately needs turns a hit into a miss (adversarial finding),
     * so speculative fills must be strictly non-destructive. Failures are
     * swallowed (best effort).
     */
    fun advise(keys: List<ExpertTileKey>) {
        if (!prefetch) return
        for (key in keys) {
            if (resident.containsKey(key)) continue
            try {
                val size = source.tileBytes(key)
                if (size > maxResidentBytes) continue
                if (residentBytes + size > maxResidentBytes) continue
                resident[key] = source.readTile(key)
                residentBytes += size
                speculative.add(key)
                stats.bytesStreamed += size
                stats.prefetches++
            } catch (e: java.io.IOException) {
                // ClosedByInterruptException means the channel died with the
                // interrupt: propagate so the decode loop fails loudly instead of
                // crashing later with ClosedChannelException (adversarial finding).
                if (e is java.nio.channels.ClosedByInterruptException ||
                    Thread.currentThread().isInterrupted
                ) throw e
            }
        }
    }

    fun evictAll() {
        stats.evictions += resident.size
        resident.clear()
        speculative.clear()
        residentBytes = 0
    }

    private fun makeRoom(need: Long) {
        // Speculative tiles die first: confirmed in-use entries outrank
        // unconfirmed lookahead (adversarial review finding).
        val specIt = speculative.iterator()
        while (residentBytes + need > maxResidentBytes && specIt.hasNext()) {
            val key = specIt.next()
            resident.remove(key)?.let { residentBytes -= it.size }
            specIt.remove()
            stats.evictions++
        }
        val it = resident.entries.iterator()
        while (residentBytes + need > maxResidentBytes && it.hasNext()) {
            val e = it.next()
            speculative.remove(e.key)
            residentBytes -= e.value.size
            it.remove()
            stats.evictions++
        }
    }
}
