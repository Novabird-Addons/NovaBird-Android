package org.novabird.feature.search.internal.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.novabird.feature.search.NovaBirdSearchIndexer

/**
 * Runs [NovaBirdSearchIndexer.indexChanges]. Scheduled by [NovaBirdIndexScheduler].
 *
 * Created by WorkManager's default factory (`K9WorkerFactory` only handles classes in `com.fsck.k9`).
 */
class NovaBirdIndexWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    private val indexer: NovaBirdSearchIndexer by inject()
    private val scheduler: NovaBirdIndexScheduler by inject()

    override suspend fun doWork(): Result {
        indexer.indexChanges()
        scheduler.requestVectors()
        return Result.success()
    }
}
