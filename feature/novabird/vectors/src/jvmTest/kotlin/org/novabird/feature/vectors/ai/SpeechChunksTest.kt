package org.novabird.feature.vectors.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class SpeechChunksTest {
    @Test
    fun `short first sentence stays whole`() {
        val sentences = listOf("Die Rechnung ist da.", "Bitte bis Freitag zahlen.")
        assertThat(SpeechChunks.quickStart(sentences)).isEqualTo(sentences)
    }

    @Test
    fun `long first sentence is split at the last comma before 100 characters`() {
        val first = "Herr Meier schickt die Rechnung für die Wartung der Heizung im Oktober, " +
            "die Summe beträgt 120 Euro, fällig ist sie bis zum 15. November."
        assertThat(SpeechChunks.quickStart(listOf(first, "Zweiter Satz.")))
            .isEqualTo(
                listOf(
                    "Herr Meier schickt die Rechnung für die Wartung der Heizung im Oktober, die Summe beträgt 120 Euro,",
                    "fällig ist sie bis zum 15. November.",
                    "Zweiter Satz.",
                ),
            )
    }

    @Test
    fun `without comma only a very long first sentence is split at a space`() {
        val medium = "Wort ".repeat(25).trim() + "."
        assertThat(SpeechChunks.quickStart(listOf(medium))).isEqualTo(listOf(medium))

        val long = "Wort ".repeat(40).trim() + "."
        val parts = SpeechChunks.quickStart(listOf(long))
        assertThat(parts.size).isEqualTo(2)
        assertThat(parts.joinToString(" ")).isEqualTo(long)
    }
}
