package app.k9mail.feature.account.setup.ui.autodiscovery.view

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import app.k9mail.autodiscovery.api.ImapServerSettings
import app.k9mail.feature.account.setup.R
import app.k9mail.feature.account.setup.ui.autodiscovery.AccountAutoDiscoveryContract.State
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.theme.BoltTheme

private val GMAIL_DOMAINS = setOf("gmail.com", "googlemail.com")
private const val APP_PASSWORDS_URL = "https://myaccount.google.com/apppasswords"

/**
 * NovaBird has no Google sign-in (OAuth client) of its own, and Gmail refuses the normal password: a Gmail account
 * needs an app password. True for Gmail addresses and for addresses whose mail server is Gmail's (Google Workspace).
 */
internal fun State.needsGmailAppPassword(): Boolean {
    val domain = emailAddress.value.substringAfterLast('@', "").trim().lowercase()
    val host = (autoDiscoverySettings?.incomingServerSettings as? ImapServerSettings)?.hostname?.value?.lowercase()
    return domain in GMAIL_DOMAINS || GMAIL_DOMAINS.any { host == it || host?.endsWith(".$it") == true }
}

@Composable
internal fun GmailAppPasswordHint(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier) {
        TextBodySmall(
            text = stringResource(R.string.account_setup_gmail_app_password_hint),
            color = BoltTheme.colors.onSurfaceVariant,
        )
        ButtonText(
            text = stringResource(R.string.account_setup_gmail_app_password_button),
            onClick = { uriHandler.openUri(APP_PASSWORDS_URL) },
        )
    }
}
