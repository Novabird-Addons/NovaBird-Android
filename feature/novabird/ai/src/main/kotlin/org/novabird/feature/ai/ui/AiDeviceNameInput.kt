package org.novabird.feature.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.molecule.input.TextInput
import net.thunderbird.components.ui.bolt.organism.AlertDialog
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import org.novabird.feature.ai.R
import org.novabird.feature.ai.settings.AiSettings

/** How this phone is listed in the Helper: a plain line, changed only in a dialog behind "Change" (user decision). */
@Composable
internal fun DeviceNameRow(state: AiSettingsUiState, actions: AiSettingsActions) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val name = state.settings.effectiveDeviceName()

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            TextBodySmall(
                text = stringResource(R.string.novabird_ai_device_name),
                color = BoltTheme.colors.onSurfaceVariant,
            )
            TextBodyMedium(text = name)
        }
        ButtonText(text = stringResource(R.string.novabird_ai_change), onClick = { editing = true })
    }

    if (editing) {
        DeviceNameDialog(
            current = name,
            onSave = { changed ->
                editing = false
                // saved without a change: an automatic name (empty setting) stays automatic
                if (changed.trim() != name) actions.setDeviceName(changed)
            },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun DeviceNameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }

    AlertDialog(
        title = stringResource(R.string.novabird_ai_device_name),
        confirmText = stringResource(R.string.novabird_ai_save),
        dismissText = stringResource(R.string.novabird_ai_cancel),
        onConfirmClick = { onSave(name) },
        onDismissClick = onDismiss,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
            TextInput(
                onTextChange = { name = it.take(AiSettings.MAX_DEVICE_NAME) },
                text = name,
                modifier = Modifier.fillMaxWidth(),
            )
            TextBodySmall(
                text = stringResource(R.string.novabird_ai_device_name_note),
                color = BoltTheme.colors.onSurfaceVariant,
            )
        }
    }
}
