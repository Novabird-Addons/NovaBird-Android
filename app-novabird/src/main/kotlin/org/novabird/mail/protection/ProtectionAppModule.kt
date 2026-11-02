package org.novabird.mail.protection

import com.fsck.k9.ui.MessageLinkGate
import com.fsck.k9.ui.MessageListItemDecorator
import com.fsck.k9.ui.MessageViewHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import org.novabird.feature.protection.novaBirdProtectionModule

/** Phishing protection in the app: the module plus the hooks for message view, link opening and message list. */
internal val novaBirdProtectionAppModule = module {
    includes(novaBirdProtectionModule)

    single<MessageViewHook> { ProtectionMessageViewHook(service = get()) }
    single<MessageLinkGate> { ProtectionLinkGate() }
    single<MessageListItemDecorator> {
        ProtectionListDecorator(service = get(), scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }
}
