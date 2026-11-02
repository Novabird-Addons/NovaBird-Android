package org.novabird.feature.protection

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.novabird.feature.search.K9MailStore
import org.novabird.feature.search.unpackK9Addresses

/**
 * Builds the add-on's sender history (`senderIndex`, see lib/history.js) from the K-9 databases of all accounts, like
 * the first scan of the add-on's background script:
 * - received mails count per sender (`n`), mails in sent folders count per recipient in To/Cc (`sent`);
 * - only the last [months] months; spam, trash, drafts and outbox do not count;
 * - senders with mail in a spam folder or with a stored suspicious/dangerous result are `flagged`.
 *
 * Unlike the add-on, older mail is never checked here (the user wants only new and opened mails checked), so senders
 * that were never flagged can reach the level "recurring" more easily. That only removes the "new sender" hint.
 */
class SenderIndexBuilder(private val mailStore: K9MailStore) {

    private class Record {
        var n = 0
        var sent = 0
        val names = mutableListOf<String>()
        var first: Long? = null
        var last: Long? = null
        var flagged = false

        fun note(name: String?, date: Long?, sentField: Boolean) {
            if (sentField) sent++ else n++
            if (!name.isNullOrBlank() && name !in names && names.size < MAX_NAMES) names += name
            if (date != null && date > 0) {
                first = first?.let { minOf(it, date) } ?: date
                last = last?.let { maxOf(it, date) } ?: date
            }
        }

        fun toJson() = JsonObject(
            buildMap {
                put("n", JsonPrimitive(n))
                put("sent", JsonPrimitive(sent))
                put("names", JsonArray(names.map(::JsonPrimitive)))
                put("first", first?.let { JsonPrimitive(it) } ?: JsonNull)
                put("last", last?.let { JsonPrimitive(it) } ?: JsonNull)
                if (flagged) put("flagged", JsonPrimitive(true))
            },
        )
    }

    fun build(now: Long, months: Int, flaggedSenders: Set<String>): JsonObject {
        val since = now - (months.coerceIn(1, HISTORY_MONTHS_MAX) * DAYS_PER_MONTH * DAY_MILLIS).toLong()
        val index = HashMap<String, Record>()
        for (account in mailStore.accounts()) {
            val db = mailStore.open(account.uuid) ?: continue
            db.use {
                val rows = it.query(
                    """
                    SELECT m.sender_list, m.to_list, m.cc_list, m.date, f.type
                    FROM messages m JOIN folders f ON f.id = m.folder_id
                    WHERE m.deleted = 0 AND m.empty = 0 AND m.date >= ?
                    """.trimIndent(),
                    listOf(since.toString()),
                )
                for (row in rows) {
                    val type = (row[4] as String?)?.lowercase()
                    val date = row[3] as Long?
                    when (type) {
                        "sent" -> (unpackK9Addresses(row[1] as String?) + unpackK9Addresses(row[2] as String?))
                            .forEach { address -> index.record(address.email).note(null, date, sentField = true) }

                        "spam" -> unpackK9Addresses(row[0] as String?).firstOrNull()?.let { address ->
                            index.record(address.email).flagged = true
                        }

                        in SKIPPED_TYPES -> Unit

                        else -> unpackK9Addresses(row[0] as String?).firstOrNull()?.let { address ->
                            index.record(address.email).note(address.name, date, sentField = false)
                        }
                    }
                }
            }
        }
        for (sender in flaggedSenders) index.record(sender).flagged = true
        return JsonObject(index.mapValues { it.value.toJson() })
    }

    private fun HashMap<String, Record>.record(email: String): Record = getOrPut(email.trim().lowercase()) { Record() }

    private companion object {
        val SKIPPED_TYPES = setOf("trash", "drafts", "outbox")
        const val MAX_NAMES = 5
        const val DAYS_PER_MONTH = 30.44
        const val DAY_MILLIS = 86_400_000.0
    }
}
