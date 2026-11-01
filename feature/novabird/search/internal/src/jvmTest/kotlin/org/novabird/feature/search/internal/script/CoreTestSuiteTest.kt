package org.novabird.feature.search.internal.script

import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import java.io.File
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.novabird.feature.search.internal.db.SearchIndexDatabase

/**
 * Runs the original vitest suites of NovaBird-Search (`novabird-search/tests`, unchanged) under QuickJS against the
 * bundled SQLite. `vitest`, `node:fs` and `helpers/node-adapter.js` are replaced by small shims (`novabird-test/`).
 */
class CoreTestSuiteTest {

    @Test
    fun `parser golden cases`() = runSuite("parser.test.js")

    @Test
    fun `parser language cases`() = runSuite("parser.lang.test.js")

    @Test
    fun `search`() = runSuite("search.test.js")

    @Test
    fun `compounds`() = runSuite("compounds.test.js")

    @Test
    fun `did you mean`() = runSuite("suggest.test.js")

    @Test
    fun `database and repository`() = runSuite("db.test.js")

    @Test
    fun `chips`() = runSuite("chips.test.js")

    @Test
    fun `folding`() = runSuite("fold.test.js")

    @Test
    fun `ranking`() = runSuite("rank.test.js")

    @Test
    fun `record`() = runSuite("record.test.js")

    @Test
    fun `text cleaning`() = runSuite("text.test.js")

    @Test
    fun `terms`() = runSuite("terms.test.js")

    @Test
    fun `health`() = runSuite("health.test.js")

    @Test
    fun `conversation`() = runSuite("conversation.test.js")

    @Test
    fun `indexer (Thunderbird variant, fake messenger)`() = runSuite("indexer.test.js")

    private fun runSuite(file: String) = runBlocking {
        val results = runJsSuite("novabird-search/tests/$file")
        val deviations = results.filter { !it.ok && knownDeviation(it.name) != null }
        val failed = results.filter { !it.ok && knownDeviation(it.name) == null }
        deviations.forEach { println("  bekannte Abweichung: ${it.name}: ${it.error} (${knownDeviation(it.name)})") }
        println(
            "$file: ${results.count { it.ok }}/${results.size} bestanden, ${deviations.size} bekannte Abweichungen, " +
                "${results.sumOf { it.ms }} ms",
        )
        results.filter { it.ms >= SLOW_MS }.forEach { println("  langsam: ${it.name} (${it.ms} ms)") }
        if (results.isEmpty()) fail("$file: keine Tests gefunden")
        if (failed.isNotEmpty()) {
            fail(failed.joinToString("\n", "$file: ${failed.size} fehlgeschlagen\n") { "- ${it.name}: ${it.error}" })
        }
    }

    internal data class CaseResult(val name: String, val ok: Boolean, val ms: Long, val error: String?)

    internal companion object {
        private const val SLOW_MS = 200L

        private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

        /**
         * Cases that cannot pass here, with the reason. Everything else must pass.
         *
         * - QuickJS on a Windows host ignores daylight saving time (getTimezoneOffset is always -60 in Berlin);
         *   on Android it uses localtime_r with tzdata. The date cases themselves pass.
         * - QuickJS is an interpreter: the parser needs a few ms per query instead of < 5 ms with the Node JIT.
         */
        fun knownDeviation(name: String): String? = when {
            isWindows && name.endsWith("läuft in Europe/Berlin") -> "QuickJS unter Windows ohne Sommerzeit"
            name.endsWith("Parser ist schnell (< 5 ms pro Anfrage im Mittel)") -> "QuickJS-Interpreter statt Node-JIT"
            else -> null
        }
        private const val TEST_ROOT = "novabird-search/tests"
        private const val SHIM_ROOT = "novabird-test"

        private val testSources: Map<String, String> by lazy {
            listOf(TEST_ROOT, SHIM_ROOT).flatMap { root ->
                val dir = File(checkNotNull(CoreTestSuiteTest::class.java.classLoader.getResource(root)).toURI())
                dir.walkTopDown().filter { it.isFile }.map { file ->
                    "$root/" + file.relativeTo(dir).invariantSeparatorsPath to file.readText()
                }.toList()
            }.toMap()
        }

        private val aliases = mapOf(
            "vitest" to "$SHIM_ROOT/vitest.js",
            "node:fs" to "$SHIM_ROOT/node-fs.js",
            "$TEST_ROOT/helpers/node-adapter.js" to "$SHIM_ROOT/node-adapter.js",
        )

        // import.meta.url is the bare module name in quickjs-kt, so relative paths may arrive without their directory.
        private fun readTestFile(sources: Map<String, String>, path: String): String {
            sources[path]?.let { return it }
            val matches = sources.keys.filter { it.startsWith("$TEST_ROOT/") && it.endsWith("/$path") }
            return sources.getValue(matches.singleOrNull() ?: error("Datei fehlt: $path"))
        }

        suspend fun runJsSuite(module: String): List<CaseResult> {
            val sources = ScriptSources.load() + testSources
            val timers = mutableMapOf<Long, CompletableDeferred<Unit>>()
            return SearchIndexDatabase(":memory:").use { main ->
                NovaBirdScriptEngine.create(
                    sources = sources,
                    mainDatabase = main,
                    dispatcher = Dispatchers.Default.limitedParallelism(1),
                    openDatabase = { name -> SearchIndexDatabase(name) },
                    aliases = aliases,
                    entryModules = listOf("$SHIM_ROOT/setup.js", "$SHIM_ROOT/vitest.js", module),
                    extraBindings = {
                        function("__nbNanoTime") { System.nanoTime().toDouble() }
                        function("__nbReadFile") { args ->
                            readTestFile(sources, args[0] as String)
                        }
                        asyncFunction("__nbSleep") { args ->
                            val id = (args[0] as Number).toLong()
                            val done = CompletableDeferred<Unit>().also { timers[id] = it }
                            withTimeoutOrNull((args[1] as Number).toLong()) { done.await() }
                            timers.remove(id)
                            null
                        }
                        function("__nbCancelSleep") { args ->
                            timers.remove((args[0] as Number).toLong())?.complete(Unit)
                            null
                        }
                    },
                ).use { engine ->
                    val json = checkNotNull(engine.evaluate("__vitestRun()"))
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
}
