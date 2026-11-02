package org.novabird.feature.ai.helper

import android.content.Context
import android.os.BatteryManager
import androidx.core.content.edit

/**
 * Remembers when the paired Helper was last reachable. If it has been away for a day (e.g. the phone is away from
 * home), the phone computes the index vectors itself, but only while charging.
 */
class HelperAbsence(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var lastSeen: Long = preferences.getLong(KEY_LAST_SEEN, 0L)

    /** Call after each answer of the Helper; written at most every few minutes. */
    fun markReachable() {
        val now = clock()
        if (now - lastSeen < WRITE_INTERVAL_MS) return
        lastSeen = now
        preferences.edit { putLong(KEY_LAST_SEEN, now) }
    }

    /** True if the phone should compute the index itself: the Helper has been away for a day and the phone charges. */
    fun shouldComputeLocally(): Boolean {
        if (lastSeen == 0L) {
            // not known yet (paired before this was remembered): start counting now
            markReachable()
        }
        return isAwayLong(lastSeen, clock()) && isCharging()
    }

    private fun isCharging(): Boolean =
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.isCharging == true

    companion object {
        private const val PREFERENCES_NAME = "novabird_ai_helper_seen"
        private const val KEY_LAST_SEEN = "lastSeen"
        private const val WRITE_INTERVAL_MS = 5L * 60 * 1000
        const val AWAY_MS = 24L * 60 * 60 * 1000

        internal fun isAwayLong(lastSeen: Long, now: Long) = lastSeen > 0 && now - lastSeen >= AWAY_MS
    }
}
