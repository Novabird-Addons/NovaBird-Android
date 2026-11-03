package org.novabird.feature.vectors

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalStdlibApi::class)
class HelperPairingTest {
    // test vector of docs/helper-kopplung.md (fixed keys)
    private val helperPub = "BAIX5hfwtkQ5KCePlpmeaaI6TywVK99tbN9m5bgCgtTtGUp968uXcS0t2jyoWqh2Wlb0X8dYWZZS8ol8ZTBuV5Q="
    private val clientPub = "BNZak5d8qj0bCBhS/1ennkZfFmBXcwS66tUF3TpIWJzzUBheiVNy32Ih6joTdVfkc/3bZ1XwW9UHw8Uz/OnJEoU="
    private val shared = "ccfc261f58193c98ca4ad4a53bbac6f0ee29bc4d48438090446908622ca79af6"
    private val key = "5b440a4a1fbee9faea5f4ad111d37b7e7bd4636dc9931197b86579c31672541b"

    @Test
    fun `secret, number and key match the Helper's test vector`() {
        val device = PairCrypto.privateKey(ByteArray(32) { 0x22 })
        val helper = PairCrypto.privateKey(ByteArray(32) { 0x11 })

        assertThat(PairCrypto.sharedSecret(device, Base64.decode(helperPub)).toHexString()).isEqualTo(shared)
        assertThat(PairCrypto.sharedSecret(helper, Base64.decode(clientPub)).toHexString()).isEqualTo(shared)
        val (number, derivedKey) = HelperPairing.derive(shared.hexToByteArray(), helperPub, clientPub)
        assertThat(number).isEqualTo("843877")
        assertThat(derivedKey.toHexString()).isEqualTo(key)
    }

    @Test
    fun `start, poll and the encrypted code against a small Helper`() {
        val helperKey = PairCrypto.privateKey(ByteArray(32) { 0x11 })
        var approved = false
        var clientPubSeen = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(HelperPairing.PATH) { exchange ->
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val answer = when (body.getValue("op").jsonPrimitive.content) {
                "start" -> {
                    clientPubSeen = body.getValue("pub").jsonPrimitive.content
                    """{"req":"r1","pub":"$helperPub","name":"Test-PC"}"""
                }

                else -> if (!approved) {
                    """{"state":"pending"}"""
                } else {
                    val secret = PairCrypto.sharedSecret(helperKey, Base64.decode(clientPubSeen))
                    val (_, k) = HelperPairing.derive(secret, helperPub, clientPubSeen)
                    val nonce = ByteArray(12) { 7 }
                    val data = HelperCrypto.aesGcmEncrypt(
                        k,
                        nonce,
                        "nbh-pair|r1".encodeToByteArray(),
                        """{"code":"ABCDE-FGHJK-LMNPQ-RSTUV","group":"Handys"}""".encodeToByteArray(),
                    )
                    """{"state":"ok","n":"${Base64.encode(nonce)}","d":"${Base64.encode(data)}"}"""
                }
            }
            val bytes = answer.encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val pairing = HelperPairing("http://127.0.0.1:${server.address.port}", "NovaBird Test")
            val started = pairing.start()
            assertThat(started.helperName).isEqualTo("Test-PC")
            assertThat(started.number.length).isEqualTo(6)
            assertThat(pairing.poll(started)).isEqualTo(HelperPairing.PollResult.Pending)

            approved = true
            val result = pairing.poll(started)
            assertThat(result).isInstanceOf<HelperPairing.PollResult.Ok>()
            assertThat((result as HelperPairing.PollResult.Ok).code).isEqualTo("ABCDE-FGHJK-LMNPQ-RSTUV")
            assertThat(result.group).isEqualTo("Handys")
        } finally {
            server.stop(0)
        }
    }
}
