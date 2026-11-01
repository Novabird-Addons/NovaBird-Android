package org.novabird.feature.sync.backfill

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Runs [PartialMessageBackfiller]. Scheduled with the constraint "unmetered network" by [PartialMessageBackfillScheduler].
 *
 * Created by WorkManager's default factory (`K9WorkerFactory` only handles classes in `com.fsck.k9`).
 */
class PartialMessageBackfillWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    private val backfiller: PartialMessageBackfiller by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val result = backfiller.run(isStopped = { isStopped })

        if (result.stopped) Result.retry() else Result.success()
    }
}
