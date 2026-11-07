package app.k9mail.feature.account.setup.ui.autodiscovery.view

import app.k9mail.autodiscovery.api.AutoDiscoveryResult
import app.k9mail.autodiscovery.api.ConnectionSecurity
import app.k9mail.autodiscovery.api.ImapServerSettings
import app.k9mail.autodiscovery.api.SmtpServerSettings
import app.k9mail.feature.account.setup.ui.autodiscovery.AccountAutoDiscoveryContract.State
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import net.thunderbird.core.common.net.toHostname
import net.thunderbird.core.common.net.toPort
import net.thunderbird.core.validation.input.StringInputField
import org.junit.Test

class GmailAppPasswordHintTest {
    @Test
    fun `gmail and googlemail addresses need an app password`() {
        assertThat(state("Jemand@GMail.com").needsGmailAppPassword()).isTrue()
        assertThat(state("jemand@googlemail.com").needsGmailAppPassword()).isTrue()
        assertThat(state("jemand@example.org").needsGmailAppPassword()).isFalse()
        assertThat(state("jemand@notgmail.com").needsGmailAppPassword()).isFalse()
    }

    @Test
    fun `an own domain on Gmail's servers needs one too`() {
        assertThat(state("chef@firma.example", host = "imap.gmail.com").needsGmailAppPassword()).isTrue()
        assertThat(state("chef@firma.example", host = "imap.firma.example").needsGmailAppPassword()).isFalse()
    }

    private fun state(email: String, host: String? = null) = State(
        emailAddress = StringInputField(email),
        autoDiscoverySettings = host?.let {
            AutoDiscoveryResult.Settings(
                incomingServerSettings = ImapServerSettings(
                    hostname = it.toHostname(),
                    port = 993.toPort(),
                    connectionSecurity = ConnectionSecurity.TLS,
                    authenticationTypes = emptyList(),
                    username = email,
                ),
                outgoingServerSettings = SmtpServerSettings(
                    hostname = "smtp.example".toHostname(),
                    port = 465.toPort(),
                    connectionSecurity = ConnectionSecurity.TLS,
                    authenticationTypes = emptyList(),
                    username = email,
                ),
                isTrusted = true,
                source = "test",
            )
        },
    )
}
