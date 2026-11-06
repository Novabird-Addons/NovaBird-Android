package org.novabird.feature.sync.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import kotlinx.collections.immutable.toImmutableList
import net.thunderbird.components.ui.bolt.atom.card.CardDefaults
import net.thunderbird.components.ui.bolt.atom.card.CardFilled
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.atom.text.TextLabelLarge
import net.thunderbird.components.ui.bolt.molecule.input.SelectInput
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import org.novabird.feature.sync.R
import org.novabird.feature.sync.settings.SyncLimitSettings
import org.novabird.feature.sync.ui.SyncLimitSettingsContract.Event
import org.novabird.feature.sync.ui.SyncLimitSettingsContract.State

/** Section label over a card with select fields, each with its note below (like the setup wizard mockup). */
@Composable
internal fun SyncLimitSettingsScreen(state: State, onEvent: (Event) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBarWithBackButton(title = stringResource(R.string.novabird_sync_settings_title), onBackClick = onBack)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(BoltTheme.spacings.double),
            verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default),
        ) {
            TextLabelLarge(
                text = stringResource(R.string.novabird_sync_settings_section),
                color = BoltTheme.colors.primary,
                modifier = Modifier.padding(horizontal = BoltTheme.spacings.default),
            )
            CardFilled(
                shape = BoltTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = BoltTheme.colors.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(BoltTheme.spacings.double),
                    verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.double),
                ) {
                    LimitField(
                        label = stringResource(R.string.novabird_sync_settings_metered_limit_title),
                        note = stringResource(R.string.novabird_sync_settings_metered_limit_description),
                        limit = state.settings.meteredLimit,
                        onChange = { onEvent(Event.MeteredLimitChanged(it)) },
                    )
                    LimitField(
                        label = stringResource(R.string.novabird_sync_settings_unmetered_limit_title),
                        note = stringResource(R.string.novabird_sync_settings_unmetered_limit_description),
                        limit = state.settings.unmeteredLimit,
                        onChange = { onEvent(Event.UnmeteredLimitChanged(it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LimitField(label: String, note: String, limit: Int, onChange: (Int) -> Unit) {
    val resources = LocalResources.current

    Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.half)) {
        SelectInput(
            options = (SyncLimitSettings.LIMIT_OPTIONS + limit).distinct().toImmutableList(),
            selectedOption = limit,
            onOptionChange = onChange,
            optionToStringTransformation = { formatLimit(it, resources) },
            label = label,
            contentPadding = PaddingValues(),
        )
        TextBodySmall(
            text = note,
            color = BoltTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = BoltTheme.spacings.half),
        )
    }
}

private fun formatLimit(limit: Int, resources: Resources): String = when {
    limit == SyncLimitSettings.NO_LIMIT -> resources.getString(R.string.novabird_sync_settings_no_limit)

    limit % SyncLimitSettings.MIB == 0 -> {
        resources.getString(R.string.novabird_sync_settings_size_mib, limit / SyncLimitSettings.MIB)
    }

    else -> resources.getString(R.string.novabird_sync_settings_size_kib, limit / SyncLimitSettings.KIB)
}
