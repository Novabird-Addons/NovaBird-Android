package org.novabird.feature.search.internal.indexer

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File

/**
 * [K9MailStore] for the K-9 databases of the app: `<accountUuid>.db` with attachments in `<accountUuid>.db_att`
 * (see `AndroidStorageFilesProvider` in legacy/core).
 *
 * Uses the platform SQLite read-only, the same library K-9 writes with; see [K9MailDatabase] for why not the bundled
 * SQLite of the index.
 *
 * @param accounts accounts to index, provided by the app module (e.g. from the account manager).
 */
class AndroidK9MailStore(
    private val context: Context,
    private val accounts: () -> List<K9Account>,
) : K9MailStore {

    override fun accounts(): List<K9Account> = accounts.invoke()

    override fun open(accountUuid: String): K9MailDatabase? {
        val file = context.getDatabasePath("$accountUuid.db")
        if (!file.isFile) return null
        val database = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: SQLiteException) {
            return null
        }
        return FrameworkK9MailDatabase(database, context.getDatabasePath("$accountUuid.db_att"))
    }
}

private class FrameworkK9MailDatabase(
    private val database: SQLiteDatabase,
    private val attachmentDirectory: File,
) : K9MailDatabase {

    override fun query(sql: String, args: List<String>): List<List<Any?>> {
        return database.rawQuery(sql, args.toTypedArray()).use { cursor ->
            val rows = ArrayList<List<Any?>>(cursor.count)
            while (cursor.moveToNext()) {
                rows += List(cursor.columnCount) { cursor.valueAt(it) }
            }
            rows
        }
    }

    override fun readPartFile(partId: Long): ByteArray? {
        val file = File(attachmentDirectory, partId.toString())
        return if (file.isFile) file.readBytes() else null
    }

    override fun close() {
        database.close()
    }

    private fun Cursor.valueAt(index: Int): Any? = when (getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
        Cursor.FIELD_TYPE_STRING -> getString(index)
        Cursor.FIELD_TYPE_BLOB -> getBlob(index)
        else -> null
    }
}
