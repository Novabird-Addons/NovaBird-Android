package org.novabird.feature.sync.backfill

import com.fsck.k9.backend.BackendManager
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.logging.Logger
import org.novabird.feature.sync.settings.SyncLimitSettingsRepository

fun interface CompleteMessageDownloader {
    fun downloadCompleteMessage(account: LegacyAccountDto, folderServerId: String, messageServerId: String)
}

internal class BackendCompleteMessageDownloader(
    private val backendManager: BackendManager,
) : CompleteMessageDownloader {
    override fun downloadCompleteMessage(account: LegacyAccountDto, folderServerId: String, messageServerId: String) {
        // the backfill runs in its own worker thread; the backend call is suspending since Thunderbird 25
        val backend = backendManager.getBackend(account.uuid)
        runBlocking { backend.downloadCompleteMessage(folderServerId, messageServerId) }
    }
}

data class BackfillResult(
    val downloaded: Int,
    val failed: Int,
    val stopped: Boolean,
)

/**
 * Downloads partially downloaded messages completely if they don't exceed the limit for unmetered networks.
 *
 * Only messages within the synchronization window of the account ([LegacyAccountDto.earliestPollDate]) are considered.
 */
class PartialMessageBackfiller(
    private val accounts: () -> List<LegacyAccountDto>,
    private val finder: PartialMessageFinder,
    private val downloader: CompleteMessageDownloader,
    private val settingsRepository: SyncLimitSettingsRepository,
    private val logger: Logger,
) {
    // A single message (or account) that can't be read or downloaded must not stop the whole backfill.
    @Suppress("TooGenericExceptionCaught")
    fun run(isStopped: () -> Boolean): BackfillResult {
        val maxSize = settingsRepository.getSettings().unmeteredLimit
        var downloaded = 0
        var failed = 0

        for (account in accounts()) {
            val earliestDate = account.earliestPollDate
            val candidates = try {
                finder.findPartialMessages(account, earliestDate)
            } catch (e: Exception) {
                logger.warn(TAG, e) { "Failed to read partially downloaded messages" }
                continue
            }
            val selected = PartialMessageSelector.select(candidates, maxSize, earliestDate)

            for (candidate in selected) {
                if (isStopped()) {
                    return BackfillResult(downloaded, failed, stopped = true)
                }

                try {
                    downloader.downloadCompleteMessage(account, candidate.folderServerId, candidate.messageServerId)
                    downloaded++
                } catch (e: Exception) {
                    logger.warn(TAG, e) { "Failed to download message completely" }
                    failed++
                }
            }
        }

        logger.info(TAG) { "Downloaded $downloaded partial messages completely, $failed failed" }
        return BackfillResult(downloaded, failed, stopped = false)
    }

    private companion object {
        const val TAG = "NovaBirdBackfill"
    }
}
