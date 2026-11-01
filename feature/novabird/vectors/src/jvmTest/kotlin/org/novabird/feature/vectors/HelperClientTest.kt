package org.novabird.feature.vectors

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import java.io.File
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue

class HelperClientTest {
    @Test
    fun `textHash is first 8 bytes of SHA-256 as in nbnet`() {
        assertThat(HelperClient.textHash("")).isEqualTo("e3b0c44298fc1c14")
    }

    @Test
    fun `normalizeCode drops separators and confusable characters`() {
        assertThat(HelperClient.normalizeCode("abcd-efgh 0O1I")).isEqualTo("ABCDEFGH")
    }

    @Test
    fun `baseUrl adds scheme and default port`() {
        assertThat(HelperClient.baseUrl("192.168.1.5")).isEqualTo("http://192.168.1.5:47531")
        assertThat(HelperClient.baseUrl("pc:4000/")).isEqualTo("http://pc:4000")
        assertThat(HelperClient.baseUrl("http://pc")).isEqualTo("http://pc:47531")
    }

    // Against a test Helper (tools/testhelper, nbhelper --server-console --loopback); skipped when it isn't running
    @Test
    fun `helper vectors match the PC reference`() {
        val log = File(System.getProperty("novabird.testhelper.log"))
        assumeTrue("test Helper not running", log.isFile)
        val start = Json.parseToJsonElement(log.readLines().first()).jsonObject
        val url = "http://127.0.0.1:${start.getValue("port").jsonPrimitive.content}"
        val client =
            HelperClient(
                url,
                HelperClient.deriveKey(start.getValue("code").jsonPrimitive.content),
                "jvmtest-0001",
                "JVM-Test",
            )

        val info = client.hello()
        assertThat(info.modelMatch).isTrue()

        val golden = Json.parseToJsonElement(javaClass.getResource("/golden.json")!!.readText()).jsonObject
        val cases = golden.getValue("cases").jsonArray.map { it.jsonObject }
        val texts = cases.map { it.getValue("text").jsonPrimitive.content }
        val keys = texts.mapIndexed { i, t -> VectorKey("<jvmtest-$i@novabird>", HelperClient.textHash(t)) }
        val vectors = client.embed(texts, keys = keys)
        val minCos = cases.indices.minOf { i ->
            cosine(vectors[i], cases[i].getValue("vector").jsonArray.map { it.jsonPrimitive.float }.toFloatArray())
        }
        println("Helper ${info.deviceName}: min cos vs PC CPU $minCos")
        assertThat(minCos).isGreaterThan(0.999)

        val (found, shared) = client.lookup(keys + VectorKey("<missing@novabird>", "0000000000000000"))
        assertThat(found).isEqualTo(texts.indices.toList())
        assertThat(cosine(shared[0], vectors[0])).isGreaterThan(0.9999)
    }
}
