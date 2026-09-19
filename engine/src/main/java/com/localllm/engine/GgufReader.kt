// SDengine — TEST BUILD. EXPERIMENTAL. See SDEngine.ADVISORIES.
package com.localllm.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * GGUF container reader over a [FileChannel].
 *
 * Parses header, KV metadata and tensor infos; tensor payloads stay on disk and
 * are read on demand via positional reads (mmap/SSD-streaming friendly — the OS
 * page cache does the paging, we never slurp the file).
 */
/** Random-access byte source: local file (OS paging friendly) or HTTP ranges. */
interface SeekableSource : AutoCloseable {
    val size: Long
    fun readFully(dst: ByteArray, off: Int, len: Int, position: Long)
}

class FileSeekableSource(file: File) : SeekableSource {
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
    override val size: Long = channel.size()

    override fun readFully(dst: ByteArray, off: Int, len: Int, position: Long) {
        if (position < 0 || position + len > size) {
            throw GgufException("Read out of bounds: pos=$position len=$len size=$size")
        }
        val buf = ByteBuffer.wrap(dst, off, len)
        var pos = position
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw GgufException("Unexpected EOF at $pos")
            pos += n
        }
    }

    override fun close() {
        try {
            channel.close()
        } catch (_: Exception) {
        }
    }
}

class MemorySeekableSource(
    private val bytes: ByteArray,
    private val totalSize: Long = bytes.size.toLong()
) : SeekableSource {
    override val size: Long get() = totalSize

    override fun readFully(dst: ByteArray, off: Int, len: Int, position: Long) {
        if (position < 0 || position + len > bytes.size) {
            throw GgufException("Range not resident: pos=$position len=$len (have ${bytes.size} of $totalSize)")
        }
        System.arraycopy(bytes, position.toInt(), dst, off, len)
    }

    override fun close() {}
}

