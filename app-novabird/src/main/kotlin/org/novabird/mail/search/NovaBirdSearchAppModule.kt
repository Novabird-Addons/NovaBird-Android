package org.novabird.mail.search

import com.fsck.k9.Preferences
import org.koin.dsl.module
import org.novabird.feature.search.K9Account
import org.novabird.feature.search.K9AccountProvider
import org.novabird.feature.search.internal.novaBirdSearchModule
import org.novabird.feature.search.internal.work.NovaBirdIndexScheduler
import org.novabird.feature.sync.backfill.PartialMessageBackfillListener

internal val novaBirdSearchAppModule = module {
    includes(novaBirdSearchModule)

    single<K9AccountProvider> {
        val preferences = get<Preferences>()
        K9AccountProvider {
            preferences.getAccounts().map { account ->
                K9Account(
                    uuid = account.uuid,
                    emails = account.identities.mapNotNull { it.email },
                )
            }
        }
    }

    // Messages completed on Wi-Fi now contain all parts; update the index.
    single<PartialMessageBackfillListener> {
        val indexScheduler = get<NovaBirdIndexScheduler>()
        PartialMessageBackfillListener { indexScheduler.requestIndexing() }
    }
}
