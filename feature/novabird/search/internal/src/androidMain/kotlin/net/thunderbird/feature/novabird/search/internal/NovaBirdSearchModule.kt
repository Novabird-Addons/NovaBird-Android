package net.thunderbird.feature.novabird.search.internal

import net.thunderbird.feature.novabird.search.NovaBirdSearch
import net.thunderbird.feature.novabird.search.NovaBirdSearchIndexer
import net.thunderbird.feature.novabird.search.internal.indexer.AndroidK9MailStore
import net.thunderbird.feature.novabird.search.internal.indexer.K9Account
import net.thunderbird.feature.novabird.search.internal.indexer.K9MailStore
import net.thunderbird.feature.novabird.search.internal.indexer.NovaBirdIndexer
import net.thunderbird.feature.novabird.search.internal.ui.NovaBirdSearchViewModel
import net.thunderbird.feature.novabird.search.internal.work.NovaBirdIndexScheduler
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Accounts to index; bound by the app module (e.g. from the account manager). */
fun interface K9AccountProvider {
    fun accounts(): List<K9Account>
}

/** Index file next to the K-9 databases, but a separate file. */
private const val INDEX_DATABASE = "novabird-search.db"

/**
 * NovaBird search. Needs a [K9AccountProvider] and a `net.thunderbird.core.logging.Logger` from the app.
 *
 * After each sync the app calls [NovaBirdIndexScheduler.requestIndexing].
 */
val novaBirdSearchModule = module {
    single { NovaBirdSearchCore(androidContext().getDatabasePath(INDEX_DATABASE).path) }
    single<K9MailStore> { AndroidK9MailStore(androidContext()) { get<K9AccountProvider>().accounts() } }
    single<NovaBirdSearch> {
        val accounts = get<K9AccountProvider>()
        NovaBirdSearchService(core = get(), ownEmails = { accounts.accounts().flatMap { it.emails } })
    }
    single<NovaBirdSearchIndexer> { NovaBirdIndexer(core = get(), mailStore = get(), logger = get()) }
    single { NovaBirdIndexScheduler(androidContext()) }
    viewModel { NovaBirdSearchViewModel(search = get(), logger = get()) }
}
