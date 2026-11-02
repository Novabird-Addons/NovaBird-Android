package org.novabird.mail.feature

import android.content.Context
import net.thunderbird.core.preference.display.inboxSettings.DisplayInboxSettingsPreferenceManager
import net.thunderbird.core.preference.update

/**
 * Display settings of the NovaBird design that differ from Thunderbird's defaults. Applied once per design version,
 * so later changes by the user stay.
 */
internal class DesignDefaults(
    private val context: Context,
    private val inboxSettings: DisplayInboxSettingsPreferenceManager,
) {
    fun apply() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= VERSION) return

        // message list: sender in the first line, subject and preview below
        inboxSettings.update { it.copy(isMessageListSenderAboveSubject = true) }

        prefs.edit().putInt(KEY_VERSION, VERSION).apply()
    }

    private companion object {
        const val PREFS = "novabird_design"
        const val KEY_VERSION = "defaultsVersion"
        const val VERSION = 1
    }
}
