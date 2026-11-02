package org.novabird.feature.protection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.novabird.feature.protection.ui.ProtectionSettingsViewModel

/** Results next to the K-9 databases, but a separate file. */
private const val PROTECTION_DATABASE = "novabird-protection.db"

/**
 * Phishing protection. Needs a `K9MailStore` and `NovaBirdMailTexts` (search module) from the app.
 *
 * After each sync the app calls [ProtectionScheduler.requestCheck].
 */
val novaBirdProtectionModule = module {
    single<ProtectionSettings> { AndroidProtectionSettings(androidContext()) }
    single<ContactsSource> { AndroidContactsSource(androidContext()) }
    single { ProtectionStore(androidContext().getDatabasePath(PROTECTION_DATABASE).path) }
    single {
        val dispatcher = Dispatchers.IO.limitedParallelism(1)
        val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
        val engineLock = Mutex()
        var engine: ProtectionEngine? = null
        ProtectionService(
            engine = {
                engineLock.withLock {
                    engine ?: ProtectionEngine.create(ProtectionScripts.load(), engineDispatcher).also { engine = it }
                }
            },
            texts = get(),
            mailStore = get(),
            store = get(),
            settings = get(),
            contacts = get(),
            dispatcher = dispatcher,
            now = System::currentTimeMillis,
            onEnabled = { get<ProtectionScheduler>().requestCheck() },
            ai = getOrNull(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
    single { ProtectionScheduler(androidContext(), get()) }
    viewModel { ProtectionSettingsViewModel(service = get()) }
}
