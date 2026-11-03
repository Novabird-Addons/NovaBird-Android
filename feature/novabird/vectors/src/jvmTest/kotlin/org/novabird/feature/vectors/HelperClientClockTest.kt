package org.novabird.feature.vectors

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class HelperClientClockTest {
    private val key = ByteArray(32) { 5 }

    @Test
    fun `a Helper whose clock is an hour ahead answers after one corrected retry`() {
        val helperOffset = 60 * 60_000L
        val timestamps = mutableListOf<Long>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(HelperClient.PATH) { exchange ->
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val plain = HelperCrypto.aesGcmDecrypt(
                key,
                Base64.decode(body.getValue("n").jsonPrimitive.content),
                "nbh1-req|${body.getValue("c").jsonPrimitive.content}".encodeToByteArray(),
                Base64.decode(body.getValue("d").jsonPrimitive.content),
            )
            val request = Json.parseToJsonElement(plain.decodeToString()).jsonObject
            val ts = request.getValue("ts").jsonPrimitive.long
            timestamps += ts
            val helperNow = System.currentTimeMillis() + helperOffset
            val answer = if (abs(ts - helperNow) > 10 * 60_000L) {
                """{"ok":false,"error":"Anfrage abgelaufen","clock":true,"now":$helperNow}"""
            } else {
                """{"ok":true,"version":"0.4.1","deviceName":"Test-PC","group":"Handys"}"""
            }
            val iv = ByteArray(12) { 9 }
            val data = HelperCrypto.aesGcmEncrypt(
                key,
                iv,
                "nbh1-res|${request.getValue("rid").jsonPrimitive.content}".encodeToByteArray(),
                answer.encodeToByteArray(),
            )
            val bytes = """{"n":"${Base64.encode(iv)}","d":"${Base64.encode(data)}"}""".encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = HelperClient("http://127.0.0.1:${server.address.port}", key, "clocktest-01", "Test")
            val info = client.hello()

            assertThat(info.deviceName).isEqualTo("Test-PC")
            assertThat(timestamps.size).isEqualTo(2)
            assertThat(abs(timestamps[1] - timestamps[0] - helperOffset) < 60_000L).isEqualTo(true)
        } finally {
            server.stop(0)
        }
    }
}
