package org.novabird.feature.search.internal.indexer

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import net.thunderbird.core.logging.Logger
import org.novabird.feature.search.IndexStats
import org.novabird.feature.search.K9MailStore
import org.novabird.feature.search.NovaBirdSearchIndexer
import org.novabird.feature.search.internal.NovaBirdSearchCore
import org.novabird.feature.search.internal.db.SearchIndexDatabase
import org.novabird.feature.search.internal.vectors.SemanticIndex

private const val TAG = "NovaBirdIndexer"

/**
 * Fills the NovaBird index from the K-9 databases, read-only. Each run compares all messages with the state stored in
 * `nb_android_messages` (fingerprint of flags, folder, subject, date and downloaded parts) and only reads new or
 * changed messages, so it can run after every sync without hooks in the K-9 storage code.
 */
class NovaBirdIndexer(
    private val core: NovaBirdSearchCore,
    private val mailStore: K9MailStore,
    private val logger: Logger,
    private val semanticIndex: SemanticIndex? = null,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : NovaBirdSearchIndexer {

    override suspend fun indexChanges(): IndexStats {
        val engine = core.engine()
        semanticIndex?.startFullPassIfNeeded()
        val accounts = mailStore.accounts()
        var indexed = 0
        var removed = 0

        for (account in accounts) {
            val database = mailStore.open(account.uuid) ?: continue
            database.use {
                val result = syncAccount(account.uuid, K9MessageReader(account.uuid, database))
                indexed += result.first
                removed += result.second
            }
        }
        removed += removeAccountsExcept(accounts.map { it.uuid }.toSet())

        val total = engine.withMainDatabase { db ->
            db.select("SELECT count(*) FROM messages", JsonArray(emptyList())).single().jsonArray[0].jsonPrimitive.long
        }
        logger.info(TAG) { "Index updated: $indexed indexed, $removed removed, $total in index" }
        return IndexStats(indexed = indexed, removed = removed, total = total.toInt())
    }

    private data class Known(val folderId: Long, val headerMessageId: String, val fingerprint: String)

    private data class IndexKey(val folderId: Long, val headerMessageId: String)

    /** @return (indexed, removed) */
    @OptIn(ExperimentalTime::class)
    private suspend fun syncAccount(accountUuid: String, reader: K9MessageReader): Pair<Int, Int> {
        val engine = core.engine()
        val entries = reader.listMessages()
        val known = engine.withMainDatabase { db -> db.knownMessages(accountUuid) }

        val current = entries.associateBy { it.k9Id }
        val changed = entries.filter { entry ->
            val old = known[entry.k9Id]
            old == null || old.fingerprint != entry.fingerprint || old.folderId != entry.folderId ||
                old.headerMessageId != entry.headerMessageId
        }
        val newKeys = entries.mapTo(HashSet()) { IndexKey(it.folderId, it.headerMessageId) }
        val staleKeys = known.filter { (k9Id, old) ->
            val now = current[k9Id]
            now == null || now.folderId != old.folderId || now.headerMessageId != old.headerMessageId
        }.values.map { IndexKey(it.folderId, it.headerMessageId) }.filterNot { it in newKeys }.toSet()

        if (staleKeys.isNotEmpty()) {
            val keys = buildJsonArray {
                for (key in staleKeys) {
                    add(
                        buildJsonObject {
                            put("headerMessageId", key.headerMessageId)
                            put("folderId", K9MessageReader.indexFolderId(accountUuid, key.folderId))
                        },
                    )
                }
            }
            engine.call("remove", keys.toString())
        }
        val goneIds = known.keys - current.keys
        if (goneIds.isNotEmpty()) {
            engine.withMainDatabase { db -> db.deleteKnown(accountUuid, goneIds) }
        }

        var indexed = 0
        for (batch in changed.chunked(batchSize)) {
            val items = batch.mapNotNull { entry -> reader.readItem(entry.k9Id)?.let { entry to it } }
            if (items.isNotEmpty()) {
                val now = Clock.System.now().toEpochMilliseconds()
                val texts = engine.call("upsert", JsonArray(items.map { it.second }).toString(), now.toString())
                semanticIndex?.collect(texts)
                engine.withMainDatabase { db -> db.saveKnown(accountUuid, items.map { it.first }) }
                indexed += items.size
            }
            yield()
        }
        return indexed to staleKeys.size
    }

    private suspend fun removeAccountsExcept(accountUuids: Set<String>): Int {
        val engine = core.engine()
        val stale = engine.withMainDatabase { db ->
            db.select("SELECT DISTINCT account_uuid FROM nb_android_messages", JsonArray(emptyList()))
                .map { it.jsonArray[0].jsonPrimitive.content }
                .filterNot { it in accountUuids }
        }
        var removed = 0
        for (accountUuid in stale) {
            val known = engine.withMainDatabase { db -> db.knownMessages(accountUuid) }
            val keys = known.values.map { IndexKey(it.folderId, it.headerMessageId) }.toSet()
            val json = buildJsonArray {
                keys.forEach { key ->
                    add(
                        buildJsonObject {
                            put("headerMessageId", key.headerMessageId)
                            put("folderId", K9MessageReader.indexFolderId(accountUuid, key.folderId))
                        },
                    )
                }
            }
            engine.call("remove", json.toString())
            engine.withMainDatabase { db -> db.deleteKnown(accountUuid, known.keys) }
            removed += keys.size
        }
        return removed
    }

    private fun SearchIndexDatabase.knownMessages(accountUuid: String): Map<Long, Known> =
        select(
            "SELECT k9_id, folder_id, header_msg_id, fingerprint FROM nb_android_messages WHERE account_uuid = ?",
            JsonArray(listOf(JsonPrimitive(accountUuid))),
        ).associate { row ->
            val r = row.jsonArray
            r[0].jsonPrimitive.long to
                Known(r[1].jsonPrimitive.long, r[2].jsonPrimitive.content, r[3].jsonPrimitive.content)
        }

    private fun SearchIndexDatabase.saveKnown(accountUuid: String, entries: List<K9MessageEntry>) {
        batch(
            buildJsonArray {
                for (e in entries) {
                    add(
                        statement(
                            """
                            INSERT OR REPLACE INTO nb_android_messages
                              (account_uuid, k9_id, folder_id, uid, header_msg_id, fingerprint) VALUES (?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            JsonPrimitive(accountUuid),
                            JsonPrimitive(e.k9Id),
                            JsonPrimitive(e.folderId),
                            JsonPrimitive(e.uid),
                            JsonPrimitive(e.headerMessageId),
                            JsonPrimitive(e.fingerprint),
                        ),
                    )
                }
            },
        )
    }

    private fun SearchIndexDatabase.deleteKnown(accountUuid: String, k9Ids: Collection<Long>) {
        batch(
            buildJsonArray {
                for (id in k9Ids) {
                    add(
                        statement(
                            "DELETE FROM nb_android_messages WHERE account_uuid = ? AND k9_id = ?",
                            JsonPrimitive(accountUuid),
                            JsonPrimitive(id),
                        ),
                    )
                }
            },
        )
    }

    private fun statement(sql: String, vararg params: JsonPrimitive) =
        JsonArray(listOf(JsonPrimitive(sql), JsonArray(params.toList())))

    private companion object {
        const val DEFAULT_BATCH_SIZE = 100
    }
}
