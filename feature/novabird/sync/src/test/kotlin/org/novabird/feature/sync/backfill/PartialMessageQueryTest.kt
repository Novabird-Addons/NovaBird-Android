package org.novabird.feature.sync.backfill

import android.database.sqlite.SQLiteDatabase
import androidx.core.content.contentValuesOf
import assertk.assertThat
import assertk.assertions.containsExactly
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the query of [LocalStorePartialMessageFinder] against the relevant tables of the K-9 message database.
 *
 * The table definitions are copied from `StoreSchemaDefinition` (legacy/storage, schema version 91), rows are written
 * the way `SaveMessageOperations` writes them.
 */
@RunWith(RobolectricTestRunner::class)
class PartialMessageQueryTest {
    private lateinit var db: SQLiteDatabase
    private var nextPartId = 1L

    @BeforeTest
    fun setUp() {
        db = SQLiteDatabase.create(null)
        createTables()
        insertFolder(id = 1, serverId = "INBOX", syncEnabled = true)
        insertFolder(id = 2, serverId = "Archive", syncEnabled = false)
        insertFolder(id = 3, serverId = "Outbox", syncEnabled = true, localOnly = true)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `partial message with missing parts has the sum of all part sizes`() {
        // Arrange
        insertMessage(
            uid = "1",
            flags = "X_DOWNLOADED_PARTIAL",
            parts = listOf(PART_IN_DATABASE to 2_000L, PART_MISSING to 900_000L, PART_MISSING to 100_000L),
        )

        // Act
        val result = db.queryPartialMessages(earliestDate = null)

        // Assert
        assertThat(result).containsExactly(candidate(uid = "1", estimatedSize = 1_002_000L))
    }

    @Test
    fun `partial message without missing parts has an unknown size`() {
        // Arrange
        insertMessage(uid = "1", flags = "X_DOWNLOADED_PARTIAL", parts = listOf(PART_IN_DATABASE to 4_096L))

        // Act
        val result = db.queryPartialMessages(earliestDate = null)

        // Assert
        assertThat(result).containsExactly(candidate(uid = "1", estimatedSize = null))
    }

    @Test
    fun `only partial, not deleted messages of synchronized server folders on or after the date are returned`() {
        // Arrange
        insertMessage(uid = "partial", flags = "X_DOWNLOADED_PARTIAL,DRAFT", date = 3_000)
        insertMessage(uid = "full", flags = "X_DOWNLOADED_FULL", date = 3_000)
        insertMessage(uid = "deleted", flags = "X_DOWNLOADED_PARTIAL", date = 3_000, deleted = true)
        insertMessage(uid = "old", flags = "X_DOWNLOADED_PARTIAL", date = 999)
        insertMessage(uid = "notSynced", flags = "X_DOWNLOADED_PARTIAL", date = 3_000, folderId = 2)
        insertMessage(uid = "local", flags = "X_DOWNLOADED_PARTIAL", date = 3_000, folderId = 3)
        insertMessage(uid = "boundary", flags = "X_DOWNLOADED_PARTIAL", date = 1_000)

        // Act
        val result = db.queryPartialMessages(earliestDate = Date(1_000))

        // Assert
        assertThat(result.map { it.messageServerId }).containsExactly("partial", "boundary")
    }

    private fun candidate(uid: String, estimatedSize: Long?) = PartialMessageCandidate(
        folderServerId = "INBOX",
        messageServerId = uid,
        date = DEFAULT_DATE,
        estimatedSize = estimatedSize,
    )

    private fun insertFolder(id: Long, serverId: String, syncEnabled: Boolean, localOnly: Boolean = false) {
        db.insertOrThrow(
            "folders",
            null,
            contentValuesOf(
                "id" to id,
                "name" to serverId,
                "server_id" to serverId,
                "local_only" to localOnly,
                "sync_enabled" to syncEnabled,
            ),
        )
    }

    private fun insertMessage(
        uid: String,
        flags: String,
        date: Long = DEFAULT_DATE,
        deleted: Boolean = false,
        folderId: Long = 1,
        parts: List<Pair<Int, Long>> = listOf(PART_IN_DATABASE to 100L, PART_MISSING to 1_000L),
    ) {
        val rootPartId = nextPartId
        db.insertOrThrow(
            "message_parts",
            null,
            contentValuesOf(
                "id" to rootPartId,
                "type" to 0,
                "parent" to -1,
                "seq" to 0,
                "mime_type" to "multipart/mixed",
                "data_location" to PART_CHILD_PART_CONTAINS_DATA,
            ),
        )
        nextPartId++
        parts.forEachIndexed { index, (dataLocation, size) ->
            db.insertOrThrow(
                "message_parts",
                null,
                contentValuesOf(
                    "id" to nextPartId++,
                    "type" to 0,
                    "root" to rootPartId,
                    "parent" to rootPartId,
                    "seq" to index + 1,
                    "decoded_body_size" to size,
                    "data_location" to dataLocation,
                ),
            )
        }

        db.insertOrThrow(
            "messages",
            null,
            contentValuesOf(
                "folder_id" to folderId,
                "uid" to uid,
                "date" to date,
                "flags" to flags,
                "deleted" to deleted,
                "empty" to false,
                "message_part_id" to rootPartId,
            ),
        )
    }

    private fun createTables() {
        createFoldersTable()
        createMessagesTable()
        createMessagePartsTable()
    }

    private fun createFoldersTable() {
        db.execSQL(
            "CREATE TABLE folders (" +
                "id INTEGER PRIMARY KEY," +
                "name TEXT, " +
                "last_updated INTEGER, " +
                "unread_count INTEGER, " +
                "visible_limit INTEGER, " +
                "status TEXT, " +
                "flagged_count INTEGER default 0, " +
                "integrate INTEGER, " +
                "top_group INTEGER, " +
                "sync_enabled INTEGER DEFAULT 0, " +
                "push_enabled INTEGER DEFAULT 0, " +
                "visible INTEGER DEFAULT 1, " +
                "notifications_enabled INTEGER DEFAULT 0, " +
                "more_messages TEXT default \"unknown\", " +
                "server_id TEXT, " +
                "local_only INTEGER, " +
                "type TEXT DEFAULT \"regular\", " +
                "account_id TEXT" +
                ")",
        )
    }

    private fun createMessagesTable() {
        db.execSQL(
            "CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY, " +
                "deleted INTEGER default 0, " +
                "folder_id INTEGER, " +
                "uid TEXT, " +
                "subject TEXT, " +
                "date INTEGER, " +
                "flags TEXT, " +
                "sender_list TEXT, " +
                "to_list TEXT, " +
                "cc_list TEXT, " +
                "bcc_list TEXT, " +
                "reply_to_list TEXT, " +
                "attachment_count INTEGER, " +
                "internal_date INTEGER, " +
                "message_id TEXT, " +
                "preview_type TEXT default \"none\", " +
                "preview TEXT, " +
                "mime_type TEXT, " +
                "normalized_subject_hash INTEGER, " +
                "empty INTEGER default 0, " +
                "read INTEGER default 0, " +
                "flagged INTEGER default 0, " +
                "answered INTEGER default 0, " +
                "forwarded INTEGER default 0, " +
                "message_part_id INTEGER," +
                "encryption_type TEXT," +
                "new_message INTEGER DEFAULT 0," +
                "account_id TEXT" +
                ")",
        )
    }

    private fun createMessagePartsTable() {
        db.execSQL(
            "CREATE TABLE message_parts (" +
                "id INTEGER PRIMARY KEY, " +
                "type INTEGER NOT NULL, " +
                "root INTEGER, " +
                "parent INTEGER NOT NULL, " +
                "seq INTEGER NOT NULL, " +
                "mime_type TEXT, " +
                "decoded_body_size INTEGER, " +
                "display_name TEXT, " +
                "header TEXT, " +
                "encoding TEXT, " +
                "charset TEXT, " +
                "data_location INTEGER NOT NULL, " +
                "data BLOB, " +
                "preamble TEXT, " +
                "epilogue TEXT, " +
                "boundary TEXT, " +
                "content_id TEXT, " +
                "server_extra TEXT" +
                ")",
        )
        db.execSQL(
            "CREATE TRIGGER set_message_part_root " +
                "AFTER INSERT ON message_parts " +
                "BEGIN " +
                "UPDATE message_parts SET root=id WHERE root IS NULL AND ROWID = NEW.ROWID; " +
                "END",
        )
    }

    private companion object {
        const val DEFAULT_DATE = 2_000L

        // Values of SaveMessageOperations.DataLocation
        const val PART_MISSING = 0
        const val PART_IN_DATABASE = 1
        const val PART_CHILD_PART_CONTAINS_DATA = 3
    }
}
