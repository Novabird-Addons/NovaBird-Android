package org.novabird.feature.protection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.thunderbird.components.ui.bolt.atom.ClickableSurface
import net.thunderbird.components.ui.bolt.atom.DividerHorizontal
import net.thunderbird.components.ui.bolt.atom.Switch
import net.thunderbird.components.ui.bolt.atom.button.ButtonFilled
import net.thunderbird.components.ui.bolt.atom.button.ButtonOutlined
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.molecule.input.CheckboxInput
import net.thunderbird.components.ui.bolt.molecule.input.TextInput
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import org.jetbrains.compose.resources.stringResource
import org.novabird.feature.protection.ProtectionLevel
import org.novabird.feature.protection.TrustedSender
import org.novabird.feature.protection.TrustedVia
import org.novabird.feature.protection.resources.Res
import org.novabird.feature.protection.resources.novabird_protect_add
import org.novabird.feature.protection.resources.novabird_protect_add_hint
import org.novabird.feature.protection.resources.novabird_protect_add_invalid
import org.novabird.feature.protection.resources.novabird_protect_ai_note
import org.novabird.feature.protection.resources.novabird_protect_ai_note_none
import org.novabird.feature.protection.resources.novabird_protect_ai_title
import org.novabird.feature.protection.resources.novabird_protect_allowed_hint
import org.novabird.feature.protection.resources.novabird_protect_allowed_title
import org.novabird.feature.protection.resources.novabird_protect_blocked_hint
import org.novabird.feature.protection.resources.novabird_protect_blocked_title
import org.novabird.feature.protection.resources.novabird_protect_cancel
import org.novabird.feature.protection.resources.novabird_protect_consent_accept
import org.novabird.feature.protection.resources.novabird_protect_consent_check
import org.novabird.feature.protection.resources.novabird_protect_consent_p1
import org.novabird.feature.protection.resources.novabird_protect_consent_p2
import org.novabird.feature.protection.resources.novabird_protect_consent_p3
import org.novabird.feature.protection.resources.novabird_protect_consent_title
import org.novabird.feature.protection.resources.novabird_protect_contacts_allow
import org.novabird.feature.protection.resources.novabird_protect_contacts_denied
import org.novabird.feature.protection.resources.novabird_protect_contacts_granted
import org.novabird.feature.protection.resources.novabird_protect_contacts_title
import org.novabird.feature.protection.resources.novabird_protect_entries_count
import org.novabird.feature.protection.resources.novabird_protect_entries_empty
import org.novabird.feature.protection.resources.novabird_protect_entry_hint
import org.novabird.feature.protection.resources.novabird_protect_entry_invalid
import org.novabird.feature.protection.resources.novabird_protect_images_note
import org.novabird.feature.protection.resources.novabird_protect_images_title
import org.novabird.feature.protection.resources.novabird_protect_level_dangerous
import org.novabird.feature.protection.resources.novabird_protect_level_normal
import org.novabird.feature.protection.resources.novabird_protect_level_suspicious
import org.novabird.feature.protection.resources.novabird_protect_off
import org.novabird.feature.protection.resources.novabird_protect_on
import org.novabird.feature.protection.resources.novabird_protect_remove
import org.novabird.feature.protection.resources.novabird_protect_scope_note
import org.novabird.feature.protection.resources.novabird_protect_spam_auth_note
import org.novabird.feature.protection.resources.novabird_protect_spam_auth_title
import org.novabird.feature.protection.resources.novabird_protect_spam_note
import org.novabird.feature.protection.resources.novabird_protect_spam_off_hint
import org.novabird.feature.protection.resources.novabird_protect_spam_phishing_note
import org.novabird.feature.protection.resources.novabird_protect_spam_phishing_title
import org.novabird.feature.protection.resources.novabird_protect_spam_section
import org.novabird.feature.protection.resources.novabird_protect_spam_server_title
import org.novabird.feature.protection.resources.novabird_protect_spam_title
import org.novabird.feature.protection.resources.novabird_protect_switch_note
import org.novabird.feature.protection.resources.novabird_protect_switch_title
import org.novabird.feature.protection.resources.novabird_protect_title
import org.novabird.feature.protection.resources.novabird_protect_trusted_count
import org.novabird.feature.protection.resources.novabird_protect_trusted_empty
import org.novabird.feature.protection.resources.novabird_protect_trusted_hint
import org.novabird.feature.protection.resources.novabird_protect_trusted_nometa
import org.novabird.feature.protection.resources.novabird_protect_trusted_note
import org.novabird.feature.protection.resources.novabird_protect_trusted_score
import org.novabird.feature.protection.resources.novabird_protect_trusted_since
import org.novabird.feature.protection.resources.novabird_protect_trusted_title
import org.novabird.feature.protection.resources.novabird_protect_trusted_via_banner
import org.novabird.feature.protection.resources.novabird_protect_trusted_via_settings
import org.novabird.feature.protection.resources.novabird_protect_undo
import org.novabird.feature.protection.resources.novabird_protect_undo_text
import org.novabird.feature.protection.ui.ProtectionSettingsViewModel.Page

