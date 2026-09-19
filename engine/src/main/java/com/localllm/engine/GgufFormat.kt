// SDengine — TEST BUILD. EXPERIMENTAL. See SDEngine.ADVISORIES.
package com.localllm.engine

/** GGUF container constants and the ggml dtype table. */
object GgufFormat {
    const val MAGIC = "GGUF"
    const val VERSION: Int = 3
    const val DEFAULT_ALIGNMENT: Long = 32L

    // ggml_type ids (ggml.h)
    const val F32 = 0
    const val F16 = 1
    const val Q4_0 = 2
    const val Q4_1 = 3
    const val Q5_0 = 6
    const val Q5_1 = 7
    const val Q8_0 = 8
    const val Q8_1 = 9
    const val Q2_K = 10
    const val Q3_K = 11
    const val Q4_K = 12
    const val Q5_K = 13
    const val Q6_K = 14
    const val Q8_K = 15
    const val I8 = 24
    const val I16 = 25
    const val I32 = 26
    const val I64 = 27
    const val F64 = 28
    const val BF16 = 30

    // Metadata value types
    const val V_UINT8 = 0
    const val V_INT8 = 1
    const val V_UINT16 = 2
    const val V_INT16 = 3
    const val V_UINT32 = 4
    const val V_INT32 = 5
    const val V_FLOAT32 = 6
    const val V_BOOL = 7
    const val V_STRING = 8
    const val V_ARRAY = 9
    const val V_UINT64 = 10
    const val V_INT64 = 11
    const val V_FLOAT64 = 12
}

/** Elements per block and bytes per block for each supported dtype. */
data class GgmlDType(val id: Int, val name: String, val blockLength: Int, val typeSizeBytes: Int)

object GgmlTypes {
    private val table = listOf(
        GgmlDType(GgufFormat.F32, "F32", 1, 4),
        GgmlDType(GgufFormat.F16, "F16", 1, 2),
        GgmlDType(GgufFormat.BF16, "BF16", 1, 2),
        GgmlDType(GgufFormat.Q4_0, "Q4_0", 32, 18),
        GgmlDType(GgufFormat.Q4_1, "Q4_1", 32, 20),
        GgmlDType(GgufFormat.Q5_0, "Q5_0", 32, 22),
        GgmlDType(GgufFormat.Q5_1, "Q5_1", 32, 24),
        GgmlDType(GgufFormat.Q8_0, "Q8_0", 32, 34),
        GgmlDType(GgufFormat.Q8_1, "Q8_1", 32, 36),
        GgmlDType(GgufFormat.Q2_K, "Q2_K", 256, 84),
        GgmlDType(GgufFormat.Q3_K, "Q3_K", 256, 110),
        GgmlDType(GgufFormat.Q4_K, "Q4_K", 256, 144),
        GgmlDType(GgufFormat.Q5_K, "Q5_K", 256, 176),
        GgmlDType(GgufFormat.Q6_K, "Q6_K", 256, 210),
        GgmlDType(GgufFormat.Q8_K, "Q8_K", 256, 292),
        GgmlDType(GgufFormat.I8, "I8", 1, 1),
        GgmlDType(GgufFormat.I16, "I16", 1, 2),
        GgmlDType(GgufFormat.I32, "I32", 1, 4),
        GgmlDType(GgufFormat.I64, "I64", 1, 8),
        GgmlDType(GgufFormat.F64, "F64", 1, 8)
    ).associateBy { it.id }

    fun of(id: Int): GgmlDType =
        table[id] ?: throw GgufException("Unsupported ggml dtype id=$id")

    fun isQuantized(id: Int): Boolean = id != GgufFormat.F32 && id != GgufFormat.F16 &&
            id != GgufFormat.BF16 && id !in (GgufFormat.I8..GgufFormat.F64)
}

/** Typed GGUF metadata value. */
sealed interface GgufValue {
    data class U8(val v: Int) : GgufValue
    data class I8(val v: Int) : GgufValue
    data class U16(val v: Int) : GgufValue
    data class I16(val v: Int) : GgufValue
    data class U32(val v: Long) : GgufValue
    data class I32(val v: Int) : GgufValue
    data class F32(val v: Float) : GgufValue
    data class Bool(val v: Boolean) : GgufValue
    data class Str(val v: String) : GgufValue
    data class Arr(val elementType: Int, val items: List<GgufValue>) : GgufValue
    data class U64(val v: Long) : GgufValue
    data class I64(val v: Long) : GgufValue
    data class F64(val v: Double) : GgufValue
}

data class GgufHeader(
    val version: Int,
    val tensorCount: Long,
    val metadataCount: Long
)

/** Tensor layout descriptor. `dataOffset` is absolute in file; dims are row-major element counts. */
data class TensorInfo(
    val name: String,
    val dims: LongArray,
    val dtypeId: Int,
    val dataOffset: Long,
    val byteSize: Long
) {
    val elementCount: Long get() = if (dims.isEmpty()) 1L else dims.fold(1L) { a, d -> a * d }

    fun dtype(): GgmlDType = GgmlTypes.of(dtypeId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TensorInfo) return false
        return name == other.name && dims.contentEquals(other.dims) &&
                dtypeId == other.dtypeId && dataOffset == other.dataOffset && byteSize == other.byteSize
    }

    override fun hashCode(): Int {
        var r = name.hashCode()
        r = 31 * r + dims.contentHashCode()
        r = 31 * r + dtypeId
        r = 31 * r + dataOffset.hashCode()
        r = 31 * r + byteSize.hashCode()
        return r
    }
}

class GgufException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Absolute byte range of one expert tile inside a 3-D MoE tensor. */
data class TileRange(val offset: Long, val length: Long, val dtypeId: Int)

/**
 * Locates expert [expertIndex] inside a 3-D MoE tensor [D0, D1, nExperts].
 * Single source of truth for SSD tile layout (engine + tests share it).
 */
fun expertTileRange(info: TensorInfo, expertIndex: Int, nExperts: Long): TileRange {
    require(info.dims.size == 3 && info.dims[2] == nExperts) {
        "MoE tensor ${info.name} has unexpected dims ${info.dims.toList()}"
    }
    val dt = info.dtype()
    val tileElements = info.dims[0] * info.dims[1]
    require(tileElements % dt.blockLength == 0L) { "MoE tile of ${info.name} not block-aligned" }
    val tileBytes = tileElements / dt.blockLength * dt.typeSizeBytes
    return TileRange(info.dataOffset + expertIndex * tileBytes, tileBytes, info.dtypeId)
}

/** Marks K-quant kernels whose bit-exactness against ggml is pending golden-vector verification. */
@RequiresOptIn("K-quant dequantization is structurally complete but not yet verified bit-exact against ggml golden vectors.")
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalQuant
