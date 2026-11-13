package org.novabird.feature.protection

import java.io.File
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Runs the original node tests of the add-on (`novabird-phishing/tests`, unchanged) under QuickJS against the bundled
 * core. `node:test` and `node:assert/strict` are replaced by small shims (`novabird-protection-test/`).
 */
class AddonTestSuiteTest {

    @Test
    fun `analyzer`() = runSuite("analyzer.test.js")

    @Test
    fun `anchors`() = runSuite("anchors.test.js")

    @Test
    fun `ai facts`() = runSuite("ai.test.js")

    @Test
    fun `files`() = runSuite("files.test.js")

    private fun runSuite(file: String) = runBlocking {
        val results = runJsSuite("$TEST_ROOT/$file")
        println("$file: ${results.count { it.ok }}/${results.size} bestanden, ${results.sumOf { it.ms }} ms")
        val failed = results.filter { !it.ok }
        if (results.isEmpty()) fail("$file: keine Tests gefunden")
        if (failed.isNotEmpty()) {
            fail(failed.joinToString("\n", "$file: ${failed.size} fehlgeschlagen\n") { "- ${it.name}: ${it.error}" })
        }
    }

    private data class CaseResult(val name: String, val ok: Boolean, val ms: Long, val error: String?)

    private companion object {
        const val TEST_ROOT = "novabird-phishing/tests"
        const val SHIM_ROOT = "novabird-protection-test"
        const val SUITE_TIMEOUT_MILLIS = 120_000L

        val testSources: Map<String, String> by lazy {
            listOf(TEST_ROOT, SHIM_ROOT).flatMap { root ->
                val dir = File(checkNotNull(AddonTestSuiteTest::class.java.classLoader.getResource(root)).toURI())
                dir.walkTopDown().filter { it.isFile }.map { file ->
                    "$root/" + file.relativeTo(dir).invariantSeparatorsPath to file.readText()
                }.toList()
            }.toMap()
        }

        val aliases = mapOf(
            "node:test" to "$SHIM_ROOT/node-test.js",
            "node:assert/strict" to "$SHIM_ROOT/node-assert.js",
        )

        suspend fun runJsSuite(module: String): List<CaseResult> {
            val sources = ProtectionScripts.load() + testSources
            return ProtectionEngine.create(
                sources = sources,
                dispatcher = Dispatchers.Default.limitedParallelism(1),
                aliases = aliases,
                entryModules = listOf(ProtectionEngine.ENTRY_MODULE, "$SHIM_ROOT/setup.js", module),
                // the whole suite of a file runs in one call; under load (parallel Gradle tasks) 15 s were too short
                evaluationTimeoutMillis = SUITE_TIMEOUT_MILLIS,
            ).use { engine ->
                val json = checkNotNull(engine.evaluate("__nodeTestRun()"))
                Json.parseToJsonElement(json).jsonArray.map {
                    val o = it.jsonObject
                    CaseResult(
                        name = o["name"]!!.jsonPrimitive.content,
                        ok = o["ok"]!!.jsonPrimitive.boolean,
                        ms = o["ms"]!!.jsonPrimitive.long,
                        error = o["error"]?.jsonPrimitive?.content,
                    )
                }
            }
        }
    }
}
