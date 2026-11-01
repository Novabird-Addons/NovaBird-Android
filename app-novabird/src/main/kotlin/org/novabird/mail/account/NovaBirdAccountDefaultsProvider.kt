package org.novabird.mail.account

import net.thunderbird.core.android.account.AccountDefaultsProvider
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.preference.storage.Storage

/**
 * Defaults for new accounts in NovaBird: synchronize all messages of the last year.
 *
 * The download size limit isn't set here. It's replaced by the NovaBird sync limits (mobile data/Wi-Fi).
 */
internal class NovaBirdAccountDefaultsProvider(
    private val defaultProvider: AccountDefaultsProvider,
) : AccountDefaultsProvider {
    override fun applyDefaults(account: LegacyAccountDto) {
        defaultProvider.applyDefaults(account)

        with(account) {
            maximumPolledMessageAge = SYNC_PERIOD_ONE_YEAR
            displayCount = ALL_MESSAGES
            resetChangeMarkers()
        }
    }

    override fun applyOverwrites(account: LegacyAccountDto, storage: Storage) {
        defaultProvider.applyOverwrites(account, storage)
    }

    internal companion object {
        // Value of 'message_age_values' for one year
        const val SYNC_PERIOD_ONE_YEAR = 365

        // Value of 'display_count_values' for all messages
        const val ALL_MESSAGES = 0
    }
}
