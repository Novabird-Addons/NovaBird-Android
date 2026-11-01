package net.thunderbird.feature.novabird.search.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import net.thunderbird.feature.novabird.search.internal.db.SearchIndexDatabase
import net.thunderbird.feature.novabird.search.internal.script.NovaBirdScriptEngine
import net.thunderbird.feature.novabird.search.internal.script.ScriptSources

/**
 * Owns the NovaBird index database and the QuickJS engine; both are created on first use.
 *
 * Besides the tables of NovaBird-Search (created by `migrate()` in JavaScript) the database holds
 * `nb_android_messages`, which maps index entries to K-9 messages (account, folder, UID) for opening hits and for the
 * incremental sync of the indexer.
 *
 * @param databasePath file of the index database (its own file, never the K-9 database), or `:memory:`.
 */
class NovaBirdSearchCore(
    private val databasePath: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val mutex = Mutex()
    private var engine: NovaBirdScriptEngine? = null
    private var database: SearchIndexDatabase? = null

    internal suspend fun engine(): NovaBirdScriptEngine = mutex.withLock {
        engine ?: open().also { engine = it }
    }

    private suspend fun open(): NovaBirdScriptEngine {
        val sources = ScriptSources.load()
        val db = SearchIndexDatabase(databasePath)
        try {
            val newEngine = NovaBirdScriptEngine.create(sources, db, dispatcher)
            newEngine.call("migrate")
            newEngine.withMainDatabase { it.createAndroidTables() }
            database = db
            return newEngine
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            db.close()
            throw e
        }
    }

    suspend fun close() = mutex.withLock {
        engine?.close()
        database?.close()
        engine = null
        database = null
    }

    private fun SearchIndexDatabase.createAndroidTables() {
        val none = JsonArray(emptyList())
        execute(
            """
            CREATE TABLE IF NOT EXISTS nb_android_messages (
              account_uuid  TEXT NOT NULL,
              k9_id         INTEGER NOT NULL,
              folder_id     INTEGER NOT NULL,
              uid           TEXT NOT NULL,
              header_msg_id TEXT NOT NULL,
              fingerprint   TEXT NOT NULL,
              PRIMARY KEY (account_uuid, k9_id)
            )
            """.trimIndent(),
            none,
        )
        execute(
            "CREATE INDEX IF NOT EXISTS nb_android_messages_key ON nb_android_messages(account_uuid, folder_id, header_msg_id)",
            none,
        )
    }
}
