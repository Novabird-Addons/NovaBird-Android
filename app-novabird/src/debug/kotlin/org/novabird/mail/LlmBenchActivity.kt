package org.novabird.mail

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.novabird.feature.vectors.ai.MailPrompts

/**
 * Debug-only test of a language model on the phone (Gemma 4 E2B with LiteRT-LM): load time, time to the first word,
 * words per second and memory for the summaries of test mails from the desktop suite (assets/llm-bench.json, same
 * instruction as the Helper). Model: adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/org.novabird.mail.debug/files/
 * Start: `adb shell am start -n org.novabird.mail.debug/org.novabird.mail.LlmBenchActivity --es configs cpu,gpu`.
 * Results in files/llm-bench.txt.
 */
class LlmBenchActivity : Activity() {
    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(view) })
        val configs = (intent.getStringExtra("configs") ?: "cpu").split(',')
        thread(name = "LlmBench") { run(configs) }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        File(filesDir, "llm-bench.txt").appendText(line + "\n")
        runOnUiThread { view.append(line + "\n") }
    }

    private fun pssMb() = Debug.getPss() / 1024

    @Suppress("TooGenericExceptionCaught")
    private fun run(configs: List<String>) {
        val model = File(getExternalFilesDir(null), "gemma-4-E2B-it.litertlm")
        if (!model.isFile) {
            log("no model: ${model.path}")
            return
        }
        val cases = Json.parseToJsonElement(assets.open("llm-bench.json").bufferedReader().readText())
            .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        for (config in configs) {
            try {
                bench(config, model, cases.map { c -> c.mapValues { it.value.jsonPrimitive.content } })
            } catch (e: Throwable) {
                log("$config: FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        log("done")
    }

    private fun bench(name: String, model: File, cases: List<Map<String, String>>) {
        val backend = when {
            name == "gpu" -> Backend.GPU()
            name.startsWith("cpu") && name.length > 3 -> Backend.CPU(threadCount = name.drop(3).toInt())
            else -> Backend.CPU()
        }
        val before = pssMb()
        val t0 = SystemClock.elapsedRealtime()
        val config =
            EngineConfig(modelPath = model.path, backend = backend, maxNumTokens = MAX_TOKENS, cacheDir = cacheDir.path)
        Engine(config)
            .use { engine ->
                engine.initialize()
                log("$name: loaded in ${SystemClock.elapsedRealtime() - t0} ms, PSS $before -> ${pssMb()} MB")
                for (c in cases) {
                    val header = MailPrompts.header(c.getValue("subject"), c.getValue("from"), c.getValue("date"))
                    val prompt = MailPrompts.summary(c.getValue("lang"), false, header, c.getValue("text"))
                    val conversation = ConversationConfig(
                        systemInstruction = Contents.of(prompt.messages[0].content),
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2),
                    )
                    engine.createConversation(conversation).use { conv ->
                        val text = StringBuilder()
                        var pieces = 0
                        var first = -1L
                        var peak = 0L
                        val start = SystemClock.elapsedRealtime()
                        runBlocking {
                            conv.sendMessageAsync(Contents.of(prompt.messages[1].content)).collect { m ->
                                if (first < 0) first = SystemClock.elapsedRealtime() - start
                                text.append(m.toString())
                                pieces++
                                if (pieces % 16 == 0) peak = maxOf(peak, pssMb())
                            }
                        }
                        val total = SystemClock.elapsedRealtime() - start
                        val perSec = if (total > first) (pieces - 1) * 1000.0 / (total - first) else 0.0
                        log(
                            "$name ${c["id"]}: first $first ms, ${"%.1f".format(perSec)} pieces/s, " +
                                "${text.length} chars in $total ms, peak PSS ${maxOf(peak, pssMb())} MB",
                        )
                        log("--- ${c["id"]} ---\n$text\n---")
                    }
                }
            }
    }

    private companion object {
        const val TAG = "NovaBirdLlmBench"
        const val MAX_TOKENS = 4096
    }
}
