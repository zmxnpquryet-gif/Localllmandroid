// SDengine — golden harness against llama.cpp logits on the same GGUF.
//
// The golden JSON is produced by engine/tools/golden/golden_gen.py (llama.cpp
// llama-server, greedy decoding, top-20 logprobs). Raw logits are not exposed
// by the server API; logprobs are, and logit gaps (logit_i - logit_max) are
// exactly logprob_i - logprob_max, so gap errors compare the two engines
// without knowing llama.cpp's softmax normalizer.
//
// Manual/nightly only: without LOCALENGINE_GOLDEN_MODEL (the GGUF) the engine
// test aborts via Assume — normal CI stays green without multi-GB downloads.
// The golden JSON comes from LOCALENGINE_GOLDEN_JSON or the committed copy in
// src/test/resources, so the structure test runs everywhere.
//
// ACCURACY GATES (tokenizer ids match, top-1 >= 99%, greedy continuation
// exact) are the Phase 1 completion criteria from the SDengine correctness
// plan: this test is expected to be RED until the transformer fixes land.
package com.localllm.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class GoldenLogitTest {

    companion object {
        private const val CLASSPATH_GOLDEN = "golden/qwen3-30b-a3b-q4km.json"

        /** env LOCALENGINE_<NAME> / system property localengine.<name> → existing file, or null. */
        private fun envFile(name: String): File? {
            val path = System.getProperty("localengine.$name", "").ifBlank {
                System.getenv("LOCALENGINE_${name.uppercase()}") ?: ""
            }.ifBlank { return null }
            val f = File(path)
            return if (f.isFile && f.length() > 1024) f else null
        }

        private fun modelFile(): File? = envFile("golden_model")
        private fun goldenFile(): File? = envFile("golden_json")

        /** Golden copy committed with the repo (structure checks without the model). */
        private fun classpathGolden(): String? =
            GoldenLogitTest::class.java.classLoader
                ?.getResourceAsStream(CLASSPATH_GOLDEN)?.use { it.readBytes().toString(Charsets.UTF_8) }

        private class GoldenPrompt(
            val prompt: String,
            val promptIds: IntArray,
            val top20: List<Pair<Int, Double>>,
            val greedyIds: IntArray
        ) {
            val goldenTop1Id: Int get() = top20.first().first
            val goldenTop1Logprob: Double get() = top20.first().second
        }

        private class PromptMetrics(
            val prompt: String,
            val tokenizerMatch: Boolean,
            val top1LastPosMatch: Boolean,
            val top1StepsMatched: Int,
            val steps: Int,
            val top20Overlap: Int,
            val gapMean: Double,
            val gapMax: Double,
            val greedyPrefix: Int,
            val greedyExact: Boolean
        )

        private fun parseGolden(text: String): Pair<JSONObject, List<GoldenPrompt>> {
            val root = JSONObject(text)
            val arr = root.getJSONArray("prompts")
            val prompts = ArrayList<GoldenPrompt>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val idArr = p.getJSONArray("prompt_ids")
                val ids = IntArray(idArr.length()) { idArr.getInt(it) }
                val top = ArrayList<Pair<Int, Double>>()
                val t20 = p.optJSONArray("top20_last_prompt_pos") ?: JSONArray()
                for (j in 0 until t20.length()) {
                    val e = t20.getJSONObject(j)
                    top.add(Pair(e.getInt("id"), e.getDouble("logprob")))
                }
                val g = p.getJSONArray("greedy")
                val gids = IntArray(g.length()) { g.getJSONObject(it).getInt("id") }
                prompts.add(GoldenPrompt(p.getString("prompt"), ids, top, gids))
            }
            return Pair(root, prompts)
        }

        /**
         * Runs one golden prompt through the engine with the GOLDEN token ids
         * (so transformer math is measured independently of tokenizer drift —
         * tokenizer agreement is tracked separately as [PromptMetrics.tokenizerMatch]).
         */
        private fun runPrompt(engine: SDEngine, golden: GoldenPrompt, maxTokens: Int): PromptMetrics {
            var step0Logits: FloatArray? = null
            val perStepArgmax = ArrayList<Int>(maxTokens)
            val result = engine.generateFromTokenIds(
                golden.promptIds, maxTokens,
                SampleConfig(temperature = 0f),
                onLogits = { logits, step ->
                    if (step == 0) step0Logits = logits.copyOf()
                    else perStepArgmax.add(argmax(logits))
                }
            )
            val tokenizerMatch =
                engine.encodePrompt(golden.prompt, addBos = false).contentEquals(golden.promptIds)

            val logits = step0Logits ?: error("no step-0 logits captured")
            // Top-20 comparison anchored at the GOLDEN top-1 id: per-id logit
            // differences stay visible even when the argmax flips.
            val anchorId = golden.goldenTop1Id
            val anchorLogit = logits[anchorId].toDouble()
            val engineTop20 = topNIndices(logits, 20).toHashSet()
            var overlap = 0
            var gapSum = 0.0
            var gapMax = 0.0
            for ((id, logprob) in golden.top20) {
                if (id in engineTop20) overlap++
                val gap = (logits[id] - anchorLogit) - (logprob - golden.goldenTop1Logprob)
                gapSum += abs(gap)
                gapMax = maxOf(gapMax, abs(gap))
            }
            val top1Match = argmax(logits) == golden.goldenTop1Id

            // Greedy continuation: engine argmax chain vs llama.cpp greedy ids.
            // Step 0's argmax compares against greedy[0]; each captured step's
            // argmax against the following golden id.
            val greedyEngine = result.tokenIds
            var prefix = 0
            while (prefix < golden.greedyIds.size && prefix < greedyEngine.size &&
                golden.greedyIds[prefix] == greedyEngine[prefix]
            ) prefix++
            var stepsMatched = if (top1Match) 1 else 0
            val followIds = golden.greedyIds.drop(1)
            for (i in 0 until minOf(perStepArgmax.size, followIds.size)) {
                if (perStepArgmax[i] == followIds[i]) stepsMatched++
            }
            val steps = 1 + minOf(perStepArgmax.size, followIds.size)
            return PromptMetrics(
                prompt = golden.prompt,
                tokenizerMatch = tokenizerMatch,
                top1LastPosMatch = top1Match,
                top1StepsMatched = stepsMatched,
                steps = steps,
                top20Overlap = overlap,
                gapMean = if (golden.top20.isEmpty()) 0.0 else gapSum / golden.top20.size,
                gapMax = gapMax,
                greedyPrefix = prefix,
                greedyExact = greedyEngine.toIntArray().contentEquals(golden.greedyIds)
            )
        }

        private fun argmax(logits: FloatArray): Int {
            var best = 0
            for (i in 1 until logits.size) if (logits[i] > logits[best]) best = i
            return best
        }

        private fun topNIndices(logits: FloatArray, n: Int): List<Int> =
            logits.indices.sortedByDescending { logits[it] }.take(n)
    }

    @Test
    fun `golden json structure is valid`() {
        val text = goldenFile()?.readText() ?: classpathGolden()
        assumeTrue(
            "needs a golden JSON: set LOCALENGINE_GOLDEN_JSON or commit $CLASSPATH_GOLDEN",
            text != null
        )
        requireNotNull(text)
        val (root, prompts) = parseGolden(text)
        val gen = root.getJSONObject("generator")
        assertTrue("llama.cpp version must be recorded", gen.getString("llama_cpp_version").isNotBlank())
        val model = root.getJSONObject("model")
        assertTrue(model.getLong("bytes") > 0)
        assertTrue("arch must be recorded", model.getString("arch").isNotBlank())
        assertTrue("need >= 3 golden prompts, have ${prompts.size}", prompts.size >= 3)
        for (p in prompts) {
            assertTrue("empty prompt ids for ${p.prompt}", p.promptIds.isNotEmpty())
            assertTrue("top-20 too small (${p.top20.size}) for ${p.prompt}", p.top20.size >= 10)
            for (j in 1 until p.top20.size) {
                assertTrue(
                    "golden top-20 not sorted desc at $j for ${p.prompt}",
                    p.top20[j - 1].second >= p.top20[j].second
                )
            }
            assertTrue("no greedy tokens for ${p.prompt}", p.greedyIds.isNotEmpty())
        }
        println("golden OK: ${prompts.size} prompts, arch=${model.getString("arch")}")
    }

    @Test
    fun `sdengine matches llama-cpp golden logits and greedy continuation`() {
        val model = modelFile()
        val text = goldenFile()?.readText() ?: classpathGolden()
        assumeTrue(
            "needs LOCALENGINE_GOLDEN_MODEL plus a golden JSON (manual/nightly only)",
            model != null && text != null
        )
        requireNotNull(model)
        requireNotNull(text)
        val (root, prompts) = parseGolden(text)
        val maxTokens = root.getJSONObject("params").getInt("max_tokens")
        val metrics = ArrayList<PromptMetrics>(prompts.size)
        SDEngine().use { engine ->
            val info = engine.openModel(model, residentCapBytes = 7L * 1024 * 1024 * 1024)
            println(
                "model arch=${info.arch} vocab=${info.vocabSize} layers=${info.nLayers} " +
                    "experts=${info.nExperts} denseResidentMB=${info.denseResidentBytes / 1048576}"
            )
            assertEquals(
                "golden model arch != loaded arch",
                root.getJSONObject("model").getString("arch"),
                info.arch
            )
            for (g in prompts) metrics.add(runPrompt(engine, g, maxTokens))
        }

        for (m in metrics) {
            println(
                "prompt=${m.prompt.take(32).replace('\n', ' ')} tok=${m.tokenizerMatch} " +
                    "top1=${m.top1LastPosMatch} top1Rate=%.3f (%d/%d) top20=%d/20 " +
                    "gapMean=%.4f gapMax=%.4f greedyPrefix=${m.greedyPrefix} greedyExact=${m.greedyExact}"
                        .format(
                            m.top1StepsMatched.toDouble() / m.steps, m.top1StepsMatched, m.steps,
                            m.top20Overlap, m.gapMean, m.gapMax
                        )
            )
        }
        val meanGap = metrics.map { it.gapMean }.average()
        val maxGap = metrics.maxOf { it.gapMax }
        println("SUMMARY: prompts=${metrics.size} meanGapError=%.4f maxGapError=%.4f".format(meanGap, maxGap))

        // ---- accuracy gates (Phase 1 completion criteria) ----
        var matched = 0
        var steps = 0
        for (m in metrics) {
            matched += m.top1StepsMatched
            steps += m.steps
        }
        val rate = matched.toDouble() / steps
        val allGreedyExact = metrics.all { it.greedyExact }
        val allTokenizerMatch = metrics.all { it.tokenizerMatch }
        println(
            "GATES: top1=%.4f (%d/%d) greedyExact=%s tokenizerMatch=%s".format(
                rate, matched, steps, allGreedyExact, allTokenizerMatch
            )
        )
        assertTrue(
            "tokenizer ids must match llama.cpp (first mismatch: ${firstTokenizerMismatch(metrics)})",
            allTokenizerMatch
        )
        assertTrue("top-1 match rate %.4f below 0.99".format(rate), rate >= 0.99)
        assertTrue("greedy continuation must match llama.cpp exactly", allGreedyExact)
    }

    private fun firstTokenizerMismatch(metrics: List<PromptMetrics>): String =
        metrics.firstOrNull { !it.tokenizerMatch }?.prompt ?: "none"
}
