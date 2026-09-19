// SDengine — TEST BUILD. EXPERIMENTAL. See SDEngine.ADVISORIES.
package com.localllm.engine

/**
 * Per-layer ring KV cache. Slot `pos` holds the K/V row of sequence position `pos`;
 * decode appends, prefill bulk-fills, and [trim]/[clear] reset session state so one
 * conversation can never observe another's keys (session isolation primitive).
 */
class KvCache(
    val nLayers: Int,
    val nKvHeads: Int,
    val headDim: Int,
    val maxSeq: Int
) {
    private val rowSize: Int = nKvHeads * headDim
    private val k: Array<FloatArray> = Array(nLayers) { FloatArray(maxSeq * rowSize) }
    private val v: Array<FloatArray> = Array(nLayers) { FloatArray(maxSeq * rowSize) }
    var length: Int = 0
        private set

    fun append(layer: Int, kRow: FloatArray, vRow: FloatArray) {
        require(kRow.size == rowSize && vRow.size == rowSize)
        require(length < maxSeq) { "KV cache overflow: length=$length maxSeq=$maxSeq" }
        kRow.copyInto(k[layer], length * rowSize)
        vRow.copyInto(v[layer], length * rowSize)
        if (layer == nLayers - 1) length++
    }

    fun kRow(layer: Int, pos: Int): FloatArray =
        k[layer].copyOfRange(pos * rowSize, pos * rowSize + rowSize)

    fun vRow(layer: Int, pos: Int): FloatArray =
        v[layer].copyOfRange(pos * rowSize, pos * rowSize + rowSize)

    /** Direct (read-only view) access for attention loops; valid for [0, length). */
    fun kBase(layer: Int): FloatArray = k[layer]

    fun vBase(layer: Int): FloatArray = v[layer]

    fun trim(newLength: Int) {
        require(newLength in 0..length)
        length = newLength
    }

    fun clear() {
        length = 0
    }
}
