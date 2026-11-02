package org.novabird.feature.sync.backfill

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.k9mail.legacy.message.controller.MessagingListener
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import java.util.concurrent.TimeUnit
import net.thunderbird.core.android.account.LegacyAccountDto

/**
 * Enqueues [PartialMessageBackfillWorker] with the constraint "unmetered network".
 */
interface BackfillWorkEnqueuer {
    /**
     * Enqueues a one-time run unless one is already pending.
     */
    fun enqueueOneTime()

    /**
     * Makes sure the periodic fallback run is scheduled.
     */
    fun ensurePeriodic()
}

internal class WorkManagerBackfillWorkEnqueuer(
    private val workManager: WorkManager,
) : BackfillWorkEnqueuer {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .build()

    override fun enqueueOneTime() {
        val request = OneTimeWorkRequestBuilder<PartialMessageBackfillWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<PartialMessageBackfillWorker>(
            PERIODIC_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val ONE_TIME_WORK_NAME = "NovaBirdPartialMessageBackfill"
        const val PERIODIC_WORK_NAME = "NovaBirdPartialMessageBackfillPeriodic"
        const val PERIODIC_INTERVAL_HOURS = 6L
    }
}

/**
 * Triggers the backfill after each folder synchronization, when the settings change and periodically.
 *
 * WorkManager holds a pending one-time run until an unmetered network is available, which covers network changes.
 */
class PartialMessageBackfillScheduler(
    private val enqueuer: BackfillWorkEnqueuer,
) {
    fun start() {
        enqueuer.ensurePeriodic()
        enqueuer.enqueueOneTime()
    }

    fun scheduleBackfill() {
        enqueuer.enqueueOneTime()
    }

    val syncListener: MessagingListener = object : SimpleMessagingListener() {
        override fun synchronizeMailboxFinished(account: LegacyAccountDto, folderId: Long) {
            scheduleBackfill()
        }
    }
}
