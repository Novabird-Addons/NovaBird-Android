package org.novabird.feature.search.internal.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import org.novabird.feature.search.NovaBirdIndexTrigger

/**
 * Schedules the indexer: once after each sync ([requestIndexing], debounced by WorkManager's unique work) and
 * periodically as a fallback, so no hooks in the K-9 storage code are needed.
 */
class NovaBirdIndexScheduler(
    private val context: Context,
    private val vectorsOnlyWhileCharging: () -> Boolean = { false },
) : NovaBirdIndexTrigger {

    /** Call after a sync (or at app start); runs the indexer soon, at most one run queued at a time. */
    override fun requestIndexing() {
        val request = OneTimeWorkRequestBuilder<NovaBirdIndexWorker>()
            .setInitialDelay(DELAY_SECONDS, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Computes vectors for queued messages (semantic search), soon and at most one run queued. Called after each index
     * run and when the vector settings change (Helper paired, model downloaded).
     */
    override fun requestVectors() {
        val request = OneTimeWorkRequestBuilder<NovaBirdVectorWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresStorageNotLow(true)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(vectorsOnlyWhileCharging())
                    .build(),
            )
            .build()
        // replace: the constraints may have changed (Helper paired: no charger needed any more); appending would wait for
        // an older run still waiting for the charger. A replaced running run loses at most its current batch.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_VECTORS, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Periodic fallback (e.g. after sync from other code paths), and a daily vector run while charging (the phone catches
     * up there if the Helper has been away for a day).
     */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<NovaBirdIndexWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build(),
            )
            .build()
        WorkManager.getInstance(
            context,
        ).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)

        val vectors = PeriodicWorkRequestBuilder<NovaBirdVectorWorker>(VECTORS_PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_VECTORS_DAILY, ExistingPeriodicWorkPolicy.KEEP, vectors)
    }

    private companion object {
        const val WORK_NOW = "novabird-search-index"
        const val WORK_PERIODIC = "novabird-search-index-periodic"
        const val WORK_VECTORS = "novabird-search-vectors"
        const val WORK_VECTORS_DAILY = "novabird-search-vectors-daily"
        const val DELAY_SECONDS = 10L
        const val PERIOD_HOURS = 6L
        const val VECTORS_PERIOD_HOURS = 24L
    }
}
