package com.localllm.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal GGUF v3 writer for engine tests: metadata entries, F32/Q8_0 tensors
 * (including the 3-D expert layout `[D0, D1, nExperts]` the pager walks),
 * 32-byte alignment.
 */
class GgufTestWriter {

    private class TensorSpec(val name: String, val dims: LongArray, val dtypeId: Int, val payload: ByteArray) {
        var offset: Long = 0
    }

    private val metadata = mutableListOf<(ByteArrayOutputStream) -> Unit>()
    private val tensors = mutableListOf<TensorSpec>()

    fun metaString(key: String, value: String) = apply {
        metadata.add { out ->
            out.le32(GgufFormat.V_STRING)
            out.str(value)
        }
        keys.add(key)
    }

    fun metaU32(key: String, value: Long) = apply {
        metadata.add { out ->
            out.le32(GgufFormat.V_UINT32)
            out.le32(value)
        }
        keys.add(key)
    }

    fun metaI32(key: String, value: Int) = apply {
        metadata.add { out ->
            out.le32(GgufFormat.V_INT32)
            out.le32(value.toLong())
        }
        keys.add(key)
    }

    fun metaF32(key: String, value: Float) = apply {
        metadata.add { out ->
            out.le32(GgufFormat.V_FLOAT32)
            out.le32(value.toRawBits().toLong())
        }
        keys.add(key)
    }

    fun metaStringArray(key: String, values: List<String>) = apply {
        metadata.add { out ->
            out.le32(GgufFormat.V_ARRAY)
            out.le32(GgufFormat.V_STRING.toLong())
            out.le64(values.size.toLong())
            values.forEach { out.str(it) }
        }
        keys.add(key)
    }

    fun f32Tensor(name: String, dims: LongArray, values: FloatArray) = apply {
        require(dims.fold(1L) { a, d -> a * d } == values.size.toLong()) {
            "Tensor $name dims ${dims.toList()} do not match ${values.size} values"
        }
        val bb = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) bb.putFloat(v)
        tensors.add(TensorSpec(name, dims, GgufFormat.F32, bb.array()))
    }

    /** Q8_0-quantized tensor; element count must be a multiple of 32. */
    fun q80Tensor(name: String, dims: LongArray, values: FloatArray) = apply {
        val n = dims.fold(1L) { a, d -> a * d }
        require(n == values.size.toLong()) {
            "Tensor $name dims ${dims.toList()} do not match ${values.size} values"
        }
        require(n % 32 == 0L) { "Tensor $name has $n values, not a Q8_0 multiple of 32" }
        val payload = ByteArray((n / 32 * 34).toInt())
        Quant.quantizeQ80Row(values, 0, payload, 0, n.toInt())
        tensors.add(TensorSpec(name, dims, GgufFormat.Q8_0, payload))
    }

    private val keys = mutableListOf<String>()

    fun build(file: File): File {
        var relative = 0L
        for (tensor in tensors) {
            tensor.offset = relative
            relative = alignUp(relative + tensor.payload.size)
        }

        val out = ByteArrayOutputStream()
        out.write(GgufFormat.MAGIC.toByteArray(Charsets.US_ASCII))
        out.le32(GgufFormat.VERSION.toLong())
        out.le64(tensors.size.toLong())
        out.le64(metadata.size.toLong())
        metadata.forEachIndexed { index, write ->
            out.str(keys[index])
            write(out)
        }
        for (tensor in tensors) {
            out.str(tensor.name)
            out.le32(tensor.dims.size.toLong())
            tensor.dims.forEach { out.le64(it) }
            out.le32(tensor.dtypeId.toLong())
            out.le64(tensor.offset)
        }
        while (out.size() % GgufFormat.DEFAULT_ALIGNMENT != 0L) out.write(0)
        for (tensor in tensors) {
            out.write(tensor.payload)
            while (out.size() % GgufFormat.DEFAULT_ALIGNMENT != 0L) out.write(0)
        }

        file.writeBytes(out.toByteArray())
        return file
    }

    private fun alignUp(value: Long, alignment: Long = GgufFormat.DEFAULT_ALIGNMENT): Long {
        val remainder = value % alignment
        return if (remainder == 0L) value else value + (alignment - remainder)
    }

    private fun ByteArrayOutputStream.le32(value: Int) = le32(value.toLong())

    private fun ByteArrayOutputStream.le32(value: Long) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())
    }

    private fun ByteArrayOutputStream.le64(value: Long) {
        write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
    }

    private fun ByteArrayOutputStream.str(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        le64(bytes.size.toLong())
        write(bytes)
    }
}
