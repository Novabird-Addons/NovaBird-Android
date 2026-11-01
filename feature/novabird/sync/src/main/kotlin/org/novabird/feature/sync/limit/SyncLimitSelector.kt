package org.novabird.feature.sync.limit

import org.novabird.feature.sync.settings.SyncLimitSettings

/**
 * Chooses the download limit depending on the type of network.
 */
object SyncLimitSelector {
    fun select(settings: SyncLimitSettings, isMetered: Boolean): Int {
        return if (isMetered) settings.meteredLimit else settings.unmeteredLimit
    }
}
