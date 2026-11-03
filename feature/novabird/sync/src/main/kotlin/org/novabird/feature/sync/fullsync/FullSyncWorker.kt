package org.novabird.feature.sync.fullsync

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import com.fsck.k9.Preferences
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.mailstore.LocalStoreProvider
import com.fsck.k9.notification.NotificationChannelManager
import com.fsck.k9.notification.NotificationResourceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.logging.Logger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.novabird.feature.sync.R
import org.novabird.feature.sync.backfill.PartialMessageBackfillScheduler

/**
 * Loads the whole mailbox of one account in one go: every synchronized folder with the account's period and number
 * of messages, then the partially downloaded messages completely. Scheduled by [FullSyncScheduler] for Wi-Fi only.
 *
 * Runs as a foreground service (notification with progress), so Android doesn't put it to sleep with the screen off
 * like the normal background sync. If Wi-Fi goes away WorkManager stops it and starts it again later; the sync is
 * incremental, so folders already loaded go quickly. Android allows such a service about 6 hours a day.
 *
 * Created by WorkManager's default factory (`K9WorkerFactory` only handles classes in `com.fsck.k9`).
 */
class FullSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    private val preferences: Preferences by inject()
    private val controller: MessagingController by inject()
    private val localStoreProvider: LocalStoreProvider by inject()
    private val channels: NotificationChannelManager by inject()
    private val icons: NotificationResourceProvider by inject()
    private val backfillScheduler: PartialMessageBackfillScheduler by inject()
    private val logger: Logger by inject()

    private val accountUuid get() = requireNotNull(inputData.getString(FullSyncScheduler.ARG_ACCOUNT_UUID))

    @Volatile
    private var progress = FullSyncProgress()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val account = preferences.getAccount(accountUuid) ?: return@withContext Result.success()

        // the account may have been set up a moment ago: get the folder list first
        controller.refreshFolderListBlocking(account)
        val folders = syncedFolders(account)
        progress = FullSyncProgress(folderCount = folders.size)
        showProgress(account)

        val listener = ProgressListener(account)
        controller.addListener(listener)
        try {
            for ((index, folder) in folders.withIndex()) {
                if (isStopped) return@withContext Result.retry()
                progress = progress.copy(folderIndex = index + 1, folderName = folder.name, done = 0, total = 0)
                showProgress(account)
                controller.synchronizeMailboxBlocking(account, folder.serverId)
            }
        } finally {
            controller.removeListener(listener)
        }

        // then the messages that only came partially (size limit) are completed, also only on Wi-Fi
        backfillScheduler.scheduleBackfill()
        logger.info(TAG) { "Full sync finished: ${folders.size} folders" }
        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val account = preferences.getAccount(accountUuid)
        return foregroundInfo(account?.displayName.orEmpty(), account?.accountNumber ?: 0)
    }

    private fun syncedFolders(account: LegacyAccountDto): List<FolderToSync> {
        val localStore = localStoreProvider.getInstance(account)
        return localStore.getPersonalNamespaces(false).mapNotNull { folder ->
            folder.open()
            // like the normal mail check: only folders that are shown and synchronized
            if (folder.isLocalOnly || !folder.isVisible || !folder.isSyncEnabled) {
                null
            } else {
                FolderToSync(folder.serverId, folder.name ?: folder.serverId)
            }
        }
    }

    private suspend fun showProgress(account: LegacyAccountDto) {
        val p = progress
        setProgress(
            workDataOf(
                FullSyncScheduler.KEY_FOLDER_INDEX to p.folderIndex,
                FullSyncScheduler.KEY_FOLDER_COUNT to p.folderCount,
                FullSyncScheduler.KEY_FOLDER_NAME to p.folderName,
                FullSyncScheduler.KEY_DONE to p.done,
                FullSyncScheduler.KEY_TOTAL to p.total,
            ),
        )
        // started from the background (Wi-Fi came back) Android may refuse the foreground service: go on without
        @Suppress("TooGenericExceptionCaught")
        try {
            setForeground(foregroundInfo(account.displayName, account.accountNumber))
        } catch (e: Exception) {
            logger.info(TAG) { "No foreground service: ${e::class.simpleName}" }
        }
    }

    private fun foregroundInfo(accountName: String, accountNumber: Int): ForegroundInfo {
        val p = progress
        val text = when {
            p.folderIndex == 0 -> applicationContext.getString(R.string.novabird_sync_full_preparing)

            p.total > 0 -> applicationContext.getString(
                R.string.novabird_sync_full_progress_messages,
                p.folderIndex,
                p.folderCount,
                p.folderName,
                p.done,
                p.total,
            )

            else -> applicationContext.getString(
                R.string.novabird_sync_full_progress_folder,
                p.folderIndex,
                p.folderCount,
                p.folderName,
            )
        }
        val open = PendingIntent.getActivity(
            applicationContext,
            accountNumber,
            FullSyncActivity.intent(applicationContext, accountUuid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, channels.miscellaneousChannelId)
            .setSmallIcon(icons.iconCheckingMail)
            .setContentTitle(applicationContext.getString(R.string.novabird_sync_full_notification_title, accountName))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(p.folderCount, (p.folderIndex - 1).coerceAtLeast(0), p.folderIndex == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                0,
                applicationContext.getString(R.string.novabird_sync_full_stop),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID + accountNumber,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    // headers first, then the messages: both counted against the messages of the folder
    private inner class ProgressListener(private val account: LegacyAccountDto) : SimpleMessagingListener() {
        private var lastUpdate = 0L

        override fun synchronizeMailboxHeadersProgress(
            account: LegacyAccountDto,
            folderServerId: String,
            completed: Int,
            total: Int,
        ) = update(account, completed, total)

        override fun synchronizeMailboxProgress(account: LegacyAccountDto, folderId: Long, completed: Int, total: Int) =
            update(account, completed, total)

        private fun update(changed: LegacyAccountDto, completed: Int, total: Int) {
            if (changed.uuid != account.uuid) return
            progress = progress.copy(done = completed, total = total)
            val now = System.currentTimeMillis()
            // the notification at most once a second
            if (now - lastUpdate < UPDATE_INTERVAL_MS && completed < total) return
            lastUpdate = now
            runBlocking { showProgress(account) }
        }
    }

    private data class FolderToSync(val serverId: String, val name: String)

    private companion object {
        const val TAG = "NovaBirdFullSync"
        const val NOTIFICATION_ID = 7_310_000
        const val UPDATE_INTERVAL_MS = 1_000L
    }
}

/** How far a full sync got (also sent to the screen as work progress). */
data class FullSyncProgress(
    val folderIndex: Int = 0,
    val folderCount: Int = 0,
    val folderName: String = "",
    val done: Int = 0,
    val total: Int = 0,
)
