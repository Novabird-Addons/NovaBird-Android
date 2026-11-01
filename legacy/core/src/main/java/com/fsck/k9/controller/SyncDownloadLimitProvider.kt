package com.fsck.k9.controller

import net.thunderbird.core.android.account.LegacyAccountDto
import org.koin.core.context.GlobalContext

/**
 * Provides the maximum size of a message that is downloaded completely during synchronization.
 *
 * Messages exceeding this size are only downloaded partially. A value of `0` means "no limit".
 *
 * Apps may bind their own implementation via Koin. If none is bound, [DefaultSyncDownloadLimitProvider] is used.
 */
fun interface SyncDownloadLimitProvider {
    fun getMaximumAutoDownloadMessageSize(account: LegacyAccountDto): Int
}

/**
 * Uses the per-account setting [LegacyAccountDto.maximumAutoDownloadMessageSize].
 */
object DefaultSyncDownloadLimitProvider : SyncDownloadLimitProvider {
    override fun getMaximumAutoDownloadMessageSize(account: LegacyAccountDto): Int {
        return account.maximumAutoDownloadMessageSize
    }
}

internal object SyncDownloadLimits {
    @JvmStatic
    fun getMaximumAutoDownloadMessageSize(account: LegacyAccountDto): Int {
        val provider = GlobalContext.getOrNull()?.getOrNull<SyncDownloadLimitProvider>()
            ?: DefaultSyncDownloadLimitProvider

        return provider.getMaximumAutoDownloadMessageSize(account)
    }
}
