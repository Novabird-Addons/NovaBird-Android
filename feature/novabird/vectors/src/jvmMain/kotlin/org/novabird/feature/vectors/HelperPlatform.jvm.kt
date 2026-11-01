package org.novabird.feature.vectors

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual object HelperCrypto {
    private const val TAG_BITS = 128
    private const val HMAC = "HmacSHA256"
    private val rng = SecureRandom()

    // PBKDF2-HMAC-SHA256 with Mac (SecretKeyFactory "PBKDF2WithHmacSHA256" needs Android 8, the app allows older)
    actual fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, bytes: Int): ByteArray {
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(password, HMAC)) }
        val out = ByteArray(bytes)
        var block = 1
        var pos = 0
        while (pos < bytes) {
            mac.update(salt)
            mac.update(blockIndex(block))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(t.size, bytes - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            block++
        }
        return out
    }

    private fun blockIndex(i: Int) = byteArrayOf(
        (i ushr 24).toByte(),
        (i ushr 16).toByte(),
        (i ushr 8).toByte(),
        i.toByte(),
    )

    actual fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, iv, aad).doFinal(plain)

    actual fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, data: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, iv, aad).doFinal(data)

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad)
        }

    actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    actual fun random(bytes: Int) = ByteArray(bytes).also { rng.nextBytes(it) }

    actual fun nowMillis() = System.currentTimeMillis()
}

internal actual object HelperHttp {
    actual fun postJson(url: String, body: String, timeoutMs: Int): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status < HttpURLConnection.HTTP_BAD_REQUEST) conn.inputStream else conn.errorStream
            return status to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}
