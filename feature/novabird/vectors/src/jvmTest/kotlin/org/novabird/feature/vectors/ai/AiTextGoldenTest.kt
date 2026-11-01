package org.novabird.feature.vectors.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Same results as the desktop JavaScript (golden-ai.json from tools/vecref/make_golden_ai.cjs). */
class AiTextGoldenTest {
    private val golden = Json.parseToJsonElement(javaClass.getResource("/golden-ai.json")!!.readText()).jsonObject
    private fun cases(kind: String) = golden.getValue(kind).jsonArray.map { it.jsonObject }
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    @Test
    fun `spoken text matches the desktop`() {
        for (c in cases("spoken")) {
            assertThat(SpokenText.text(c.str("in"), c.str("lang")), c.str("in")).isEqualTo(c.str("out"))
        }
    }

    @Test
    fun `sentences match the desktop`() {
        for (c in cases("sentences")) {
            val want = c.getValue("out").jsonArray.map { it.jsonPrimitive.content }
            assertThat(AiText.sentences(c.str("in")), c.str("in")).isEqualTo(want)
        }
    }

    @Test
    fun `quoted history is removed like the desktop`() {
        for (c in cases("strip")) {
            assertThat(AiText.stripQuotedText(c.str("in")), c.str("in")).isEqualTo(c.str("out"))
        }
    }

    @Test
    fun `suspicious and language guess match the desktop`() {
        for (c in cases("suspicious")) {
            assertThat(
                MailPrompts.suspicious(c.str("in")),
                c.str("in"),
            ).isEqualTo(c.getValue("out").jsonPrimitive.boolean)
        }
        for (c in cases("guessLang")) {
            assertThat(AiText.guessLang(c.str("in"), c.str("fallback")), c.str("in")).isEqualTo(c.str("out"))
        }
    }

    @Test
    fun `digest answers are parsed like the desktop`() {
        for (c in cases("parseDigest")) {
            val out = c.getValue("out").jsonObject
            val got = runCatching { MailPrompts.parseDigest(c.str("in")) }
            if (out.containsKey("error")) {
                assertThat(got.exceptionOrNull()?.message, c.str("in")).isEqualTo(out.str("error"))
            } else {
                val e = got.getOrThrow()
                assertThat(e.cat).isEqualTo(out.str("cat"))
                assertThat(e.who).isEqualTo(out.str("who"))
                assertThat(e.what).isEqualTo(out.str("what"))
                val tasks = out.getValue("tasks").jsonArray.map {
                    it.jsonObject
                }.map { DigestTask(it.str("title"), it.str("due")) }
                assertThat(e.tasks).isEqualTo(tasks)
            }
        }
    }

    @Test
    fun `summary prompts match the desktop`() {
        for (c in cases("prompt").filter { it.str("lang") in listOf("de", "en") }) {
            val s = MailPrompts.summary(c.str("lang"), c.str("aiLang") == "mail", c.str("header"), c.str("body"), TAG)
            assertThat(s.messages.map { listOf(it.role, it.content) }).isEqualTo(messages(c))
            val meta = c.getValue("meta").jsonObject
            assertThat(listOf(s.meta.lang, s.meta.target, s.meta.open, s.meta.close))
                .isEqualTo(listOf(meta.str("lang"), meta.str("target"), meta.str("open"), meta.str("close")))
        }
    }

    @Test
    fun `digest prompts and overview text match the desktop`() {
        for (c in cases("digestPrompt")) {
            val m = MailPrompts.digest(c.str("lang"), Day.parse(c.str("date"))!!, c.str("header"), c.str("body"), TAG)
            assertThat(m.map { listOf(it.role, it.content) }).isEqualTo(messages(c))
        }
        for (c in cases("overview")) {
            val entries = c.getValue("entries").jsonArray.map { it.jsonObject }.map { e ->
                DigestEntry(
                    e.str("cat"),
                    e.str("who"),
                    e.str("what"),
                    e.getValue("tasks").jsonArray.map {
                        it.jsonObject
                    }.map { DigestTask(it.str("title"), it.str("due")) },
                )
            }
            assertThat(MailPrompts.overviewText(c.str("lang"), entries)).isEqualTo(c.str("out"))
        }
    }

    @Test
    fun `mail text keeps forwarded mails and short replies whole`() {
        val reply = "Danke, passt so – bis morgen um zehn im Büro.\n\n" +
            "Am 1.11.2026 schrieb Anna:\n> Treffen wir uns morgen?"
        assertThat(AiText.mailText(reply, "Re: Treffen")).isEqualTo("Danke, passt so – bis morgen um zehn im Büro.")
        assertThat(AiText.mailText(reply, "WG: Treffen")).isEqualTo(reply)
    }

    private fun messages(c: JsonObject) =
        (c.getValue("messages") as JsonArray).map { it.jsonObject }.map { listOf(it.str("role"), it.str("content")) }

    private companion object {
        const val TAG = "0b30557a9fc4"
    }
}
