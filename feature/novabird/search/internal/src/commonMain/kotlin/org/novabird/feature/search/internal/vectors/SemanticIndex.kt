package org.novabird.feature.search.internal.vectors

import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Scoring is a dot product over all vectors in memory (as `scoreAll` in NovaBird-Search `semantic/main.js`).
 */
class SemanticIndex(
    private val core: NovaBirdSearchCore,
    private val backend: () -> NovaBirdVectorBackend?,
    private val logger: Logger,
) : NovaBirdVectorIndexer {
    private val memoryLock = Mutex()
    private var memory: Memory? = null

    private class Memory(
        val model: String,
        val ids: Array<String>,
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
            while (!shouldStop()) {
                val pending = core.engine().withMainDatabase { db -> db.pending(NovaBirdVectorBackend.MAX_INDEX_BATCH) }
                if (pending.isEmpty()) break
                val vectors = b.embedForIndex(pending) ?: break
                core.engine().withMainDatabase { db -> db.store(pending, vectors, b.modelId) }
                stored += pending.size
            }
            if (stored > 0) {
                memoryLock.withLock { memory = null }
                logger.info(TAG) { "Vectors computed: $stored" }
            }
        }
        return stats()
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
        val hits = ArrayList<Pair<String, Double>>()
        for (i in mem.ids.indices) {
            val base = i * mem.dims
            var dot = 0.0
            for (k in 0 until mem.dims) dot += mem.q[base + k] * qv[k]
            dot *= mem.scale[i]
            if (dot >= threshold) hits += mem.ids[i] to dot
        }
        hits.sortByDescending { it.second }
        return hits.take(limit)
    }

    private suspend fun memory(model: String): Memory? = memoryLock.withLock {
        memory?.takeIf { it.model == model }?.let { return@withLock it }
        val rows = core.engine().withMainDatabase { db ->
            db.select(
                "SELECT header_msg_id, scale, q FROM nb_vectors WHERE model = ?",
                JsonArray(listOf(JsonPrimitive(model))),
            )
        }
        if (rows.isEmpty()) return@withLock null
        val first = Base64.decode(rows[0].jsonArray[2].jsonPrimitive.content)
        val dims = first.size
        val ids = Array(rows.size) { "" }
        val q = ByteArray(rows.size * dims)
        val scale = FloatArray(rows.size)
        rows.forEachIndexed { i, row ->
            val r = row.jsonArray
            ids[i] = r[0].jsonPrimitive.content
            scale[i] = r[1].jsonPrimitive.double.toFloat()
            Base64.decode(r[2].jsonPrimitive.content).copyInto(q, i * dims, 0, dims)
        }
        Memory(model, ids, q, scale, dims).also { memory = it }
    }

    private fun SearchIndexDatabase.pending(limit: Int): List<VectorItem> =
        select("SELECT header_msg_id, h, text FROM nb_vec_queue LIMIT ?", JsonArray(listOf(JsonPrimitive(limit)))).map {
            val r = it.jsonArray
            VectorItem(r[0].jsonPrimitive.content, r[1].jsonPrimitive.content, r[2].jsonPrimitive.content)
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
        private const val INT8_MAX = 127f
        private val NONE = JsonArray(emptyList())

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
