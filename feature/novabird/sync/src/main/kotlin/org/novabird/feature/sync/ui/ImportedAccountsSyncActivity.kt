package org.novabird.feature.sync.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.fsck.k9.Preferences
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.ui.base.BaseActivity
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
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
import org.koin.core.qualifier.named
import org.novabird.feature.sync.R
import org.novabird.feature.sync.fullsync.AccountFolderSetup
import org.novabird.feature.sync.fullsync.FullSyncScheduler
import org.novabird.feature.sync.fullsync.FullSyncScope

/**
 * Makes accounts from a settings import (QR code from Thunderbird on the PC, settings file) usable on the phone. They
 * skip the setup wizard and keep the PC's values: often only 25 messages, "notify" off (the PC still shows new mail),
 * no push. Asks period and count, whether to load the mailbox now, and at the end notifications, push and the
 * background activity. Period and count are saved right away (NovaBird's defaults when it opens), the rest when the
 * screen closes.
 */
@Suppress("TooManyFunctions") // one small builder per row of the screen
class ImportedAccountsSyncActivity : BaseActivity() {
    private val themeProvider: FeatureThemeProvider by inject()
    private val settingViewProvider: SettingViewProvider by inject()
    private val preferences: Preferences by inject()
    private val controller: MessagingController by inject()
    private val folderSetup: AccountFolderSetup by inject()
    private val fullSync: FullSyncScheduler by inject()
    private val appScope: CoroutineScope by inject(named("AppCoroutineScope"))

    private val choices: ImportChoices by viewModels()
    private val accounts get() = choices.accounts
    private val backgroundAllowed = MutableStateFlow(true)
    private var applied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uuids = intent.getStringArrayListExtra(EXTRA_ACCOUNT_UUIDS).orEmpty()
        setResult(RESULT_OK)

        lifecycleScope.launch {
            // after turning the phone the choices are still there
            if (accounts.value.isNotEmpty()) return@launch
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

    override fun onResume() {
        super.onResume()
        // back from the battery dialog
        backgroundAllowed.value = isBackgroundAllowed()
    }

    // also the back button and the arrow in the top bar
    override fun finish() {
        if (!applied) {
            applied = true
            val choices = accounts.value
            appScope.launch(Dispatchers.IO) { choices.forEach { runCatching { apply(it) } } }
        }
        super.finish()
    }

    @Composable
    private fun Screen() {
        val list by accounts.collectAsState()
        val background by backgroundAllowed.collectAsState()
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
                add(fullSyncSetting(account))
            }
            // asked at the end (user decision): what the phone does with new mail
            for (account in list) {
                add(
                    SettingDecoration.SectionHeader(
                        id = PHONE + account.uuid,
                        title = { getString(R.string.novabird_sync_import_phone_header, account.name) },
                    ),
                )
                add(notifySetting(account))
                add(pushSetting(account))
            }
            if (!background) add(backgroundSetting())
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
        val uuid = setting.id.substringAfter(':')
        val account = accounts.value.firstOrNull { it.uuid == uuid }
        val changed = account?.let { changedBy(it, setting) }
        if (account != null && changed != null) {
            accounts.value = accounts.value.map { if (it.uuid == uuid) changed else it }
            if (changed.period != account.period || changed.count != account.count) {
                lifecycleScope.launch(Dispatchers.IO) { save(uuid, changed.period, changed.count) }
            }
        }
    }

