package org.novabird.feature.search.internal.vectors

import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.thunderbird.core.logging.Logger
import org.novabird.feature.search.NovaBirdVectorBackend
import org.novabird.feature.search.NovaBirdVectorIndexer
import org.novabird.feature.search.VectorIndexStats
import org.novabird.feature.search.VectorItem
import org.novabird.feature.search.internal.NovaBirdSearchCore
import org.novabird.feature.search.internal.db.SearchIndexDatabase
import org.novabird.feature.vectors.HelperClient
import org.novabird.feature.vectors.VectorText

private const val TAG = "NovaBirdSemanticIndex"

/**
 * Vectors of the semantic search in the NovaBird index database:
 * - `nb_vec_queue`: texts the keyword indexer collected (subject + cleaned body, [VectorText]) that still need a vector,
 * - `nb_vectors`: one vector per Message-ID (int8 with scale, like the desktop store), with the text hash and model.
 *
 * Scoring is a dot product over all vectors in memory (as `scoreAll` in NovaBird-Search `semantic/main.js`). They stay in
 * the Java heap (about 900 bytes per mail, 256 MB is all the app gets), so only the newest [maxVectors] mails have a
 * vector; they are computed newest first, so the mails that matter most are searchable first.
 */
class SemanticIndex(
    private val core: NovaBirdSearchCore,
    private val backend: () -> NovaBirdVectorBackend?,
    private val logger: Logger,
    private val maxVectors: Int = MAX_VECTORS,
) : NovaBirdVectorIndexer {
    private val memoryLock = Mutex()
    private var memory: Memory? = null

    private class Memory(
        val model: String,
        val ids: List<String>,
        val q: ByteArray,
        val scale: FloatArray,
        val dims: Int,
    )

    /**
     * Before an index run: if vectors are wanted but the texts of already indexed messages were never collected, mark all
     * messages as changed so the keyword indexer reads them once more. @return true if a full pass was started.
     */
    suspend fun startFullPassIfNeeded(): Boolean {
        if (backend()?.isEnabled() != true) return false
        val started = core.engine().withMainDatabase { db ->
            if (db.meta(META_TEXT_VERSION) == TEXT_VERSION) {
                false
            } else {
                db.execute("UPDATE nb_android_messages SET fingerprint = ''", NONE)
                db.setMeta(META_TEXT_VERSION, TEXT_VERSION)
                true
            }
        }
        if (started) logger.info(TAG) { "Collecting texts of all messages for vectors" }
        return started
    }

    /** Answer of `upsert` in glue.js: `[{id, subject, body}]`; queues texts whose vector is missing or outdated. */
    suspend fun collect(upsertAnswer: String?) {
        val b = backend() ?: return
        if (!b.isEnabled() || upsertAnswer.isNullOrEmpty()) return
        val rows = Json.parseToJsonElement(upsertAnswer).jsonArray.map { it.jsonObject }
        val items = rows.map { r ->
            val text = VectorText.of(
                r.getValue("subject").jsonPrimitive.content,
                r.getValue("body").jsonPrimitive.content,
            )
            Triple(r.getValue("id").jsonPrimitive.content, HelperClient.textHash(text), text)
        }.filter { it.first.isNotEmpty() }
        if (items.isEmpty()) return
        core.engine().withMainDatabase { db ->
            val have = db.vectorHashes(items.map { it.first }, b.modelId)
            db.batch(
                buildJsonArray {
                    for ((id, h, text) in items) {
                        if (have[id] == h) continue
                        add(
                            statement(
                                "INSERT OR REPLACE INTO nb_vec_queue (header_msg_id, h, text) VALUES (?, ?, ?)",
                                id,
                                h,
                                text,
                            ),
                        )
                    }
                },
            )
        }
    }

    override suspend fun indexVectors(shouldStop: () -> Boolean): VectorIndexStats {
        val b = backend()
        if (b != null && b.isEnabled()) {
            var stored = 0
            var before = Long.MAX_VALUE
            while (!shouldStop()) {
                val pending = core.engine().withMainDatabase { db -> db.nextBatch(before) }
                if (pending.isEmpty()) break
                val items = pending.map { it.item }
                val vectors = b.embedForIndex(items) ?: break
                core.engine().withMainDatabase { db -> db.store(items, vectors, b.modelId) }
                stored += items.size
                before = pending.last().date
            }
            if (stored > 0) {
                memoryLock.withLock { memory = null }
                logger.info(TAG) { "Vectors computed: $stored" }
            }
        }
        return stats()
    }

    /** After an index run: drops vectors and queued texts of messages that are no longer in any indexed folder. */
    suspend fun removeOrphans() {
        val removed = core.engine().withMainDatabase { db ->
            ORPHAN_TABLES.sumOf { table ->
                db.execute(
                    "DELETE FROM $table WHERE NOT EXISTS " +
                        "(SELECT 1 FROM messages m WHERE m.header_msg_id = $table.header_msg_id)",
                    NONE,
                )
            }
        }
        if (removed > 0) {
            memoryLock.withLock { memory = null }
            logger.info(TAG) { "Vectors of deleted messages removed: $removed" }
        }
    }

    override suspend fun recollectTexts() {
        core.engine().withMainDatabase { db ->
            db.execute("DELETE FROM nb_meta WHERE key = ?", JsonArray(listOf(JsonPrimitive(META_TEXT_VERSION))))
        }
    }

    override suspend fun stats(): VectorIndexStats = core.engine().withMainDatabase { db ->
        val model = backend()?.modelId ?: ""
        VectorIndexStats(
            withVector = db.count(
                "SELECT count(*) FROM nb_vectors v WHERE v.model = ? AND EXISTS " +
                    "(SELECT 1 FROM messages m WHERE m.header_msg_id = v.header_msg_id)",
                model,
            ),
            pending = db.count("SELECT count(*) FROM nb_vec_queue"),
            messages = db.count("SELECT count(DISTINCT header_msg_id) FROM messages"),
        )
    }

    /**
     * Message-IDs similar to [query] with their score (cosine), best first, at least [threshold]; null if there is no
     * backend or no vectors.
     */
    suspend fun similar(query: String, limit: Int, threshold: Double = THRESHOLD_MEDIUM): List<Pair<String, Double>>? {
        val b = backend() ?: return null
        if (!b.isEnabled()) return null
        val mem = memory(b.modelId) ?: return null
        val qv = b.embedQuery(query) ?: return null
        if (qv.size != mem.dims) return null
        // about 30 million steps at 30 000 mails, and the search screen calls this on the main thread
        return withContext(Dispatchers.Default) {
            val hits = ArrayList<Pair<String, Double>>()
            for (i in mem.ids.indices) {
                val base = i * mem.dims
                var dot = 0.0
                for (k in 0 until mem.dims) dot += mem.q[base + k] * qv[k]
                dot *= mem.scale[i]
                if (dot >= threshold) hits += mem.ids[i] to dot
            }
            hits.sortByDescending { it.second }
            hits.take(limit)
        }
    }

    private suspend fun memory(model: String): Memory? = memoryLock.withLock {
        memory?.takeIf { it.model == model }?.let { return@withLock it }
        load(model).also { memory = it }
    }

    /** Page by page: all rows at once as JSON would take far more than the vectors themselves (about 1.5 KB per mail). */
    private suspend fun load(model: String): Memory? {
        val count = core.engine().withMainDatabase { db -> db.count("SELECT count(*) FROM nb_vectors") }
        if (count == 0) return null
        val ids = ArrayList<String>(count)
        val scale = FloatArray(count)
        var q = ByteArray(0)
        var dims = 0
        var last = 0L
        while (ids.size < count) {
            val after = last
            val page = core.engine().withMainDatabase { db ->
                db.select(
                    "SELECT rowid, header_msg_id, scale, q FROM nb_vectors WHERE model = ? AND rowid > ? ORDER BY rowid LIMIT ?",
                    JsonArray(listOf(JsonPrimitive(model), JsonPrimitive(after), JsonPrimitive(LOAD_PAGE))),
                )
            }
            if (page.isEmpty()) break
            last = page.last().jsonArray[0].jsonPrimitive.long
            withContext(Dispatchers.Default) {
                for (row in page.take(count - ids.size)) {
                    val r = row.jsonArray
                    val vector = Base64.decode(r[3].jsonPrimitive.content)
                    if (dims == 0) {
                        dims = vector.size
                        q = ByteArray(count * dims)
                    }
                    vector.copyInto(q, ids.size * dims, 0, dims)
                    scale[ids.size] = r[2].jsonPrimitive.double.toFloat()
                    ids += r[1].jsonPrimitive.content
                }
            }
        }
        return if (ids.isEmpty()) null else Memory(model, ids, q, scale, dims)
    }

    private class Pending(val item: VectorItem, val date: Long)

    /**
     * The next texts to compute, newest mails first and not newer than [before]. With a full store, older vectors make
     * room; once only mails older than all stored ones are left, they are dropped from the queue.
     */
    private fun SearchIndexDatabase.nextBatch(before: Long): List<Pending> {
        val free = maxVectors - count("SELECT count(*) FROM nb_vectors")
        val limit = if (free >
            0
        ) {
            minOf(free, NovaBirdVectorBackend.MAX_INDEX_BATCH)
        } else {
            NovaBirdVectorBackend.MAX_INDEX_BATCH
        }
        val batch = pending(limit, before)
        return if (free > 0 || batch.isEmpty() || makeRoom(batch.size, batch.first().date)) batch else emptyList()
    }

    // CROSS JOIN keeps the order of the tables: down the date index instead of sorting the whole queue
    private fun SearchIndexDatabase.pending(limit: Int, before: Long): List<Pending> = select(
        "SELECT q.header_msg_id, q.h, q.text, m.date FROM messages m CROSS JOIN nb_vec_queue q " +
            "ON q.header_msg_id = m.header_msg_id WHERE m.date <= ? ORDER BY m.date DESC LIMIT ?",
        JsonArray(listOf(JsonPrimitive(before), JsonPrimitive(limit))),
    ).map {
        val r = it.jsonArray
        Pending(
            VectorItem(r[0].jsonPrimitive.content, r[1].jsonPrimitive.content, r[2].jsonPrimitive.content),
            r[3].jsonPrimitive.long,
        )
    }.distinctBy { it.item.messageId }

    /** Removes up to [needed] vectors of mails older than [newest]; false (and the queue is cleared) if there are none. */
    private fun SearchIndexDatabase.makeRoom(needed: Int, newest: Long): Boolean {
        val oldest = select(
            "SELECT v.header_msg_id FROM messages m CROSS JOIN nb_vectors v " +
                "ON v.header_msg_id = m.header_msg_id WHERE m.date < ? ORDER BY m.date LIMIT ?",
            JsonArray(listOf(JsonPrimitive(newest), JsonPrimitive(needed))),
        ).map { it.jsonArray[0].jsonPrimitive.content }.distinct()
        if (oldest.isEmpty()) {
            execute(
                "DELETE FROM nb_vec_queue WHERE header_msg_id IN (SELECT header_msg_id FROM messages WHERE date <= ?)",
                JsonArray(listOf(JsonPrimitive(newest))),
            )
            return false
        }
        batch(
            buildJsonArray {
                oldest.forEach { add(statement("DELETE FROM nb_vectors WHERE header_msg_id = ?", it)) }
            },
        )
        return true
    }

    private fun SearchIndexDatabase.store(items: List<VectorItem>, vectors: List<FloatArray>, model: String) {
        batch(
            buildJsonArray {
                items.forEachIndexed { i, item ->
                    val (scale, q) = quantize(vectors[i])
                    add(
                        JsonArray(
                            listOf(
                                JsonPrimitive(
                                    "INSERT OR REPLACE INTO nb_vectors (header_msg_id, model, h, scale, q) VALUES (?, ?, ?, ?, ?)",
                                ),
                                JsonArray(
                                    listOf(
                                        JsonPrimitive(item.messageId),
                                        JsonPrimitive(model),
                                        JsonPrimitive(item.textHash),
                                        JsonPrimitive(scale),
                                        JsonPrimitive(Base64.encode(q)),
                                    ),
                                ),
                            ),
                        ),
                    )
                    add(
                        statement(
                            "DELETE FROM nb_vec_queue WHERE header_msg_id = ? AND h = ?",
                            item.messageId,
                            item.textHash,
                        ),
                    )
                }
            },
        )
    }

    private fun SearchIndexDatabase.vectorHashes(ids: List<String>, model: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (chunk in ids.distinct().chunked(SQL_CHUNK)) {
            val params = JsonArray(listOf(JsonPrimitive(model)) + chunk.map { JsonPrimitive(it) })
            select(
                "SELECT header_msg_id, h FROM nb_vectors WHERE model = ? AND header_msg_id IN (${chunk.joinToString(
                    ",",
                ) {
                    "?"
                }})",
                params,
            ).forEach { out[it.jsonArray[0].jsonPrimitive.content] = it.jsonArray[1].jsonPrimitive.content }
        }
        return out
    }

    private fun SearchIndexDatabase.count(sql: String, vararg params: String): Int =
        select(sql, JsonArray(params.map { JsonPrimitive(it) })).single().jsonArray[0].jsonPrimitive.long.toInt()

    private fun SearchIndexDatabase.meta(key: String): String? =
        select("SELECT value FROM nb_meta WHERE key = ?", JsonArray(listOf(JsonPrimitive(key))))
            .firstOrNull()?.jsonArray?.get(0)?.jsonPrimitive?.content

    private fun SearchIndexDatabase.setMeta(key: String, value: String) {
        execute(
            "INSERT OR REPLACE INTO nb_meta (key, value) VALUES (?, ?)",
            JsonArray(listOf(JsonPrimitive(key), JsonPrimitive(value))),
        )
    }

    private fun statement(sql: String, vararg params: String) =
        JsonArray(listOf(JsonPrimitive(sql), JsonArray(params.map { JsonPrimitive(it) })))

    companion object {
        /** Thresholds of NovaBird-Search (low/medium/high strictness). */
        const val THRESHOLD_MEDIUM = 0.32
        private const val TEXT_VERSION = "1"
        private const val META_TEXT_VERSION = "vectorTextVersion"
        private const val SQL_CHUNK = 500

        /** The newest mails with a vector: about 90 MB in the Java heap. */
        private const val MAX_VECTORS = 100_000
        private const val LOAD_PAGE = 2000
        private const val INT8_MAX = 127f
        private val NONE = JsonArray(emptyList())
        private val ORPHAN_TABLES = listOf("nb_vectors", "nb_vec_queue")

        /** int8 with one scale per vector: v ≈ q * scale. */
        internal fun quantize(v: FloatArray): Pair<Double, ByteArray> {
            val max = v.maxOf { abs(it) }.takeIf { it > 0f } ?: 1f
            val scale = max / INT8_MAX
            return scale.toDouble() to ByteArray(v.size) { (v[it] / scale).roundToInt().coerceIn(-127, 127).toByte() }
        }

        /** Search text for the meaning: without operators (von: …, -word) and quotes, like freeText in semantic/main.js. */
        fun freeText(text: String): String {
            val operator = Regex("^\\p{L}+:")
            return text.split(Regex("\\s+"))
                .filter { w ->
                    w.isNotEmpty() && !operator.containsMatchIn(w) && !w.startsWith("-") &&
                        !w.startsWith("!")
                }
                .joinToString(" ")
                .replace(Regex("[\"„“”]"), "")
                .trim()
        }

        internal val CREATE_TABLES = listOf(
            "CREATE TABLE IF NOT EXISTS nb_vec_queue (header_msg_id TEXT PRIMARY KEY, h TEXT NOT NULL, text TEXT NOT NULL)",
            """
            CREATE TABLE IF NOT EXISTS nb_vectors (
              header_msg_id TEXT PRIMARY KEY,
              model         TEXT NOT NULL,
              h             TEXT NOT NULL,
              scale         REAL NOT NULL,
              q             TEXT NOT NULL
            )
            """.trimIndent(),
            "CREATE TABLE IF NOT EXISTS nb_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        )
    }
}
