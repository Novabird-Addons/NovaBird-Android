package org.novabird.mail.logo

import com.fsck.k9.contacts.SenderLogoLoader
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** Sender logos (BIMI) for the contact pictures of the message list. */
val novaBirdLogoModule = module {
    single { SenderLogoSettings(androidContext()) }
    single<SenderLogoLoader> { BimiLogoLoader(context = androidContext(), settings = get(), logger = get()) }
}
