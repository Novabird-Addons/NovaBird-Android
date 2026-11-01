package org.novabird.mail

import com.fsck.k9.controller.MessagingController
import net.thunderbird.app.common.FeatureFlagApplication
import org.koin.android.ext.android.inject
import org.koin.core.module.Module
import org.novabird.feature.sync.backfill.PartialMessageBackfillScheduler

class NovaBirdApp : FeatureFlagApplication() {
    private val messagingController: MessagingController by inject()
    private val backfillScheduler: PartialMessageBackfillScheduler by inject()

    override fun provideAppModule(): Module = appModule

    // Feature flags are evaluated per app. NovaBird uses the same flags as Thunderbird.
    override val appName: String = "thunderbird"
    override val appVersion: String = BuildConfig.VERSION_NAME

    override fun onCreate() {
        super.onCreate()

        messagingController.addListener(backfillScheduler.syncListener)
        backfillScheduler.start()
    }
}
