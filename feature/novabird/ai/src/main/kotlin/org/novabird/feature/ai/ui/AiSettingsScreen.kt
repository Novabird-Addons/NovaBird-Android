package org.novabird.feature.ai.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import net.thunderbird.components.ui.bolt.atom.DividerHorizontal
import net.thunderbird.components.ui.bolt.atom.Switch
import net.thunderbird.components.ui.bolt.atom.button.ButtonFilled
import net.thunderbird.components.ui.bolt.atom.button.ButtonOutlined
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.atom.text.TextDisplayMedium
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.molecule.input.PasswordInput
import net.thunderbird.components.ui.bolt.molecule.input.TextInput
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import org.novabird.feature.ai.R
import org.novabird.feature.ai.settings.AiSettings
import org.novabird.feature.vectors.HelperClient

@Composable
internal fun AiSettingsScreen(
    state: AiSettingsUiState,
    actions: AiSettingsActions,
    isMetered: () -> Boolean,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBarWithBackButton(title = stringResource(R.string.novabird_ai_settings_title), onBackClick = onBack)
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
            HelperSection(state, actions)
            DividerHorizontal()
            LocalSection(state, actions, isMetered)
            DividerHorizontal()
            IndexSection(state, actions)
        }
    }
}

@Composable
private fun HelperSection(state: AiSettingsUiState, actions: AiSettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
        TextTitleMedium(text = stringResource(R.string.novabird_ai_helper_title))
        TextBodySmall(
            text = stringResource(R.string.novabird_ai_helper_description),
            color = BoltTheme.colors.onSurfaceVariant,
        )
        HelperStatusView(state.helper)
        DeviceNameRow(state, actions)
        if (state.helper is HelperStatus.NotPaired) {
            PairingForm(state, actions)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
                ButtonOutlined(
                    text = stringResource(R.string.novabird_ai_helper_check),
                    onClick = { actions.checkHelper() },
                )
                ButtonText(text = stringResource(R.string.novabird_ai_helper_unpair), onClick = { actions.unpair() })
            }
        }
        val paired = state.helper !is HelperStatus.NotPaired
        SwitchRow(
            title = stringResource(R.string.novabird_ai_helper_search),
            checked = paired && state.settings.helperSearch,
            enabled = paired,
            onChange = { actions.setHelperSearch(it) },
        )
        SwitchRow(
            title = stringResource(R.string.novabird_ai_helper_summary),
            note = stringResource(R.string.novabird_ai_helper_summary_note),
            checked = paired && state.settings.helperChat,
            enabled = paired,
            onChange = { actions.setHelperChat(it) },
        )
        SwitchRow(
            title = stringResource(R.string.novabird_ai_helper_tts),
            note = stringResource(R.string.novabird_ai_helper_tts_note),
            checked = paired && state.settings.helperSpeech,
            enabled = paired,
            onChange = { actions.setHelperSpeech(it) },
        )
        SpeechRateRow(state.settings.speechRate, actions::setSpeechRate)
    }
}

// reading speed for both voices (the Helper's and the phone's), like the desktop
@Composable
private fun SpeechRateRow(rate: Float, onChange: (Float) -> Unit) {
    TextBodyLarge(text = stringResource(R.string.novabird_ai_speech_rate))
    val locale = LocalConfiguration.current.locales[0]
    Row(
        horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.half),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        for (r in AiSettings.SPEECH_RATES) {
            val label = String.format(locale, "%.2f", r).trimEnd('0').trimEnd(',', '.') + "×"
            if (r == rate) {
                ButtonFilled(text = label, onClick = {})
            } else {
                ButtonOutlined(text = label, onClick = { onChange(r) })
            }
        }
    }
}

