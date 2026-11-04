package org.novabird.feature.sync

import android.content.Context
import android.net.ConnectivityManager
import com.fsck.k9.Preferences
import com.fsck.k9.controller.SyncDownloadLimitProvider
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.novabird.feature.sync.backfill.BackendCompleteMessageDownloader
import org.novabird.feature.sync.backfill.BackfillWorkEnqueuer
import org.novabird.feature.sync.backfill.CompleteMessageDownloader
import org.novabird.feature.sync.backfill.LocalStorePartialMessageFinder
import org.novabird.feature.sync.backfill.PartialMessageBackfillScheduler
import org.novabird.feature.sync.backfill.PartialMessageBackfiller
import org.novabird.feature.sync.backfill.PartialMessageFinder
import org.novabird.feature.sync.backfill.WorkManagerBackfillWorkEnqueuer
import org.novabird.feature.sync.fullsync.AccountFolderSetup
import org.novabird.feature.sync.fullsync.FullSyncScheduler
import org.novabird.feature.sync.limit.AndroidNetworkState
import org.novabird.feature.sync.limit.NetworkAwareSyncDownloadLimitProvider
import org.novabird.feature.sync.limit.NetworkState
import org.novabird.feature.sync.settings.SharedPreferencesSyncLimitSettingsRepository
import org.novabird.feature.sync.settings.SyncLimitSettingsRepository
import org.novabird.feature.sync.ui.SyncLimitSettingsViewModel

val novaBirdSyncModule = module {
    single<SyncLimitSettingsRepository> { SharedPreferencesSyncLimitSettingsRepository(androidContext()) }

    single<NetworkState> {
        val connectivityManager = androidContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        AndroidNetworkState(connectivityManager)
    }

    single<SyncDownloadLimitProvider> {
        NetworkAwareSyncDownloadLimitProvider(
            settingsRepository = get(),
            networkState = get(),
        )
    }

    single<PartialMessageFinder> { LocalStorePartialMessageFinder(localStoreProvider = get()) }

    single<CompleteMessageDownloader> { BackendCompleteMessageDownloader(backendManager = get()) }

    single {
        val preferences = get<Preferences>()
        PartialMessageBackfiller(
            accounts = { preferences.getAccounts() },
            finder = get(),
            downloader = get(),
            settingsRepository = get(),
            logger = get(),
        )
    }

    single<BackfillWorkEnqueuer> { WorkManagerBackfillWorkEnqueuer(workManager = get()) }

    single { PartialMessageBackfillScheduler(enqueuer = get()) }

    single { FullSyncScheduler(workManager = get()) }
    single { AccountFolderSetup(localStoreProvider = get(), messageStoreManager = get()) }

    viewModel {
        SyncLimitSettingsViewModel(
            repository = get(),
            backfillScheduler = get(),
        )
    }
}