/** Settings of the phishing protection: switch with the mandatory notice, trusted senders with undo. */
@Composable
fun ProtectionSettingsScreen(
    viewModel: ProtectionSettingsViewModel,
    onClose: () -> Unit,
    onRequestContacts: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val back = { if (!viewModel.onBack()) onClose() }
    val title = when (state.page) {
        Page.MAIN -> stringResource(Res.string.novabird_protect_title)
        Page.CONSENT -> stringResource(Res.string.novabird_protect_consent_title)
        Page.TRUSTED -> stringResource(Res.string.novabird_protect_trusted_title)
        Page.BLOCKED -> stringResource(Res.string.novabird_protect_blocked_title)
        Page.ALLOWED -> stringResource(Res.string.novabird_protect_allowed_title)
    }
    Scaffold(topBar = { TopAppBarWithBackButton(title = title, onBackClick = back) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BoltTheme.spacings.double, vertical = BoltTheme.spacings.default),
            verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default),
        ) {
            when (state.page) {
                Page.MAIN -> MainPage(state, viewModel, onRequestContacts)

                Page.CONSENT -> ConsentPage(state, viewModel)

                Page.TRUSTED -> TrustedPage(state, viewModel)

                Page.BLOCKED -> EntriesPage(
                    state,
                    viewModel,
                    state.spam.blocked,
                    Res.string.novabird_protect_blocked_hint,
                )

                Page.ALLOWED -> EntriesPage(
                    state,
                    viewModel,
                    state.spam.allowed,
                    Res.string.novabird_protect_allowed_hint,
                )
            }
        }
    }
}

@Composable
private fun MainPage(
    state: ProtectionSettingsViewModel.State,
    actions: ProtectionSettingsViewModel,
    onRequestContacts: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            TextBodyLarge(text = stringResource(Res.string.novabird_protect_switch_title))
            TextBodySmall(
                text = stringResource(
                    if (state.enabled) Res.string.novabird_protect_on else Res.string.novabird_protect_off,
                ),
                color = BoltTheme.colors.onSurfaceVariant,
            )
        }
        Switch(checked = state.enabled, onCheckedChange = actions::onSwitch)
    }
    TextBodySmall(
        text = stringResource(Res.string.novabird_protect_switch_note),
        color = BoltTheme.colors.onSurfaceVariant,
    )
    TextBodySmall(
        text = stringResource(Res.string.novabird_protect_scope_note),
        color = BoltTheme.colors.onSurfaceVariant,
    )
    DividerHorizontal()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            TextBodyLarge(text = stringResource(Res.string.novabird_protect_ai_title))
            val aiNote = if (state.aiAvailable) {
                Res.string.novabird_protect_ai_note
            } else {
                Res.string.novabird_protect_ai_note_none
            }
            TextBodySmall(text = stringResource(aiNote), color = BoltTheme.colors.onSurfaceVariant)
        }
        Switch(checked = state.useAi, onCheckedChange = actions::onUseAi, enabled = state.enabled)
    }
    DividerHorizontal()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            TextBodyLarge(text = stringResource(Res.string.novabird_protect_images_title))
            TextBodySmall(
                text = stringResource(Res.string.novabird_protect_images_note),
                color = BoltTheme.colors.onSurfaceVariant,
            )
        }
        Switch(checked = state.scanImages, onCheckedChange = actions::onScanImages, enabled = state.enabled)
    }
    state.contactsGranted?.let { granted ->
        DividerHorizontal()
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                TextBodyLarge(text = stringResource(Res.string.novabird_protect_contacts_title))
                val contactsNote = if (granted) {
                    Res.string.novabird_protect_contacts_granted
                } else {
                    Res.string.novabird_protect_contacts_denied
                }
                TextBodySmall(text = stringResource(contactsNote), color = BoltTheme.colors.onSurfaceVariant)
            }
            if (!granted) {
                ButtonOutlined(
                    text = stringResource(Res.string.novabird_protect_contacts_allow),
                    onClick = onRequestContacts,
                )
            }
        }
    }
    DividerHorizontal()
    ClickableSurface(onClick = actions::openTrusted, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = BoltTheme.spacings.default)) {
            TextBodyLarge(text = stringResource(Res.string.novabird_protect_trusted_title))
            TextBodySmall(
                text = stringResource(Res.string.novabird_protect_trusted_count, state.trusted.size),
                color = BoltTheme.colors.onSurfaceVariant,
            )
        }
    }
    SpamSection(state, actions)
}

