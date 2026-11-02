package org.novabird.feature.sync.backfill

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.fsck.k9.mailstore.LocalStoreProvider
import java.util.Date
import net.thunderbird.core.android.account.LegacyAccountDto

fun interface PartialMessageFinder {
    /**
     * Returns the partially downloaded messages of synchronized folders on or after [earliestDate] (`null` = all).
     */
    fun findPartialMessages(account: LegacyAccountDto, earliestDate: Date?): List<PartialMessageCandidate>
}

/**
 * Reads partially downloaded messages from the local message database (read-only).
 */
internal class LocalStorePartialMessageFinder(
    private val localStoreProvider: LocalStoreProvider,
) : PartialMessageFinder {
    override fun findPartialMessages(account: LegacyAccountDto, earliestDate: Date?): List<PartialMessageCandidate> {
        val database = localStoreProvider.getInstance(account).database

        return database.execute(false) { db -> db.queryPartialMessages(earliestDate) }
    }
}

/**
 * Queries partially downloaded messages of synchronized folders, newest first.
 *
 * The server size of a message isn't stored locally. The size is estimated as the sum of the sizes of all message
 * parts. For parts that weren't downloaded the size reported by the server in `BODYSTRUCTURE` is stored. If a message
 * has no missing parts (the server didn't provide a structure), its size is unknown.
 */
internal fun SQLiteDatabase.queryPartialMessages(earliestDate: Date?): List<PartialMessageCandidate> {
    val selectionArgs = earliestDate?.let { arrayOf(it.time.toString()) }
    val query = if (earliestDate != null) "$QUERY AND m.date >= ? $ORDER" else "$QUERY $ORDER"

    return rawQuery(query, selectionArgs).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(cursor.toCandidate())
            }
        }
    }
}

private fun Cursor.toCandidate(): PartialMessageCandidate {
    val knownSize = if (isNull(INDEX_KNOWN_SIZE)) 0L else getLong(INDEX_KNOWN_SIZE)
    val missingParts = getInt(INDEX_MISSING_PARTS)

    return PartialMessageCandidate(
        folderServerId = getString(INDEX_FOLDER_SERVER_ID),
        messageServerId = getString(INDEX_MESSAGE_SERVER_ID),
        date = getLong(INDEX_DATE),
        estimatedSize = if (missingParts > 0) knownSize else null,
    )
}

// Values of the column 'message_parts.data_location' (see SaveMessageOperations.DataLocation)
private const val DATA_LOCATION_MISSING = 0
private const val DATA_LOCATION_IN_DATABASE = 1
private const val DATA_LOCATION_ON_DISK = 2

private const val INDEX_FOLDER_SERVER_ID = 0
private const val INDEX_MESSAGE_SERVER_ID = 1
private const val INDEX_DATE = 2
private const val INDEX_KNOWN_SIZE = 3
private const val INDEX_MISSING_PARTS = 4

// Only folders with 'sync_enabled' are synchronized (see MessagingController.checkMail)
private const val QUERY = "SELECT f.server_id, m.uid, m.date, " +
    "(SELECT SUM(p.decoded_body_size) FROM message_parts p WHERE p.root = m.message_part_id AND " +
    "p.data_location IN ($DATA_LOCATION_MISSING, $DATA_LOCATION_IN_DATABASE, $DATA_LOCATION_ON_DISK)), " +
    "(SELECT COUNT(*) FROM message_parts p WHERE p.root = m.message_part_id AND " +
    "p.data_location = $DATA_LOCATION_MISSING) " +
    "FROM messages m JOIN folders f ON f.id = m.folder_id " +
    "WHERE m.deleted = 0 AND m.empty = 0 AND m.message_part_id IS NOT NULL " +
    "AND IFNULL(f.local_only, 0) = 0 AND f.sync_enabled = 1 " +
    "AND m.flags LIKE '%X_DOWNLOADED_PARTIAL%'"

private const val ORDER = "ORDER BY m.date DESC"
