package org.novabird.feature.protection

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class ProtectionEngineTest {

    private fun headers(vararg pairs: Pair<String, String>) =
        JsonObject(pairs.associate { (name, value) -> name to JsonArray(listOf(JsonPrimitive(value))) })

    private fun input(headers: JsonObject, html: String) = buildJsonObject {
        put("headers", headers)
        putJsonArray("parts") {
            add(
                buildJsonObject {
                    put("contentType", "text/html")
                    put("body", html)
                },
            )
        }
    }

    private suspend fun <T> withEngine(block: suspend (ProtectionEngine) -> T): T =
        ProtectionEngine.create(ProtectionScripts.load(), Dispatchers.Default.limitedParallelism(1)).use { block(it) }

    @Test
    fun `brand name from a foreign domain with a lookalike link is dangerous, with German reasons`() = runTest {
        withEngine { engine ->
            val result = engine.check(
                input(
                    headers("from" to "PayPal Service <service@paypal-konto.example.invalid>", "subject" to "Konto"),
                    """<p>Ihr Konto ist gesperrt.</p><a href="https://paypa1-login.example.invalid/x">www.paypal.de</a>""",
                ),
            )

            assertThat(result.verdict.level).isEqualTo(ProtectionLevel.DANGEROUS)
            val reasons = engine.reasons(result.verdict, "de")
            assertThat(reasons).isNotEmpty()
            assertThat(reasons.map { it.id }).contains("linkMismatch")
        }
    }

    @Test
    fun `known contact with a plain mail is normal`() = runTest {
        withEngine { engine ->
            val context = buildJsonObject {
                putJsonArray("contactAddresses") { add(JsonPrimitive("anna@firma.example")) }
            }
            val result = engine.check(
                input(headers("from" to "Anna <anna@firma.example>", "subject" to "Termin"), "<p>Bis morgen</p>"),
                context,
            )

            assertThat(result.verdict.level).isEqualTo(ProtectionLevel.NORMAL)
        }
    }

    @Test
    fun `international host names in links arrive in Punycode`() {
        assertThat(HostNames.toAscii("PАYPAL.de")).isEqualTo("xn--pypal-4ve.de")
        assertThat(HostNames.toAscii("[::1]")).isEqualTo("[::1]")
    }

    @Test
    fun `texts of the add-on with placeholders`() = runTest {
        withEngine { engine ->
            assertThat(engine.text("de", "sig_riskyAttachment", listOf("rechnung.exe")))
                .isEqualTo("Riskanter Anhang: rechnung.exe.")
            assertThat(engine.nameKey("Max Mustermann")).isNotEmpty()
        }
    }
}
