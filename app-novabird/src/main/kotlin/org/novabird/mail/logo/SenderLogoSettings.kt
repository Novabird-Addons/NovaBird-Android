package org.novabird.mail.logo

import android.content.Context
import androidx.core.content.edit
import com.bumptech.glide.Glide

/** Switch for sender logos (BIMI); off by default, because the logos are loaded from the senders' servers. */
class SenderLogoSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Volatile
    var isEnabled: Boolean = preferences.getBoolean(KEY_ENABLED, false)
        private set

    fun setEnabled(context: Context, enabled: Boolean) {
        isEnabled = enabled
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
        // contact pictures are kept in Glide's memory cache: drop them so the list shows logos or letters right away
        Glide.get(context).clearMemory()
    }

    private companion object {
        const val PREFERENCES = "novabird_logos"
        const val KEY_ENABLED = "enabled"
    }
}
