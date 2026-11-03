package org.novabird.feature.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.molecule.input.TextInput
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import org.novabird.feature.ai.R
import org.novabird.feature.ai.settings.AiSettings

// how this phone is listed in the Helper (prefilled with the name in use)
@Composable
internal fun DeviceNameInput(state: AiSettingsUiState, actions: AiSettingsActions) = Column {
    var name by rememberSaveable { mutableStateOf(state.settings.effectiveDeviceName()) }
    TextInput(
        onTextChange = {
            name = it.take(AiSettings.MAX_DEVICE_NAME)
            actions.setDeviceName(name)
        },
        text = name,
        label = stringResource(R.string.novabird_ai_device_name),
        modifier = Modifier.fillMaxWidth(),
    )
    TextBodySmall(
        text = stringResource(R.string.novabird_ai_device_name_note),
        color = BoltTheme.colors.onSurfaceVariant,
    )
}
