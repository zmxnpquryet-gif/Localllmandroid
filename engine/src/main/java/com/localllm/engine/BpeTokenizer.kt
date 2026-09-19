package com.localllm.engine

/**
 * GPT-2-style byte-level BPE over a GGUF `tokenizer.ggml.tokens` table.
 *
 * Encode: UTF-8 bytes → initial single-byte pieces → repeated lowest-rank merge.
 * Merge ranks come from `tokenizer.ggml.merges` ("left right" strings, resolved to
 * ids by longest-valid-left split). When merges are absent, maximal-munch greedy
 * matching is used instead. Pretokenizer regexes are intentionally out of scope —
 * documented approximation, exact for merge-complete tables.
 */
class BpeTokenizer(
    tokens: List<ByteArray>,
    merges: List<String>,
    val bosId: Int = 1,
    val eosId: Int = 2
) {
    val vocabSize: Int = tokens.size
    private val tokenBytes: List<ByteArray> = tokens
    private val tokenOf: Map<ByteString, Int> =
        tokens.mapIndexed { id, bytes -> ByteString(bytes) to id }.toMap()
    private val byteTokenOf = IntArray(256) { -1 }

    /** (leftId, rightId) -> rank (lower merges first). */
    private val mergeRank = HashMap<Long, Int>()

    init {
        for (b in 0 until 256) {
            byteTokenOf[b] = tokenOf[ByteString(byteArrayOf(b.toByte()))] ?: -1
        }
        merges.forEachIndexed { rank, merge ->
            val split = splitMerge(merge)
            if (split != null) {
                mergeRank[(split.first.toLong() shl 32) or (split.second.toLong() and 0xFFFFFFFFL)] = rank
            }
        }
    }

    private fun splitMerge(merge: String): Pair<Int, Int>? {
        val bytes = merge.toByteArray(Charsets.UTF_8)
        // GGUF merges join the two token byte-strings with a single ASCII space.
        // Resolve by longest valid left part.
        for (cut in bytes.size - 1 downTo 1) {
            if (bytes[cut - 1] != ' '.code.toByte()) continue
            val left = tokenOf[ByteString(bytes.copyOfRange(0, cut - 1))]
            val right = tokenOf[ByteString(bytes.copyOfRange(cut, bytes.size))]
            if (left != null && right != null) return Pair(left, right)
        }
        return null
    }

    fun encode(text: String, addBos: Boolean = false): IntArray {
        val out = ArrayList<Int>()
        if (addBos && bosId in tokenBytes.indices) out.add(bosId)
        if (text.isEmpty()) return out.toIntArray()
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (mergeRank.isEmpty()) {
            maximalMunch(bytes, out)
        } else {
            bpeMerge(bytes, out)
        }
        return out.toIntArray()
    }

    private fun maximalMunch(bytes: ByteArray, out: ArrayList<Int>) {
        var pos = 0
        var maxLen = 1
        for (t in tokenBytes) maxLen = maxOf(maxLen, t.size)
        while (pos < bytes.size) {
            var matched = -1
            var len = minOf(maxLen, bytes.size - pos)
            while (len > 0) {
                val id = tokenOf[ByteString(bytes.copyOfRange(pos, pos + len))]
                if (id != null) {
                    matched = id
                    break
                }
                len--
            }
            if (matched < 0) {
                // Unknown byte: fall back to single-byte token or skip.
                val single = byteTokenOf[bytes[pos].toInt() and 0xFF]
                if (single >= 0) out.add(single)
                pos++
            } else {
                out.add(matched)
                pos += len
            }
        }
    }

    private fun bpeMerge(bytes: ByteArray, out: ArrayList<Int>) {
        val pieces = ArrayList<Int>(bytes.size)
        for (b in bytes) {
            val single = byteTokenOf[b.toInt() and 0xFF]
            if (single >= 0) pieces.add(single)
        }
        while (pieces.size >= 2) {
            var bestRank = Int.MAX_VALUE
            var bestAt = -1
            for (i in 0 until pieces.size - 1) {
                val key = (pieces[i].toLong() shl 32) or (pieces[i + 1].toLong() and 0xFFFFFFFFL)
                val rank = mergeRank[key] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestAt = i
                }
            }
            if (bestAt < 0) break
            val merged = tokenOf[concat(tokenBytes[pieces[bestAt]], tokenBytes[pieces[bestAt + 1]])]
            if (merged == null) break
            pieces[bestAt] = merged
            pieces.removeAt(bestAt + 1)
        }
        out.addAll(pieces)
    }

    private fun concat(a: ByteArray, b: ByteArray): ByteString {
        val out = ByteArray(a.size + b.size)
        a.copyInto(out)
        b.copyInto(out, a.size)
        return ByteString(out)
    }

    /** Decodes ids to text; control/special ids (bos/eos) are skipped by default. */
    fun decode(ids: IntArray, skipSpecials: Boolean = true): String {
        var total = 0
        for (id in ids) {
            if (id !in tokenBytes.indices) continue
            if (skipSpecials && (id == bosId || id == eosId)) continue
            total += tokenBytes[id].size
        }
        val out = ByteArray(total)
        var pos = 0
        for (id in ids) {
            if (id !in tokenBytes.indices) continue
            if (skipSpecials && (id == bosId || id == eosId)) continue
            val t = tokenBytes[id]
            t.copyInto(out, pos)
            pos += t.size
        }
        return out.toString(Charsets.UTF_8)
    }

    companion object {
        /** Builds a tokenizer from GGUF `tokenizer.ggml.*` metadata. */
        fun fromMetadata(reader: GgufReader): BpeTokenizer? {
            val tokenStrs = reader.stringArray("tokenizer.ggml.tokens")
            if (tokenStrs.isEmpty()) return null
            // GGUF stores tokens as UTF-8 strings; byte tokens appear as raw single chars.
            val tokens = tokenStrs.map { it.toByteArray(Charsets.UTF_8) }
            val merges = reader.stringArray("tokenizer.ggml.merges")
            val bos = reader.i32("tokenizer.ggml.bos_token_id") ?: 1
            val eos = reader.i32("tokenizer.ggml.eos_token_id") ?: 2
            return BpeTokenizer(tokens, merges, bos, eos)
        }
    }

    /** Byte-array wrapper with value equality for map keys. */
    private data class ByteString(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is ByteString && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}
