package net.thunderbird.feature.novabird.search.internal.indexer

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

/**
 * K-9 database for tests: the relevant tables with the columns of `StoreSchemaDefinition` (schema 91), in memory.
 * Writes go through [insertMessage] etc., the indexer only reads through [K9MailDatabase].
 */
class FakeK9MailStore(private val account: K9Account) : K9MailStore {
    private val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:")
    private val files = mutableMapOf<Long, ByteArray>()
    private var nextPartId = 1L

    init {
        connection.execSQL(
            "CREATE TABLE folders (id INTEGER PRIMARY KEY, name TEXT, server_id TEXT, type TEXT DEFAULT \"regular\", " +
                "local_only INTEGER, account_id TEXT)",
        )
        connection.execSQL(
            "CREATE TABLE messages (id INTEGER PRIMARY KEY, deleted INTEGER default 0, folder_id INTEGER, uid TEXT, " +
                "subject TEXT, date INTEGER, flags TEXT, sender_list TEXT, to_list TEXT, cc_list TEXT, bcc_list TEXT, " +
                "reply_to_list TEXT, attachment_count INTEGER, internal_date INTEGER, message_id TEXT, " +
                "preview_type TEXT default \"none\", preview TEXT, mime_type TEXT, normalized_subject_hash INTEGER, " +
                "empty INTEGER default 0, read INTEGER default 0, flagged INTEGER default 0, answered INTEGER default 0, " +
                "forwarded INTEGER default 0, message_part_id INTEGER, encryption_type TEXT, " +
                "new_message INTEGER DEFAULT 0, account_id TEXT)",
        )
        connection.execSQL(
            "CREATE TABLE message_parts (id INTEGER PRIMARY KEY, type INTEGER NOT NULL, root INTEGER, " +
                "parent INTEGER NOT NULL, seq INTEGER NOT NULL, mime_type TEXT, decoded_body_size INTEGER, " +
                "display_name TEXT, header TEXT, encoding TEXT, charset TEXT, data_location INTEGER NOT NULL, " +
                "data BLOB, preamble TEXT, epilogue TEXT, boundary TEXT, content_id TEXT, server_extra TEXT)",
        )
    }

    override fun accounts(): List<K9Account> = listOf(account)

    override fun open(accountUuid: String): K9MailDatabase? = if (accountUuid == account.uuid) Reader() else null

    fun addFolder(id: Long, name: String, type: String) {
        exec("INSERT INTO folders (id, name, server_id, type) VALUES (?, ?, ?, ?)", id, name, name, type)
    }

    data class TestPart(
        val mimeType: String,
        val header: String,
        val body: ByteArray? = null,
        val encoding: String? = null,
        val charset: String? = null,
        val onDisk: Boolean = false,
        val children: List<TestPart> = emptyList(),
    )

    @Suppress("LongParameterList")
    fun insertMessage(
        id: Long,
        folderId: Long,
        uid: String,
        messageId: String?,
        subject: String,
        date: Long,
        sender: String,
        to: String,
        root: TestPart,
        read: Boolean = false,
        flagged: Boolean = false,
    ) {
        val rootId = insertPart(root, parent = -1, seq = 0, rootId = null)
        exec(
            "INSERT INTO messages (id, folder_id, uid, message_id, subject, date, sender_list, to_list, cc_list, " +
                "read, flagged, message_part_id, internal_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', ?, ?, ?, ?)",
            id, folderId, uid, messageId, subject, date, sender, to, if (read) 1 else 0, if (flagged) 1 else 0,
            rootId, date,
        )
    }

    fun exec(sql: String, vararg args: Any?) {
        connection.prepare(sql).use { st ->
            args.forEachIndexed { i, v ->
                when (v) {
                    null -> st.bindNull(i + 1)
                    is Long -> st.bindLong(i + 1, v)
                    is Int -> st.bindLong(i + 1, v.toLong())
                    is ByteArray -> st.bindBlob(i + 1, v)
                    else -> st.bindText(i + 1, v.toString())
                }
            }
            st.step()
        }
    }

    private fun insertPart(part: TestPart, parent: Long, seq: Int, rootId: Long?): Long {
        val id = nextPartId++
        val location = when {
            part.mimeType.startsWith("multipart/") -> 3L // CHILD_PART_CONTAINS_DATA
            part.body == null -> 0L // MISSING
            part.onDisk -> 2L
            else -> 1L
        }
        if (part.onDisk && part.body != null) files[id] = part.body
        exec(
            "INSERT INTO message_parts (id, type, root, parent, seq, mime_type, decoded_body_size, header, encoding, " +
                "charset, data_location, data) VALUES (?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, rootId ?: id, parent, seq, part.mimeType, part.body?.size?.toLong() ?: 0L, part.header, part.encoding,
            part.charset, location, if (location == 1L) part.body else null,
        )
        part.children.forEachIndexed { index, child -> insertPart(child, parent = id, seq = index, rootId = rootId ?: id) }
        return id
    }

    private inner class Reader : K9MailDatabase {
        override fun query(sql: String, args: List<String>): List<List<Any?>> {
            return connection.prepare(sql).use { st ->
                args.forEachIndexed { i, v -> st.bindText(i + 1, v) }
                val rows = mutableListOf<List<Any?>>()
                while (st.step()) {
                    rows += List(st.getColumnCount()) { c ->
                        when (st.getColumnType(c)) {
                            SQLITE_DATA_INTEGER -> st.getLong(c)
                            SQLITE_DATA_FLOAT -> st.getDouble(c)
                            SQLITE_DATA_TEXT -> st.getText(c)
                            SQLITE_DATA_BLOB -> st.getBlob(c)
                            else -> null
                        }
                    }
                }
                rows
            }
        }

        override fun readPartFile(partId: Long): ByteArray? = files[partId]

        override fun close() = Unit
    }
}
