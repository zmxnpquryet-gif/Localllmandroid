package com.localllm.android.document

import java.util.Locale
import kotlin.math.sqrt

/**
 * On-device lexical embedding: feature-hashed word unigrams plus character trigrams,
 * L2-normalized into a fixed-size vector.
 *
 * Why not a neural embedding model: the app must work fully offline with no extra
 * model download, and the shipped runtimes (llama.cpp generation, LiteRT-LM) do not
 * expose an embedding endpoint in this build. Hashing still gives vector retrieval
 * with cosine ranking and handles Korean/CJK through character n-grams, but it
 * matches *terms*, not meaning. It is deliberately deterministic so an index built
 * on one run stays searchable after the app restarts.
 */
class HashedTextEmbedder(private val dimensions: Int = DEFAULT_DIMENSIONS) {

    init {
        require(dimensions > 8) { "dimensions must be > 8" }
    }

    fun embed(text: String): FloatArray {
        val vector = FloatArray(dimensions)
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return vector

        for (match in WORD_PATTERN.findAll(normalized)) {
            val word = match.value
            add(vector, word, 1.0f)
            // Character trigrams: Korean particles and CJK compounds rarely split on
            // spaces, so whole-word matches alone would miss most morphology.
            if (word.length >= 3) {
                for (i in 0..word.length - 3) {
                    add(vector, word.substring(i, i + 3), 0.5f)
                }
            }
        }
        if (normalized.length >= 3) {
            for (i in 0..normalized.length - 3) {
                add(vector, normalized.substring(i, i + 3), 0.25f)
            }
        }

        var norm = 0f
        for (value in vector) norm += value * value
        norm = sqrt(norm)
        if (norm > 0f) {
            for (i in vector.indices) vector[i] /= norm
        }
        return vector
    }

    /** Vectors from [embed] are unit length, so cosine similarity is the dot product. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    private fun add(vector: FloatArray, token: String, weight: Float) {
        val hash = fnv1a(token)
        val index = (hash ushr 1) % dimensions
        val sign = if (hash and 0x40000000 != 0) 1f else -1f
        vector[index] += sign * weight
    }

    private fun fnv1a(token: String): Int {
        var hash = -0x7ee3623b // 2166136261
        for (char in token) {
            hash = hash xor char.code
            hash *= 16777619
        }
        return hash
    }

    companion object {
        const val DEFAULT_DIMENSIONS = 512
        private val WORD_PATTERN = Regex("[\\p{L}\\p{N}_]+")
    }
}
