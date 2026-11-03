package org.novabird.feature.vectors

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal actual object PairCrypto {
    private const val COORDINATE_BYTES = 32
    private const val RAW_BYTES = 1 + 2 * COORDINATE_BYTES
    private const val UNCOMPRESSED: Byte = 0x04

    private fun generator() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }

    private val params: ECParameterSpec by lazy { (generator().generateKeyPair().public as ECPublicKey).params }

    actual fun newKeyPair(): Pair<Any, ByteArray> {
        val pair = generator().generateKeyPair()
        val point = (pair.public as ECPublicKey).w
        return pair.private to (byteArrayOf(UNCOMPRESSED) + fixed(point.affineX) + fixed(point.affineY))
    }

    actual fun privateKey(scalar: ByteArray): Any =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), params))

    actual fun sharedSecret(privateKey: Any, peerPublic: ByteArray): ByteArray {
        require(peerPublic.size == RAW_BYTES && peerPublic[0] == UNCOMPRESSED) { "pub" }
        val x = BigInteger(1, peerPublic.copyOfRange(1, 1 + COORDINATE_BYTES))
        val y = BigInteger(1, peerPublic.copyOfRange(1 + COORDINATE_BYTES, RAW_BYTES))
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        return KeyAgreement.getInstance("ECDH").run {
            init(privateKey as PrivateKey)
            doPhase(peer, true)
            generateSecret()
        }
    }

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** Unsigned big-endian value in exactly 32 bytes. */
    private fun fixed(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == COORDINATE_BYTES -> bytes
            bytes.size > COORDINATE_BYTES -> bytes.copyOfRange(bytes.size - COORDINATE_BYTES, bytes.size)
            else -> ByteArray(COORDINATE_BYTES - bytes.size) + bytes
        }
    }
}
