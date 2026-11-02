package org.novabird.feature.search

/** Mails for the AI functions (summary, reading aloud, overview), read-only from the K-9 databases. */
interface NovaBirdMailTexts {
    /** One mail, or `null` if it is gone. */
    suspend fun mail(location: MessageLocation): MailForAi?

    /**
     * Newest mails of the last [hours] in the inboxes of all accounts (like the desktop "Was gibt's Neues"), newest
     * first, at most [max]; [RecentMails.total] counts all found.
     */
    suspend fun recentMails(hours: Int, unreadOnly: Boolean, max: Int): RecentMails
}

/** A mail with its text parts: [plain] (text/plain parts) and [html] (text/html parts), both may be empty. */
data class MailForAi(
    val location: MessageLocation,
    val headerMessageId: String,
    val subject: String,
    val from: String,
    val date: Long,
    val plain: String,
    val html: String,
)

data class RecentMails(val mails: List<MailForAi>, val total: Int)
