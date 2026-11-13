package org.novabird.feature.vectors

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HelperClientSpaceTest {
    private val key = ByteArray(32) { 7 }

    @Test
    fun `shared vectors are looked up and kept in the phone space`() {
        val requests = mutableListOf<JsonObject>()
        val server = fakeHelper(requests)
        try {
            val client = HelperClient("http://127.0.0.1:${server.address.port}", key, "spacetest-01", "Test")
            val keys = listOf(VectorKey("<a@novabird>", "0123456789abcdef"))

            client.lookup(keys)
            client.embed(listOf("Text"), keys = keys)
            client.embed(listOf("Frage"), query = true)

            assertThat(requests.map { it["op"]?.jsonPrimitive?.content }).containsExactly("vlookup", "embed", "embed")
            assertThat(requests.map { it["space"]?.jsonPrimitive?.content }).containsExactly("phone", "phone", null)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `hello tells whether the Helper keeps phone vectors apart`() {
        for ((spaces, separate) in listOf("""["phone"]""" to true, null to false)) {
            val hello = if (spaces ==
                null
            ) {
                """{"ok":true,"version":"0.4.2"}"""
            } else {
                """{"ok":true,"vecSpaces":$spaces}"""
            }
            val server = fakeHelper(mutableListOf(), hello)
            try {
                val info = HelperClient("http://127.0.0.1:${server.address.port}", key, "spacetest-01", "Test").hello()
                if (separate) {
                    assertThat(
                        info.separatesPhoneVectors,
                    ).isTrue()
                } else {
                    assertThat(info.separatesPhoneVectors).isFalse()
                }
            } finally {
                server.stop(0)
            }
        }
    }

    private fun fakeHelper(requests: MutableList<JsonObject>, hello: String = """{"ok":true}"""): HttpServer {
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
            requests += request
            val answer = when (request["op"]?.jsonPrimitive?.content) {
                "vlookup" -> """{"ok":true,"found":[],"vectors":""}"""
                "hello" -> hello
                else -> """{"ok":true,"vectors":"${Base64.encode(oneVector())}"}"""
            }
            val iv = ByteArray(12) { 3 }
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
        return server
    }

    private fun oneVector(): ByteArray =
        ByteBuffer.allocate(2 * 4).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f).putFloat(0f).array()
}
