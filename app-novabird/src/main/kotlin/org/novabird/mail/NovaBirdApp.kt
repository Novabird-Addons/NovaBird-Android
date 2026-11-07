package org.novabird.mail

import app.k9mail.feature.onboarding.permissions.domain.PermissionsDomainContract.UseCase.CheckPushPermissionsMissing
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import com.fsck.k9.controller.MessagingController
import net.thunderbird.app.common.FeatureFlagApplication
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import org.koin.android.ext.android.inject
import org.koin.core.module.Module
import org.novabird.feature.ai.local.ModelDownloads
import org.novabird.feature.ai.local.ModelFiles
import org.novabird.feature.ai.settings.AiSettingsRepository
import org.novabird.feature.protection.ProtectionScheduler
import org.novabird.feature.search.internal.work.NovaBirdIndexScheduler
import org.novabird.feature.sync.backfill.PartialMessageBackfillScheduler
import org.novabird.mail.account.OrphanAccountFiles
import org.novabird.mail.account.PushPermissionsPrompt
import org.novabird.mail.feature.DesignDefaults

class NovaBirdApp : FeatureFlagApplication() {
    private val messagingController: MessagingController by inject()
    private val backfillScheduler: PartialMessageBackfillScheduler by inject()
    private val indexScheduler: NovaBirdIndexScheduler by inject()
    private val protectionScheduler: ProtectionScheduler by inject()
    private val aiSettings: AiSettingsRepository by inject()
    private val modelFiles: ModelFiles by inject()
    private val modelDownloads: ModelDownloads by inject()
    private val designDefaults: DesignDefaults by inject()
    private val accountManager: LegacyAccountDtoManager by inject()
    private val pushPermissionsMissing: CheckPushPermissionsMissing by inject()

    override fun provideAppModule(): Module = appModule

    // Feature flags are evaluated per app. NovaBird uses the same flags as Thunderbird.
    override val appName: String = "thunderbird"
    override val appVersion: String = BuildConfig.VERSION_NAME

    override fun onCreate() {
        super.onCreate()

        designDefaults.apply()

        // after an update: ask once for what push needs, if it's missing
        registerActivityLifecycleCallbacks(
            PushPermissionsPrompt(this, BuildConfig.VERSION_CODE, accountManager, pushPermissionsMissing),
        )

        // empty databases of removed accounts (see OrphanAccountFiles); off the main thread, before any setup
        Thread {
            val uuids = accountManager.getAccounts().mapTo(HashSet()) { it.uuid }
            OrphanAccountFiles.removeOrphans(getDatabasePath("x").parentFile ?: return@Thread, uuids)
        }.start()

        // After each folder sync: complete partial messages on Wi-Fi, update the search index and check new mails
        // for phishing (only if the user switched the protection on).
        messagingController.addListener(backfillScheduler.syncListener)
        messagingController.addListener(
            object : SimpleMessagingListener() {
                override fun synchronizeMailboxFinished(account: LegacyAccountDto, folderId: Long) {
                    indexScheduler.requestIndexing()
                    protectionScheduler.requestCheck()
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
