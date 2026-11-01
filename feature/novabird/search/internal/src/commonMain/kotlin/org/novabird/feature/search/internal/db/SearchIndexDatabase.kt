package org.novabird.feature.search.internal.db

import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The NovaBird index database: its own file, opened with the bundled SQLite (FTS5, trigram, fts5vocab).
 *
 * Mirrors the adapter contract of NovaBird-Search (`db/adapter.js`): `execute`, `select`, `batch`, parameters as
 * positional arrays. Not thread-safe; callers confine it to one thread at a time.
 *
 * @param fileName path of the database file, or `:memory:`.
 */
internal class SearchIndexDatabase(fileName: String) : AutoCloseable {
    private val connection: SQLiteConnection =
        BundledSQLiteDriver().open(fileName, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)

    init {
        connection.execSQL("PRAGMA journal_mode = WAL")
        connection.execSQL("PRAGMA foreign_keys = OFF")
    }

    /** Runs one statement; returns the number of changed rows. */
    fun execute(sql: String, params: JsonArray): Long {
        connection.prepare(sql).use { statement ->
            statement.bindAll(params)
            while (statement.step()) {
                // drain (e.g. PRAGMA with result rows)
            }
        }
        return connection.prepare("SELECT changes()").use { statement ->
            if (statement.step()) statement.getLong(0) else 0L
        }
    }

    /** Runs a query; returns the rows as positional arrays (INTEGER, REAL, TEXT or null; BLOBs as null). */
    fun select(sql: String, params: JsonArray): JsonArray {
        return connection.prepare(sql).use { statement ->
            statement.bindAll(params)
            val rows = mutableListOf<JsonElement>()
            while (statement.step()) {
                rows.add(JsonArray(List(statement.getColumnCount()) { statement.columnValue(it) }))
            }
            JsonArray(rows)
        }
    }

    /** Runs the statements `[[sql, params], …]` in one transaction. */
    fun batch(statements: JsonArray) {
        transaction {
            for (statement in statements) {
                val (sql, params) = statement.jsonArray
                execute(sql.jsonPrimitive.content, params.jsonArray)
            }
        }
    }

    fun <T> transaction(block: () -> T): T {
        connection.execSQL("BEGIN IMMEDIATE")
        val result = try {
            block()
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            connection.execSQL("ROLLBACK")
            throw e
        }
        connection.execSQL("COMMIT")
        return result
    }

    override fun close() {
        connection.close()
    }

    private fun SQLiteStatement.bindAll(params: JsonArray) {
        params.forEachIndexed { index, value -> bindValue(index + 1, value) }
    }

    private fun SQLiteStatement.bindValue(index: Int, value: JsonElement) {
        val primitive = value as? JsonPrimitive
        when {
            primitive == null || primitive is JsonNull -> bindNull(index)
            primitive.isString -> bindText(index, primitive.content)
            primitive.booleanOrNull != null -> bindLong(index, if (primitive.booleanOrNull == true) 1L else 0L)
            primitive.longOrNull != null -> bindLong(index, primitive.longOrNull!!)
            primitive.doubleOrNull != null -> bindValueDouble(index, primitive.doubleOrNull!!)
            else -> bindText(index, primitive.content)
        }
    }

    private fun SQLiteStatement.bindValueDouble(index: Int, value: Double) {
        // JSON.stringify writes integral numbers without fraction; this covers values like 1.0E12 as well.
        if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < MAX_SAFE_INTEGER) {
            bindLong(index, value.toLong())
        } else {
            bindDouble(index, value)
        }
    }

    private fun SQLiteStatement.columnValue(index: Int): JsonElement {
        return when (getColumnType(index)) {
            SQLITE_DATA_INTEGER -> JsonPrimitive(getLong(index))
            SQLITE_DATA_FLOAT -> JsonPrimitive(getDouble(index))
            SQLITE_DATA_TEXT -> JsonPrimitive(getText(index))
            else -> JsonNull
        }
    }

    private companion object {
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0
    }
}
