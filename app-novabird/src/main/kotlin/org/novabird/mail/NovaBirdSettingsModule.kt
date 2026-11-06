package org.novabird.mail

import app.k9mail.feature.onboarding.permissions.domain.PermissionsDomainContract.UseCase.CheckBackgroundActivity
import app.k9mail.feature.settings.import.SettingsImportExternalContract.ImportedAccountsReview
import net.thunderbird.feature.account.settings.api.AccountFullSync
import net.thunderbird.feature.account.settings.api.ReplacedAccountSettings
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.novabird.feature.sync.fullsync.FullSyncActivity
import org.novabird.feature.sync.ui.ImportedAccountsSyncActivity
import app.k9mail.feature.onboarding.permissions.domain.usecase.CheckBackgroundActivity as AndroidCheckBackgroundActivity

internal val novaBirdSettingsModule = module {
    // The message size per account is replaced by NovaBird's sync limits (mobile data / Wi-Fi).
    single<ReplacedAccountSettings> { ReplacedAccountSettings { true } }

    // Account settings → Fetching mail: load the whole mailbox at once (Wi-Fi, foreground service with progress).
    single<AccountFullSync> { AccountFullSync(FullSyncActivity::intent) }

    // Imported accounts skip the setup wizard: ask for period and number of messages after the import.
    single<ImportedAccountsReview> { ImportedAccountsReview(ImportedAccountsSyncActivity::intent) }

    // The setup wizard asks to run in the background without battery optimization (otherwise mail comes late).
    single<CheckBackgroundActivity> { AndroidCheckBackgroundActivity(androidContext()) }
}
