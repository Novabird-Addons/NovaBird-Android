package org.novabird.feature.vectors

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * NovaBird Helper in network mode (counterpart: novabird-helper/NetServer.cs, same protocol as shared/nbnet.js).
 * Key = PBKDF2-SHA256(pairing code, "NovaBird-Net-1", 300 000 rounds); every request and answer is AES-256-GCM with
 * additional data "nbh1-req|<client>" or "nbh1-res|<request id>". The pairing code selects the Helper group.
 */
class HelperClient(
    url: String,
    code: String,
    private val clientId: String,
    private val label: String,
    private val model: EmbeddingModel = EmbeddingModel.JINA_DE,
) {
    private val endpoint = url.trim().trimEnd('/') + PATH
    private val key: ByteArray

    init {
        val normalized = normalizeCode(code)
        require(normalized.length == CODE_LENGTH) { "code" }
        require(CLIENT.matches(clientId)) { "client" }
        key = HelperCrypto.pbkdf2Sha256(normalized.encodeToByteArray(), SALT.encodeToByteArray(), ITERATIONS, KEY_BYTES)
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
            speech = msg["speech"]?.let { it !is kotlinx.serialization.json.JsonNull } ?: false,
            chatModel = msg["chatModel"]?.jsonPrimitive?.contentOrNull,
        )
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
                keys?.let { put("keys", keysJson(it)) }
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
        val rid = Base64.encode(HelperCrypto.random(16))
        val iv = HelperCrypto.random(12)
        val plain = buildJsonObject {
            put("ts", HelperCrypto.nowMillis())
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
        val (status, text) = HelperHttp.postJson(endpoint, body, TIMEOUT_MS)
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
        if (msg["ok"]?.jsonPrimitive?.boolean != true) {
            throw HelperException(msg["error"]?.jsonPrimitive?.content ?: "helper")
        }
        return msg
    }

    companion object {
        const val PATH = "/nbhelper/v1"
        const val DEFAULT_PORT = 47531
        const val MAX_TEXTS = 64
        const val MAX_CHARS = 20_000
        const val MAX_LOOKUP = 1000
        private const val ITERATIONS = 300_000
        private const val KEY_BYTES = 32
        private const val CODE_LENGTH = 20
        private const val SALT = "NovaBird-Net-1"
        private const val TIMEOUT_MS = 60_000
        private const val HTTP_OK = 200
        private const val HTTP_FORBIDDEN = 403
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // without 0/O, 1/I
        private val CLIENT = Regex("^[A-Za-z0-9_-]{8,64}$")

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
)

data class VectorKey(val messageId: String, val textHash: String)

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
}

/** Plain HTTP POST of a JSON body; returns status and body text. */
internal expect object HelperHttp {
    fun postJson(url: String, body: String, timeoutMs: Int): Pair<Int, String>
}
