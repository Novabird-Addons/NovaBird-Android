package org.novabird.feature.vectors

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.io.File
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class BpeTokenizerTest {
    private val modelDir = File(System.getProperty("novabird.model.dir"))
    private val tokenizer by lazy { BpeTokenizer(modelDir.resolve("tokenizer.json").readText()) }

    // golden.json: ids from HF tokenizers (tools/vecref/make_golden.py)
    @Test
    fun `ids match HF tokenizers`() {
        val golden = Json.parseToJsonElement(javaClass.getResource("/golden.json")!!.readText()).jsonObject
        for (case in golden.getValue("cases").jsonArray) {
            val o = case.jsonObject
            val text = o.getValue("text").jsonPrimitive.content
            val expected = o.getValue("ids").jsonArray.map { it.jsonPrimitive.long }

            val ids = tokenizer.encode(text, EmbeddingModel.JINA_DE.maxTokens).toList()

            assertThat(ids, text.take(40)).isEqualTo(expected)
        }
    }
}
