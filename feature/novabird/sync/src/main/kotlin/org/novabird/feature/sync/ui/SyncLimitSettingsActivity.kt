package org.novabird.feature.sync.ui

import android.content.res.Resources
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fsck.k9.ui.base.BaseActivity
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import net.thunderbird.core.ui.setting.SettingValue
import net.thunderbird.core.ui.setting.SettingValue.Select.SelectOption
import net.thunderbird.core.ui.setting.SettingViewProvider
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.novabird.feature.sync.R
import org.novabird.feature.sync.settings.SyncLimitSettings
import org.novabird.feature.sync.ui.SyncLimitSettingsContract.Event

/**
 * Settings screen for the NovaBird synchronization limits.
 */
class SyncLimitSettingsActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()
    private val settingViewProvider: SettingViewProvider by inject()
    private val viewModel: SyncLimitSettingsViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            themeProvider.WithTheme {
                SyncLimitSettingsScreen()
            }
        }
    }

    @Composable
    private fun SyncLimitSettingsScreen() {
        val state by viewModel.state.collectAsState()

        settingViewProvider.SettingView(
            title = getString(R.string.novabird_sync_settings_title),
            settings = persistentListOf(
                limitSetting(
                    id = METERED_LIMIT_ID,
                    title = getString(R.string.novabird_sync_settings_metered_limit_title),
                    description = getString(R.string.novabird_sync_settings_metered_limit_description),
                    value = state.settings.meteredLimit,
                ),
                limitSetting(
                    id = UNMETERED_LIMIT_ID,
                    title = getString(R.string.novabird_sync_settings_unmetered_limit_title),
                    description = getString(R.string.novabird_sync_settings_unmetered_limit_description),
                    value = state.settings.unmeteredLimit,
                ),
            ),
            onSettingValueChange = { setting ->
                val limit = (setting.value as? SelectOption)?.id?.toIntOrNull() ?: return@SettingView
                when (setting.id) {
                    METERED_LIMIT_ID -> viewModel.event(Event.MeteredLimitChanged(limit))
                    UNMETERED_LIMIT_ID -> viewModel.event(Event.UnmeteredLimitChanged(limit))
                }
            },
            onBack = { finish() },
        )
    }

    private fun limitSetting(id: String, title: String, description: String, value: Int): SettingValue.Select {
        val options = (SyncLimitSettings.LIMIT_OPTIONS + value).distinct()
            .map { limit -> limitOption(limit, resources) }
            .toImmutableList()

        return SettingValue.Select(
            id = id,
            title = { title },
            description = { description },
            displayValueAsSecondaryText = true,
            value = limitOption(value, resources),
            options = options,
        )
    }

    private fun limitOption(limit: Int, resources: Resources): SelectOption {
        val title = formatLimit(limit, resources)
        return SelectOption(id = limit.toString(), title = { title })
    }

    private companion object {
        const val METERED_LIMIT_ID = "novabird_sync_metered_limit"
        const val UNMETERED_LIMIT_ID = "novabird_sync_unmetered_limit"

        fun formatLimit(limit: Int, resources: Resources): String {
            return when {
                limit == SyncLimitSettings.NO_LIMIT -> resources.getString(R.string.novabird_sync_settings_no_limit)

                limit % SyncLimitSettings.MIB == 0 -> {
                    resources.getString(R.string.novabird_sync_settings_size_mib, limit / SyncLimitSettings.MIB)
                }

                else -> resources.getString(R.string.novabird_sync_settings_size_kib, limit / SyncLimitSettings.KIB)
            }
        }
    }
}