    private fun changedBy(account: AccountScope, setting: SettingValue<*>): AccountScope? {
        val option = (setting.value as? SelectOption)?.id
        val switch = setting.value as? Boolean
        val id = setting.id
        return when {
            id.startsWith(PERIOD) -> option?.toIntOrNull()?.let { account.copy(period = it) }
            id.startsWith(COUNT) -> option?.toIntOrNull()?.let { account.copy(count = it) }
            id.startsWith(FULL_SYNC) -> option?.let { account.copy(fullSync = fullSyncScope(it)) }
            id.startsWith(NOTIFY) -> switch?.let { account.copy(notify = it) }
            id.startsWith(PUSH) -> switch?.let { account.copy(push = it) }
            else -> null
        }
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

    private fun fullSyncSetting(account: AccountScope): SettingValue.Select {
        val options = listOf(FullSyncScope.ALL, FullSyncScope.INBOX, null).map { fullSyncOption(it) }
        return SettingValue.Select(
            id = FULL_SYNC + account.uuid,
            title = { getString(R.string.novabird_sync_import_full) },
            description = {
                if (account.missingCredentials) getString(R.string.novabird_sync_import_no_password) else null
            },
            displayValueAsSecondaryText = true,
            value = fullSyncOption(account.fullSync),
            options = options.toImmutableList(),
        )
    }

    private fun fullSyncOption(scope: FullSyncScope?) = SelectOption(
        id = scope?.name ?: NONE,
        title = {
            getString(
                when (scope) {
                    FullSyncScope.ALL -> R.string.novabird_sync_import_full_all
                    FullSyncScope.INBOX, FullSyncScope.SYNCED -> R.string.novabird_sync_import_full_inbox
                    null -> R.string.novabird_sync_import_full_none
                },
            )
        },
    )

    private fun notifySetting(account: AccountScope) = SettingValue.Switch(
        id = NOTIFY + account.uuid,
        title = { getString(R.string.novabird_sync_import_notify) },
        description = { getString(R.string.novabird_sync_import_notify_note) },
        value = account.notify,
    )

    private fun pushSetting(account: AccountScope) = SettingValue.Switch(
        id = PUSH + account.uuid,
        title = { getString(R.string.novabird_sync_import_push) },
        description = {
            getString(
                if (account.missingCredentials) {
                    R.string.novabird_sync_import_no_password
                } else {
                    R.string.novabird_sync_import_push_note
                },
            )
        },
        value = account.push,
    )

    private fun backgroundSetting() = SettingValue.ActionText(
        id = "background",
        title = { getString(R.string.novabird_sync_import_background) },
        description = { getString(R.string.novabird_sync_import_background_note) },
        value = "",
        onClick = ::requestBackgroundActivity,
    )

    private fun isBackgroundAllowed(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    // the permission is declared by the app; some phones only have the settings list
    @SuppressLint("BatteryLife")
    private fun requestBackgroundActivity() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (_: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun reload(uuids: List<String>) {
        accounts.value = uuids.mapNotNull { uuid ->
            preferences.getAccount(uuid)?.let {
                val missing = it.incomingServerSettings.isMissingCredentials
                AccountScope(
                    uuid = uuid,
                    name = it.displayName,
                    period = it.maximumPolledMessageAge,
                    count = it.displayCount,
                    fullSync = if (missing) null else FullSyncScope.ALL,
                    missingCredentials = missing,
                )
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

    // runs after the screen is gone: the folder list may have to come from the server first
    private fun apply(choice: AccountScope) {
        val account = preferences.getAccount(choice.uuid) ?: return
        if (account.isNotifyNewMail != choice.notify) {
            account.isNotifyNewMail = choice.notify
            preferences.saveAccount(account)
        }
        if (choice.missingCredentials) return
        if (choice.push) {
            if (account.inboxFolderId == null) controller.refreshFolderListBlocking(account)
            preferences.getAccount(choice.uuid)?.let(folderSetup::enableInboxPush)
        }
        choice.fullSync?.let { fullSync.start(choice.uuid, it) }
    }

    /** What the user chose so far; outlives the activity when the phone is turned. */
    internal class ImportChoices : ViewModel() {
        val accounts = MutableStateFlow<List<AccountScope>>(emptyList())
    }

    internal data class AccountScope(
        val uuid: String,
        val name: String,
        val period: Int,
        val count: Int,
        val fullSync: FullSyncScope?,
        val missingCredentials: Boolean,
        val notify: Boolean = true,
        val push: Boolean = !missingCredentials,
    )

    @Suppress("MagicNumber")
    companion object {
        private const val EXTRA_ACCOUNT_UUIDS = "accountUuids"
        private const val HEADER = "header:"
        private const val PHONE = "phone:"
        private const val PERIOD = "period:"
        private const val COUNT = "count:"
        private const val FULL_SYNC = "fullSync:"
        private const val NOTIFY = "notify:"
        private const val PUSH = "push:"
        private const val NONE = "none"

        // account setting values: days (-1 = no limit), number of messages (0 = all)
        private const val DEFAULT_PERIOD = 365
        private const val ALL = 0
        private const val NO_LIMIT = -1
        private val PERIOD_OPTIONS = listOf(28, 84, 168, 365, 730, NO_LIMIT)
        private val COUNT_OPTIONS = listOf(100, 250, 500, 1000, 2500, 5000, 10000, ALL)

        fun intent(context: Context, accountUuids: List<String>): Intent =
            Intent(context, ImportedAccountsSyncActivity::class.java)
                .putStringArrayListExtra(EXTRA_ACCOUNT_UUIDS, ArrayList(accountUuids))

        private fun fullSyncScope(id: String): FullSyncScope? = FullSyncScope.entries.firstOrNull { it.name == id }

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
