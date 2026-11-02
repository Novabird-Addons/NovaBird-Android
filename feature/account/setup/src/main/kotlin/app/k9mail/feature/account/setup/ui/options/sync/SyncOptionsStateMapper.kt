package app.k9mail.feature.account.setup.ui.options.sync

import app.k9mail.feature.account.common.domain.entity.AccountState
import app.k9mail.feature.account.common.domain.entity.AccountSyncOptions
import app.k9mail.feature.account.setup.domain.entity.EmailCheckFrequency
import app.k9mail.feature.account.setup.domain.entity.EmailDisplayCount
import app.k9mail.feature.account.setup.domain.entity.EmailSyncPeriod
import app.k9mail.feature.account.setup.ui.options.sync.SyncOptionsContract.State

internal fun AccountState.toSyncOptionsState(
    defaultMessageDisplayCount: EmailDisplayCount = EmailDisplayCount.DEFAULT,
    defaultSyncPeriod: EmailSyncPeriod? = null,
    defaultInboxPush: Boolean? = null,
): State {
    val options = syncOptions
    // push (IMAP IDLE) only exists for IMAP accounts
    val inboxPush = defaultInboxPush?.takeIf { incomingServerSettings?.type == IMAP }
    return if (options == null) {
        State(messageDisplayCount = defaultMessageDisplayCount, syncPeriod = defaultSyncPeriod, inboxPush = inboxPush)
    } else {
        State(
            checkFrequency = EmailCheckFrequency.fromMinutes(options.checkFrequencyInMinutes),
            messageDisplayCount = EmailDisplayCount.fromCount(options.messageDisplayCount),
            showNotification = options.showNotification,
            syncPeriod = defaultSyncPeriod?.let { options.syncPeriodDays?.let(EmailSyncPeriod::fromDays) ?: it },
            inboxPush = inboxPush?.let { options.inboxPush ?: it },
        )
    }
}

internal fun State.toAccountSyncOptions(): AccountSyncOptions {
    return AccountSyncOptions(
        checkFrequencyInMinutes = checkFrequency.minutes,
        messageDisplayCount = messageDisplayCount.count,
        showNotification = showNotification,
        syncPeriodDays = syncPeriod?.days,
        inboxPush = inboxPush,
    )
}

private const val IMAP = "imap"
