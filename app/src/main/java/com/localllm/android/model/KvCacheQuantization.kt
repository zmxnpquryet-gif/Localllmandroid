package com.localllm.android.model

/**
 * KV-cache precision for llama.cpp.
 *
 * The cache is the part of the footprint that grows with the context window, so
 * lowering its precision is the one lever that lets a large context fit without
 * changing the context length itself. [bytesPerElement] follows the ggml block
 * layout (one value per K and V element) so the pre-load estimate matches what
 * the runtime actually allocates.
 */
enum class KvCacheQuantization(
    val id: String,
    val label: String,
    /** Bytes per element for K (and the same for V). */
    val bytesPerElement: Double,
    /** ggml_type id handed to llama.cpp as `type_k` / `type_v`. */
    val ggmlTypeId: Int
) {
    F16("f16", "F16", 2.0, 1),
    Q8_0("q8_0", "Q8_0", 34.0 / 32.0, 8),
    Q4_0("q4_0", "Q4_0", 18.0 / 32.0, 2);

    companion object {
        /** Unknown or legacy values fall back to the lossless default. */
        fun fromId(id: String?): KvCacheQuantization =
            entries.firstOrNull { it.id == id } ?: F16
    }
}
