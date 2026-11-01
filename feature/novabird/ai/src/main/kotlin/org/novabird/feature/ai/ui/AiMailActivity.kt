package org.novabird.feature.ai.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fsck.k9.ui.base.BaseActivity
import net.thunderbird.components.ui.bolt.atom.CircularProgressIndicator
import net.thunderbird.components.ui.bolt.atom.button.ButtonFilled
import net.thunderbird.components.ui.bolt.atom.button.ButtonOutlined
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import org.novabird.feature.ai.R
import org.novabird.feature.ai.speech.SpeechPlayer
import org.novabird.feature.search.MessageLocation

/** AI summary and reading aloud of one mail; opened from the message view's menu. */
class AiMailActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()
    private val viewModel: AiMailViewModel by viewModel {
        parametersOf(
            MessageLocation(
                intent.getStringExtra(EXTRA_ACCOUNT).orEmpty(),
                intent.getLongExtra(EXTRA_FOLDER, -1),
                intent.getStringExtra(EXTRA_UID).orEmpty(),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.start(intent.getStringExtra(EXTRA_MODE) ?: MODE_SUMMARY)
        setContent {
            themeProvider.WithTheme {
                val state by viewModel.state.collectAsState()
                val speech by viewModel.speech.state.collectAsState()
                AiMailScreen(
                    state = state,
                    speech = speech,
                    onRead = viewModel::read,
                    onStop = viewModel::stopReading,
                    onSummarize = viewModel::summarize,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        const val MODE_SUMMARY = "summary"
        const val MODE_READ = "read"
        private const val EXTRA_ACCOUNT = "account"
        private const val EXTRA_FOLDER = "folder"
        private const val EXTRA_UID = "uid"
        private const val EXTRA_MODE = "mode"

        fun intent(context: Context, location: MessageLocation, mode: String): Intent =
            Intent(context, AiMailActivity::class.java)
                .putExtra(EXTRA_ACCOUNT, location.accountUuid)
                .putExtra(EXTRA_FOLDER, location.folderId)
                .putExtra(EXTRA_UID, location.uid)
                .putExtra(EXTRA_MODE, mode)
    }
}

@Composable
@Suppress("LongParameterList")
private fun AiMailScreen(
    state: AiMailUiState,
    speech: SpeechPlayer.State,
    onRead: () -> Unit,
    onStop: () -> Unit,
    onSummarize: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBarWithBackButton(title = stringResource(R.string.novabird_ai_mail_title), onBackClick = onBack)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BoltTheme.spacings.double, vertical = BoltTheme.spacings.default),
            verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default),
        ) {
            val mail = state.mail
            when {
                state.missing -> TextBodyMedium(text = stringResource(R.string.novabird_ai_mail_missing))

                mail == null -> CircularProgressIndicator()

                else -> {
                    TextTitleMedium(text = mail.subject)
                    TextBodySmall(text = mail.from, color = BoltTheme.colors.onSurfaceVariant)
                    SpeechButtons(speech, onRead = onRead, onStop = onStop)
                    SummaryPart(state, onSummarize = onSummarize)
                }
            }
        }
    }
}

@Composable
internal fun SpeechButtons(speech: SpeechPlayer.State, onRead: () -> Unit, onStop: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (speech.status == SpeechPlayer.Status.IDLE) {
            ButtonOutlined(text = stringResource(R.string.novabird_ai_read), onClick = onRead)
        } else {
            ButtonFilled(text = stringResource(R.string.novabird_ai_read_stop), onClick = onStop)
            if (speech.status == SpeechPlayer.Status.LOADING) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                TextBodySmall(text = stringResource(R.string.novabird_ai_read_wait))
            } else {
                TextBodySmall(
                    text = stringResource(
                        if (speech.engine ==
                            SpeechPlayer.Engine.HELPER
                        ) {
                            R.string.novabird_ai_read_helper
                        } else {
                            R.string.novabird_ai_read_phone
                        },
                    ),
                    color = BoltTheme.colors.onSurfaceVariant,
                )
            }
        }
    }
    speech.error?.let {
        TextBodySmall(text = stringResource(R.string.novabird_ai_read_failed, it), color = BoltTheme.colors.error)
    }
}

@Composable
private fun SummaryPart(state: AiMailUiState, onSummarize: () -> Unit) {
    TextTitleMedium(text = stringResource(R.string.novabird_ai_summary))
    if (!state.summaryAvailable) {
        TextBodySmall(
            text = stringResource(R.string.novabird_ai_needs_helper),
            color = BoltTheme.colors.onSurfaceVariant,
        )
        return
    }
    if (state.summary.isNotEmpty()) TextBodyLarge(text = state.summary.replace("**", ""))
    if (state.summarizing) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            TextBodySmall(text = stringResource(R.string.novabird_ai_summary_running))
        }
    } else {
        ButtonOutlined(
            text = stringResource(
                if (state.summary.isEmpty()) R.string.novabird_ai_summary_start else R.string.novabird_ai_summary_again,
            ),
            onClick = onSummarize,
        )
    }
    state.error?.let { TextBodySmall(text = aiErrorText(it), color = BoltTheme.colors.error) }
}

@Composable
internal fun aiErrorText(code: String): String = when (code) {
    "helper" -> stringResource(R.string.novabird_ai_needs_helper)
    "code" -> stringResource(R.string.novabird_ai_error_code)
    "unreachable" -> stringResource(R.string.novabird_ai_error_unreachable)
    else -> stringResource(R.string.novabird_ai_error_helper, code)
}
