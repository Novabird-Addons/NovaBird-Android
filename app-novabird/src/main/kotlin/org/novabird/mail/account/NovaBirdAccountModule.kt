package org.novabird.mail.account

import net.thunderbird.core.android.account.AccountDefaultsProvider
import org.koin.core.qualifier.named
import org.koin.dsl.module

internal val novaBirdAccountModule = module {
    single<AccountDefaultsProvider>(named("app")) {
        NovaBirdAccountDefaultsProvider(defaultProvider = get(named("default")))
    }
}
