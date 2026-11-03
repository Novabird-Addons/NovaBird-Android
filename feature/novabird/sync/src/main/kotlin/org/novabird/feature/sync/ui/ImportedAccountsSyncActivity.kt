package org.novabird.feature.sync.ui

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.fsck.k9.Preferences
import com.fsck.k9.ui.base.BaseActivity
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.components.ui.bolt.atom.button.ButtonFilled
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.setting.Setting
import net.thunderbird.core.ui.setting.SettingDecoration
import net.thunderbird.core.ui.setting.SettingValue
import net.thunderbird.core.ui.setting.SettingValue.Select.SelectOption
import net.thunderbird.core.ui.setting.SettingViewProvider
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import org.koin.android.ext.android.inject
import org.novabird.feature.sync.R

/**
 * Asks how far back and how many messages to sync for accounts that came from a settings import (QR code from
 * Thunderbird on the PC, settings file). They skip the setup wizard and would keep the imported values (often only
 * 25 messages). Opened by the import screen before it closes; NovaBird's defaults (one year, all messages) are set
 * when it opens and every change is saved right away.
 */
class ImportedAccountsSyncActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()
    private val settingViewProvider: SettingViewProvider by inject()
    private val preferences: Preferences by inject()

    private val accounts = MutableStateFlow<List<AccountScope>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uuids = intent.getStringArrayListExtra(EXTRA_ACCOUNT_UUIDS).orEmpty()
        setResult(RESULT_OK)

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // first opening: NovaBird's defaults instead of the imported values
                if (savedInstanceState == null) uuids.forEach { save(it, period = DEFAULT_PERIOD, count = ALL) }
            }
            reload(uuids)
        }

        setContent {
            themeProvider.WithTheme {
                Screen()
            }
        }
    }

    @Composable
    private fun Screen() {
        val list by accounts.collectAsState()
        val settings = buildList<Setting> {
            add(
                SettingDecoration.Custom(id = "intro") { modifier ->
                    TextBodyMedium(
                        text = getString(R.string.novabird_sync_import_intro),
                        modifier = modifier.padding(BoltTheme.spacings.double),
                    )
                },
            )
            for (account in list) {
                add(SettingDecoration.SectionHeader(id = HEADER + account.uuid, title = { account.name }))
                add(periodSetting(account))
                add(countSetting(account))
            }
            add(
                SettingDecoration.Custom(id = "done") { modifier ->
                    ButtonFilled(
                        text = getString(R.string.novabird_sync_import_done),
                        onClick = { finish() },
                        modifier = modifier.padding(BoltTheme.spacings.double),
                    )
                },
            )
        }.toImmutableList()

        settingViewProvider.SettingView(
            title = getString(R.string.novabird_sync_import_title),
            settings = settings,
            onSettingValueChange = { setting -> onChange(setting) },
            onBack = { finish() },
            modifier = Modifier,
        )
    }

    private fun onChange(setting: SettingValue<*>) {
        val value = (setting.value as? SelectOption)?.id?.toIntOrNull()
        val uuid = setting.id.substringAfter(':')
        val account = accounts.value.firstOrNull { it.uuid == uuid }
        val changed = when {
            value == null || account == null -> null
            setting.id.startsWith(PERIOD) -> account.copy(period = value)
            setting.id.startsWith(COUNT) -> account.copy(count = value)
            else -> null
        } ?: return
        accounts.value = accounts.value.map { if (it.uuid == uuid) changed else it }
        lifecycleScope.launch(Dispatchers.IO) { save(uuid, changed.period, changed.count) }
    }

    private fun periodSetting(account: AccountScope): SettingValue.Select {
        val options = (PERIOD_OPTIONS + account.period).distinct().map { periodOption(it, resources) }
        return SettingValue.Select(
            id = PERIOD + account.uuid,
            title = { getString(R.string.novabird_sync_import_period) },
            displayValueAsSecondaryText = true,
            value = periodOption(account.period, resources),
            options = options.toImmutableList(),
        )
    }

    private fun countSetting(account: AccountScope): SettingValue.Select {
        val options = (COUNT_OPTIONS + account.count).distinct().map { countOption(it, resources) }
        return SettingValue.Select(
            id = COUNT + account.uuid,
            title = { getString(R.string.novabird_sync_import_count) },
            displayValueAsSecondaryText = true,
            value = countOption(account.count, resources),
            options = options.toImmutableList(),
        )
    }

    private fun reload(uuids: List<String>) {
        accounts.value = uuids.mapNotNull { uuid ->
            preferences.getAccount(uuid)?.let {
                AccountScope(uuid, it.displayName, it.maximumPolledMessageAge, it.displayCount)
            }
        }
    }

    // the displayCount setter marks the visible limits as changed, so saveAccount() resets the folders' limits too
    private fun save(uuid: String, period: Int, count: Int) {
        val account = preferences.getAccount(uuid) ?: return
        if (account.maximumPolledMessageAge == period && account.displayCount == count) return
        account.maximumPolledMessageAge = period
        account.displayCount = count
        preferences.saveAccount(account)
    }

    private data class AccountScope(val uuid: String, val name: String, val period: Int, val count: Int)

    @Suppress("MagicNumber")
    companion object {
        private const val EXTRA_ACCOUNT_UUIDS = "accountUuids"
        private const val HEADER = "header:"
        private const val PERIOD = "period:"
        private const val COUNT = "count:"

        // account setting values: days (-1 = no limit), number of messages (0 = all)
        private const val DEFAULT_PERIOD = 365
        private const val ALL = 0
        private const val NO_LIMIT = -1
        private val PERIOD_OPTIONS = listOf(28, 84, 168, 365, 730, NO_LIMIT)
        private val COUNT_OPTIONS = listOf(100, 250, 500, 1000, 2500, 5000, 10000, ALL)

        fun intent(context: Context, accountUuids: List<String>): Intent =
            Intent(context, ImportedAccountsSyncActivity::class.java)
                .putStringArrayListExtra(EXTRA_ACCOUNT_UUIDS, ArrayList(accountUuids))

        private fun periodOption(days: Int, resources: Resources): SelectOption {
            val title = when (days) {
                NO_LIMIT -> resources.getString(R.string.novabird_sync_import_period_all)
                28 -> resources.getString(R.string.novabird_sync_import_period_1_month)
                84 -> resources.getString(R.string.novabird_sync_import_period_3_months)
                168 -> resources.getString(R.string.novabird_sync_import_period_6_months)
                365 -> resources.getString(R.string.novabird_sync_import_period_1_year)
                730 -> resources.getString(R.string.novabird_sync_import_period_2_years)
                else -> resources.getQuantityString(R.plurals.novabird_sync_import_period_days, days, days)
            }
            return SelectOption(id = days.toString(), title = { title })
        }

        private fun countOption(count: Int, resources: Resources): SelectOption {
            val title = if (count == ALL) {
                resources.getString(R.string.novabird_sync_import_count_all)
            } else {
                resources.getQuantityString(R.plurals.novabird_sync_import_count_n, count, count)
            }
            return SelectOption(id = count.toString(), title = { title })
        }
    }
}
