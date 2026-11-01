package org.novabird.mail

import app.k9mail.legacy.message.controller.SimpleMessagingListener
import com.fsck.k9.controller.MessagingController
import net.thunderbird.app.common.FeatureFlagApplication
import net.thunderbird.core.android.account.LegacyAccountDto
import org.koin.android.ext.android.inject
import org.koin.core.module.Module
import org.novabird.feature.ai.local.ModelDownloads
import org.novabird.feature.ai.local.ModelFiles
import org.novabird.feature.ai.settings.AiSettingsRepository
import org.novabird.feature.search.internal.work.NovaBirdIndexScheduler
import org.novabird.feature.sync.backfill.PartialMessageBackfillScheduler

class NovaBirdApp : FeatureFlagApplication() {
    private val messagingController: MessagingController by inject()
    private val backfillScheduler: PartialMessageBackfillScheduler by inject()
    private val indexScheduler: NovaBirdIndexScheduler by inject()
    private val aiSettings: AiSettingsRepository by inject()
    private val modelFiles: ModelFiles by inject()
    private val modelDownloads: ModelDownloads by inject()

    override fun provideAppModule(): Module = appModule

    // Feature flags are evaluated per app. NovaBird uses the same flags as Thunderbird.
    override val appName: String = "thunderbird"
    override val appVersion: String = BuildConfig.VERSION_NAME

    override fun onCreate() {
        super.onCreate()

        // After each folder sync: complete partial messages on Wi-Fi and update the search index.
        messagingController.addListener(backfillScheduler.syncListener)
        messagingController.addListener(
            object : SimpleMessagingListener() {
                override fun synchronizeMailboxFinished(account: LegacyAccountDto, folderId: Long) {
                    indexScheduler.requestIndexing()
                }
            },
        )

        backfillScheduler.start()
        indexScheduler.schedulePeriodic()
        indexScheduler.requestIndexing()
        // semantic search on the phone is on by default: fetch the model on Wi-Fi if it is missing
        modelDownloads.ensureModel(aiSettings.settings.value.localEnabled, modelFiles)
    }
}
