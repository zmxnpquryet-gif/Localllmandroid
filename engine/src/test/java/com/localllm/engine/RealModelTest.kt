// SDengine — real-file verification. Runs only when LOCALENGINE_MODEL points at a GGUF file.
// Korean sample below is codepoint-built: raw non-ASCII literals in test sources
// have proven unreliable in this toolchain (see TokenizerSamplerTest).
package com.localllm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RealModelTest {

    private fun koreanHello(): String =
        String(Character.toChars(0xC548)) +
            String(Character.toChars(0xB155)) +
            String(Character.toChars(0xD558)) +
            String(Character.toChars(0xC138)) +
            String(Character.toChars(0xC694))

    private fun modelFile(): File? {
        val path = System.getProperty("localengine.model", "").ifBlank {
            System.getenv("LOCALENGINE_MODEL") ?: ""
        }.ifBlank { return null }
        val f = File(path)
        return if (f.isFile && f.length() > 1024) f else null
    }

    @Test
    fun `real gguf metadata tokenizer and tensor inventory`() {
        val f = modelFile() ?: run {
            println("SKIP: set LOCALENGINE_MODEL to a GGUF file")
            return
        }
        GgufReader.open(f).use { r ->
            println("arch=${r.architecture()} tensors=${r.tensors.size} file=${f.length()}")
            assertEquals("llama", r.architecture())
            val dim = r.archU32("embedding_length") ?: error("no embedding_length")
            assertTrue(dim > 0)
            val layers = r.archU32("block_count") ?: error("no block_count")
            assertTrue(layers > 0)
            println("dim=$dim layers=$layers ffn=${r.archU32("feed_forward_length")}")

            val tok = BpeTokenizer.fromMetadata(r) ?: error("no tokenizer")
            println("vocab=${tok.vocabSize}")
            assertTrue(tok.vocabSize > 1000)
            val enIds = tok.encode("Hello world", addBos = false)
            assertTrue(enIds.isNotEmpty())
            assertEquals("Hello world", tok.decode(enIds))
            val korean = koreanHello()
            val koIds = tok.encode(korean, addBos = false)
            assertTrue(koIds.isNotEmpty())
            assertEquals(korean, tok.decode(koIds))
            println("en tokens=${enIds.size} ko tokens=${koIds.size}")

            val names = r.tensors.map { it.name }.toSet()
            for (required in listOf("token_embd.weight", "output_norm.weight", "blk.0.attn_q.weight")) {
                assertTrue("missing $required", names.contains(required))
            }
            assertTrue(names.contains("blk.${layers - 1}.attn_q.weight"))

            val norm = r.tensors.first { it.name == "output_norm.weight" }
            val floats = FloatArray(norm.elementCount.toInt())
            Quant.dequantizeRow(norm.dtypeId, r.tensorBytes(norm), 0, floats, 0, floats.size)
            assertTrue(floats.all { it.isFinite() })
            println("output_norm[0..3]=${floats.take(4)}")
        }
    }

    @Test
    fun `real model full open behind opt-in flag`() {
        val f = modelFile() ?: run {
            println("SKIP: set LOCALENGINE_MODEL to a GGUF file")
            return
        }
        if (System.getenv("LOCALENGINE_FULL") != "1") {
            println("SKIP full open (set LOCALENGINE_FULL=1 to attempt resident decode)")
            return
        }
        SDEngine().use { e ->
            val info = e.openModel(f, residentCapBytes = 3L * 1024 * 1024 * 1024)
            println("opened=$info resident=${e.residentBytes()}")
            assertTrue(info.nLayers > 0)
            // Dense weights decode lazily on first generate(); openModel only binds
            // names, offsets and caps, so assert the bound payload instead.
            assertTrue(info.tensorBytes > 0)
        }
    }
}
