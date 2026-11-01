package org.novabird.mail

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.novabird.feature.vectors.BpeTokenizer
import org.novabird.feature.vectors.EmbedDevice
import org.novabird.feature.vectors.EmbeddingModel
import org.novabird.feature.vectors.OnnxEmbedder
import org.novabird.feature.vectors.cosine

/**
 * Debug-only vector benchmark: tokenizer and vectors on the phone vs the PC reference (assets/golden.json).
 * Model files go to files/models/jina-de. Start with
 * `adb shell am start -n org.novabird.mail.debug/org.novabird.mail.VectorBenchActivity --es configs cpu4,nnapi512`.
 */
class VectorBenchActivity : Activity() {
    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(view) })
        val configs = (intent.getStringExtra("configs") ?: "cpu4").split(',')
        thread(name = "VectorBench") { run(configs) }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        File(filesDir, "bench.txt").appendText(line + "\n")
        runOnUiThread { view.append(line + "\n") }
    }

    private class Case(val text: String, val ids: List<Long>, val vector: FloatArray)

    private fun run(configs: List<String>) {
        try {
            val dir = File(filesDir, "models/jina-de")
            val golden = Json.parseToJsonElement(assets.open("golden.json").bufferedReader().readText()).jsonObject
            val cases = golden.getValue("cases").jsonArray.map { c ->
                val o = c.jsonObject
                Case(
                    o.getValue("text").jsonPrimitive.content,
                    o.getValue("ids").jsonArray.map { it.jsonPrimitive.long },
                    o.getValue("vector").jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
                )
            }
            var t = SystemClock.elapsedRealtime()
            val tokenizer = BpeTokenizer(File(dir, "tokenizer.json").readText())
            log("tokenizer load ${SystemClock.elapsedRealtime() - t} ms")
            t = SystemClock.elapsedRealtime()
            val ids = cases.map { tokenizer.encode(it.text, EmbeddingModel.JINA_DE.maxTokens) }
            val idsOk = cases.indices.count { ids[it].toList() == cases[it].ids }
            log("tokenize ${SystemClock.elapsedRealtime() - t} ms, ids equal $idsOk/${cases.size}")

            val model = File(dir, EmbeddingModel.JINA_DE.onnxPath)
            val vectors = HashMap<String, List<FloatArray>>()
            for (config in configs) {
                vectors[config] = bench(config, model, cases, ids)
            }
            // Deviation between configs (e.g. CPU vs NNAPI)
            val names = vectors.keys.toList()
            for (i in names.indices) {
                for (j in i + 1 until names.size) {
                    val a = vectors.getValue(names[i])
                    val b = vectors.getValue(names[j])
                    val cos = a.indices.map { cosine(a[it], b[it]) }
                    log("${names[i]} vs ${names[j]}: min cos ${"%.6f".format(cos.min())}")
                }
            }
            log("DONE")
        } catch (e: Throwable) {
            Log.e(TAG, "bench failed", e)
            log("FAILED: $e")
        }
    }

    private fun bench(config: String, model: File, cases: List<Case>, ids: List<LongArray>): List<FloatArray> {
        val (device, threads, fixed) = parse(config)
        val pssBefore = Debug.getPss()
        var t = SystemClock.elapsedRealtime()
        log("$config: creating session")
        OnnxEmbedder(model, device, threads, fixed).use { embedder ->
            val load = SystemClock.elapsedRealtime() - t
            log("$config: session ready in $load ms, warm-up")
            embedder.embed(ids[0]) // warm-up
            val out = ArrayList<FloatArray>()
            val times = ArrayList<Long>()
            for (i in cases.indices) {
                File(filesDir, "bench.txt").appendText("  $config case $i (${ids[i].size} tokens)\n")
                t = SystemClock.elapsedRealtime()
                out += embedder.embed(ids[i])
                times += SystemClock.elapsedRealtime() - t
            }
            val cos = cases.indices.map { cosine(out[it], cases[it].vector) }
            val pss = (Debug.getPss() - pssBefore) / 1024
            log(
                "$config: load $load ms, +PSS $pss MB, ms per text ${times.joinToString(",")} " +
                    "(tokens ${ids.joinToString(",") { it.size.toString() }}), " +
                    "cos vs PC min ${"%.6f".format(cos.min())} mean ${"%.6f".format(cos.average())}",
            )
            return out
        }
    }

    // cpu4, cpu8, xnn4, nnapi512, nnapinocpu512, webgpu0 (dynamic shapes), webgpu512
    private fun parse(config: String): Triple<EmbedDevice, Int, Int?> {
        val m = Regex("([a-z]+?)(\\d+)").matchEntire(config) ?: error("bad config $config")
        val n = m.groupValues[2].toInt()
        return when (m.groupValues[1]) {
            "cpu" -> Triple(EmbedDevice.CPU, n, null)
            "xnn" -> Triple(EmbedDevice.XNNPACK, n, null)
            "nnapi" -> Triple(EmbedDevice.NNAPI, 4, n)
            "nnapinocpu" -> Triple(EmbedDevice.NNAPI_NO_CPU, 4, n)
            "webgpu" -> Triple(EmbedDevice.WEBGPU, 4, n.takeIf { it > 0 })
            else -> error("bad config $config")
        }
    }

    private companion object {
        const val TAG = "NovaBirdVec"
    }
}
