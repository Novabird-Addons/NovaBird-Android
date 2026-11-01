package org.novabird.feature.search.internal.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.novabird.feature.search.NovaBirdVectorIndexer

/**
 * Runs [NovaBirdVectorIndexer.indexVectors] until the queue is empty, the backend is unavailable or WorkManager stops the
 * worker; in the last case it asks for another run. Scheduled by [NovaBirdIndexScheduler.requestVectors].
 */
class NovaBirdVectorWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    private val vectorIndexer: NovaBirdVectorIndexer by inject()
    private val scheduler: NovaBirdIndexScheduler by inject()

    override suspend fun doWork(): Result {
        vectorIndexer.indexVectors(shouldStop = { isStopped })
        if (isStopped) scheduler.requestVectors()
        return Result.success()
    }
}
