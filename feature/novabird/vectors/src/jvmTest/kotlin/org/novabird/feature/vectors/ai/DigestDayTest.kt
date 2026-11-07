package org.novabird.feature.vectors.ai

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class DigestDayTest {
    @Test
    fun `a real date is a day`() {
        assertThat(Day.parse("2026-11-07")).isEqualTo(Day(2026, 11, 7))
    }

    @Test
    fun `a date that does not exist is no day`() {
        assertThat(Day.parse("2026-13-01")).isNull()
        assertThat(Day.parse("2026-00-10")).isNull()
        assertThat(Day.parse("2026-11-00")).isNull()
        assertThat(Day.parse("2026-11-32")).isNull()
    }

    @Test
    fun `the overview survives a date the model made up`() {
        val entry = DigestEntry("todo", "Anna", "Eine Frage", listOf(DigestTask("Antworten", "2026-13-45")))

        assertThat(MailPrompts.overviewText("de", listOf(entry))).contains("Antworten")
    }
}
