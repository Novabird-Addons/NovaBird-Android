package org.novabird.feature.protection

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runs the phishing check of the Novabird-phishing add-on (JavaScript, unchanged, see `novabird-phishing/SOURCE.md`)
 * in QuickJS.
 *
 * All calls are serialized and run on the dispatcher given to [create], which must not run tasks in parallel: QuickJS
 * is single-threaded.
 */
class ProtectionEngine private constructor(
    private val quickJs: QuickJs,
    private val dispatcher: CoroutineDispatcher,
) : AutoCloseable {
    private val mutex = Mutex()

    private var result: Result<String?>? = null

    init {
        quickJs.function("__nbResolve") { args ->
            result = Result.success(args.getOrNull(0)?.toString())
            null
        }
        quickJs.function("__nbReject") { args ->
            result = Result.failure(ProtectionScriptException(args.getOrNull(0)?.toString() ?: "Fehler"))
            null
        }
    }

    /**
     * Evaluates the expression [code] (script, not module) and returns its value as text. If the value is a Promise,
     * its result is returned; a rejection is thrown as [ProtectionScriptException].
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

    /** Calls `NovaBirdProtection.<name>(...)`; each argument is passed as a JS string. */
    private suspend fun call(name: String, vararg args: String): String {
        val arguments = args.joinToString(", ") { jsString(it) }
        return checkNotNull(evaluate("NovaBirdProtection.$name($arguments)")) { "$name ohne Ergebnis" }
    }

    /**
     * Builds the features of a message and rates it.
     *
     * @param input `{ headers: { name: [value, …] }, parts: [{ contentType, name?, body? }], qrUrls?, imageTexts?,
     *   attachmentScan? }` as `buildFeatures()` of the add-on expects it.
     * @param context what is known about the sender (see `glue.js`).
     * @param config user settings in the add-on's format (`lib/config.js`); empty means defaults.
     */
    suspend fun check(input: JsonObject, context: JsonObject = EMPTY, config: JsonObject = EMPTY): CheckResult {
        val json = Json.parseToJsonElement(call("check", input.toString(), context.toString(), config.toString()))
        val features = json.jsonObject.getValue("features").jsonObject
        return CheckResult(features, Verdict.fromJson(json.jsonObject.getValue("verdict").jsonObject))
    }

    /** Features of a message (see [check] for [input]); input of [analyze]. */
    suspend fun features(input: JsonObject, config: JsonObject = EMPTY): JsonObject =
        Json.parseToJsonElement(call("features", input.toString(), config.toString())).jsonObject

    /** Sets the device contacts used by every following check. */
    suspend fun setContacts(contacts: List<DeviceContact>) {
        val json = JsonArray(
            contacts.map { contact ->
                JsonObject(
                    mapOf(
                        "names" to JsonArray(contact.names.map(::JsonPrimitive)),
                        "emails" to JsonArray(contact.emails.map(::JsonPrimitive)),
                    ),
                )
            },
        )
        call("setContacts", json.toString())
    }

    /** Sets the sender history (`{ address: { n, sent, names, first, last, flagged? } }`) for every following check. */
    suspend fun setSenderIndex(senderIndex: JsonObject) {
        call("setSenderIndex", senderIndex.toString())
    }

    /** Order, shipment or invoice numbers in [subject] and [text], at most three. */
    suspend fun anchors(subject: String, text: String): List<String> =
        Json.parseToJsonElement(call("anchors", subject, text)).jsonArray.map { it.jsonPrimitive.content }

    /** Could a related earlier mail lower the rating (`relatedCouldHelp` of the add-on)? */
    suspend fun relatedCouldHelp(verdict: Verdict): Boolean =
        call("relatedCouldHelp", verdict.json.toString()) == "true"

    /** Does an earlier mail from [author] count as a relation: from a sent folder, a contact or an established sender? */
    suspend fun trustedEarlier(author: String, fromSentFolder: Boolean): Boolean =
        call("trustedEarlier", author, fromSentFolder.toString()) == "true"

    /** Rates features from [check] again, e.g. with more context (thread found, related mail, AI facts). */
    suspend fun analyze(features: JsonObject, context: JsonObject = EMPTY, config: JsonObject = EMPTY): Verdict {
        val json = call("analyze", features.toString(), context.toString(), config.toString())
        return Verdict.fromJson(Json.parseToJsonElement(json).jsonObject)
    }

    /** Explanations of the signals in [language] (`de` or `en`), strongest first. */
    suspend fun reasons(verdict: Verdict, language: String, relatedDay: String = ""): List<Reason> {
        val json = call("reasons", verdict.json.toString(), language, relatedDay)
        return Json.parseToJsonElement(json).jsonArray.map {
            val o = it.jsonObject
            Reason(
                id = o.getValue("id").jsonPrimitive.content,
                weight = o.getValue("weight").jsonPrimitive.content.toDouble(),
                text = o.getValue("text").jsonPrimitive.content,
            )
        }
    }

    /** Key under which the add-on compares display names (`text.js` nameKey); empty if the name has none. */
    suspend fun nameKey(name: String): String = call("nameKey", name)

    /** Text [key] of the add-on in [language] with placeholders `{0}`, `{1}` … filled from [params]. */
    suspend fun text(language: String, key: String, params: List<String> = emptyList()): String =
        call("text", language, key, JsonArray(params.map(::JsonPrimitive)).toString())

    override fun close() {
        quickJs.close()
    }

    companion object {
        const val ENTRY_MODULE = "novabird-protection/glue.js"
        private const val SCRIPT_NAME = "novabird-protection-call.js"
        private const val BOOT_NAME = "novabird-protection-boot.js"
        private val EMPTY = JsonObject(emptyMap())

        /**
         * @param sources module name (path relative to the script root) to code, see [ProtectionScripts.load].
         * @param aliases module names mapped to other module names in [sources] (tests, e.g. `node:test`).
         * @param entryModules modules imported at start; they register their API on `globalThis`.
         * @param extraBindings further functions for JavaScript (tests).
         */
        suspend fun create(
            sources: Map<String, String>,
            dispatcher: CoroutineDispatcher,
            aliases: Map<String, String> = emptyMap(),
            entryModules: List<String> = listOf(ENTRY_MODULE),
            extraBindings: QuickJs.() -> Unit = {},
        ): ProtectionEngine = withContext(dispatcher) {
            val loader = moduleLoader {
                normalize { baseName, requestedName -> resolveModuleName(baseName, requestedName, aliases) }
                load { name -> sources[name]?.let(ModuleContent::Source) }
            }
            val quickJs = QuickJs.create(dispatcher, loader)
            try {
                quickJs.function("__nbHost") { args -> HostNames.toAscii(args[0] as String) }
                quickJs.extraBindings()
                val boot = entryModules.joinToString("\n") { "import ${jsString(it)};" }
                quickJs.evaluate<Any?>(boot, BOOT_NAME, true)
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                quickJs.close()
                throw e
            }
            ProtectionEngine(quickJs, dispatcher)
        }

        /** JSON string literal; valid JavaScript (ES2019+) for any input, so no code can be injected. */
        internal fun jsString(value: String): String = JsonPrimitive(value).toString()

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

/** An error thrown by the JavaScript phishing core. */
class ProtectionScriptException(message: String) : Exception(message)
