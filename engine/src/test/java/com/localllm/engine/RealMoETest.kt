// SDengine — live MoE verification against a real gated-expert GGUF.
// Reads ONLY byte ranges over HTTP (header + a sample of expert tiles):
// the full 19GB file is never downloaded. Skips cleanly offline.
package com.localllm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random

class RealMoETest {

    companion object {
        const val DEFAULT_URL =
            "https://huggingface.co/Qwen/Qwen3-30B-A3B-GGUF/resolve/main/Qwen3-30B-A3B-Q4_K_M.gguf"
        const val HEADER_BYTES = 8L * 1024 * 1024
    }

    private class HttpRange(val baseUrl: String) {
        private var resolved: String? = null
        var totalBytes: Long? = null
            private set

        private fun parseTotal(contentRange: String?): Long? {
            // "bytes 0-0/198547..." -> total suffix
            if (contentRange == null) return null
            val slash = contentRange.lastIndexOf('/')
            if (slash < 0) return null
            return contentRange.substring(slash + 1).trim().toLongOrNull()
        }

        fun resolve(): String? {
            resolved?.let { return it }
            var current = baseUrl
            repeat(5) {
                val c = URL(current).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.setRequestProperty("Range", "bytes=0-0")
                c.connectTimeout = 15000
                c.readTimeout = 30000
                try {
                    val code = c.responseCode
                    if (code in 200..299) {
                        if (code == 206) totalBytes = parseTotal(c.getHeaderField("Content-Range"))
                        resolved = current
                        return current
                    }
                    if (code in 300..399) {
                        val loc = c.getHeaderField("Location") ?: return null
                        current = URL(URL(current), loc).toString()
                    } else return null
                } finally {
                    c.disconnect()
                }
            }
            return null
        }

        fun getRange(start: Long, endInclusive: Long): ByteArray? {
            val target = resolve() ?: return null
            var current = target
            repeat(6) {
                val c = URL(current).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.setRequestProperty("Range", "bytes=$start-$endInclusive")
                c.connectTimeout = 15000
                c.readTimeout = 120000
                try {
                    when (c.responseCode) {
                        206 -> return c.inputStream.use { it.readBytes() }
                        in 300..399 -> {
                            val loc = c.getHeaderField("Location") ?: return null
                            current = URL(URL(current), loc).toString()
                        }
                        else -> return null
                    }
                } finally {
                    c.disconnect()
                }
            }
            return null
        }
    }

    private class HttpTileSource(val http: HttpRange) : ExpertTileSource {
        val ranges = HashMap<ExpertTileKey, Pair<Long, Int>>()
        override fun readTile(key: ExpertTileKey): ByteArray {
            val (off, len) = ranges[key] ?: error("no layout for $key")
            return http.getRange(off, off + len - 1) ?: error("range fetch failed for $key")
        }

        override fun tileBytes(key: ExpertTileKey): Long =
            ranges[key]?.second?.toLong() ?: error("no layout for $key")
    }

