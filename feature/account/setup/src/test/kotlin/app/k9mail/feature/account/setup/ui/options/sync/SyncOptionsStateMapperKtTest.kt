package app.k9mail.feature.account.setup.ui.options.sync

import app.k9mail.feature.account.common.domain.entity.AccountState
import app.k9mail.feature.account.common.domain.entity.AccountSyncOptions
import app.k9mail.feature.account.setup.domain.entity.EmailCheckFrequency
import app.k9mail.feature.account.setup.domain.entity.EmailDisplayCount
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
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
    fun `should preselect inbox push only for IMAP accounts`() {
        val imap = AccountState(incomingServerSettings = serverSettings("imap"))
        val pop3 = AccountState(incomingServerSettings = serverSettings("pop3"))

        assertThat(imap.toSyncOptionsState(defaultInboxPush = true).inboxPush).isEqualTo(true)
        assertThat(pop3.toSyncOptionsState(defaultInboxPush = true).inboxPush).isEqualTo(null)
        assertThat(imap.toSyncOptionsState(defaultInboxPush = null).inboxPush).isEqualTo(null)
    }

    @Test
    fun `should keep the chosen inbox push and pass it on`() {
        val accountState = AccountState(
            incomingServerSettings = serverSettings("imap"),
            syncOptions = AccountSyncOptions(
                checkFrequencyInMinutes = 60,
                messageDisplayCount = 250,
                showNotification = false,
                inboxPush = false,
            ),
        )

        val state = accountState.toSyncOptionsState(defaultInboxPush = true)

        assertThat(state.inboxPush).isEqualTo(false)
        assertThat(state.copy(inboxPush = true).toAccountSyncOptions().inboxPush).isEqualTo(true)
    }

    private fun serverSettings(type: String) = ServerSettings(
        type = type,
        host = "mail.example.org",
        port = 993,
        connectionSecurity = ConnectionSecurity.SSL_TLS_REQUIRED,
        authenticationType = AuthType.PLAIN,
        username = "user@example.org",
        password = null,
        clientCertificateAlias = null,
    )

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
