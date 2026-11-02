package app.k9mail.feature.account.setup.domain.entity

import kotlinx.collections.immutable.toImmutableList

/** How far back messages are synchronized (account setting "maximumPolledMessageAge", -1 = no limit). */
@Suppress("MagicNumber")
enum class EmailSyncPeriod(
    val days: Int,
) {
    ONE_MONTH(28),
    THREE_MONTHS(84),
    SIX_MONTHS(168),
    ONE_YEAR(365),
    TWO_YEARS(730),
    ALL(-1),
    ;

    companion object {
        fun all() = entries.toImmutableList()

        fun fromDays(days: Int): EmailSyncPeriod? = all().find { it.days == days }
    }
}
