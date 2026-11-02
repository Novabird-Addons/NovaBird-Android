package org.novabird.feature.protection

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** A stored check result of one message (K-9 database id [k9Id] in account [accountUuid]). */
data class StoredVerdict(
    val accountUuid: String,
    val k9Id: Long,
    val folderId: Long,
    val uid: String,
    val headerMessageId: String,
    val sender: String,
    val verdict: Verdict,
    val rulesVersion: String,
    val checkedAt: Long,
) {
    val level: ProtectionLevel get() = verdict.level
}

/**
 * Check results in their own database file (`novabird-protection.db`), opened with the bundled SQLite like the search
 * index. The K-9 databases stay read-only.
 *
 * Not thread-safe; callers confine it to one thread at a time (see [ProtectionService]).
 *
 * @param fileName path of the database file, or `:memory:`.
 */
class ProtectionStore(fileName: String) : AutoCloseable {
    private val connection: SQLiteConnection =
        BundledSQLiteDriver().open(fileName, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)

    init {
        connection.execSQL("PRAGMA journal_mode = WAL")
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS nb_protection (
              account_uuid TEXT NOT NULL, k9_id INTEGER NOT NULL, folder_id INTEGER NOT NULL, uid TEXT NOT NULL,
              header_message_id TEXT NOT NULL, sender TEXT NOT NULL, level TEXT NOT NULL, score INTEGER NOT NULL,
              verdict_json TEXT NOT NULL, rules_version TEXT NOT NULL, checked_at INTEGER NOT NULL,
              PRIMARY KEY (account_uuid, k9_id))
            """.trimIndent(),
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS nb_protection_sender ON nb_protection (sender, level)")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS nb_protection_account (account_uuid TEXT PRIMARY KEY, watermark INTEGER NOT NULL)",
        )
    }

    fun save(record: StoredVerdict) {
        connection.prepare(
            """
            INSERT OR REPLACE INTO nb_protection (account_uuid, k9_id, folder_id, uid, header_message_id, sender, level,
              score, verdict_json, rules_version, checked_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, record.accountUuid)
            statement.bindLong(2, record.k9Id)
            statement.bindLong(3, record.folderId)
            statement.bindText(4, record.uid)
            statement.bindText(5, record.headerMessageId)
            statement.bindText(6, record.sender)
            statement.bindText(7, record.level.id)
            statement.bindLong(8, record.verdict.score.toLong())
            statement.bindText(9, record.verdict.json.toString())
            statement.bindText(10, record.rulesVersion)
            statement.bindLong(11, record.checkedAt)
            statement.step()
        }
    }

    fun get(accountUuid: String, k9Id: Long): StoredVerdict? = connection.prepare(
        """
        SELECT account_uuid, k9_id, folder_id, uid, header_message_id, sender, verdict_json, rules_version, checked_at
        FROM nb_protection WHERE account_uuid = ? AND k9_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.bindText(1, accountUuid)
        statement.bindLong(2, k9Id)
        if (statement.step()) statement.toStoredVerdict() else null
    }

    /** Levels of the given messages that have a result (for the message list). */
    fun levels(accountUuid: String, k9Ids: Collection<Long>): Map<Long, ProtectionLevel> {
        if (k9Ids.isEmpty()) return emptyMap()
        val result = HashMap<Long, ProtectionLevel>()
        for (chunk in k9Ids.distinct().chunked(MAX_PARAMS)) {
            val placeholders = chunk.joinToString(",") { "?" }
            connection.prepare(
                "SELECT k9_id, level FROM nb_protection WHERE account_uuid = ? AND k9_id IN ($placeholders)",
            ).use { statement ->
                statement.bindText(1, accountUuid)
                chunk.forEachIndexed { i, id -> statement.bindLong(i + 2, id) }
                while (statement.step()) {
                    result[statement.getLong(0)] = ProtectionLevel.fromId(statement.getText(1))
                }
            }
        }
        return result
    }

    /** Ids among [k9Ids] already checked with [rulesVersion]. */
    fun checkedIds(accountUuid: String, k9Ids: Collection<Long>, rulesVersion: String): Set<Long> {
        val result = HashSet<Long>()
        for (chunk in k9Ids.distinct().chunked(MAX_PARAMS)) {
            val placeholders = chunk.joinToString(",") { "?" }
            connection.prepare(
                "SELECT k9_id FROM nb_protection WHERE account_uuid = ? AND rules_version = ? AND k9_id IN ($placeholders)",
            ).use { statement ->
                statement.bindText(1, accountUuid)
                statement.bindText(2, rulesVersion)
                chunk.forEachIndexed { i, id -> statement.bindLong(i + 3, id) }
                while (statement.step()) result += statement.getLong(0)
            }
        }
        return result
    }

    /** Removes all results of [sender] (trusted or untrusted: the next open checks again). Returns the number removed. */
    fun deleteBySender(sender: String): Long {
        connection.prepare("DELETE FROM nb_protection WHERE sender = ?").use { statement ->
            statement.bindText(1, sender)
            statement.step()
        }
        return connection.prepare("SELECT changes()").use { if (it.step()) it.getLong(0) else 0L }
    }

    /** Senders with at least one mail rated suspicious or dangerous; they never count as established (lib/history.js). */
    fun flaggedSenders(): Set<String> =
        connection.prepare("SELECT DISTINCT sender FROM nb_protection WHERE level != 'normal' AND sender != ''")
            .use { statement ->
                buildSet { while (statement.step()) add(statement.getText(0)) }
            }

    /** Highest K-9 message id that existed when protection was switched on; only newer mails are checked in the background. */
    fun watermark(accountUuid: String): Long? =
        connection.prepare("SELECT watermark FROM nb_protection_account WHERE account_uuid = ?").use { statement ->
            statement.bindText(1, accountUuid)
            if (statement.step()) statement.getLong(0) else null
        }

    fun setWatermark(accountUuid: String, watermark: Long) {
        connection.prepare("INSERT OR REPLACE INTO nb_protection_account (account_uuid, watermark) VALUES (?, ?)")
            .use { statement ->
                statement.bindText(1, accountUuid)
                statement.bindLong(2, watermark)
                statement.step()
            }
    }

    fun clearWatermarks() {
        connection.execSQL("DELETE FROM nb_protection_account")
    }

    override fun close() {
        connection.close()
    }

    private fun SQLiteStatement.toStoredVerdict() = StoredVerdict(
        accountUuid = getText(0),
        k9Id = getLong(1),
        folderId = getLong(2),
        uid = getText(3),
        headerMessageId = getText(4),
        sender = getText(5),
        verdict = Verdict.fromJson(Json.parseToJsonElement(getText(6)).jsonObject),
        rulesVersion = getText(7),
        checkedAt = getLong(8),
    )

    private companion object {
        const val MAX_PARAMS = 500
    }
}
