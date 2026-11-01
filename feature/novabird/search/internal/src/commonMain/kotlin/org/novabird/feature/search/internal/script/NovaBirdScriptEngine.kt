package org.novabird.feature.search.internal.script

import com.dokar.quickjs.ModuleContent
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.moduleLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.novabird.feature.search.internal.db.SearchIndexDatabase

/**
 * Runs the NovaBird-Search core (JavaScript, unchanged from the PC) in QuickJS against [SearchIndexDatabase]s.
 *
 * JavaScript reaches the databases through `__nbExecute`, `__nbSelect` and `__nbBatch` (see `glue.js`). Handle 0 is
 * the main database. All calls are serialized and run on the dispatcher given to [create], which must not run tasks in
 * parallel: QuickJS and the SQLite connections are single-threaded.
 */
internal class NovaBirdScriptEngine private constructor(
    private val quickJs: QuickJs,
    private val dispatcher: CoroutineDispatcher,
    private val databases: MutableList<SearchIndexDatabase?>,
) : AutoCloseable {
    private val mutex = Mutex()

    private var result: Result<String?>? = null

    /**
     * Evaluates the expression [code] (script, not module) and returns its value as text. If the value is a Promise,
     * its result is returned; a rejection is thrown as [ScriptException].
     */
    suspend fun evaluate(code: String): String? = mutex.withLock {
        withContext(dispatcher) {
            result = null
            quickJs.evaluate<Any?>(
                "Promise.resolve($code).then(__nbResolve, (e) => __nbReject(String(e?.stack ? e + \"\\n\" + e.stack : e)))",
                SCRIPT_NAME,
                false,
            )
            checkNotNull(result) { "JavaScript-Aufruf ohne Ergebnis" }.getOrThrow()
        }
    }

    private fun bindResult() {
        quickJs.function("__nbResolve") { args ->
            result = Result.success(args.getOrNull(0)?.toString())
            null
        }
        quickJs.function("__nbReject") { args ->
            result = Result.failure(ScriptException(args.getOrNull(0)?.toString() ?: "Fehler"))
            null
        }
    }

    init {
        bindResult()
    }

    /** Runs [block] on the main database, serialized with all script calls. */
    suspend fun <T> withMainDatabase(block: (SearchIndexDatabase) -> T): T = mutex.withLock {
        withContext(dispatcher) { block(checkNotNull(databases[0])) }
    }

    /** Calls `NovaBird.<name>(...)`; each argument is passed as a JS string. */
    suspend fun call(name: String, vararg args: String): String? {
        val arguments = args.joinToString(", ") { jsString(it) }
        return evaluate("NovaBird.$name($arguments)")
    }

    /** Closes QuickJS and all databases opened from JavaScript; the main database belongs to the caller. */
    override fun close() {
        quickJs.close()
        databases.drop(1).forEach { it?.close() }
    }

    companion object {
        const val ENTRY_MODULE = "novabird-android/glue.js"
        private const val SCRIPT_NAME = "novabird-call.js"
        private const val BOOT_NAME = "novabird-boot.js"

        /**
         * @param sources module name (path relative to the script root, e.g. `novabird-search/src/search.js`) to code.
         * @param mainDatabase database with handle 0.
         * @param openDatabase if set, JavaScript may open further databases with `__nbOpen(name)` (tests only).
         * @param aliases module names mapped to other module names in [sources] (tests, e.g. `vitest`).
         * @param entryModules modules imported at start; they register their API on `globalThis`.
         * @param extraBindings further functions for JavaScript (tests).
         */
        @Suppress("LongParameterList")
        suspend fun create(
            sources: Map<String, String>,
            mainDatabase: SearchIndexDatabase,
            dispatcher: CoroutineDispatcher,
            openDatabase: ((String) -> SearchIndexDatabase)? = null,
            aliases: Map<String, String> = emptyMap(),
            entryModules: List<String> = listOf(ENTRY_MODULE),
            extraBindings: QuickJs.() -> Unit = {},
        ): NovaBirdScriptEngine = withContext(dispatcher) {
            val loader = moduleLoader {
                normalize { baseName, requestedName -> resolveModuleName(baseName, requestedName, aliases) }
                load { name -> sources[name]?.let(ModuleContent::Source) }
            }
            val databases = mutableListOf<SearchIndexDatabase?>(mainDatabase)
            val quickJs = QuickJs.create(dispatcher, loader)
            try {
                quickJs.bindDatabases(databases, openDatabase)
                quickJs.extraBindings()
                val boot = entryModules.joinToString("\n") { "import ${jsString(it)};" }
                quickJs.evaluate<Any?>(boot, BOOT_NAME, true)
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                quickJs.close()
                throw e
            }
            NovaBirdScriptEngine(quickJs, dispatcher, databases)
        }

        private fun QuickJs.bindDatabases(
            databases: MutableList<SearchIndexDatabase?>,
            openDatabase: ((String) -> SearchIndexDatabase)?,
        ) {
            fun database(handle: Any?): SearchIndexDatabase {
                val index = (handle as Number).toInt()
                return checkNotNull(databases.getOrNull(index)) { "Datenbank $index ist nicht offen" }
            }
            function("__nbExecute") { args ->
                database(args[0]).execute(args[1] as String, parseArray(args[2])).toDouble()
            }
            function("__nbSelect") { args ->
                database(args[0]).select(args[1] as String, parseArray(args[2])).toString()
            }
            function("__nbBatch") { args ->
                database(args[0]).batch(parseArray(args[1]))
                null
            }
            if (openDatabase != null) {
                function("__nbOpen") { args ->
                    databases += openDatabase(args[0] as String)
                    databases.lastIndex.toDouble()
                }
                function("__nbClose") { args ->
                    val index = (args[0] as Number).toInt()
                    if (index > 0) databases[index]?.close()
                    if (index > 0) databases[index] = null
                    null
                }
            }
        }

        private fun parseArray(value: Any?): JsonArray = Json.parseToJsonElement(value as String) as JsonArray

        /** JSON string literal; valid JavaScript (ES2019+) for any input, so no code can be injected. */
        fun jsString(value: String): String = JsonPrimitive(value).toString()

        internal fun resolveModuleName(baseName: String, requestedName: String, aliases: Map<String, String>): String {
            val name = if (requestedName.startsWith("./") || requestedName.startsWith("../")) {
                val parts = baseName.split('/').dropLast(1).toMutableList()
                for (segment in requestedName.split('/')) {
                    when (segment) {
                        ".", "" -> Unit
                        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                        else -> parts += segment
                    }
                }
                parts.joinToString("/")
            } else {
                requestedName
            }
            return aliases[name] ?: name
        }
    }
}