@Composable
private fun AutoPairing(state: AiSettingsUiState, actions: AiSettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
        when (val auto = state.autoPair) {
            AutoPair.Starting -> TextBodyMedium(text = stringResource(R.string.novabird_ai_pair_starting))

            is AutoPair.Compare -> {
                TextBodyMedium(text = stringResource(R.string.novabird_ai_pair_compare, auto.helperName))
                TextDisplayMedium(text = auto.number.chunked(NUMBER_GROUP).joinToString(" "))
                TextBodySmall(
                    text = stringResource(R.string.novabird_ai_pair_compare_note),
                    color = BoltTheme.colors.onSurfaceVariant,
                )
                ButtonText(text = stringResource(R.string.novabird_ai_pair_cancel), onClick = {
                    actions.cancelAutoPair()
                })
            }

            else -> {
                TextBodySmall(
                    text = stringResource(R.string.novabird_ai_pair_intro),
                    color = BoltTheme.colors.onSurfaceVariant,
                )
                val searching = state.search == HelperSearch.Searching
                ButtonFilled(
                    text = stringResource(
                        if (searching) R.string.novabird_ai_pair_searching else R.string.novabird_ai_pair_search,
                    ),
                    onClick = { actions.searchHelpers() },
                    enabled = !searching,
                )
                (state.search as? HelperSearch.Done)?.let { done ->
                    if (done.found.isEmpty()) {
                        TextBodySmall(text = stringResource(R.string.novabird_ai_pair_none_found))
                    }
                    for (helper in done.found) {
                        ButtonOutlined(
                            text = stringResource(R.string.novabird_ai_pair_with, helper.name, helper.address),
                            onClick = { actions.pairWith(helper.url) },
                        )
                    }
                }
                (auto as? AutoPair.Failed)?.let {
                    TextBodySmall(text = autoPairError(it.reason), color = BoltTheme.colors.error)
                }
            }
        }
    }
}

@Composable
private fun autoPairError(reason: String): String = when (reason) {
    "denied" -> stringResource(R.string.novabird_ai_pair_denied)
    "expired" -> stringResource(R.string.novabird_ai_pair_expired)
    "pairing-off" -> stringResource(R.string.novabird_ai_pair_off)
    else -> stringResource(R.string.novabird_ai_error_unreachable)
}

@Composable
private fun PairingForm(state: AiSettingsUiState, actions: AiSettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
        AutoPairing(state, actions)
        if (state.autoPair !is AutoPair.Compare && state.autoPair != AutoPair.Starting) {
            DividerHorizontal()
            TextBodySmall(
                text = stringResource(R.string.novabird_ai_pair_manual),
                color = BoltTheme.colors.onSurfaceVariant,
            )
            ManualPairing(state, actions)
        }
    }
}

/** Number shown in groups of three ("843 877"). */
private const val NUMBER_GROUP = 3

@Composable
private fun ManualPairing(state: AiSettingsUiState, actions: AiSettingsActions) {
    var address by rememberSaveable { mutableStateOf(state.settings.helperAddress) }
    // the pairing code is typed by the user and kept only until pairing (then only the derived key is stored)
    var code by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
        TextInput(
            onTextChange = { address = it },
            text = address,
            label = stringResource(R.string.novabird_ai_helper_address),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        TextBodySmall(text = stringResource(R.string.novabird_ai_helper_code))
        PasswordInput(onPasswordChange = { code = it }, password = code, modifier = Modifier.fillMaxWidth())
        state.pairingError?.let { TextBodySmall(text = errorText(it), color = BoltTheme.colors.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
            ButtonFilled(
                text = stringResource(
                    if (state.pairing) R.string.novabird_ai_helper_pairing else R.string.novabird_ai_helper_pair,
                ),
                onClick = { actions.pair(address, code) },
                enabled = !state.pairing && address.isNotBlank() && HelperClient.isValidCode(code),
            )
            // the address alone: pairing with the number to compare (the Helper must be in pairing mode)
            ButtonOutlined(
                text = stringResource(R.string.novabird_ai_pair_without_code),
                onClick = { actions.pairWith(address) },
                enabled = !state.pairing && address.isNotBlank() && code.isBlank(),
            )
        }
    }
}

@Composable
private fun IndexSection(state: AiSettingsUiState, actions: AiSettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
        TextTitleMedium(text = stringResource(R.string.novabird_ai_index_title))
        val stats = state.stats
        TextBodyMedium(
            text = if (stats == null) {
                "…"
            } else {
                stringResource(R.string.novabird_ai_index_stats, stats.withVector, stats.messages, stats.pending)
            },
        )
        ButtonText(text = stringResource(R.string.novabird_ai_index_refresh), onClick = { actions.refreshIndex() })
        Spacer(modifier = Modifier.height(BoltTheme.spacings.double))
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    note: String? = null,
) {
    val color = if (enabled) BoltTheme.colors.onSurface else BoltTheme.colors.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            TextBodyLarge(text = title, color = color)
            note?.let { TextBodySmall(text = it, color = BoltTheme.colors.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
internal fun errorText(reason: String): String = when (reason) {
    "code" -> stringResource(R.string.novabird_ai_error_code)
    "model" -> stringResource(R.string.novabird_ai_error_model)
    "clock" -> stringResource(R.string.novabird_ai_error_clock)
    else -> stringResource(R.string.novabird_ai_error_unreachable)
}
