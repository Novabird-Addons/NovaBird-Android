package org.novabird.feature.sync.fullsync

import app.k9mail.legacy.mailstore.MessageStoreManager
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mailstore.LocalStoreProvider
import net.thunderbird.core.android.account.LegacyAccountDto

/** Folder settings that the full sync and the import screen switch on (Thunderbird keeps them per folder). */
class AccountFolderSetup(
    private val localStoreProvider: LocalStoreProvider,
    private val messageStoreManager: MessageStoreManager,
) {
    /** Synchronization on for every shown folder of the account except trash, spam and the phone-only folders. */
    fun enableSyncForAllFolders(account: LegacyAccountDto) {
        val messageStore = messageStoreManager.getMessageStore(account.uuid)
        val skipped = setOfNotNull(account.trashFolderId, account.spamFolderId)
        for (folder in localStoreProvider.getInstance(account).getPersonalNamespaces(false)) {
            folder.open()
            val excluded = folder.isLocalOnly || !folder.isVisible || folder.isSyncEnabled ||
                folder.databaseId in skipped || folder.type == FolderType.TRASH || folder.type == FolderType.SPAM
            if (!excluded) messageStore.setSyncEnabled(folder.databaseId, true)
        }
    }

    /** Push for the inbox; does nothing while the account has no inbox yet (folder list not loaded). */
    fun enableInboxPush(account: LegacyAccountDto) {
        val inboxId = account.inboxFolderId ?: return
        messageStoreManager.getMessageStore(account.uuid).setPushEnabled(inboxId, true)
    }
}
