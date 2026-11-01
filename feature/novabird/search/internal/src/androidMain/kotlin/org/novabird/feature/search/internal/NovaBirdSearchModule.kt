package org.novabird.feature.search.internal

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.novabird.feature.search.K9AccountProvider
import org.novabird.feature.search.K9MailStore
import org.novabird.feature.search.NovaBirdIndexTrigger
import org.novabird.feature.search.NovaBirdSearch
import org.novabird.feature.search.NovaBirdSearchIndexer
import org.novabird.feature.search.NovaBirdVectorBackend
import org.novabird.feature.search.NovaBirdVectorIndexer
import org.novabird.feature.search.internal.indexer.AndroidK9MailStore
import org.novabird.feature.search.internal.indexer.NovaBirdIndexer
import org.novabird.feature.search.internal.ui.NovaBirdSearchViewModel
import org.novabird.feature.search.internal.vectors.SemanticIndex
import org.novabird.feature.search.internal.work.NovaBirdIndexScheduler

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
    // the vector backend (NovaBird Helper or the phone) is optional; it is looked up on each use
    single {
        val scope = this
        SemanticIndex(core = get(), backend = { scope.getOrNull<NovaBirdVectorBackend>() }, logger = get())
    }
    single<NovaBirdVectorIndexer> { get<SemanticIndex>() }
    single<NovaBirdSearch> {
        val accounts = get<K9AccountProvider>()
        NovaBirdSearchService(
            core = get(),
            ownEmails = { accounts.accounts().flatMap { it.emails } },
            semanticIndex = get(),
        )
    }
    single<NovaBirdSearchIndexer> {
        NovaBirdIndexer(core = get(), mailStore = get(), logger = get(), semanticIndex = get())
    }
    single { NovaBirdIndexScheduler(androidContext()) }
    single<NovaBirdIndexTrigger> { get<NovaBirdIndexScheduler>() }
    viewModel { NovaBirdSearchViewModel(search = get(), logger = get()) }
}
