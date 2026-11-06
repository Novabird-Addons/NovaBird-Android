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
    /** QR codes, text in images and attachment types the check used (`null`: none read). */
    val extras: MailExtras? = null,
    /** Spam decision (`null`: no spam, or spam detection off when it was checked). */
    val spam: SpamDecision? = null,
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
        val columns = connection.prepare("PRAGMA table_info(nb_protection)").use { statement ->
            buildSet { while (statement.step()) add(statement.getText(1)) }
        }
        if ("extras_json" !in columns) connection.execSQL("ALTER TABLE nb_protection ADD COLUMN extras_json TEXT")
        if ("spam_reason" !in columns) {
            connection.execSQL("ALTER TABLE nb_protection ADD COLUMN spam_reason TEXT")
            connection.execSQL("ALTER TABLE nb_protection ADD COLUMN spam_detail TEXT")
        }
        connection.execSQL("CREATE INDEX IF NOT EXISTS nb_protection_sender ON nb_protection (sender, level)")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS nb_protection_account (account_uuid TEXT PRIMARY KEY, watermark INTEGER NOT NULL)",
        )
        val accountColumns = connection.prepare("PRAGMA table_info(nb_protection_account)").use { statement ->
            buildSet { while (statement.step()) add(statement.getText(1)) }
        }
        if ("since" !in accountColumns) connection.execSQL("ALTER TABLE nb_protection_account ADD COLUMN since INTEGER")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS nb_protection_media (account_uuid TEXT NOT NULL, k9_id INTEGER NOT NULL, " +
                "PRIMARY KEY (account_uuid, k9_id))",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS nb_protection_authserv (account_uuid TEXT NOT NULL, authserv TEXT NOT NULL, " +
                "seen INTEGER NOT NULL, PRIMARY KEY (account_uuid, authserv))",
        )
    }

    /**
     * Counts the authserv-id of the topmost Authentication-Results header of a new mail ("" if it has none). Same rule
     * as the add-on (lib/authserv.js): at most 10 ids per account (a sender can make up a new one per mail), and from
     * 200 mails on all counts are halved, so a change of the mail provider wins again.
     */
    fun countAuthserv(accountUuid: String, authserv: String) {
        val counts = authservCounts(accountUuid).toMutableMap()
        counts[authserv] = (counts[authserv] ?: 0) + 1
        var entries = counts.toList()
        if (entries.sumOf { it.second } >= AUTHSERV_MAX_MAILS) entries = entries.map { (id, n) -> id to n / 2 }
        entries = entries.filter { it.second > 0 }.sortedByDescending { it.second }.take(AUTHSERV_MAX_IDS)

        connection.prepare("DELETE FROM nb_protection_authserv WHERE account_uuid = ?").use { statement ->
            statement.bindText(1, accountUuid)
            statement.step()
        }
        for ((id, seen) in entries) {
            connection.prepare(
                "INSERT INTO nb_protection_authserv (account_uuid, authserv, seen) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.bindText(1, accountUuid)
                statement.bindText(2, id)
                statement.bindLong(3, seen)
                statement.step()
            }
        }
    }

    /** How often each authserv-id ("" = none) was seen on top of the account's mails. */
    fun authservCounts(accountUuid: String): Map<String, Long> =
        connection.prepare(
            "SELECT authserv, seen FROM nb_protection_authserv WHERE account_uuid = ?",
        ).use { statement ->
            statement.bindText(1, accountUuid)
            buildMap { while (statement.step()) put(statement.getText(0), statement.getLong(1)) }
        }

    /** K-9 id and Message-ID of all results of the account. */
    fun messageIds(accountUuid: String): Map<Long, String> =
        connection.prepare(
            "SELECT k9_id, header_message_id FROM nb_protection WHERE account_uuid = ?",
        ).use { statement ->
            statement.bindText(1, accountUuid)
            buildMap { while (statement.step()) put(statement.getLong(0), statement.getText(1)) }
        }

    /** Removes the results of [k9Ids] and their marks of a media read. */
    fun deleteResults(accountUuid: String, k9Ids: Collection<Long>) {
        for (table in listOf("nb_protection", "nb_protection_media")) {
            for (chunk in k9Ids.distinct().chunked(MAX_PARAMS)) {
                val placeholders = chunk.joinToString(",") { "?" }
                connection.prepare(
                    "DELETE FROM $table WHERE account_uuid = ? AND k9_id IN ($placeholders)",
                ).use { statement ->
                    statement.bindText(1, accountUuid)
                    chunk.forEachIndexed { i, id -> statement.bindLong(i + 2, id) }
                    statement.step()
                }
            }
        }
    }

    fun save(record: StoredVerdict) {
        connection.prepare(
            """
            INSERT OR REPLACE INTO nb_protection (account_uuid, k9_id, folder_id, uid, header_message_id, sender, level,
              score, verdict_json, rules_version, checked_at, extras_json, spam_reason, spam_detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            val extras = record.extras
            if (extras == null) statement.bindNull(12) else statement.bindText(12, extras.toJson().toString())
            val spam = record.spam
            if (spam == null) {
                statement.bindNull(13)
                statement.bindNull(14)
            } else {
                statement.bindText(13, spam.reason.id)
                statement.bindText(14, spam.detail)
            }
            statement.step()
        }
    }

    fun get(accountUuid: String, k9Id: Long): StoredVerdict? = connection.prepare(
        """
        SELECT account_uuid, k9_id, folder_id, uid, header_message_id, sender, verdict_json, rules_version, checked_at,
          extras_json, spam_reason, spam_detail
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

    /** Level and spam mark of the given messages that have a result (for the message list). */
    fun marks(accountUuid: String, k9Ids: Collection<Long>): Map<Long, ListMark> {
        if (k9Ids.isEmpty()) return emptyMap()
        val result = HashMap<Long, ListMark>()
        for (chunk in k9Ids.distinct().chunked(MAX_PARAMS)) {
            val placeholders = chunk.joinToString(",") { "?" }
            connection.prepare(
                "SELECT k9_id, level, spam_reason FROM nb_protection WHERE account_uuid = ? AND k9_id IN ($placeholders)",
            ).use { statement ->
                statement.bindText(1, accountUuid)
                chunk.forEachIndexed { i, id -> statement.bindLong(i + 2, id) }
                while (statement.step()) {
                    result[statement.getLong(0)] =
                        ListMark(ProtectionLevel.fromId(statement.getText(1)), spam = !statement.isNull(2))
                }
            }
        }
        return result
    }

    /** Senders with at least one stored spam decision. */
    fun spamSenders(): Set<String> =
        connection.prepare("SELECT DISTINCT sender FROM nb_protection WHERE spam_reason IS NOT NULL AND sender != ''")
            .use { statement ->
                buildSet { while (statement.step()) add(statement.getText(0)) }
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

    /**
     * Time of the last finished background check of the account (`null` for data from before this was stored). Only
     * mails that reached the server after it count as new; older mails loaded later (e.g. after "all messages") don't.
     */
    fun since(accountUuid: String): Long? =
        connection.prepare("SELECT since FROM nb_protection_account WHERE account_uuid = ?").use { statement ->
            statement.bindText(1, accountUuid)
            if (statement.step() && !statement.isNull(0)) statement.getLong(0) else null
        }

    fun setWatermark(accountUuid: String, watermark: Long, since: Long?) {
        connection.prepare(
            "INSERT OR REPLACE INTO nb_protection_account (account_uuid, watermark, since) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.bindText(1, accountUuid)
            statement.bindLong(2, watermark)
            if (since == null) statement.bindNull(3) else statement.bindLong(3, since)
            statement.step()
        }
    }

    /**
     * Marks that images and attachments of a mail are being read. The mark stays if the app dies while reading (native
     * text recognition, memory), so the next check knows and leaves the media of that mail out instead of dying again.
     */
    fun markMediaAttempt(accountUuid: String, k9Id: Long) = mediaStatement(
        "INSERT OR REPLACE INTO nb_protection_media (account_uuid, k9_id) VALUES (?, ?)",
        accountUuid,
        k9Id,
    )

    fun clearMediaAttempt(accountUuid: String, k9Id: Long) = mediaStatement(
        "DELETE FROM nb_protection_media WHERE account_uuid = ? AND k9_id = ?",
        accountUuid,
        k9Id,
    )

    fun mediaAttempted(accountUuid: String, k9Id: Long): Boolean =
        connection.prepare("SELECT 1 FROM nb_protection_media WHERE account_uuid = ? AND k9_id = ?").use { statement ->
            statement.bindText(1, accountUuid)
            statement.bindLong(2, k9Id)
            statement.step()
        }

    private fun mediaStatement(sql: String, accountUuid: String, k9Id: Long) {
        connection.prepare(sql).use { statement ->
            statement.bindText(1, accountUuid)
            statement.bindLong(2, k9Id)
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
        extras = if (isNull(9)) null else MailExtras.fromJson(getText(9)),
        spam = if (isNull(10)) {
            null
        } else {
            SpamReason.fromId(getText(10))?.let { SpamDecision(it, if (isNull(11)) "" else getText(11)) }
        },
    )

    private companion object {
        const val MAX_PARAMS = 500
        const val AUTHSERV_MAX_MAILS = 200L
        const val AUTHSERV_MAX_IDS = 10
    }
}
