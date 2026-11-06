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

    /** One mail with all root headers and all parts as the phishing check reads it, or `null` if it is gone. */
    suspend fun mailForCheck(accountUuid: String, k9Id: Long): MailForCheck? = null

    /**
     * Message-IDs (like [MailForCheck.headerMessageId]) of those of [k9Ids] that are mails in the account right now, or
     * `null` if the account can't be read. K-9 gives the id of a deleted newest mail to the next mail, so the id alone
     * doesn't say which mail it is.
     */
    suspend fun headerMessageIds(accountUuid: String, k9Ids: Collection<Long>): Map<Long, String>? = null

    /**
     * Decoded content of part [partId] (see [MailPart.partId]) if it is stored locally and at most [maxBytes] long,
     * otherwise `null`. Nothing is downloaded.
     */
    suspend fun partBytes(accountUuid: String, partId: Long, maxBytes: Int): ByteArray? = null
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

/**
 * A mail for the phishing check, shaped like the input of the add-on's `buildFeatures()`.
 *
 * @property headers root headers, lower-case names to values in order; address headers and the subject with RFC 2047
 *   encoded words decoded, all others raw (e.g. `authentication-results`).
 * @property parts leaf MIME parts in tree order; [MailPart.body] only for text parts (and SVG), at most 400,000 chars.
 * @property folderType K-9 folder type of the mail (`inbox`, `sent`, `spam`, …, lower case), or `null`.
 */
data class MailForCheck(
    val accountUuid: String,
    val k9Id: Long,
    val location: MessageLocation,
    val headerMessageId: String,
    val date: Long,
    val folderType: String?,
    val headers: Map<String, List<String>>,
    val parts: List<MailPart>,
)

/**
 * One MIME part: content type (lower case), file name if it has one, decoded text body if it is a text part.
 *
 * @property partId id for [NovaBirdMailTexts.partBytes] (`0`: unknown).
 * @property contentId Content-ID without angle brackets (inline images: `cid:` links point to it), or `null`.
 * @property size decoded size in bytes as K-9 stored it (`0` if unknown).
 * @property downloaded the content is stored locally (small mails, or parts loaded by opening the mail).
 */
data class MailPart(
    val contentType: String,
    val name: String?,
    val body: String?,
    val partId: Long = 0L,
    val contentId: String? = null,
    val size: Long = 0L,
    val downloaded: Boolean = false,
)
