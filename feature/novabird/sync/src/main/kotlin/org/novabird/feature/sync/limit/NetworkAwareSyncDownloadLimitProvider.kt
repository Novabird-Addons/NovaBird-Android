package org.novabird.feature.sync.limit

import com.fsck.k9.controller.SyncDownloadLimitProvider
import net.thunderbird.core.android.account.LegacyAccountDto
import org.novabird.feature.sync.settings.SyncLimitSettingsRepository

/**
 * Replaces the per-account setting "Fetch messages up to" with a limit for metered and one for unmetered networks.
 */
internal class NetworkAwareSyncDownloadLimitProvider(
    private val settingsRepository: SyncLimitSettingsRepository,
    private val networkState: NetworkState,
) : SyncDownloadLimitProvider {
    override fun getMaximumAutoDownloadMessageSize(account: LegacyAccountDto): Int {
        return SyncLimitSelector.select(
            settings = settingsRepository.getSettings(),
            isMetered = networkState.isActiveNetworkMetered(),
        )
    }
}
