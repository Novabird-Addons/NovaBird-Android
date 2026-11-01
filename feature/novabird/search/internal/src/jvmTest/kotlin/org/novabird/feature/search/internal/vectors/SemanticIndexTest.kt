package org.novabird.feature.search.internal.vectors

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import kotlin.math.abs
import kotlin.test.Test

class SemanticIndexTest {
    @Test
    fun `freeText drops operators, exclusions and quotes`() {
        assertThat(SemanticIndex.freeText("von:petra „Rechnung Heizung“ -spam !alt  Wartung"))
            .isEqualTo("Rechnung Heizung Wartung")
    }

    @Test
    fun `quantize keeps the vector within int8 precision`() {
        val v = floatArrayOf(0.5f, -0.25f, 0.125f, 0f)
        val (scale, q) = SemanticIndex.quantize(v)
        for (i in v.indices) assertThat(abs(q[i] * scale - v[i])).isLessThan(0.5 / 127)
        assertThat(q[0].toInt()).isEqualTo(127)
    }
}