class GgufReader private constructor(
    private val source: SeekableSource
) : AutoCloseable {
    val fileSize: Long get() = source.size

    lateinit var header: GgufHeader
        private set
    lateinit var metadata: Map<String, GgufValue>
        private set
    lateinit var tensors: List<TensorInfo>
        private set
    var dataBaseOffset: Long = -1
        private set

    val alignment: Long
        get() = (metadata["general.alignment"] as? GgufValue.U32)?.v
            ?: GgufFormat.DEFAULT_ALIGNMENT

    // ---- metadata convenience ----

    fun string(key: String): String? = (metadata[key] as? GgufValue.Str)?.v

    fun u32(key: String): Long? = (metadata[key] as? GgufValue.U32)?.v

    fun i32(key: String): Int? = (metadata[key] as? GgufValue.I32)?.v

    fun f32(key: String): Float? = (metadata[key] as? GgufValue.F32)?.v

    fun bool(key: String): Boolean? = (metadata[key] as? GgufValue.Bool)?.v

    fun architecture(): String? = string("general.architecture")

    fun archU32(suffix: String): Long? = architecture()?.let { u32("$it.$suffix") }

    fun archI32(suffix: String): Int? = architecture()?.let { i32("$it.$suffix") }

    fun archF32(suffix: String): Float? = architecture()?.let { f32("$it.$suffix") }

    fun archString(suffix: String): String? = architecture()?.let { string("$it.$suffix") }

    fun stringArray(key: String): List<String> =
        (metadata[key] as? GgufValue.Arr)?.items?.mapNotNull { (it as? GgufValue.Str)?.v } ?: emptyList()

    // ---- tensor payload ----

    /** Reads the full payload of one tensor. Callers needing streaming should slice ranges instead. */
    fun tensorBytes(info: TensorInfo): ByteArray {
        if (info.byteSize > Int.MAX_VALUE) throw GgufException("Tensor ${info.name} exceeds 2GB single-read cap")
        val out = ByteArray(info.byteSize.toInt())
        readFully(out, 0, out.size, info.dataOffset)
        return out
    }

    /** Positional range read; thread-safe per [FileChannel] contract. */
    fun readRange(position: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        readFully(out, 0, length, position)
        return out
    }

    override fun close() {
        try {
            source.close()
        } catch (_: Exception) {
        }
    }

    // ---- internals ----

    private fun readFully(dst: ByteArray, off: Int, len: Int, position: Long) {
        source.readFully(dst, off, len, position)
    }

    private inner class Cursor(var pos: Long) {
        fun bytes(n: Int): ByteArray {
            val out = ByteArray(n)
            readFully(out, 0, n, pos)
            pos += n
            return out
        }

        fun u8(): Int = bytes(1)[0].toInt() and 0xFF
        fun i8(): Int = bytes(1)[0].toInt()

        fun u16(): Int {
            val b = bytes(2)
            return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        }

        fun i16(): Int = u16().let { if (it >= 0x8000) it - 0x10000 else it }

        fun u32(): Long {
            val b = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN)
            return b.int.toLong() and 0xFFFFFFFFL
        }

        fun i32(): Int {
            val b = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN)
            return b.int
        }

        fun f32(): Float {
            val b = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN)
            return b.float
        }

        fun u64(): Long {
            val b = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN)
            return b.long
        }

        fun i64(): Long = u64()

        fun f64(): Double {
            val b = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN)
            return b.double
        }

        fun bool(): Boolean = u8() != 0

        fun str(): String {
            val len = u64()
            if (len > 16 * 1024 * 1024) throw GgufException("Absurd string length $len")
            return String(bytes(len.toInt()), Charsets.UTF_8)
        }

        fun value(type: Int): GgufValue = when (type) {
            GgufFormat.V_UINT8 -> GgufValue.U8(u8())
            GgufFormat.V_INT8 -> GgufValue.I8(i8())
            GgufFormat.V_UINT16 -> GgufValue.U16(u16())
            GgufFormat.V_INT16 -> GgufValue.I16(i16())
            GgufFormat.V_UINT32 -> GgufValue.U32(u32())
            GgufFormat.V_INT32 -> GgufValue.I32(i32())
            GgufFormat.V_FLOAT32 -> GgufValue.F32(f32())
            GgufFormat.V_BOOL -> GgufValue.Bool(bool())
            GgufFormat.V_STRING -> GgufValue.Str(str())
            GgufFormat.V_ARRAY -> {
                val elemType = u32().toInt()
                val n = u64()
                if (n > 16 * 1024 * 1024) throw GgufException("Absurd array length $n")
                GgufValue.Arr(elemType, List(n.toInt()) { value(elemType) })
            }
            GgufFormat.V_UINT64 -> GgufValue.U64(u64())
            GgufFormat.V_INT64 -> GgufValue.I64(i64())
            GgufFormat.V_FLOAT64 -> GgufValue.F64(f64())
            else -> throw GgufException("Unknown metadata type $type")
        }
    }

    private fun parse() {
        val c = Cursor(0)
        val magic = String(c.bytes(4), Charsets.US_ASCII)
        if (magic != GgufFormat.MAGIC) throw GgufException("Bad magic: '$magic'")
        val version = c.i32()
        if (version != GgufFormat.VERSION) throw GgufException("Unsupported GGUF version $version")
        val nTensors = c.u64()
        val nMeta = c.u64()
        if (nTensors > 100_000 || nMeta > 100_000) throw GgufException("Absurd counts: tensors=$nTensors meta=$nMeta")
        header = GgufHeader(version, nTensors, nMeta)

        val meta = LinkedHashMap<String, GgufValue>(nMeta.toInt().coerceAtLeast(16))
        repeat(nMeta.toInt()) {
            val key = c.str()
            val type = c.u32().toInt()
            meta[key] = c.value(type)
        }
        metadata = meta

        val rawInfos = ArrayList<RawTensor>(nTensors.toInt().coerceAtLeast(0))
        repeat(nTensors.toInt()) {
            val name = c.str()
            val nDims = c.u32().toInt()
            if (nDims > 8) throw GgufException("Tensor $name has absurd rank $nDims")
            val dims = LongArray(nDims) { c.u64() }
            val dtype = c.u32().toInt()
            val offset = c.u64()
            rawInfos.add(RawTensor(name, dims, dtype, offset))
        }

        val align = alignment
        dataBaseOffset = alignUp(c.pos, align)
        tensors = rawInfos.map { raw ->
            val dt = GgmlTypes.of(raw.dtype)
            val elements = if (raw.dims.isEmpty()) 1L else raw.dims.fold(1L) { a, d -> a * d }
            if (elements % dt.blockLength != 0L) {
                throw GgufException("Tensor ${raw.name} element count $elements not a multiple of block ${dt.blockLength}")
            }
            val bytes = elements / dt.blockLength * dt.typeSizeBytes
            val abs = dataBaseOffset + raw.offset
            if (abs < 0 || abs + bytes > fileSize) {
                throw GgufException("Tensor ${raw.name} payload out of bounds")
            }
            TensorInfo(raw.name, raw.dims, raw.dtype, abs, bytes)
        }
    }

    private data class RawTensor(val name: String, val dims: LongArray, val dtype: Int, val offset: Long)

    companion object {
        fun alignUp(v: Long, align: Long): Long {
            if (align <= 0) return v
            val r = v % align
            return if (r == 0L) v else v + (align - r)
        }

        fun open(file: File): GgufReader {
            if (!file.isFile || file.length() < 24) throw GgufException("Not a GGUF file: ${file.path}")
            return open(FileSeekableSource(file))
        }

        fun open(source: SeekableSource): GgufReader {
            val reader = GgufReader(source)
            try {
                reader.parse()
            } catch (e: Exception) {
                try {
                    source.close()
                } catch (_: Exception) {
                }
                throw if (e is GgufException) e else GgufException("Parse failed: ${e.message}", e)
            }
            return reader
        }
    }
}
