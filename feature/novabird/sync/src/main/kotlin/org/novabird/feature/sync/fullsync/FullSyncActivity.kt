package org.novabird.feature.sync.fullsync

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.fsck.k9.Preferences
import com.fsck.k9.ui.base.BaseActivity
import net.thunderbird.components.ui.bolt.atom.button.ButtonFilled
import net.thunderbird.components.ui.bolt.atom.button.ButtonOutlined
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import org.koin.android.ext.android.inject
import org.novabird.feature.sync.R

/**
 * "Jetzt alle Mails abrufen" for one account (account settings → Fetching mail): starts and stops [FullSyncWorker]
 * and shows how far it got.
 */
class FullSyncActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()
    private val scheduler: FullSyncScheduler by inject()
    private val preferences: Preferences by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accountUuid = intent.getStringExtra(EXTRA_ACCOUNT_UUID)
        val account = accountUuid?.let { preferences.getAccount(it) }
        if (accountUuid == null || account == null) {
            finish()
            return
        }

        setContent {
            themeProvider.WithTheme {
                val states = remember(accountUuid) { scheduler.state(accountUuid) }
                val state by states.collectAsState(initial = FullSyncState.Idle)
                FullSyncScreen(
                    accountName = account.displayName,
                    state = state,
                    onStart = { scope -> scheduler.start(accountUuid, scope) },
                    onStop = { scheduler.stop(accountUuid) },
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ACCOUNT_UUID = "accountUuid"

        fun intent(context: Context, accountUuid: String): Intent =
            Intent(context, FullSyncActivity::class.java).putExtra(EXTRA_ACCOUNT_UUID, accountUuid)
    }
}

@Composable
private fun FullSyncScreen(
    accountName: String,
    state: FullSyncState,
    onStart: (FullSyncScope) -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBarWithBackButton(title = stringResource(R.string.novabird_sync_full_title), onBackClick = onBack)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(BoltTheme.spacings.double),
            verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.double),
        ) {
            TextTitleMedium(text = accountName)
            TextBodyMedium(text = stringResource(R.string.novabird_sync_full_description))
            TextBodySmall(
                text = stringResource(R.string.novabird_sync_full_note),
                color = BoltTheme.colors.onSurfaceVariant,
            )
            StateArea(state)
            if (state is FullSyncState.Waiting || state is FullSyncState.Running) {
                ButtonOutlined(text = stringResource(R.string.novabird_sync_full_stop), onClick = onStop)
            } else {
                ButtonFilled(
                    text = stringResource(R.string.novabird_sync_full_start),
                    onClick = { onStart(FullSyncScope.ALL) },
                )
                ButtonOutlined(
                    text = stringResource(R.string.novabird_sync_full_start_inbox),
                    onClick = { onStart(FullSyncScope.INBOX) },
                )
            }
        }
    }
}

@Composable
private fun StateArea(state: FullSyncState) = Column(
    verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default),
) {
    when (state) {
        FullSyncState.Idle -> Unit

        FullSyncState.Waiting -> TextBodyMedium(text = stringResource(R.string.novabird_sync_full_waiting))

        is FullSyncState.Running -> {
            val p = state.progress
            if (p.folderCount == 0) {
                TextBodyMedium(text = stringResource(R.string.novabird_sync_full_preparing))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                TextBodyMedium(
                    text = stringResource(
                        R.string.novabird_sync_full_folder,
                        p.folderIndex,
                        p.folderCount,
                        p.folderName,
                    ),
                )
                LinearProgressIndicator(
                    progress = { (p.folderIndex - 1).coerceAtLeast(0).toFloat() / p.folderCount },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (p.total > 0) {
                    TextBodySmall(text = stringResource(R.string.novabird_sync_full_messages, p.done, p.total))
                    LinearProgressIndicator(
                        progress = { p.done.toFloat() / p.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        FullSyncState.Finished -> TextBodyMedium(text = stringResource(R.string.novabird_sync_full_finished))

        is FullSyncState.Failed -> TextBodyMedium(
            text = stringResource(
                when (state.reason) {
                    FullSyncScheduler.ERROR_LOGIN -> R.string.novabird_sync_full_failed_login
                    FullSyncScheduler.ERROR_NO_FOLDERS -> R.string.novabird_sync_full_failed_no_folders
                    FullSyncScheduler.ERROR_FOLDERS -> R.string.novabird_sync_full_failed_folders
                    else -> R.string.novabird_sync_full_failed
                },
            ),
            color = BoltTheme.colors.error,
        )
    }
}
