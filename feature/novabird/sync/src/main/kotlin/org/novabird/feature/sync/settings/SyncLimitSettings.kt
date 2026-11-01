package org.novabird.feature.sync.settings

/**
 * Size limits for messages that are downloaded completely during synchronization.
 *
 * Each limit applies to a whole message including attachments, in bytes. `0` means "no limit".
 *
 * @property meteredLimit Limit used on metered networks (e.g. mobile data).
 * @property unmeteredLimit Limit used on unmetered networks (e.g. Wi-Fi).
 */
data class SyncLimitSettings(
    val meteredLimit: Int = DEFAULT_METERED_LIMIT,
    val unmeteredLimit: Int = DEFAULT_UNMETERED_LIMIT,
) {
    companion object {
        const val NO_LIMIT = 0

        const val KIB = 1024
        const val MIB = 1024 * KIB

        const val DEFAULT_METERED_LIMIT = 512 * KIB
        const val DEFAULT_UNMETERED_LIMIT = 25 * MIB

        /**
         * Limits offered in the settings screen.
         */
        val LIMIT_OPTIONS = listOf(
            64 * KIB,
            128 * KIB,
            256 * KIB,
            512 * KIB,
            1 * MIB,
            2 * MIB,
            5 * MIB,
            10 * MIB,
            25 * MIB,
            50 * MIB,
            NO_LIMIT,
        )
    }
}
