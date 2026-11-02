package org.novabird.feature.protection

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Checks new mails after a sync ([ProtectionService.checkNewMails]). Scheduled by [ProtectionScheduler].
 *
 * Created by WorkManager's default factory (`K9WorkerFactory` only handles classes in `com.fsck.k9`).
 */
class ProtectionWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    private val service: ProtectionService by inject()

    override suspend fun doWork(): Result {
        service.checkNewMails()
        return Result.success()
    }
}

/** Runs [ProtectionWorker] soon after a sync; at most one run queued, nothing while the protection is off. */
class ProtectionScheduler(
    private val context: Context,
    private val settings: ProtectionSettings,
) {
    fun requestCheck() {
        if (!settings.enabled) return
        val request = OneTimeWorkRequestBuilder<ProtectionWorker>()
            .setInitialDelay(DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        // append: a sync that ends while a run is going still gets its mails checked
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val WORK_NAME = "novabird-protection-check"
        const val DELAY_SECONDS = 2L
    }
}
