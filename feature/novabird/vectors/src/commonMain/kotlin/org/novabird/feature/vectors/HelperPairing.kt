package org.novabird.feature.vectors

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pairing without typing the code (Helper 0.4.1+, docs/helper-kopplung.md, novabird-helper/Pairing.cs): ECDH P-256 with
 * the Helper, both sides show the same 6-digit number, the admin allows the request at the Helper, then the phone gets
 * the group's pairing code encrypted. Afterwards everything works as before (address + code, [HelperClient]).
 */
class HelperPairing(
    url: String,
    private val label: String,
    private val timeoutMs: Int = HelperClient.DEFAULT_TIMEOUT_MS,
) {
    private val endpoint = url.trim().trimEnd('/') + PATH

    /** Sends our public key; the answer gives the request id and the number to compare at the Helper. */
    fun start(): Started {
        val (privateKey, publicRaw) = PairCrypto.newKeyPair()
        val clientPub = Base64.encode(publicRaw)
        val body = buildJsonObject {
            put("op", "start")
            put("pub", clientPub)
            put("label", label.take(MAX_LABEL))
        }
        val answer = post(body)
        val req = answer.text("req") ?: throw HelperException("answer")
        val helperPub = answer.text("pub") ?: throw HelperException("answer")
        val shared = try {
            PairCrypto.sharedSecret(privateKey, Base64.decode(helperPub))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw HelperException("answer", e)
        }
        val (number, key) = derive(shared, helperPub, clientPub)
        return Started(req = req, helperName = answer.text("name").orEmpty(), number = number, key = key)
    }

    /** One poll of a started request; call about every 1.5 s until it is no longer [PollResult.Pending]. */
    fun poll(started: Started): PollResult {
        val (status, text) = HelperHttp.postJson(
            endpoint,
            buildJsonObject {
                put("op", "poll")
                put("req", started.req)
            }.toString(),
            timeoutMs,
        )
        if (status == HTTP_NOT_FOUND) return PollResult.Expired
        val answer = parse(status, text)
        return when (answer.text("state")) {
            "pending" -> PollResult.Pending

            "denied" -> PollResult.Denied

            "expired" -> PollResult.Expired

            "ok" -> {
                val plain = try {
                    HelperCrypto.aesGcmDecrypt(
                        started.key,
                        Base64.decode(answer.text("n").orEmpty()),
                        "nbh-pair|${started.req}".encodeToByteArray(),
                        Base64.decode(answer.text("d").orEmpty()),
                    )
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    throw HelperException("answer", e)
                }
                val result = Json.parseToJsonElement(plain.decodeToString()).jsonObject
                val code = result.text("code") ?: throw HelperException("answer")
                if (!HelperClient.isValidCode(code)) throw HelperException("answer")
                PollResult.Ok(code = code, group = result.text("group"))
            }

            else -> throw HelperException("answer")
        }
    }

    private fun post(body: JsonObject): JsonObject {
        val (status, text) = HelperHttp.postJson(endpoint, body.toString(), timeoutMs)
        return parse(status, text)
    }

    private fun parse(status: Int, text: String): JsonObject {
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (status != HTTP_OK) throw HelperException(json?.text("error") ?: "http $status")
        return json ?: throw HelperException("answer")
    }

    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    /** A started request: show [number] (6 digits) and ask the user to compare it at the Helper. */
    class Started(val req: String, val helperName: String, val number: String, internal val key: ByteArray)

    sealed interface PollResult {
        data object Pending : PollResult
        data object Denied : PollResult
        data object Expired : PollResult
        data class Ok(val code: String, val group: String?) : PollResult
    }

    companion object {
        const val PATH = "/nbhelper/pair"
        const val POLL_INTERVAL_MS = 1_500L
        const val MAX_WAIT_MS = 5 * 60_000L
        private const val MAX_LABEL = 40
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404
        private const val NUMBER_MODULO = 1_000_000L
        private const val NUMBER_DIGITS = 6
        private const val KEY_BYTES = 32
        private const val NUMBER_BYTES = 4
        private const val BYTE_MASK = 0xFF
        private const val BITS_PER_BYTE = 8

        /** Number to compare and AES key, from the ECDH secret and both public keys as sent (Base64). */
        internal fun derive(shared: ByteArray, helperPub: String, clientPub: String): Pair<String, ByteArray> {
            val pubs = "|$helperPub|$clientPub"
            val numberBytes = hkdf(shared, "nbh-pair-sas$pubs", NUMBER_BYTES)
            var value = 0L
            for (b in numberBytes) value = (value shl BITS_PER_BYTE) or (b.toLong() and BYTE_MASK.toLong())
            val number = (value % NUMBER_MODULO).toString().padStart(NUMBER_DIGITS, '0')
            return number to hkdf(shared, "nbh-pair-key$pubs", KEY_BYTES)
        }

        /** HKDF-SHA256 (RFC 5869) with empty salt. */
        internal fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray {
            val prk = PairCrypto.hmacSha256(ByteArray(KEY_BYTES), ikm)
            val out = ByteArray(length)
            var previous = ByteArray(0)
            var pos = 0
            var counter = 1
            while (pos < length) {
                previous =
                    PairCrypto.hmacSha256(prk, previous + info.encodeToByteArray() + byteArrayOf(counter.toByte()))
                val n = minOf(previous.size, length - pos)
                previous.copyInto(out, pos, 0, n)
                pos += n
                counter++
            }
            return out
        }
    }
}

/** Elliptic-curve parts of the pairing (P-256, raw public keys `04 || X || Y`). */
internal expect object PairCrypto {
    /** New key pair: private key (platform object) and the raw public key (65 bytes). */
    fun newKeyPair(): Pair<Any, ByteArray>

    /** Private key from its 32-byte scalar (only for tests with fixed keys). */
    fun privateKey(scalar: ByteArray): Any

    /** ECDH secret (32 bytes, the X coordinate) of [privateKey] and the raw public key [peerPublic]. */
    fun sharedSecret(privateKey: Any, peerPublic: ByteArray): ByteArray

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
}
