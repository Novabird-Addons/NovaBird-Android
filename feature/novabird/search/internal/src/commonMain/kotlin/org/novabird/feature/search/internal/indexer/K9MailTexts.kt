package org.novabird.feature.search.internal.indexer

import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.novabird.feature.search.K9MailStore
import org.novabird.feature.search.MailForAi
import org.novabird.feature.search.MailForCheck
import org.novabird.feature.search.MessageLocation
import org.novabird.feature.search.NovaBirdMailTexts
import org.novabird.feature.search.RecentMails

/** [NovaBirdMailTexts] from the K-9 databases (read-only, like the indexer). */
internal class K9MailTexts(
    private val mailStore: K9MailStore,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : NovaBirdMailTexts {
    override suspend fun mail(location: MessageLocation): MailForAi? = withContext(Dispatchers.Default) {
        mailStore.open(location.accountUuid)?.use { db ->
            val reader = K9MessageReader(location.accountUuid, db)
            reader.findId(location.folderId, location.uid)?.let { reader.readText(it) }
        }
    }

    override suspend fun mailForCheck(accountUuid: String, k9Id: Long): MailForCheck? =
        withContext(Dispatchers.Default) {
            mailStore.open(accountUuid)?.use { db -> K9MessageReader(accountUuid, db).readForCheck(k9Id) }
        }

    override suspend fun headerMessageIds(accountUuid: String, k9Ids: Collection<Long>): Map<Long, String>? =
        withContext(Dispatchers.Default) {
            mailStore.open(accountUuid)?.use { db -> K9MessageReader(accountUuid, db).headerMessageIds(k9Ids) }
        }

    override suspend fun partBytes(accountUuid: String, partId: Long, maxBytes: Int): ByteArray? =
        withContext(Dispatchers.Default) {
            mailStore.open(accountUuid)?.use { db -> K9MessageReader(accountUuid, db).readPartBytes(partId, maxBytes) }
        }

    override suspend fun recentMails(hours: Int, unreadOnly: Boolean, max: Int): RecentMails =
        withContext(Dispatchers.Default) {
            val since = now() - hours * MILLIS_PER_HOUR
            val found = mailStore.accounts().flatMap { account ->
                mailStore.open(account.uuid)?.use { db ->
                    K9MessageReader(account.uuid, db).recentInbox(since, unreadOnly).map {
                        Triple(account.uuid, it.first, it.second)
                    }
                }.orEmpty()
            }.sortedByDescending { it.third }
            val mails = found.take(max).groupBy { it.first }.flatMap { (uuid, rows) ->
                mailStore.open(uuid)?.use { db ->
                    val reader = K9MessageReader(uuid, db)
                    rows.mapNotNull { reader.readText(it.second) }
                }.orEmpty()
            }.sortedByDescending { it.date }
            RecentMails(mails, found.size)
        }

    private companion object {
        const val MILLIS_PER_HOUR = 3_600_000L
    }
}
