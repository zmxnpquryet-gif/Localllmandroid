package com.localllm.android.memory

import android.util.Log
import com.localllm.engine.GgufReader
import java.io.File

/**
 * Footprint estimate for a GGUF model before it is mapped, so the load can be
 * resized or refused instead of walking into an LMK kill. llama.cpp maps the
 * weights (file size) and allocates the KV cache on top; that sum is what the
 * previous "model size vs available RAM" check was missing.
 */
object MemoryEstimator {

    private const val OVERHEAD_BYTES = 96L * 1024L * 1024L
    private const val KV_BYTES_PER_ELEMENT = 2L
    private val CONTEXT_STEPS = intArrayOf(512, 1024, 2048, 4096, 8192)

    data class Estimate(
        val fileBytes: Long,
        val kvBytes: Long,
        val overheadBytes: Long,
        val contextWindow: Int,
        val nLayers: Int,
        val kvHeads: Int,
        val headDim: Int
    ) {
        val totalBytes: Long get() = fileBytes + kvBytes + overheadBytes

        fun totalMb(): Long = totalBytes / MemorySnapshot.MB
    }

    enum class Status { OK, REDUCED, REFUSED, UNKNOWN }

    data class Decision(
        val status: Status,
        val effectiveContextWindow: Int,
        val estimate: Estimate?,
        val reason: String
    )

    fun estimate(file: File, contextWindow: Int): Estimate? {
        return try {
            val size = file.length()
            if (size <= 0) return null
            val reader = GgufReader.open(file)
            try {
                val nLayers = reader.archU32("block_count")?.toInt() ?: return null
                val nHeads = reader.archU32("attention.head_count")?.toInt() ?: return null
                val dim = reader.archU32("embedding_length")?.toInt() ?: 0
                val kvHeads = reader.archU32("attention.head_count_kv")?.toInt() ?: nHeads
                val headDim = reader.archU32("attention.key_length")?.toInt()
                    ?: if (nHeads > 0 && dim > 0) dim / nHeads else 0
                if (nLayers <= 0 || kvHeads <= 0 || headDim <= 0) return null
                Estimate(
                    fileBytes = size,
                    kvBytes = kvBytes(nLayers, kvHeads, headDim, contextWindow),
                    overheadBytes = OVERHEAD_BYTES,
                    contextWindow = contextWindow,
                    nLayers = nLayers,
                    kvHeads = kvHeads,
                    headDim = headDim
                )
            } finally {
                reader.close()
            }
        } catch (t: Throwable) {
            Log.d("MemoryEstimator", "Estimate unavailable for ${file.name}: ${t.message}")
            null
        }
    }

    fun kvBytes(nLayers: Int, kvHeads: Int, headDim: Int, contextWindow: Int): Long {
        return 2L * nLayers * kvHeads * headDim * contextWindow * KV_BYTES_PER_ELEMENT
    }

    /**
     * Compares the footprint with the memory the device reports as available.
     * Above 80% of available RAM the context is reduced; above 95% the load is
     * refused with a reason the UI can show.
     */
    fun decide(estimate: Estimate, snapshot: MemorySnapshot, requestedContextWindow: Int): Decision {
        if (snapshot.unknown || snapshot.availMb <= 0) {
            return Decision(Status.UNKNOWN, requestedContextWindow, estimate, "memory info unavailable")
        }
        val availBytes = snapshot.availMb * MemorySnapshot.MB
        val softLimit = (availBytes * 0.80).toLong()
        val hardLimit = (availBytes * 0.95).toLong()
        if (estimate.totalBytes <= softLimit) {
            return Decision(Status.OK, requestedContextWindow, estimate, "fits (${estimate.totalMb()}MB)")
        }
        val candidate = CONTEXT_STEPS
            .filter { it <= requestedContextWindow }
            .sortedDescending()
            .firstOrNull { ctx ->
                val shrunk = estimate.copy(
                    kvBytes = kvBytes(estimate.nLayers, estimate.kvHeads, estimate.headDim, ctx),
                    contextWindow = ctx
                )
                shrunk.totalBytes <= softLimit
            }
        if (candidate != null) {
            return Decision(
                Status.REDUCED,
                candidate,
                estimate.copy(
                    kvBytes = kvBytes(estimate.nLayers, estimate.kvHeads, estimate.headDim, candidate),
                    contextWindow = candidate
                ),
                "context reduced to $candidate (est ${estimate.totalMb()}MB vs avail ${snapshot.availMb}MB)"
            )
        }
        val fitsAtMinimum = estimate.copy(
            kvBytes = kvBytes(estimate.nLayers, estimate.kvHeads, estimate.headDim, CONTEXT_STEPS.first()),
            contextWindow = CONTEXT_STEPS.first()
        ).totalBytes <= hardLimit
        return if (fitsAtMinimum) {
            Decision(
                Status.REDUCED,
                CONTEXT_STEPS.first(),
                estimate.copy(
                    kvBytes = kvBytes(estimate.nLayers, estimate.kvHeads, estimate.headDim, CONTEXT_STEPS.first()),
                    contextWindow = CONTEXT_STEPS.first()
                ),
                "context reduced to ${CONTEXT_STEPS.first()} (est ${estimate.totalMb()}MB vs avail ${snapshot.availMb}MB)"
            )
        } else {
            Decision(
                Status.REFUSED,
                requestedContextWindow,
                estimate,
                "needs ~${estimate.totalMb()}MB but only ${snapshot.availMb}MB available"
            )
        }
    }
}