    @Test
    fun `live moe tile streaming and residency`() {
        val url = System.getenv("LOCALENGINE_MOE_URL")?.ifBlank { null } ?: DEFAULT_URL
        val http = HttpRange(url)
        val header = try {
            http.getRange(0, HEADER_BYTES - 1)
        } catch (e: Exception) {
            null
        }
        if (header == null || header.size < HEADER_BYTES) {
            println("SKIP: cannot fetch GGUF header ranges (offline?)")
            return
        }
        val total = http.totalBytes ?: header.size.toLong()
        println("remote file bytes=$total")
        GgufReader.open(MemorySeekableSource(header, total)).use { r ->
            val arch = r.architecture()
            println("arch=$arch tensors=${r.tensors.size}")
            assertEquals("qwen3moe", arch)
            val nExperts = r.archU32("expert_count")?.toInt() ?: error("no expert_count")
            val topK = r.archU32("expert_used_count")?.toInt() ?: error("no expert_used_count")
            val nLayers = r.archU32("block_count")?.toInt() ?: error("no block_count")
            println("experts=$nExperts topK=$topK layers=$nLayers")
            assertEquals(128, nExperts)
            assertEquals(8, topK)
            assertEquals(48, nLayers)
            val byName = r.tensors.associateBy { it.name }

            // Tile layout: 128 consecutive block-aligned ranges per 3-D tensor.
            val gate0 = byName["blk.0.ffn_gate_exps.weight"] ?: error("no gate_exps")
            val tiles0 = (0 until nExperts).map { expertTileRange(gate0, it, nExperts.toLong()) }
            for (i in 1 until tiles0.size) {
                assertEquals(tiles0[i - 1].offset + tiles0[i - 1].length, tiles0[i].offset)
            }
            val layerBytes = tiles0.sumOf { it.length } * 3
            val totalExpertBytes = r.tensors
                .filter {
                    it.name.contains(".ffn_gate_exps.") || it.name.contains(".ffn_up_exps.") ||
                        it.name.contains(".ffn_down_exps.")
                }
                .sumOf { it.byteSize }
            println("layer0 experts bytes=$layerBytes total expert bytes=$totalExpertBytes")

            // Residency simulation: 2 steps x 8 layers x topK gate tiles through a 256MB pager.
            val src = HttpTileSource(http)
            for (layer in 0 until 8) {
                for (kind in listOf(ExpertTileKey.GATE, ExpertTileKey.UP, ExpertTileKey.DOWN)) {
                    val base = when (kind) {
                        ExpertTileKey.GATE -> "blk.$layer.ffn_gate_exps.weight"
                        ExpertTileKey.UP -> "blk.$layer.ffn_up_exps.weight"
                        else -> "blk.$layer.ffn_down_exps.weight"
                    }
                    val info = byName[base] ?: error("missing $base")
                    for (e in 0 until nExperts) {
                        val t = expertTileRange(info, e, nExperts.toLong())
                        src.ranges[ExpertTileKey(layer, e, kind)] = Pair(t.offset, t.length.toInt())
                    }
                }
            }
            val cap = 256L * 1024 * 1024
            val pager = ExpertPager(src, maxResidentBytes = cap)
            val rng = Random(7)
            repeat(2) {
                for (layer in 0 until 8) {
                    val picked = (0 until nExperts).shuffled(rng).take(topK)
                    for (e in picked) pager.acquire(ExpertTileKey(layer, e, ExpertTileKey.GATE))
                }
            }
            println("resident=${pager.residentBytes()} cap=$cap hits=${pager.stats.hits} misses=${pager.stats.misses} streamed=${pager.stats.bytesStreamed}")
            assertTrue(pager.residentBytes() <= cap)
            assertTrue(pager.stats.misses > 0)
            assertTrue(pager.stats.bytesStreamed < totalExpertBytes)

            // Real Q4_K gate tile dequant plausibility (structural, not bit-exactness).
            val probe = src.readTile(ExpertTileKey(0, 0, ExpertTileKey.GATE))
            val dt = gate0.dtype()
            val n = probe.size / dt.typeSizeBytes * dt.blockLength
            val out = FloatArray(n)
            Quant.dequantizeRow(gate0.dtypeId, probe, 0, out, 0, n)
            assertTrue(out.all { it.isFinite() })
            val mean = out.average()
            var variance = 0.0
            for (v in out) variance += (v - mean) * (v - mean)
            val std = kotlin.math.sqrt(variance / out.size)
            println("gate tile mean=$mean std=$std")
            assertTrue(kotlin.math.abs(mean) < 1.0)
            assertTrue(std > 1e-4 && std < 10.0)

            // Router smoke: decode layer-0 router, top-K must land in range.
            val routerInfo = byName["blk.0.ffn_gate_inp.weight"] ?: error("no router")
            val routerBytes = http.getRange(routerInfo.dataOffset, routerInfo.dataOffset + routerInfo.byteSize - 1)
                ?: error("router fetch failed")
            val rdt = routerInfo.dtype()
            val rcount = routerBytes.size / rdt.typeSizeBytes * rdt.blockLength
            val rmat = FloatArray(rcount)
            Quant.dequantizeRow(routerInfo.dtypeId, routerBytes, 0, rmat, 0, rcount)
            val dim = rcount / nExperts
            val hidden = FloatArray(dim) { rng.nextFloat() - 0.5f }
            val scores = FloatArray(nExperts)
            ReferenceKernels.matVec(scores, rmat, hidden, nExperts, dim)
            val top = ReferenceKernels.topK(scores, topK)
            assertEquals(topK, top.size)
            assertTrue(top.all { it in 0 until nExperts })
            println("router topK=" + top.toList())
        }
    }
}
