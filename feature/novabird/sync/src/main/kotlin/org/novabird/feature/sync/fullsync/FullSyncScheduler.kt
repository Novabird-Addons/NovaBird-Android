package org.novabird.feature.sync.fullsync

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** State of the full sync of one account, as shown on [FullSyncActivity]. */
sealed interface FullSyncState {
    data object Idle : FullSyncState

    /** Started, waiting for Wi-Fi (or for Android to run it). */
    data object Waiting : FullSyncState
    data class Running(val progress: FullSyncProgress) : FullSyncState
    data object Finished : FullSyncState

    /** [reason]: one of the `ERROR_` values of [FullSyncScheduler], or null. */
    data class Failed(val reason: String?) : FullSyncState
}

/** Which folders a full sync loads. */
enum class FullSyncScope {
    /** the folders that are synchronized anyway (for a new account only the inbox) */
    SYNCED,
    INBOX,

    /** first switches synchronization on for all folders except trash and spam ([AccountFolderSetup]) */
    ALL,
}

/** Starts, stops and observes the full sync ([FullSyncWorker]) of an account; one at a time per account. */
class FullSyncScheduler(private val workManager: WorkManager) {
    /** Starts the full sync of the account (only on Wi-Fi); does nothing if it is already waiting or running. */
    fun start(accountUuid: String, scope: FullSyncScope = FullSyncScope.SYNCED) {
        val request = OneTimeWorkRequestBuilder<FullSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setInputData(workDataOf(ARG_ACCOUNT_UUID to accountUuid, ARG_SCOPE to scope.name))
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(workName(accountUuid), ExistingWorkPolicy.KEEP, request)
    }

    fun stop(accountUuid: String) {
        workManager.cancelUniqueWork(workName(accountUuid))
    }

    fun state(accountUuid: String): Flow<FullSyncState> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(accountUuid)).map { infos ->
            infos.lastOrNull()?.toState() ?: FullSyncState.Idle
        }

    private fun WorkInfo.toState(): FullSyncState = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> FullSyncState.Waiting

        WorkInfo.State.RUNNING -> FullSyncState.Running(
            FullSyncProgress(
                folderIndex = progress.getInt(KEY_FOLDER_INDEX, 0),
                folderCount = progress.getInt(KEY_FOLDER_COUNT, 0),
                folderName = progress.getString(KEY_FOLDER_NAME).orEmpty(),
                done = progress.getInt(KEY_DONE, 0),
                total = progress.getInt(KEY_TOTAL, 0),
            ),
        )

        WorkInfo.State.SUCCEEDED -> FullSyncState.Finished

        WorkInfo.State.FAILED -> FullSyncState.Failed(outputData.getString(KEY_ERROR))

        WorkInfo.State.CANCELLED -> FullSyncState.Idle
    }

    companion object {
        const val ERROR_LOGIN = "login"
        const val ERROR_NO_FOLDERS = "noFolders"
        const val ERROR_FOLDERS = "folders"

        internal const val KEY_ERROR = "error"
        internal const val ARG_ACCOUNT_UUID = "accountUuid"
        internal const val ARG_SCOPE = "scope"
        internal const val KEY_FOLDER_INDEX = "folderIndex"
        internal const val KEY_FOLDER_COUNT = "folderCount"
        internal const val KEY_FOLDER_NAME = "folderName"
        internal const val KEY_DONE = "done"
        internal const val KEY_TOTAL = "total"
        private const val TAG = "novabird_full_sync"

        internal fun workName(accountUuid: String) = "novabird_full_sync_$accountUuid"
    }
}
