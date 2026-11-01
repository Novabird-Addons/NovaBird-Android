package app.k9mail.feature.account.setup.domain.entity

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import org.junit.Test

class EmailDisplayCountTest {

    @Test
    fun `fromCount() should map 0 to ALL`() {
        val result = EmailDisplayCount.fromCount(0)

        assertThat(result).isSameInstanceAs(EmailDisplayCount.ALL)
    }

    @Test
    fun `ALL should be the last option`() {
        val result = EmailDisplayCount.all().last()

        assertThat(result).isSameInstanceAs(EmailDisplayCount.ALL)
    }

    @Test
    fun `DEFAULT should stay at 100 messages`() {
        assertThat(EmailDisplayCount.DEFAULT.count).isEqualTo(100)
    }
}