@Composable
private fun SpamSection(state: ProtectionSettingsViewModel.State, actions: ProtectionSettingsViewModel) {
    DividerHorizontal()
    TextTitleMedium(text = stringResource(Res.string.novabird_protect_spam_section))
    SwitchRow(
        title = stringResource(Res.string.novabird_protect_spam_title),
        note = stringResource(
            if (state.enabled) Res.string.novabird_protect_spam_note else Res.string.novabird_protect_spam_off_hint,
        ),
        checked = state.spam.enabled,
        enabled = state.enabled,
        onChange = actions::onSpamEnabled,
    )
    if (!state.spam.enabled || !state.enabled) return
    SwitchRow(
        title = stringResource(Res.string.novabird_protect_spam_server_title),
        note = null,
        checked = state.spam.serverFlag,
        enabled = true,
        onChange = actions::onSpamServerFlag,
    )
    SwitchRow(
        title = stringResource(Res.string.novabird_protect_spam_auth_title),
        note = stringResource(Res.string.novabird_protect_spam_auth_note),
        checked = state.spam.authFail,
        enabled = true,
        onChange = actions::onSpamAuthFail,
    )
    SwitchRow(
        title = stringResource(Res.string.novabird_protect_spam_phishing_title),
        note = stringResource(Res.string.novabird_protect_spam_phishing_note),
        checked = state.spam.phishing,
        enabled = true,
        onChange = actions::onSpamPhishing,
    )
    ListRow(
        title = stringResource(Res.string.novabird_protect_blocked_title),
        count = state.spam.blocked.size,
        onClick = actions::openBlocked,
    )
    ListRow(
        title = stringResource(Res.string.novabird_protect_allowed_title),
        count = state.spam.allowed.size,
        onClick = actions::openAllowed,
    )
}

