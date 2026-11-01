package app.k9mail.feature.account.setup.ui.options.sync

import app.k9mail.feature.account.common.domain.entity.AccountState
import app.k9mail.feature.account.common.domain.entity.AccountSyncOptions
import app.k9mail.feature.account.setup.domain.entity.EmailCheckFrequency
import app.k9mail.feature.account.setup.domain.entity.EmailDisplayCount
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

class SyncOptionsStateMapperKtTest {

    @Test
    fun `should map state to account options`() {
        val state = SyncOptionsContract.State(
            checkFrequency = EmailCheckFrequency.EVERY_2_HOURS,
            messageDisplayCount = EmailDisplayCount.MESSAGES_100,
            showNotification = true,
        )

        val result = state.toAccountSyncOptions()

        assertThat(result).isEqualTo(
            AccountSyncOptions(
                checkFrequencyInMinutes = 120,
                messageDisplayCount = 100,
                showNotification = true,
            ),
        )
    }

    @Test
    fun `should map ALL to message display count 0`() {
        val state = SyncOptionsContract.State(messageDisplayCount = EmailDisplayCount.ALL)

        val result = state.toAccountSyncOptions()

        assertThat(result.messageDisplayCount).isEqualTo(0)
    }

    @Test
    fun `should use default message display count when account state has no sync options`() {
        val accountState = AccountState(syncOptions = null)

        val result = accountState.toSyncOptionsState(defaultMessageDisplayCount = EmailDisplayCount.ALL)

        assertThat(result).isEqualTo(SyncOptionsContract.State(messageDisplayCount = EmailDisplayCount.ALL))
    }

    @Test
    fun `should keep stored message display count instead of default`() {
        val accountState = AccountState(
            syncOptions = AccountSyncOptions(
                checkFrequencyInMinutes = 60,
                messageDisplayCount = 250,
                showNotification = false,
            ),
        )

        val result = accountState.toSyncOptionsState(defaultMessageDisplayCount = EmailDisplayCount.ALL)

        assertThat(result).isEqualTo(
            SyncOptionsContract.State(
                checkFrequency = EmailCheckFrequency.EVERY_HOUR,
                messageDisplayCount = EmailDisplayCount.MESSAGES_250,
                showNotification = false,
            ),
        )
    }
}
