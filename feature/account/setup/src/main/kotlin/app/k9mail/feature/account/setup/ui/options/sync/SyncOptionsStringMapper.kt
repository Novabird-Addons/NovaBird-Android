package app.k9mail.feature.account.setup.ui.options.sync

import android.content.res.Resources
import app.k9mail.feature.account.setup.R
import app.k9mail.feature.account.setup.domain.entity.EmailCheckFrequency
import app.k9mail.feature.account.setup.domain.entity.EmailDisplayCount
import app.k9mail.feature.account.setup.domain.entity.EmailSyncPeriod

internal fun EmailDisplayCount.toResourceString(resources: Resources): String {
    return if (this == EmailDisplayCount.ALL) {
        resources.getString(R.string.account_setup_options_email_display_count_all)
    } else {
        resources.getQuantityString(
            R.plurals.account_setup_options_email_display_count_messages,
            count,
            count,
        )
    }
}

internal fun EmailSyncPeriod.toResourceString(resources: Resources): String = resources.getString(
    when (this) {
        EmailSyncPeriod.ONE_MONTH -> R.string.account_setup_options_sync_period_1_month
        EmailSyncPeriod.THREE_MONTHS -> R.string.account_setup_options_sync_period_3_months
        EmailSyncPeriod.SIX_MONTHS -> R.string.account_setup_options_sync_period_6_months
        EmailSyncPeriod.ONE_YEAR -> R.string.account_setup_options_sync_period_1_year
        EmailSyncPeriod.TWO_YEARS -> R.string.account_setup_options_sync_period_2_years
        EmailSyncPeriod.ALL -> R.string.account_setup_options_sync_period_all
    },
)

@Suppress("MagicNumber")
internal fun EmailCheckFrequency.toResourceString(resources: Resources): String {
    return when (minutes) {
        -1 -> resources.getString(R.string.account_setup_options_email_check_frequency_never)

        in 1..59 -> resources.getQuantityString(
            R.plurals.account_setup_options_email_check_frequency_minutes,
            minutes,
            minutes,
        )

        else -> resources.getQuantityString(
            R.plurals.account_setup_options_email_check_frequency_hours,
            (minutes / 60),
            (minutes / 60),
        )
    }
}
