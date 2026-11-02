package app.k9mail.feature.account.common.domain.entity

data class AccountSyncOptions(
    val checkFrequencyInMinutes: Int,
    val messageDisplayCount: Int,
    val showNotification: Boolean,
    /** Sync messages from the last N days (-1 = no limit); null = keep the account default. */
    val syncPeriodDays: Int? = null,
    /** Enable push for the inbox; null = keep the default (off). */
    val inboxPush: Boolean? = null,
)
