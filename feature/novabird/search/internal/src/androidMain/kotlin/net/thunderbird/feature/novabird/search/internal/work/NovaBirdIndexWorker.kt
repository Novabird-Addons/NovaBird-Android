package net.thunderbird.feature.novabird.search.internal.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import net.thunderbird.feature.novabird.search.NovaBirdSearchIndexer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

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

    override suspend fun doWork(): Result {
        indexer.indexChanges()
        return Result.success()
    }
}
