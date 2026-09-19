// SDengine — TEST BUILD. EXPERIMENTAL. See SDEngine.ADVISORIES.
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
    private val tokenBytes: List<ByteArray> = tokens.map { unveilByteToken(it) }
    private val tokenOf: Map<ByteString, Int> =
        tokenBytes.mapIndexed { id, bytes -> ByteString(bytes) to id }.toMap()
    /**
     * Convention auto-detect. GPT-2-family tables spell spaces as U+0120
     * ("Ġworld"); literal-family tables use raw 0x20 bytes. Input mapping and
     * decode inversion follow the detected side — mixing them drops spaces.
     */
    private val useGpt2: Boolean =
        tokenBytes.any { it.size >= 2 && it[0] == 0xC4.toByte() && it[1] == 0xA0.toByte() }
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
        // GPT-2 byte alphabet: raw UTF-8 bytes map through bytes_to_unicode so
        // pieces match the stored (mapped) token spellings like "Ġworld".
        // Literal-family tables (raw spaces) skip mapping via [useGpt2].
        val raw = text.toByteArray(Charsets.UTF_8)
        val bytes = if (useGpt2) mapToGpt2(raw) else raw
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
        if (useGpt2) {
            // Atomic units are mapped CHARACTERS (U+0120 'Ġ' is 2 bytes in UTF-8);
            // splitting them into raw bytes would orphan every non-ASCII piece.
            val text = bytes.toString(Charsets.UTF_8)
            for (ch in text) {
                val id = tokenOf[ByteString(ch.toString().toByteArray(Charsets.UTF_8))]
                if (id != null) pieces.add(id)
            }
        } else {
            for (b in bytes) {
                val single = byteTokenOf[b.toInt() and 0xFF]
                if (single >= 0) pieces.add(single)
            }
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
        return if (useGpt2) unmapFromGpt2(out.toString(Charsets.UTF_8)).toString(Charsets.UTF_8)
        else out.toString(Charsets.UTF_8)
    }

    companion object {
        /**
         * GGUF byte-token spelling ("<0x20>") resolves to the raw byte, mirroring
         * llama.cpp. Without this, bytes lacking a literal single-char token
         * (typically 0x00-0x20) silently vanish from the output.
         */
        fun unveilByteToken(bytes: ByteArray): ByteArray {
            if (bytes.size == 6 && bytes[0] == '<'.code.toByte() && bytes[1] == '0'.code.toByte() &&
                (bytes[2] == 'x'.code.toByte() || bytes[2] == 'X'.code.toByte()) &&
                bytes[5] == '>'.code.toByte()
            ) {
                val hex = String(bytes.copyOfRange(3, 5), Charsets.US_ASCII)
                try {
                    return byteArrayOf(hex.toInt(16).toByte())
                } catch (_: NumberFormatException) {
                }
            }
            return bytes
        }

        /**
         * Canonical GPT-2 bytes_to_unicode table (byte -> codepoint).
         * Printable ASCII/Latin-1 map to themselves; every other byte maps to
         * U+0100 and up (so 0x20 <-> U+0120 'Ġ', 0x0A <-> U+010A 'Ċ').
         */
        val GPT2_ENCODE: IntArray by lazy {
            val bs = ArrayList<Int>()
            for (b in 33..126) bs.add(b) // '!'..'~'
            for (b in 161..172) bs.add(b)
            for (b in 174..255) bs.add(b)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) {
                if (!bs.contains(b)) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            val table = IntArray(256)
            for (i in bs.indices) table[bs[i]] = cs[i]
            table
        }

        private val GPT2_DECODE: Map<Int, Int> by lazy {
            HashMap<Int, Int>(512).also { m ->
                for (b in 0 until 256) m[GPT2_ENCODE[b]] = b
            }
        }

        /** Raw UTF-8 bytes -> GPT-2 mapped UTF-8 bytes (for piece lookup). */
        fun mapToGpt2(bytes: ByteArray): ByteArray {
            val out = ByteArray(bytes.size * 2 + 8)
            var w = 0
            for (raw in bytes) {
                val cp = GPT2_ENCODE[raw.toInt() and 0xFF]
                if (cp < 0x80) {
                    out[w++] = cp.toByte()
                } else {
                    out[w++] = (0xC0 or (cp shr 6)).toByte()
                    out[w++] = (0x80 or (cp and 0x3F)).toByte()
                }
            }
            return out.copyOf(w)
        }

        /** Mapped text -> raw UTF-8 bytes (inverse of [mapToGpt2]). */
        fun unmapFromGpt2(text: String): ByteArray {
            val out = ByteArray(text.length * 3 + 8)
            var w = 0
            for (ch in text) {
                val raw = GPT2_DECODE[ch.code]
                if (raw != null) {
                    out[w++] = raw.toByte()
                } else {
                    // Unexpected literal char: preserve its UTF-8 bytes.
                    val enc = ch.toString().toByteArray(Charsets.UTF_8)
                    enc.copyInto(out, w)
                    w += enc.size
                }
            }
            return out.copyOf(w)
        }
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
