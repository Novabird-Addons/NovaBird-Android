package org.novabird.mail.protection

import app.k9mail.legacy.mailstore.MessageStoreManager
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.controller.ManualSpamHook
import com.fsck.k9.mail.Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.novabird.feature.protection.ProtectionService

/**
 * "Als Spam markieren" by hand puts the senders onto the blocked list of the spam detection. The From header is read
 * right away (a few rows), because the move that follows takes the mail out of its folder.
 */
class ProtectionSpamHook(
    private val service: ProtectionService,
    private val messageStoreManager: MessageStoreManager,
    private val scope: CoroutineScope,
) : ManualSpamHook {
    override fun onMarkedAsSpam(messages: List<MessageReference>) {
        val senders = messages.mapNotNull(::senderOf).distinct()
        if (senders.isEmpty()) return
        // a failure only means the sender is not blocked; the mail is moved anyway
        scope.launch { runCatching { service.blockSenders(senders) } }
    }

    private fun senderOf(reference: MessageReference): String? = runCatching {
        messageStoreManager.getMessageStore(reference.accountUuid)
            .getHeaders(reference.folderId, reference.uid, setOf("From"))
            .firstOrNull()
            ?.value
            ?.let { Address.parse(it).firstOrNull()?.address }
    }.getOrNull()
}
