package org.novabird.feature.sync.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface SyncLimitSettingsRepository {
    val settings: StateFlow<SyncLimitSettings>

    fun getSettings(): SyncLimitSettings = settings.value

    fun update(settings: SyncLimitSettings)
}

/**
 * Stores the NovaBird sync limits in their own [android.content.SharedPreferences] file, separate from the account
 * settings. Reads are synchronous because the limit is queried on the synchronization thread.
 */
internal class SharedPreferencesSyncLimitSettingsRepository(
    context: Context,
) : SyncLimitSettingsRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val mutableSettings = MutableStateFlow(load())
    override val settings: StateFlow<SyncLimitSettings> = mutableSettings.asStateFlow()

    override fun update(settings: SyncLimitSettings) {
        val sanitized = settings.sanitized()
        preferences.edit {
            putInt(KEY_METERED_LIMIT, sanitized.meteredLimit)
            putInt(KEY_UNMETERED_LIMIT, sanitized.unmeteredLimit)
        }
        mutableSettings.value = sanitized
    }

    private fun load(): SyncLimitSettings {
        return SyncLimitSettings(
            meteredLimit = preferences.getInt(KEY_METERED_LIMIT, SyncLimitSettings.DEFAULT_METERED_LIMIT),
            unmeteredLimit = preferences.getInt(KEY_UNMETERED_LIMIT, SyncLimitSettings.DEFAULT_UNMETERED_LIMIT),
        ).sanitized()
    }

    private fun SyncLimitSettings.sanitized(): SyncLimitSettings {
        return SyncLimitSettings(
            meteredLimit = meteredLimit.coerceAtLeast(SyncLimitSettings.NO_LIMIT),
            unmeteredLimit = unmeteredLimit.coerceAtLeast(SyncLimitSettings.NO_LIMIT),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "novabird_sync"
        const val KEY_METERED_LIMIT = "meteredLimit"
        const val KEY_UNMETERED_LIMIT = "unmeteredLimit"
    }
}
