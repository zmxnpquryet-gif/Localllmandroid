// SDengine — TEST BUILD. EXPERIMENTAL. See SDEngine.ADVISORIES.
package com.localllm.engine

import java.util.Random
import kotlin.math.exp

data class SampleConfig(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val seed: Long = 0L
)

/** Logit sampler: greedy at temperature<=0, otherwise temp/top-k/top-p. Deterministic per seed. */
class Sampler(private val rng: Random = Random(0L)) {

    fun sample(logits: FloatArray, config: SampleConfig): Int {
        require(logits.isNotEmpty())
        if (config.temperature <= 0f || config.temperature.isNaN()) {
            return argmax(logits)
        }
        val invTemp = 1f / config.temperature.coerceAtLeast(1e-6f)
        // Softmax in a numerically stable way.
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v
        val probs = FloatArray(logits.size)
        var sum = 0.0
        for (i in logits.indices) {
            val p = exp(((logits[i] - max) * invTemp).toDouble())
            probs[i] = p.toFloat()
            sum += p
        }
        val invSum = (1.0 / sum).toFloat()
        for (i in probs.indices) probs[i] *= invSum

        // Order candidates by probability desc.
        val order = probs.indices.sortedByDescending { probs[it] }
        val k = config.topK.coerceAtLeast(1).coerceAtMost(order.size)
        var cum = 0f
        var cutoff = k
        val topPCut = config.topP.coerceIn(0f, 1f)
        for (i in 0 until k) {
            cum += probs[order[i]]
            if (cum >= topPCut) {
                cutoff = i + 1
                break
            }
        }
        cutoff = maxOf(1, cutoff)
        var mass = 0f
        for (i in 0 until cutoff) mass += probs[order[i]]
        var r = rng.nextFloat() * mass
        for (i in 0 until cutoff) {
            r -= probs[order[i]]
            if (r <= 0f) return order[i]
        }
        return order[cutoff - 1]
    }

    private fun argmax(logits: FloatArray): Int {
        var best = 0
        for (i in 1 until logits.size) if (logits[i] > logits[best]) best = i
        return best
    }
}
