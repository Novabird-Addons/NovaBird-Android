package org.novabird.mail.protection

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.Preferences
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.notification.NotificationController
import net.thunderbird.core.android.account.LegacyAccountDto
import org.novabird.feature.protection.SpamMover

/**
 * Moves mails with Thunderbird's own move (also on the server), like "Als Spam markieren" by hand. Without a spam or
 * inbox folder in the account nothing is moved; the list then only shows the grey spam symbol.
 */
class K9SpamMover(
    private val preferences: Preferences,
    private val messagingController: MessagingController,
    private val notifications: NotificationController,
) : SpamMover {
    override suspend fun moveToSpam(accountUuid: String, folderId: Long, uid: String): Boolean =
        move(accountUuid, folderId, uid, removeNotification = true) { it.spamFolderId }

    override suspend fun moveToInbox(accountUuid: String, folderId: Long, uid: String): Boolean =
        move(accountUuid, folderId, uid, removeNotification = false) { it.inboxFolderId }

    private fun move(
        accountUuid: String,
        folderId: Long,
        uid: String,
        removeNotification: Boolean,
        target: (LegacyAccountDto) -> Long?,
    ): Boolean {
        val account = preferences.getAccount(accountUuid)
        val targetFolderId = account?.let(target)
        if (account == null || targetFolderId == null || targetFolderId == folderId) return false
        val reference = MessageReference(accountUuid, folderId, uid)
        // the "new mail" notification may already be shown; a spam mail should not stay there
        if (removeNotification) runCatching { notifications.removeNewMailNotification(account, reference) }
        messagingController.moveMessage(account, folderId, reference, targetFolderId)
        return true
    }
}
