package org.novabird.feature.sync.backfill

import java.util.Date
import org.novabird.feature.sync.settings.SyncLimitSettings

/**
 * A message that was only downloaded partially.
 *
 * @property estimatedSize Estimated size of the complete message in bytes, or `null` if it is unknown.
 */
data class PartialMessageCandidate(
    val folderServerId: String,
    val messageServerId: String,
    val date: Long,
    val estimatedSize: Long?,
)

/**
 * Decides which partially downloaded messages are downloaded completely when on an unmetered network.
 */
object PartialMessageSelector {
    /**
     * @param maxSize Maximum size of a message in bytes, `0` means "no limit".
     * @param earliestDate Only messages on or after this date are selected. `null` means "all messages".
     * @return The selected messages, newest first.
     */
    fun select(
        candidates: List<PartialMessageCandidate>,
        maxSize: Int,
        earliestDate: Date?,
    ): List<PartialMessageCandidate> {
        val earliestTime = earliestDate?.time

        return candidates
            .filter { candidate -> earliestTime == null || candidate.date >= earliestTime }
            .filter { candidate -> isWithinLimit(candidate.estimatedSize, maxSize) }
            .sortedByDescending { it.date }
    }

    private fun isWithinLimit(estimatedSize: Long?, maxSize: Int): Boolean {
        return when {
            maxSize == SyncLimitSettings.NO_LIMIT -> true

            // Without a size we can't guarantee the limit. Leave it to the user to download such a message.
            estimatedSize == null -> false

            else -> estimatedSize <= maxSize
        }
    }
}
