package org.novabird.feature.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.collections.immutable.toImmutableList
import net.thunderbird.components.ui.bolt.atom.button.ButtonOutlined
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.molecule.input.SelectInput
import net.thunderbird.components.ui.bolt.organism.AlertDialog
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import org.novabird.feature.ai.R
import org.novabird.feature.ai.local.DownloadState
import org.novabird.feature.ai.settings.AiSettings
import org.novabird.feature.ai.settings.KeepLoaded

/** "Compute on the phone": switch with hint, model download (Wi-Fi or explicit OK), keep loaded, threads, GPU. */
@Composable
internal fun LocalSection(state: AiSettingsUiState, actions: AiSettingsActions, isMetered: () -> Boolean) {
    var showHint by remember { mutableStateOf(false) }
    var askMobile by remember { mutableStateOf(false) }
    val download = { if (isMetered()) askMobile = true else actions.startDownload(allowMobileData = false) }

    Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
        TextTitleMedium(text = stringResource(R.string.novabird_ai_local_title))
        SwitchRow(
            title = stringResource(R.string.novabird_ai_local_enable),
            checked = state.settings.localEnabled,
            onChange = { on -> if (on) showHint = true else actions.setLocalEnabled(false) },
        )
        if (state.helper !is HelperStatus.NotPaired) {
            TextBodySmall(
                text = stringResource(R.string.novabird_ai_local_with_helper),
                color = BoltTheme.colors.onSurfaceVariant,
            )
        }
        ModelRow(state, actions, onDownload = download)
        LocalOptions(state, actions)

        if (showHint) {
            AlertDialog(
                title = stringResource(R.string.novabird_ai_local_hint_title),
                text = stringResource(R.string.novabird_ai_local_hint_text),
                confirmText = stringResource(R.string.novabird_ai_local_hint_confirm),
                dismissText = stringResource(R.string.novabird_ai_cancel),
                onConfirmClick = {
                    showHint = false
                    actions.setLocalEnabled(true)
                    if (!state.modelReady && state.download is DownloadState.Idle) download()
                },
                onDismissClick = { showHint = false },
                onDismissRequest = { showHint = false },
            )
        }
        if (askMobile) {
            AlertDialog(
                title = stringResource(R.string.novabird_ai_download_mobile_title),
                text = stringResource(R.string.novabird_ai_download_mobile_text),
                confirmText = stringResource(R.string.novabird_ai_download_mobile_confirm),
                dismissText = stringResource(R.string.novabird_ai_download_wifi_only),
                onConfirmClick = {
                    askMobile = false
                    actions.startDownload(allowMobileData = true)
                },
                onDismissClick = {
                    askMobile = false
                    actions.startDownload(allowMobileData = false)
                },
                onDismissRequest = { askMobile = false },
            )
        }
    }
}

@Composable
private fun LocalOptions(state: AiSettingsUiState, actions: AiSettingsActions) {
    val keepLabels = KeepLoaded.entries.associateWith { keepLoadedText(it) }
    Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
        SelectInput(
            options = KeepLoaded.entries.toImmutableList(),
            selectedOption = state.settings.keepLoaded,
            onOptionChange = { actions.setKeepLoaded(it) },
            optionToStringTransformation = { keepLabels.getValue(it) },
            label = stringResource(R.string.novabird_ai_local_keep),
        )
        val cores = Runtime.getRuntime().availableProcessors()
        val autoLabel = stringResource(R.string.novabird_ai_local_threads_auto, AiSettings.defaultThreads(cores))
        SelectInput(
            options = (0..cores).toList().toImmutableList(),
            selectedOption = state.settings.threads.coerceIn(0, cores),
            onOptionChange = { actions.setThreads(it) },
            optionToStringTransformation = { if (it == 0) autoLabel else it.toString() },
            label = stringResource(R.string.novabird_ai_local_threads, cores),
        )
        // NNAPI and WebGPU gave no usable speed-up on the test phone (Dimensity 9400), see the vector spike
        SwitchRow(
            title = stringResource(R.string.novabird_ai_local_gpu),
            note = stringResource(R.string.novabird_ai_local_gpu_note),
            checked = false,
            enabled = false,
            onChange = {},
        )
    }
}

@Composable
private fun ModelRow(state: AiSettingsUiState, actions: AiSettingsActions, onDownload: () -> Unit) {
    val download = state.download
    val text = when {
        state.modelReady -> stringResource(R.string.novabird_ai_model_ready)
        download is DownloadState.Running -> stringResource(R.string.novabird_ai_model_running, download.percent)
        download is DownloadState.Waiting && download.forWifi -> stringResource(R.string.novabird_ai_model_wait_wifi)
        download is DownloadState.Waiting -> stringResource(R.string.novabird_ai_model_wait_network)
        download is DownloadState.Failed -> stringResource(R.string.novabird_ai_model_failed)
        else -> stringResource(R.string.novabird_ai_model_missing)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextBodyMedium(text = text, modifier = Modifier.weight(1f))
        when {
            state.modelReady -> ButtonText(
                text = stringResource(R.string.novabird_ai_model_delete),
                onClick = { actions.deleteModel() },
            )

            download is DownloadState.Running || download is DownloadState.Waiting -> ButtonText(
                text = stringResource(R.string.novabird_ai_cancel),
                onClick = { actions.cancelDownload() },
            )

            else -> ButtonOutlined(text = stringResource(R.string.novabird_ai_model_download), onClick = onDownload)
        }
    }
}

@Composable
private fun keepLoadedText(keep: KeepLoaded): String = when (keep) {
    KeepLoaded.ALWAYS -> stringResource(R.string.novabird_ai_keep_always)
    KeepLoaded.WITH_APP -> stringResource(R.string.novabird_ai_keep_with_app)
    else -> stringResource(R.string.novabird_ai_keep_minutes, keep.minutes ?: 0)
}
