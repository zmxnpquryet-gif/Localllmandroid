package com.localllm.engine

/**
 * Incremental decode for token-by-token streaming.
 *
 * Decoding the whole id list from scratch on every step turns a token that lands
 * mid-multibyte-character into U+FFFD replacement text baked into the "previous"
 * string; when the character completes, the prefix comparison fails and the whole
 * sentence is re-emitted as the delta. Instead this decoder tracks stored bytes,
 * unmaps them to original-text bytes, and only emits complete UTF-8 prefixes —
 * an incomplete trailing sequence is held, never shown.
 */
class TokenStreamDecoder(private val tok: BpeTokenizer) {
    private var consumedStored = 0
    private var carryMapped = ByteArray(0)
    private var carryOrig = ByteArray(0)

    /**
     * Feeds the FULL stored bytes of the ids so far ([BpeTokenizer.storedBytes]) and
     * returns newly completed text, possibly empty. Id lists only grow.
     *
     * Two carry levels: unmapping is only ever run on complete stored characters
     * (running it on a split multibyte spelling would bake a U+FFFD into the
     * stream), and text is only emitted on complete original-text characters.
     */
    fun pushStored(all: ByteArray): String {
        require(all.size >= consumedStored) { "token stream went backwards" }
        val tail = all.copyOfRange(consumedStored, all.size)
        consumedStored = all.size
        if (tail.isEmpty() && carryMapped.isEmpty() && carryOrig.isEmpty()) return ""
        val mapped = carryMapped + tail
        val m = completeUtf8PrefixLength(mapped)
        carryMapped = mapped.copyOfRange(m, mapped.size)
        val combined = carryOrig + tok.unmapStored(mapped.copyOfRange(0, m))
        val n = completeUtf8PrefixLength(combined)
        carryOrig = combined.copyOfRange(n, combined.size)
        return if (n == 0) "" else String(combined, 0, n, Charsets.UTF_8)
    }

    /** End of stream: emit whatever is left (replacement chars if truly truncated). */
    fun flush(): String {
        val rest = carryOrig + tok.unmapStored(carryMapped)
        carryMapped = ByteArray(0)
        carryOrig = ByteArray(0)
        consumedStored = 0
        return if (rest.isEmpty()) "" else String(rest, Charsets.UTF_8)
    }

    companion object {
        /**
         * Length of the longest prefix of [bytes] that is complete UTF-8. A trailing
         * incomplete sequence (at most 4 bytes) is excluded; an invalid lead byte is
         * included so it surfaces as a replacement char exactly like String would.
         */
        fun completeUtf8PrefixLength(bytes: ByteArray): Int {
            val end = bytes.size
            if (end == 0) return 0
            var i = end - 1
            while (i >= 0 && (bytes[i].toInt() and 0xC0) == 0x80) i--
            if (i < 0) return 0 // all continuation bytes: nothing complete yet
            val lead = bytes[i].toInt() and 0xFF
            val need = when {
                lead and 0x80 == 0 -> 1
                lead and 0xE0 == 0xC0 -> 2
                lead and 0xF0 == 0xE0 -> 3
                lead and 0xF8 == 0xF0 -> 4
                else -> return end
            }
            return if (end - i >= need) end else i
        }
    }
}
