package org.novabird.feature.vectors

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal actual object HelperCrypto {
    private const val TAG_BITS = 128
    private val rng = SecureRandom()

    actual fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, bytes: Int): ByteArray {
        // the pairing code is ASCII (normalized), so chars = bytes
        val spec = PBEKeySpec(String(password, Charsets.US_ASCII).toCharArray(), salt, iterations, bytes * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

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
