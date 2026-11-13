package org.novabird.feature.vectors

import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * NovaBird Helper in network mode (counterpart: novabird-helper/NetServer.cs, same protocol as shared/nbnet.js).
 * Key = PBKDF2-SHA256(pairing code, "NovaBird-Net-1", 300 000 rounds); every request and answer is AES-256-GCM with
 * additional data "nbh1-req|<client>" or "nbh1-res|<request id>". The pairing code selects the Helper group.
 */
class HelperClient(
    url: String,
    private val key: ByteArray,
    private val clientId: String,
    private val label: String,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val model: EmbeddingModel = EmbeddingModel.JINA_DE,
) {
    private val endpoint = url.trim().trimEnd('/') + PATH

    init {
        require(key.size == KEY_BYTES) { "key" }
        require(CLIENT.matches(clientId)) { "client" }
    }

    /** Helper info (device, model state, group); [HelperInfo.modelMatch] is false if the Helper has another model. */
    fun hello(): HelperInfo {
        val msg = call("hello", buildJsonObject { put("model", modelJson()) })
        return HelperInfo(
            version = msg["version"]?.jsonPrimitive?.content.orEmpty(),
            deviceName = msg["deviceName"]?.jsonPrimitive?.content.orEmpty(),
            modelReady = msg["modelReady"]?.jsonPrimitive?.boolean ?: false,
            modelMatch = msg["modelMatch"]?.jsonPrimitive?.boolean ?: false,
            group = msg["group"]?.jsonPrimitive?.contentOrNull,
            speech = (msg["speech"] as? JsonObject)?.get("available")?.jsonPrimitive?.booleanOrNull ?: false,
            chatModel = msg["chatModel"]?.jsonPrimitive?.contentOrNull,
            vectorSpaces = (msg["vecSpaces"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        )
    }

    /**
     * Starts an answer of the language model chosen in the Helper (summary, overview). [messages] are role/content pairs;
     * [meta] only for summaries (lets the Helper apply its own instruction). Returns the job id for [chatPoll].
     */
    fun chat(messages: List<ChatMessage>, maxNew: Int, meta: ChatMeta? = null): String {
        val msg = call(
            "chat",
            buildJsonObject {
                put(
                    "messages",
                    buildJsonArray {
                        messages.forEach { m ->
                            add(
                                buildJsonObject {
                                    put("role", m.role)
                                    put("content", m.content)
                                },
                            )
                        }
                    },
                )
                put("maxNew", maxNew)
                meta?.let {
                    put(
                        "meta",
                        buildJsonObject {
                            put("lang", it.lang)
                            put("thread", it.thread)
                            put("target", it.target)
                            put("open", it.open)
                            put("close", it.close)
                        },
                    )
                }
            },
        )
        return msg.getValue("id").jsonPrimitive.content
    }

    /** New text since the last poll (the Helper waits up to 1 s for it); [ChatPoll.done] at the end. */
    fun chatPoll(id: String): ChatPoll {
        val msg = call("chatPoll", buildJsonObject { put("id", id) })
        val stats = msg["stats"] as? JsonObject
        return ChatPoll(
            text = msg["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            done = msg["done"]?.jsonPrimitive?.booleanOrNull ?: false,
            error = msg["error"]?.jsonPrimitive?.contentOrNull,
            model = stats?.get("model")?.jsonPrimitive?.contentOrNull,
            tokPerSec = stats?.get("tokPerSec")?.jsonPrimitive?.doubleOrNull,
        )
    }

    fun chatCancel(id: String) {
        call("chatCancel", buildJsonObject { put("id", id) })
    }

    /** Whole answer: polls until done, [onText] gets the text so far (think blocks removed); [cancelled] stops early. */
    fun chatText(
        messages: List<ChatMessage>,
        maxNew: Int,
        meta: ChatMeta? = null,
        cancelled: () -> Boolean = { false },
        onText: (String) -> Unit = {},
    ): String {
        val id = chat(messages, maxNew, meta)
        val text = StringBuilder()
        while (true) {
            if (cancelled()) {
                runCatching { chatCancel(id) }
                throw HelperException("cancelled")
            }
            val r = chatPoll(id)
            if (r.text.isNotEmpty()) {
                text.append(r.text)
                onText(withoutThink(text.toString()))
            }
            r.error?.let { throw HelperException(it) }
            if (r.done) return withoutThink(text.toString())
            if (r.text.isEmpty()) HelperCrypto.sleep(POLL_IDLE_MS)
        }
    }

    /** One sentence read aloud by the Helper (at most [MAX_TTS_CHARS]): WAV bytes. [lang] e.g. "german", "english". */
    fun tts(text: String, lang: String, voice: String? = null): ByteArray {
        val msg = call(
            "tts",
            buildJsonObject {
                put("text", text)
                put("lang", lang)
                voice?.let { put("voice", it) }
            },
        )
        return Base64.decode(msg.getValue("wav").jsonPrimitive.content)
    }

    /**
     * Vectors for up to [MAX_TEXTS] texts (normalized). [query] puts the request before indexing work.
     * [keys] (Message-ID + text hash per text) makes the Helper keep the vectors for other devices of the group.
     */
    fun embed(texts: List<String>, query: Boolean = false, keys: List<VectorKey>? = null): List<FloatArray> {
        require(texts.size in 1..MAX_TEXTS) { "1..$MAX_TEXTS texts" }
        val msg = call(
            "embed",
            buildJsonObject {
                put("model", modelJson())
                put("texts", buildJsonArray { texts.forEach { add(JsonPrimitive(it)) } })
                put("prio", if (query) "query" else "index")
                keys?.let {
                    put("keys", keysJson(it))
                    put("space", VECTOR_SPACE)
                }
            },
        )
        return decodeVectors(msg.getValue("vectors").jsonPrimitive.content, texts.size)
    }

    /** Vectors already computed in this group: indexes into [items] that were found, and their vectors. */
    fun lookup(items: List<VectorKey>): Pair<List<Int>, List<FloatArray>> {
        val msg = call(
            "vlookup",
            buildJsonObject {
                put("model", modelJson())
                put("items", keysJson(items))
                put("space", VECTOR_SPACE)
            },
        )
        val found = msg["found"]?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty()
        val vectors = if (found.isEmpty()) {
            emptyList()
        } else {
            decodeVectors(
                msg.getValue("vectors").jsonPrimitive.content,
                found.size,
            )
        }
        return found to vectors
    }

    private fun modelJson() = buildJsonObject {
        put("id", model.id)
        put("revision", model.revision)
    }

    private fun keysJson(keys: List<VectorKey>) = buildJsonArray {
        keys.forEach { k ->
            add(
                buildJsonObject {
                    put("id", k.messageId)
                    put("h", k.textHash)
                },
            )
        }
    }

    private fun decodeVectors(b64: String, count: Int): List<FloatArray> {
        val bytes = Base64.decode(b64)
        val dims = bytes.size / 4 / count
        if (dims * 4 * count != bytes.size || dims <= 0) throw HelperException("answer")
        return List(count) { i ->
            val v = FloatArray(dims) { d -> Float.fromBits(readIntLe(bytes, (i * dims + d) * 4)) }
            normalize(v)
        }
    }

    private fun call(op: String, payload: JsonObject): JsonObject {
        var msg = send(op, payload)
        val helperNow = msg["now"]?.jsonPrimitive?.longOrNull
        if (msg.isClockError() && helperNow != null) {
            // the clocks of phone and Helper differ by more than the Helper allows (10 min): use the Helper's time
            // (the answer is encrypted with the group key, so "now" cannot be forged) and send once more
            clockOffsetMs = helperNow - HelperCrypto.nowMillis()
            msg = send(op, payload)
        }
        if (msg["ok"]?.jsonPrimitive?.boolean != true) {
            throw HelperException(if (msg.isClockError()) "clock" else msg["error"]?.jsonPrimitive?.content ?: "helper")
        }
        return msg
    }

    private fun JsonObject.isClockError() = this["clock"]?.jsonPrimitive?.booleanOrNull == true

    private fun send(op: String, payload: JsonObject): JsonObject {
        val rid = Base64.encode(HelperCrypto.random(16))
        val iv = HelperCrypto.random(12)
        val plain = buildJsonObject {
            put("ts", HelperCrypto.nowMillis() + clockOffsetMs)
            put("rid", rid)
            put("op", op)
            put("label", label)
            payload.forEach { (k, v) -> put(k, v) }
        }.toString().encodeToByteArray()
        val data = HelperCrypto.aesGcmEncrypt(key, iv, "nbh1-req|$clientId".encodeToByteArray(), plain)
        val body = buildJsonObject {
            put("c", clientId)
            put("n", Base64.encode(iv))
            put("d", Base64.encode(data))
        }.toString()
        val (status, text) = HelperHttp.postJson(endpoint, body, timeoutMs)
        if (status == HTTP_FORBIDDEN) throw HelperException("code")
        if (status != HTTP_OK) throw HelperException("http $status")
        val msg = try {
            val res = Json.parseToJsonElement(text).jsonObject
            val out = HelperCrypto.aesGcmDecrypt(
                key,
                Base64.decode(res.getValue("n").jsonPrimitive.content),
                "nbh1-res|$rid".encodeToByteArray(),
                Base64.decode(res.getValue("d").jsonPrimitive.content),
            )
            Json.parseToJsonElement(out.decodeToString()).jsonObject
        } catch (e: Exception) {
            throw HelperException("answer", e)
        }
        return msg
    }

    companion object {
        /** Difference between the Helper's clock and ours, learned from a "clock" answer (0 while the clocks agree). */
        @Volatile
        private var clockOffsetMs = 0L

        const val PATH = "/nbhelper/v1"
        const val DEFAULT_PORT = 47531
        const val MAX_TEXTS = 64
        const val MAX_CHARS = 20_000
        const val MAX_LOOKUP = 1000

        /**
         * Shared vectors of phones are kept apart from those of the PCs in the same group: both use the same model
         * but build the mail text differently, so their records for the same Message-ID would keep replacing each
         * other in the Helper (it keeps the newest per Message-ID). Helpers without spaces ignore the field.
         */
        const val VECTOR_SPACE = "phone"
        private const val ITERATIONS = 300_000
        private const val KEY_BYTES = 32
        private const val CODE_LENGTH = 20
        private const val SALT = "NovaBird-Net-1"
        const val DEFAULT_TIMEOUT_MS = 60_000
        const val MAX_TTS_CHARS = 600
        private const val POLL_IDLE_MS = 20L
        private val THINK = Regex("<think>[\\s\\S]*?</think>")
        private val THINK_OPEN = Regex("<think>[\\s\\S]*$")

        /** Removes think blocks of some models (also one still open), like ai/ai.js clean(). */
        fun withoutThink(text: String) = text.replace(THINK, "").replace(THINK_OPEN, "").trimStart()
        private const val HTTP_OK = 200
        private const val HTTP_FORBIDDEN = 403
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // without 0/O, 1/I
        private val CLIENT = Regex("^[A-Za-z0-9_-]{8,64}$")

        /** Key for [code] (PBKDF2, slow on purpose: about 1.4 s on a phone). Store the key, never the code. */
        fun deriveKey(code: String): ByteArray {
            val normalized = normalizeCode(code)
            require(normalized.length == CODE_LENGTH) { "code" }
            return HelperCrypto.pbkdf2Sha256(
                normalized.encodeToByteArray(),
                SALT.encodeToByteArray(),
                ITERATIONS,
                KEY_BYTES,
            )
        }

        /** True if [code] has the length of a pairing code after normalizing. */
        fun isValidCode(code: String) = normalizeCode(code).length == CODE_LENGTH

        /** Address as typed by the user ("192.168.1.5", "pc:47531", "http://…") → base URL with port. */
        fun baseUrl(address: String): String {
            val a = address.trim().trimEnd('/')
            val withScheme = if (a.startsWith("http://") || a.startsWith("https://")) a else "http://$a"
            val hostPart = withScheme.substringAfter("://")
            return if (hostPart.contains(':') && !hostPart.startsWith("[")) withScheme else "$withScheme:$DEFAULT_PORT"
        }

        /** Pairing code without spaces, separators and confusable characters, upper case (valid: 20 characters). */
        fun normalizeCode(code: String) = code.uppercase().filter { it in CODE_ALPHABET }

        /** Text hash for sharing vectors: first 8 bytes of SHA-256 (UTF-8), hex (shared/nbnet.js textHash). */
        fun textHash(text: String) =
            HelperCrypto.sha256(text.encodeToByteArray()).take(8).joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
    }
}

data class HelperInfo(
    val version: String,
    val deviceName: String,
    val modelReady: Boolean,
    val modelMatch: Boolean,
    val group: String?,
    val speech: Boolean,
    val chatModel: String?,
    /** Separate vector stores the Helper keeps ("phone" from Helper 0.5.0 on). */
    val vectorSpaces: List<String> = emptyList(),
) {
    /**
     * False for Helpers before 0.5.0: they keep one store per group, so phones and PCs in the same group push each
     * other's vectors out ([VECTOR_SPACE] is ignored there).
     */
    val separatesPhoneVectors: Boolean get() = HelperClient.VECTOR_SPACE in vectorSpaces
}

data class VectorKey(val messageId: String, val textHash: String)

data class ChatMessage(val role: String, val content: String)

/** For the Helper's own summary instruction: language, thread or single mail, target language text and markers. */
data class ChatMeta(val lang: String, val thread: Boolean, val target: String, val open: String, val close: String)

data class ChatPoll(val text: String, val done: Boolean, val error: String?, val model: String?, val tokPerSec: Double?)

class HelperException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun readIntLe(b: ByteArray, o: Int) =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

internal fun normalize(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += x.toDouble() * x
    val n = kotlin.math.sqrt(s)
    if (n > 0) for (i in v.indices) v[i] = (v[i] / n).toFloat()
    return v
}

/** Platform crypto (javax.crypto on JVM and Android). */
internal expect object HelperCrypto {
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, bytes: Int): ByteArray
    fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray
    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, data: ByteArray): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun random(bytes: Int): ByteArray
    fun nowMillis(): Long
    fun sleep(ms: Long)
}

/** Plain HTTP POST of a JSON body; returns status and body text. */
internal expect object HelperHttp {
    fun postJson(url: String, body: String, timeoutMs: Int): Pair<Int, String>
}
