package org.novabird.feature.vectors

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test

class VectorTextTest {
    @Test
    fun `subject and body are joined and clipped`() {
        val text = VectorText.of("Betreff", "x".repeat(2000))
        assertThat(text.length).isEqualTo(VectorText.MAX_CHARS)
        assertThat(text.substringBefore('\n')).isEqualTo("Betreff")
    }

    @Test
    fun `surrogate pair at the cut is dropped and lone surrogates replaced`() {
        // the subject line is 5 chars, so the emoji starts at index 1199 and is cut in half
        val body = "a".repeat(VectorText.MAX_CHARS - 6) + "😀"
        assertThat(VectorText.of("Subj", body).endsWith("a")).isTrue()
        assertThat(VectorText.clip("a\uD800b")).isEqualTo("a�b")
    }
}
