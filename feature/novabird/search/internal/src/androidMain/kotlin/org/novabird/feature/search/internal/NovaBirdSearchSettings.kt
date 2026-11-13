package org.novabird.feature.search.internal

import android.content.Context

/**
 * Search settings of the phone. [ageLevel]: older mails count less with "best first" and relevance, like the desktop
 * setting "Neuere Mails bevorzugen" (Normal by default). A time in the query itself turns it off (rank.js).
 */
class NovaBirdSearchSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Volatile
    var ageLevel: AgeLevel = AgeLevel.from(preferences.getString(KEY_AGE_LEVEL, null))
        private set

    fun updateAgeLevel(level: AgeLevel) {
        ageLevel = level
        preferences.edit().putString(KEY_AGE_LEVEL, level.value).apply()
    }

    /** Values as in the desktop add-on (settings.js `ageLevel`). */
    enum class AgeLevel(val value: String) {
        NORMAL("normal"),
        STRONG("strong"),
        OFF("off"),
        ;

        companion object {
            fun from(value: String?): AgeLevel = entries.firstOrNull { it.value == value } ?: NORMAL
        }
    }

    private companion object {
        const val PREFERENCES = "novabird_search"
        const val KEY_AGE_LEVEL = "ageLevel"
    }
}
