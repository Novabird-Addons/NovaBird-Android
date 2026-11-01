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
              CASE WHEN mime_type LIKE 'text/%' AND data_location = $DATA_IN_DATABASE THEN data END,
              decoded_body_size
            FROM message_parts WHERE root = ? ORDER BY parent, seq
            """.trimIndent(),
            listOf(rootPartId.toString()),
        ).map { r ->
            Part(
                id = r[0] as Long,
                parent = r[1] as Long? ?: -1L,
                mimeType = (r[2] as String?)?.lowercase().orEmpty(),
                header = r[3] as String?,
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
        val name = MimeText.parameters(disposition)["filename"]
            ?: MimeText.parameters(headers["content-type"]?.firstOrNull())["name"]
        return buildJsonObject {
            put("contentType", part.mimeType)
            name?.let { put("name", it) }
            put("size", part.size)
            putJsonObject("headers") {
                disposition?.let { putJsonArray("content-disposition") { add(it) } }
            }
            textBody(part, isAttachment = name != null || disposition?.startsWith("attachment", true) == true)
                ?.let { put("body", it) }
            val children = all.filter { it.parent == part.id && it.id != part.id }
            if (children.isNotEmpty()) put("parts", buildJsonArray { children.forEach { add(toJson(it, all)) } })
        }
    }

    private fun textBody(part: Part, isAttachment: Boolean): String? {
        if (isAttachment || (part.mimeType != "text/plain" && part.mimeType != "text/html")) return null
        val raw = when (part.dataLocation) {
            DATA_IN_DATABASE -> part.data
            DATA_ON_DISK -> database.readPartFile(part.id)
            else -> null
        } ?: return null
        val bytes = if (raw.size > MAX_TEXT_BYTES) raw.copyOf(MAX_TEXT_BYTES) else raw
        return MimeText.decodeBody(bytes, part.encoding, part.charset)
    }

    internal data class PackedAddress(val email: String, val name: String?)

    companion object {
        // message_parts.data_location (see legacy/storage DataLocation.kt)
        private const val DATA_IN_DATABASE = 1L
        private const val DATA_ON_DISK = 2L

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

        /** Format of `Address.pack()` in K-9: `email;\u0001name,\u0001email…`, quotes in names escaped. */
        fun unpackAddresses(packed: String?): List<PackedAddress> {
            if (packed.isNullOrEmpty()) return emptyList()
            return packed.split(",\u0001").filter { it.isNotEmpty() }.map { pair ->
                val separator = pair.indexOf(";\u0001")
                if (separator < 0) {
                    PackedAddress(pair, null)
                } else {
                    PackedAddress(pair.substring(0, separator), pair.substring(separator + 2).replace("\\\"", "\""))
                }
            }
        }

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
            return h.toULong().toString(16)
        }

        private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L
    }
}
