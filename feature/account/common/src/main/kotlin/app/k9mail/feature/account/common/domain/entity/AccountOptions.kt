package app.k9mail.feature.account.common.domain.entity

data class AccountOptions(
    val accountName: String,
    val displayName: String,
    val emailSignature: String?,
    val checkFrequencyInMinutes: Int,
    val messageDisplayCount: Int,
    val showNotification: Boolean,
    /** Sync messages from the last N days (-1 = no limit); null = keep the account default. */
    val syncPeriodDays: Int? = null,
)
