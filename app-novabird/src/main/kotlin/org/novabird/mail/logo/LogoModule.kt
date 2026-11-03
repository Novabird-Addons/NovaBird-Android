package org.novabird.mail.logo

import com.fsck.k9.contacts.SenderLogoLoader
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.novabird.feature.protection.ProtectionService

/** Sender logos (BIMI) for the contact pictures of the message list. */
val novaBirdLogoModule = module {
    single { SenderLogoSettings(androidContext()) }
    single<SenderLogoLoader> {
        val protection = getOrNull<ProtectionService>()
        BimiLogoLoader(
            context = androidContext(),
            settings = get(),
            logger = get(),
            isFlaggedSender = { address -> protection != null && runBlocking { protection.isFlaggedSender(address) } },
        )
    }
}
