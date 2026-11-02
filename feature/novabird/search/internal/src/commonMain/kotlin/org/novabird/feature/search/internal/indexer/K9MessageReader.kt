package org.novabird.feature.search.internal.indexer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.novabird.feature.search.K9MailDatabase
import org.novabird.feature.search.MailForAi
import org.novabird.feature.search.MailForCheck
import org.novabird.feature.search.MailPart
import org.novabird.feature.search.MessageLocation
import org.novabird.feature.search.unpackK9Addresses

/** A message as listed in the K-9 database, with a fingerprint of everything the index depends on. */
internal data class K9MessageEntry(
    val k9Id: Long,
    val folderId: Long,
    val uid: String,
    val headerMessageId: String,
    val fingerprint: String,
)

/**
 * Reads messages from a [K9MailDatabase] and builds the input of `toRecord()` (NovaBird-Search
 * `indexer/record.js`): a Thunderbird-like header and the MIME part tree like `messages.getFull()`.
 */
internal class K9MessageReader(
    private val accountUuid: String,
    private val database: K9MailDatabase,
) {
    /** All messages that belong in the index (not deleted, not placeholders). */
    fun listMessages(): List<K9MessageEntry> {
        val rows = database.query(
            """
            SELECT m.id, m.folder_id, m.uid, m.message_id, m.read, m.flagged, m.date, m.subject,
              f.name, f.type, m.sender_list, m.to_list, m.cc_list,
              (SELECT count(*) || ':' || total(p.data_location) || ':' || total(ifnull(p.decoded_body_size, 0))
                 FROM message_parts p WHERE p.root = m.message_part_id)
            FROM messages m JOIN folders f ON f.id = m.folder_id
            WHERE m.deleted = 0 AND m.empty = 0 AND m.uid IS NOT NULL
            """.trimIndent(),
        )
        return rows.map { r ->
            val k9Id = r[0] as Long
            val folderId = r[1] as Long
            val uid = r[2] as String
            K9MessageEntry(
                k9Id = k9Id,
                folderId = folderId,
                uid = uid,
                headerMessageId = headerMessageIdOf(r[3] as String?, folderId, uid),
                fingerprint = fingerprint(r.drop(4)),
            )
        }
    }

    /** `{ header, part, refs }` for the given message, or `null` if it is gone. */
    fun readItem(k9Id: Long): JsonObject? {
        val row = database.query(
            """
            SELECT m.id, m.folder_id, m.uid, m.message_id, m.read, m.flagged, m.date, m.internal_date, m.subject,
              m.sender_list, m.to_list, m.cc_list, m.message_part_id, f.name, f.type
            FROM messages m JOIN folders f ON f.id = m.folder_id
            WHERE m.id = ? AND m.deleted = 0 AND m.empty = 0
            """.trimIndent(),
            listOf(k9Id.toString()),
        ).firstOrNull() ?: return null

        val folderId = row[1] as Long
        val uid = row[2] as String
        val rootPartId = row[12] as Long?
        val parts = rootPartId?.let { readParts(it) }.orEmpty()
        val root = parts.firstOrNull { it.id == rootPartId }
        val rootHeaders = MimeText.parseHeaders(root?.header)

        val header = buildJsonObject {
            put("headerMessageId", headerMessageIdOf(row[3] as String?, folderId, uid))
            put("author", unpackAddresses(row[9] as String?).firstOrNull()?.let(::formatAddress).orEmpty())
            put("recipients", JsonArray(unpackAddresses(row[10] as String?).map { JsonPrimitive(formatAddress(it)) }))
            put("ccList", JsonArray(unpackAddresses(row[11] as String?).map { JsonPrimitive(formatAddress(it)) }))
            put("subject", row[8] as String? ?: "")
            put("date", (row[6] as Long?)?.takeIf { it > 0 } ?: (row[7] as Long?) ?: 0L)
            put("read", (row[4] as Long?) == 1L)
            put("flagged", (row[5] as Long?) == 1L)
            putJsonArray("tags") {}
            putJsonObject("folder") {
                put("id", indexFolderId(accountUuid, folderId))
                put("accountId", accountUuid)
                put("name", row[13] as String? ?: "")
                putJsonArray("specialUse") { folderTypeOf(row[14] as String?)?.let { add(it) } }
            }
        }
        val refs = (rootHeaders["references"].orEmpty() + rootHeaders["in-reply-to"].orEmpty())
        return buildJsonObject {
            put("header", header)
            put("part", root?.let { toJson(it, parts) } ?: buildJsonObject { put("contentType", "text/plain") })
            put("refs", JsonArray(MimeText.messageIds(refs).map(::JsonPrimitive)))
        }
    }

    private class Part(
        val id: Long,
        val parent: Long,
        val mimeType: String,
        val header: String?,
        val encoding: String?,
        val charset: String?,
        val dataLocation: Long,
        val data: ByteArray?,
        val size: Long,
    )

    private fun readParts(rootPartId: Long): List<Part> {
        return database.query(
            """
            SELECT id, parent, mime_type, header, encoding, charset, data_location,
              CASE WHEN (mime_type LIKE 'text/%' OR mime_type LIKE '%svg%') AND data_location = $DATA_IN_DATABASE THEN data END,
              decoded_body_size
            FROM message_parts WHERE root = ? ORDER BY parent, seq
            """.trimIndent(),
            listOf(rootPartId.toString()),
        ).map { r ->
            Part(
                id = r[0] as Long,
                parent = r[1] as Long? ?: -1L,
                mimeType = (r[2] as String?)?.lowercase().orEmpty(),
                // K-9 writes the header as bytes into a TEXT column
                header = when (val header = r[3]) {
                    is ByteArray -> header.decodeToString()
                    else -> header as String?
                },
                encoding = r[4] as String?,
                charset = r[5] as String?,
                dataLocation = r[6] as Long? ?: 0L,
                data = r[7] as ByteArray?,
                size = r[8] as Long? ?: 0L,
            )
        }
    }

    /** Thunderbird MessagePart shape: `{ contentType, name, size, headers: { content-disposition }, body, parts }`. */
    private fun toJson(part: Part, all: List<Part>): JsonObject {
        val headers = MimeText.parseHeaders(part.header)
        val disposition = headers["content-disposition"]?.firstOrNull()
        val contentType = MimeText.parameters(headers["content-type"]?.firstOrNull())
        val name = MimeText.parameters(disposition)["filename"] ?: contentType["name"]
        // K-9 leaves message_parts.charset empty when saving; the Content-Type header has it
        val charset = contentType["charset"] ?: part.charset
        return buildJsonObject {
            put("contentType", part.mimeType)
            name?.let { put("name", it) }
            put("size", part.size)
            putJsonObject("headers") {
                disposition?.let { putJsonArray("content-disposition") { add(it) } }
            }
            textBody(part, charset, isAttachment = name != null || disposition?.startsWith("attachment", true) == true)
                ?.let { put("body", it) }
            val children = all.filter { it.parent == part.id && it.id != part.id }
            if (children.isNotEmpty()) put("parts", buildJsonArray { children.forEach { add(toJson(it, all)) } })
        }
    }

    private fun textBody(part: Part, charset: String?, isAttachment: Boolean): String? {
        if (isAttachment || (part.mimeType != "text/plain" && part.mimeType != "text/html")) return null
        val raw = when (part.dataLocation) {
            DATA_IN_DATABASE -> part.data
            DATA_ON_DISK -> database.readPartFile(part.id)
            else -> null
        } ?: return null
        val bytes = if (raw.size > MAX_TEXT_BYTES) raw.copyOf(MAX_TEXT_BYTES) else raw
        return MimeText.decodeBody(bytes, part.encoding, charset)
    }

    /** K-9 id of the message in [folderId] with [uid], or `null`. */
    fun findId(folderId: Long, uid: String): Long? = database.query(
        "SELECT id FROM messages WHERE folder_id = ? AND uid = ? AND deleted = 0 AND empty = 0",
        listOf(folderId.toString(), uid),
    ).firstOrNull()?.get(0) as Long?

    /** Messages in inbox folders since [sinceMillis] (K-9 id, date), optionally only unread ones. */
    fun recentInbox(sinceMillis: Long, unreadOnly: Boolean): List<Pair<Long, Long>> = database.query(
        """
        SELECT m.id, m.date FROM messages m JOIN folders f ON f.id = m.folder_id
        WHERE m.deleted = 0 AND m.empty = 0 AND m.uid IS NOT NULL AND lower(f.type) = 'inbox' AND m.date >= ?
        """.trimIndent() + if (unreadOnly) " AND m.read = 0" else "",
        listOf(sinceMillis.toString()),
    ).map { (it[0] as Long) to (it[1] as Long? ?: 0L) }

    /** Subject, sender, date and the text parts of a message for the AI functions, or `null` if it is gone. */
    fun readText(k9Id: Long): MailForAi? {
        val row = database.query(
            """
            SELECT m.folder_id, m.uid, m.message_id, m.date, m.internal_date, m.subject, m.sender_list, m.message_part_id
            FROM messages m WHERE m.id = ? AND m.deleted = 0 AND m.empty = 0
            """.trimIndent(),
            listOf(k9Id.toString()),
        ).firstOrNull() ?: return null
        val folderId = row[0] as Long
        val uid = row[1] as String
        val rootPartId = row[7] as Long?
        val parts = rootPartId?.let { readParts(it) }.orEmpty()
        val plain = mutableListOf<String>()
        val html = mutableListOf<String>()
        for (part in parts) {
            val headers = MimeText.parseHeaders(part.header)
            val disposition = headers["content-disposition"]?.firstOrNull()
            val contentType = MimeText.parameters(headers["content-type"]?.firstOrNull())
            val name = MimeText.parameters(disposition)["filename"] ?: contentType["name"]
            val attachment = name != null || disposition?.startsWith("attachment", true) == true
            val text = textBody(part, contentType["charset"] ?: part.charset, attachment) ?: continue
            if (part.mimeType == "text/plain") plain += text else html += text
        }
        return MailForAi(
            location = MessageLocation(accountUuid, folderId, uid),
            headerMessageId = headerMessageIdOf(row[2] as String?, folderId, uid),
            subject = row[5] as String? ?: "",
            from = unpackAddresses(row[6] as String?).firstOrNull()?.let(::formatAddress).orEmpty(),
            date = (row[3] as Long?)?.takeIf { it > 0 } ?: (row[4] as Long?) ?: 0L,
            plain = plain.joinToString("\n\n").trim(),
            html = html.joinToString("\n\n"),
        )
    }

    /** All root headers and the leaf parts of a message for the phishing check, or `null` if it is gone. */
    fun readForCheck(k9Id: Long): MailForCheck? {
        val row = database.query(
            """
            SELECT m.folder_id, m.uid, m.message_id, m.date, m.internal_date, m.message_part_id, f.type
            FROM messages m JOIN folders f ON f.id = m.folder_id
            WHERE m.id = ? AND m.deleted = 0 AND m.empty = 0
            """.trimIndent(),
            listOf(k9Id.toString()),
        ).firstOrNull() ?: return null
        val folderId = row[0] as Long
        val uid = row[1] as String
        val rootPartId = row[5] as Long?
        val parts = rootPartId?.let { readParts(it) }.orEmpty()
        val root = parts.firstOrNull { it.id == rootPartId }
        val headers = MimeText.parseHeaders(root?.header).mapValues { (name, values) ->
            if (name in DECODED_HEADERS) values.map(MimeText::decodeEncodedWords) else values
        }
        val leaves = mutableListOf<MailPart>()
        fun walk(part: Part) {
            val children = parts.filter { it.parent == part.id && it.id != part.id }
            if (children.isEmpty()) leaves += checkPart(part) else children.forEach(::walk)
        }
        root?.let(::walk)
        return MailForCheck(
            accountUuid = accountUuid,
            k9Id = k9Id,
            location = MessageLocation(accountUuid, folderId, uid),
            headerMessageId = headerMessageIdOf(row[2] as String?, folderId, uid),
            date = (row[3] as Long?)?.takeIf { it > 0 } ?: (row[4] as Long?) ?: 0L,
            folderType = (row[6] as String?)?.lowercase(),
            headers = headers,
            parts = leaves,
        )
    }

    /** Like `flattenParts()` of the add-on: name and, for text and SVG parts (attachments too), the decoded body. */
    private fun checkPart(part: Part): MailPart {
        val headers = MimeText.parseHeaders(part.header)
        val disposition = headers["content-disposition"]?.firstOrNull()
        val contentType = MimeText.parameters(headers["content-type"]?.firstOrNull())
        val name = MimeText.parameters(disposition)["filename"] ?: contentType["name"]
        val isText = part.mimeType.startsWith("text/") || part.mimeType.contains("svg")
        val body = if (isText) {
            when (part.dataLocation) {
                DATA_IN_DATABASE -> part.data
                DATA_ON_DISK -> database.readPartFile(part.id)
                else -> null
            }?.let { raw ->
                val bytes = if (raw.size > MAX_TEXT_BYTES) raw.copyOf(MAX_TEXT_BYTES) else raw
                MimeText.decodeBody(bytes, part.encoding, contentType["charset"] ?: part.charset).take(MAX_CHECK_CHARS)
            }
        } else {
            null
        }
        return MailPart(contentType = part.mimeType, name = name, body = body)
    }

    internal data class PackedAddress(val email: String, val name: String?)

    companion object {
        // message_parts.data_location (see legacy/storage DataLocation.kt)
        private const val DATA_IN_DATABASE = 1L
        private const val DATA_ON_DISK = 2L

        /** Headers whose values the add-on gets decoded (Thunderbird's getHeaders decodes RFC 2047 there). */
        private val DECODED_HEADERS = setOf("from", "to", "cc", "reply-to", "sender", "subject")

        /** Like `flattenParts()` of the add-on, which keeps at most 400,000 characters per part. */
        private const val MAX_CHECK_CHARS = 400_000

        /** Enough for the index: repository.js keeps at most 100,000 characters of body text. */
        private const val MAX_TEXT_BYTES = 1_000_000

        fun indexFolderId(accountUuid: String, folderId: Long) = "$accountUuid:$folderId"

        /** Messages without Message-ID get a stable synthetic one (unique per account, folder and UID). */
        fun headerMessageIdOf(messageId: String?, folderId: Long, uid: String): String {
            val id = messageId?.trim()?.removePrefix("<")?.removeSuffix(">")?.trim()
            return if (id.isNullOrEmpty()) "k9-$folderId-$uid@novabird.invalid" else id
        }

        /** K-9 folder types to the Thunderbird special-use names that NovaBird-Search knows. */
        fun folderTypeOf(type: String?): String? = when (type?.lowercase()) {
            "inbox" -> "inbox"
            "sent" -> "sent"
            "drafts" -> "drafts"
            "archive" -> "archives"
            "trash" -> "trash"
            "spam" -> "junk"
            "outbox" -> "outbox"
            else -> null
        }

        /** Format of `Address.pack()` in K-9, see [unpackK9Addresses]. */
        fun unpackAddresses(packed: String?): List<PackedAddress> =
            unpackK9Addresses(packed).map { PackedAddress(it.email, it.name) }

        /** `Name <email>` as record.js parseAddress() expects it (no quotes or angle brackets in the name). */
        fun formatAddress(address: PackedAddress): String {
            val name = address.name?.replace(Regex("[\"<>]"), "")?.trim()
            return if (name.isNullOrEmpty()) address.email else "$name <${address.email}>"
        }

        private fun fingerprint(values: List<Any?>): String {
            var h = FNV_OFFSET
            for (c in values.joinToString("\u0000") { it?.toString() ?: "\u0001" }) {
                h = (h xor c.code.toLong()) * FNV_PRIME
            }
            return "$READER_VERSION-" + h.toULong().toString(16)
        }

        /** Raise when the reader extracts text differently, so that every message is indexed again. */
        private const val READER_VERSION = 2

        private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L
    }
}
