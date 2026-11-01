package org.novabird.mail.account

import app.k9mail.feature.account.setup.AccountSetupExternalContract.MessageDisplayCountProvider
import app.k9mail.feature.account.setup.domain.entity.EmailDisplayCount
import net.thunderbird.core.android.account.AccountDefaultsProvider
import org.koin.core.qualifier.named
import org.koin.dsl.module

internal val novaBirdAccountModule = module {
    single<AccountDefaultsProvider>(named("app")) {
        NovaBirdAccountDefaultsProvider(defaultProvider = get(named("default")))
    }

    // The setup wizard asks for the number of messages and overwrites the default above, so preselect the same value.
    single<MessageDisplayCountProvider>(named("app")) {
        MessageDisplayCountProvider {
            EmailDisplayCount.fromCount(NovaBirdAccountDefaultsProvider.ALL_MESSAGES)
        }
    }
}
