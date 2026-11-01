package org.novabird.feature.search.internal

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.novabird.feature.search.MessageLocation
import org.novabird.feature.search.NovaBirdSearch
import org.novabird.feature.search.SearchChip
import org.novabird.feature.search.SearchHit
import org.novabird.feature.search.SearchOptions
import org.novabird.feature.search.SearchResult
import org.novabird.feature.search.SearchSort
import org.novabird.feature.search.internal.db.SearchIndexDatabase
import org.novabird.feature.search.internal.vectors.SemanticIndex

/**
 * [NovaBirdSearch] backed by the NovaBird-Search core (`search.js`) under QuickJS.
 *
 * @param ownEmails own addresses of all accounts, for "von mir" / "an mich" (merged with [SearchOptions.myEmails]).
 * @param now current time in ms; relative dates ("letzte Woche") are resolved against it.
 */
class NovaBirdSearchService
@OptIn(ExperimentalTime::class)
constructor(
    private val core: NovaBirdSearchCore,
    private val ownEmails: () -> List<String> = { emptyList() },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val semanticIndex: SemanticIndex? = null,
) : NovaBirdSearch {

    override suspend fun search(text: String, options: SearchOptions): SearchResult {
        val json = core.engine().call("search", text, optionsJson(options))
        return toResult(json)
    }

    override suspend fun searchWithoutChip(
        previous: SearchResult,
        chipId: String,
        options: SearchOptions,
    ): SearchResult {
        val json = core.engine().call("searchWithoutChip", previous.parsedQuery, chipId, optionsJson(options))
        return toResult(json)
    }

    /**
     * Semantic search as `semanticAugment` in NovaBird-Search: messages similar by meaning are added to the keyword hits
     * (relevance: both rankings fused like `fuseRanks`, otherwise appended). Only for plain text queries for now: chips like
     * sender, date or attachment would have to filter the similar messages too.
     */
    override suspend fun addSimilar(text: String, result: SearchResult, options: SearchOptions): SearchResult {
        val index = semanticIndex ?: return result
        if (result.isEmptyQuery || result.chips.any { it.kind != "text" }) return result
        val query = SemanticIndex.freeText(text)
        if (query.length < MIN_SEMANTIC_QUERY) return result
        val similar = index.similar(query, limit = SEMANTIC_LIMIT * 3) ?: return result
        if (similar.isEmpty()) return result

        val score = similar.toMap()
        val have = result.hits.mapTo(HashSet()) { it.headerMessageId }
        val extraRows = core.engine().withMainDatabase { db ->
            db.similarRows(similar.map { it.first }.filterNot { it in have }, options.includeTrash)
        }.sortedByDescending { score[it.text("header_msg_id")] ?: 0.0 }.take(SEMANTIC_LIMIT)
        if (extraRows.isEmpty()) return result
        val locations = core.engine().withMainDatabase { db -> db.locationsOf(extraRows) }
        val extras = extraRows.map { row ->
            toHit(row, locations[row.text("folder_id") to row.text("header_msg_id")])
                .copy(score = score[row.text("header_msg_id")], isSimilar = true)
        }
        val hits = if (options.sort == SearchSort.RELEVANCE) {
            val semanticRank = similar.withIndex().associate { (i, s) -> s.first to i }
            val lexicalRank = result.hits.withIndex().associate { (i, h) -> h.headerMessageId to i }
            fun fused(id: String) = listOfNotNull(lexicalRank[id], semanticRank[id]).sumOf { 1.0 / (RRF_K + it + 1) }
            (result.hits + extras).sortedByDescending { fused(it.headerMessageId) }
        } else {
            (result.hits + extras).sortedByDescending { it.date }
        }
        return result.copy(hits = hits, similarCount = extras.size)
    }

    private fun SearchIndexDatabase.similarRows(ids: List<String>, includeTrash: Boolean): List<JsonObject> {
        if (ids.isEmpty()) return emptyList()
        val skip = if (includeTrash) "" else " AND folder_type NOT IN ('trash', 'junk')"
        val rows = select(
            "SELECT ${HIT_COLUMNS.joinToString()} FROM messages WHERE header_msg_id IN (${ids.joinToString(",") {
                "?"
            }})$skip",
            JsonArray(ids.map { JsonPrimitive(it) }),
        )
        return rows.map { row -> JsonObject(HIT_COLUMNS.zip(row.jsonArray).toMap()) }
            .distinctBy { it.text("header_msg_id") }
    }

    private fun optionsJson(options: SearchOptions): String = buildJsonObject {
        put("sort", if (options.sort == SearchSort.RELEVANCE) "relevance" else "date")
        put("limit", options.limit)
        put("includeTrash", options.includeTrash)
        put("now", now())
        putJsonArray("myEmails") { (options.myEmails + ownEmails()).distinct().forEach { add(it.lowercase()) } }
        putJsonArray("langs") { options.languages.forEach { add(it) } }
    }.toString()

    private suspend fun toResult(json: String?): SearchResult {
        val result = Json.parseToJsonElement(checkNotNull(json)).jsonObject
        val rows = result["rows"]!!.jsonArray.map { it.jsonObject }
        val locations = core.engine().withMainDatabase { db -> db.locationsOf(rows) }
        return SearchResult(
            hits = rows.map { row -> toHit(row, locations[row.text("folder_id") to row.text("header_msg_id")]) },
            chips = result["chips"]!!.jsonArray.map { chip ->
                val c = chip.jsonObject
                SearchChip(
                    id = c.text("id"),
                    kind = c.text("kind"),
                    label = c.text("label"),
                    isAmbiguous = c["ambiguous"]?.jsonPrimitive?.booleanOrNull == true,
                )
            },
            suggestion = (result["suggest"] as? JsonObject)?.text("text")?.takeIf { it.isNotEmpty() },
            isEmptyQuery = result["empty"]?.jsonPrimitive?.booleanOrNull == true,
            parsedQuery = result["query"].toString(),
        )
    }

    private fun toHit(row: JsonObject, location: MessageLocation?) = SearchHit(
        headerMessageId = row.text("header_msg_id"),
        folderName = row.text("folder_name"),
        folderType = row.text("folder_type"),
        date = row["date"]?.jsonPrimitive?.longOrNull ?: 0L,
        authorName = row.text("author_name"),
        authorEmail = row.text("author_email"),
        subject = row.text("subject"),
        hasAttachment = row.flag("has_attachment"),
        isRead = row.flag("is_read"),
        isFlagged = row.flag("is_flagged"),
        score = (row["score"] as? JsonPrimitive)?.doubleOrNull,
        location = location,
    )

    /** Index folder IDs are `<accountUuid>:<k9FolderId>` (see K9MessageReader.indexFolderId). */
    private fun SearchIndexDatabase.locationsOf(rows: List<JsonObject>): Map<Pair<String, String>, MessageLocation> {
        val out = HashMap<Pair<String, String>, MessageLocation>()
        for (row in rows) {
            val folderKey = row.text("folder_id")
            val headerMessageId = row.text("header_msg_id")
            val accountUuid = folderKey.substringBeforeLast(':', "")
            val folderId = folderKey.substringAfterLast(':').toLongOrNull() ?: continue
            val uid = select(
                "SELECT uid FROM nb_android_messages WHERE account_uuid = ? AND folder_id = ? AND header_msg_id = ? LIMIT 1",
                JsonArray(listOf(JsonPrimitive(accountUuid), JsonPrimitive(folderId), JsonPrimitive(headerMessageId))),
            ).firstOrNull()?.jsonArray?.get(0)?.jsonPrimitive?.content ?: continue
            out[folderKey to headerMessageId] = MessageLocation(accountUuid, folderId, uid)
        }
        return out
    }

    private companion object {
        const val MIN_SEMANTIC_QUERY = 3
        const val SEMANTIC_LIMIT = 50
        const val RRF_K = 60
        val HIT_COLUMNS = listOf(
            "header_msg_id", "folder_id", "folder_name", "folder_type", "date", "author_name", "author_email",
            "subject", "has_attachment", "is_read", "is_flagged",
        )
    }

    private fun JsonObject.text(key: String): String {
        val value: JsonElement = this[key] ?: JsonNull
        return if (value is JsonPrimitive && value !is JsonNull) value.content else ""
    }

    private fun JsonObject.flag(key: String): Boolean {
        val value = this[key] as? JsonPrimitive ?: return false
        return value.booleanOrNull ?: (value.longOrNull == 1L)
    }
}
