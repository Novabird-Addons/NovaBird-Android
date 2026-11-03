package org.novabird.mail

import net.thunderbird.feature.account.settings.api.ReplacedAccountSettings
import org.koin.dsl.module

/** The message size per account is replaced by NovaBird's sync limits (mobile data / Wi-Fi). */
internal val novaBirdSettingsModule = module {
    single<ReplacedAccountSettings> { ReplacedAccountSettings { true } }
}