@Composable
private fun SwitchRow(title: String, note: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            TextBodyLarge(text = title)
            note?.let { TextBodySmall(text = it, color = BoltTheme.colors.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ListRow(title: String, count: Int, onClick: () -> Unit) {
    ClickableSurface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = BoltTheme.spacings.default)) {
            TextBodyLarge(text = title)
            TextBodySmall(
                text = stringResource(Res.string.novabird_protect_entries_count, count),
                color = BoltTheme.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntriesPage(
    state: ProtectionSettingsViewModel.State,
    actions: ProtectionSettingsViewModel,
    entries: List<String>,
    hint: org.jetbrains.compose.resources.StringResource,
) {
    TextBodySmall(text = stringResource(hint), color = BoltTheme.colors.onSurfaceVariant)
    if (entries.isEmpty()) {
        TextBodyMedium(text = stringResource(Res.string.novabird_protect_entries_empty))
    }
    for (entry in entries.sorted()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextBodyLarge(text = entry, modifier = Modifier.weight(1f))
            ButtonText(text = stringResource(Res.string.novabird_protect_remove), onClick = {
                actions.removeEntry(entry)
            })
        }
        DividerHorizontal()
    }
    state.undoEntry?.let { removed ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextBodyMedium(
                text = stringResource(Res.string.novabird_protect_undo_text, removed),
                modifier = Modifier.weight(1f),
            )
            ButtonText(text = stringResource(Res.string.novabird_protect_undo), onClick = actions::undoRemoveEntry)
        }
        DividerHorizontal()
    }
    TextInput(
        text = state.newEntry,
        onTextChange = actions::onNewEntryChange,
        label = stringResource(Res.string.novabird_protect_entry_hint),
        errorMessage = if (state.newEntryInvalid) stringResource(Res.string.novabird_protect_entry_invalid) else null,
        modifier = Modifier.fillMaxWidth(),
    )
    ButtonOutlined(text = stringResource(Res.string.novabird_protect_add), onClick = actions::addEntry)
}

@Composable
private fun ConsentPage(state: ProtectionSettingsViewModel.State, actions: ProtectionSettingsViewModel) {
    TextBodyMedium(text = stringResource(Res.string.novabird_protect_consent_p1))
    TextBodyMedium(text = stringResource(Res.string.novabird_protect_consent_p2))
    TextBodyMedium(text = stringResource(Res.string.novabird_protect_consent_p3))
    CheckboxInput(
        text = stringResource(Res.string.novabird_protect_consent_check),
        checked = state.consentChecked,
        onCheckedChange = actions::onConsentChecked,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
        ButtonText(text = stringResource(Res.string.novabird_protect_cancel), onClick = actions::onConsentCancel)
        Spacer(modifier = Modifier.weight(1f))
        ButtonFilled(
            text = stringResource(Res.string.novabird_protect_consent_accept),
            onClick = actions::onConsentAccept,
            enabled = state.consentChecked,
        )
    }
}

@Composable
private fun TrustedPage(state: ProtectionSettingsViewModel.State, actions: ProtectionSettingsViewModel) {
    TextBodySmall(
        text = stringResource(Res.string.novabird_protect_trusted_hint),
        color = BoltTheme.colors.onSurfaceVariant,
    )
    if (state.trusted.isEmpty()) {
        TextBodyMedium(text = stringResource(Res.string.novabird_protect_trusted_empty))
    }
    for (sender in state.trusted.sortedByDescending { it.since }) {
        TrustedEntry(sender, onRemove = { actions.remove(sender) })
        DividerHorizontal()
    }
    state.undo?.let { removed ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextBodyMedium(
                text = stringResource(Res.string.novabird_protect_undo_text, removed.address),
                modifier = Modifier.weight(1f),
            )
            ButtonText(text = stringResource(Res.string.novabird_protect_undo), onClick = actions::undoRemove)
        }
        DividerHorizontal()
    }
    TextInput(
        text = state.newAddress,
        onTextChange = actions::onNewAddressChange,
        label = stringResource(Res.string.novabird_protect_add_hint),
        errorMessage = if (state.newAddressInvalid) stringResource(Res.string.novabird_protect_add_invalid) else null,
        modifier = Modifier.fillMaxWidth(),
    )
    ButtonOutlined(text = stringResource(Res.string.novabird_protect_add), onClick = actions::addTrusted)
    TextBodySmall(
        text = stringResource(Res.string.novabird_protect_trusted_note),
        color = BoltTheme.colors.onSurfaceVariant,
    )
}

@Composable
private fun TrustedEntry(sender: TrustedSender, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.quarter)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextBodyLarge(text = sender.address, modifier = Modifier.weight(1f))
            ButtonText(text = stringResource(Res.string.novabird_protect_remove), onClick = onRemove)
        }
        TextBodySmall(text = metaLine(sender), color = BoltTheme.colors.onSurfaceVariant)
        for (reason in sender.reasons) {
            TextBodySmall(text = "• $reason", color = BoltTheme.colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun metaLine(sender: TrustedSender): String {
    if (sender.since <= 0L) return stringResource(Res.string.novabird_protect_trusted_nometa)
    val parts = mutableListOf(
        stringResource(Res.string.novabird_protect_trusted_since, formatDay(sender.since)),
        stringResource(
            when (sender.via) {
                TrustedVia.BANNER -> Res.string.novabird_protect_trusted_via_banner
                TrustedVia.SETTINGS -> Res.string.novabird_protect_trusted_via_settings
            },
        ),
    )
    val score = sender.score
    val level = sender.level
    if (score != null && level != null) {
        parts += stringResource(Res.string.novabird_protect_trusted_score, score, stringResource(levelName(level)))
    }
    return parts.joinToString(" · ")
}

private fun levelName(level: ProtectionLevel) = when (level) {
    ProtectionLevel.NORMAL -> Res.string.novabird_protect_level_normal
    ProtectionLevel.SUSPICIOUS -> Res.string.novabird_protect_level_suspicious
    ProtectionLevel.DANGEROUS -> Res.string.novabird_protect_level_dangerous
}

@OptIn(ExperimentalTime::class)
private fun formatDay(epochMillis: Long): String {
    // ISO "2026-11-02" from kotlinx-datetime, shown as 02.11.2026 (the same in DE and EN)
    val (year, month, day) = Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault()).date.toString().split('-')
    return "$day.$month.$year"
}
