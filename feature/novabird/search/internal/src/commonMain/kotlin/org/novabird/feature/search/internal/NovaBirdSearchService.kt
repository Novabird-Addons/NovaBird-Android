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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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
 * @param defaultLanguages query languages when [SearchOptions.languages] is empty.
 */
class NovaBirdSearchService
@OptIn(ExperimentalTime::class)
constructor(
    private val core: NovaBirdSearchCore,
    private val ownEmails: () -> List<String> = { emptyList() },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val semanticIndex: SemanticIndex? = null,
    private val defaultLanguages: () -> List<String> = { listOf("de") },
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

    override suspend fun textWithoutChip(
        text: String,
        previous: SearchResult,
        chipId: String,
        options: SearchOptions,
    ): String? {
        val json = core.engine().call("textWithoutChip", text, previous.parsedQuery, chipId, optionsJson(options))
        return Json.parseToJsonElement(checkNotNull(json)).jsonPrimitive.contentOrNull
    }

    /**
     * Semantic search as `semanticAugment` in NovaBird-Search: messages similar by meaning are added to the keyword hits
     * (relevance: both rankings fused like `fuseRanks`; best: keyword and meaning hits rated together like `fuseBest`,
     * the rest by date; date: appended). Chips like sender, date or attachment filter the similar messages with the same
     * conditions as the keyword hits.
     */
    override suspend fun addSimilar(text: String, result: SearchResult, options: SearchOptions): SearchResult {
        val index = semanticIndex ?: return result
        if (result.isEmptyQuery) return result
        val query = SemanticIndex.freeText(text)
        if (query.length < MIN_SEMANTIC_QUERY) return result
        val similar = index.similar(query, limit = SEMANTIC_LIMIT * 3) ?: return result
        if (similar.isEmpty()) return result

        val score = similar.toMap()
        val have = result.hits.mapTo(HashSet()) { it.headerMessageId }
        val candidates = similar.map { it.first }.filterNot { it in have }
        val ids = if (result.chips.any { it.kind != "text" }) {
            allowedByFilters(result.parsedQuery, candidates, options)
        } else {
            candidates
        }
        val extraRows = core.engine().withMainDatabase { db -> db.similarRows(ids, options.includeTrash) }
            .sortedByDescending { score[it.text("header_msg_id")] ?: 0.0 }
            .take(SEMANTIC_LIMIT)
        if (extraRows.isEmpty()) return result
        val locations = core.engine().withMainDatabase { db -> db.locationsOf(extraRows) }
        val extras = extraRows.map { row ->
            toHit(row, locations[row.text("folder_id") to row.text("header_msg_id")])
                .copy(score = score[row.text("header_msg_id")], isSimilar = true)
        }
        return when (options.sort) {
            SearchSort.RELEVANCE -> {
                // meaning hits ranked with the same weight for older mails as the keyword hits (core sortSemByAge)
                val semanticRank = semanticByAge(result, extras, similar, options).withIndex().associate { (i, id) ->
                    id to i
                }
                val lexicalRank = result.hits.withIndex().associate { (i, h) -> h.headerMessageId to i }
                fun fused(
                    id: String,
                ) = listOfNotNull(lexicalRank[id], semanticRank[id]).sumOf { 1.0 / (RRF_K + it + 1) }
                val hits = (result.hits + extras).sortedByDescending { fused(it.headerMessageId) }
                result.copy(hits = hits, similarCount = extras.size, bestCount = 0)
            }

            SearchSort.BEST -> {
                val (hits, bestCount) = bestFirst(result, extras, similar, options)
                result.copy(hits = hits, similarCount = extras.size, bestCount = bestCount)
            }

            SearchSort.DATE -> result.copy(
                hits = (result.hits + extras).sortedByDescending { it.date },
                similarCount = extras.size,
                bestCount = 0,
            )
        }
    }

    /** Message-IDs of the shown meaning hits, best first, older mails counting less (core `sortSemByAge`). */
    private suspend fun semanticByAge(
        result: SearchResult,
        extras: List<SearchHit>,
        similar: List<Pair<String, Double>>,
        options: SearchOptions,
    ): List<String> {
        val json = core.engine().call(
            "sortSemByAge",
            semanticRows(result.hits + extras, similar).toString(),
            optionsJson(options),
            result.age,
        )
        return Json.parseToJsonElement(checkNotNull(json)).jsonArray.map { it.jsonPrimitive.content }
    }

    /** Meaning hits among [shown] with their cosine and date, for the core's ranking. */
    private fun semanticRows(shown: List<SearchHit>, similar: List<Pair<String, Double>>): JsonArray {
        val dates = HashMap<String, Long>()
        shown.forEach { dates.putIfAbsent(it.headerMessageId, it.date) }
        return JsonArray(
            similar.mapNotNull { (id, score) ->
                val date = dates[id] ?: return@mapNotNull null
                buildJsonObject {
                    put("header_msg_id", id)
                    put("score", score)
                    put("date", date)
                }
            },
        )
    }

    /**
     * Best keyword and meaning hits on top, the rest by date (core `fuseBest` and `bestThenDate`, like the desktop).
     * Returns the hits and how many of them are the best ones.
     */
    private suspend fun bestFirst(
        result: SearchResult,
        extras: List<SearchHit>,
        similar: List<Pair<String, Double>>,
        options: SearchOptions,
    ): Pair<List<SearchHit>, Int> {
        val all = result.hits + extras
        val semJson = semanticRows(all, similar)
        val allJson = JsonArray(
            all.mapIndexed { i, hit ->
                buildJsonObject {
                    put("header_msg_id", hit.headerMessageId)
                    put("date", hit.date)
                    put("i", i)
                }
            },
        )
        val json = core.engine().call(
            "fuseBestThenDate",
            result.relevantRows,
            semJson.toString(),
            allJson.toString(),
            optionsJson(options),
            result.age,
        )
        val out = Json.parseToJsonElement(checkNotNull(json)).jsonObject
        val rows = out["rows"]!!.jsonArray.map { it.jsonObject }
        val bestRows = out["bestIds"]!!.jsonArray.size
        // best rows come from the relevance list or the meaning hits: matched to a shown hit by Message-ID
        val firstIndex = HashMap<String, Int>()
        all.forEachIndexed { i, hit -> firstIndex.putIfAbsent(hit.headerMessageId, i) }
        val missing = rows.filter { it["i"] == null && it.text("header_msg_id") !in firstIndex && "folder_id" in it }
        val locations = if (missing.isEmpty()) {
            emptyMap()
        } else {
            core.engine().withMainDatabase { db -> db.locationsOf(missing) }
        }
        val used = HashSet<Int>()
        val hits = rows.map { row ->
            val i = row["i"]?.jsonPrimitive?.intOrNull ?: firstIndex[row.text("header_msg_id")]
            when {
                i != null -> all[i].takeIf { used.add(i) }
                "folder_id" in row -> toHit(row, locations[row.text("folder_id") to row.text("header_msg_id")])
                else -> null
            }
        }
        return hits.filterNotNull() to hits.take(bestRows).count { it != null }
    }

    private suspend fun allowedByFilters(parsedQuery: String, ids: List<String>, options: SearchOptions): List<String> {
        if (ids.isEmpty()) return ids
        val idsJson = JsonArray(ids.map(::JsonPrimitive)).toString()
        val json = core.engine().call("filterIds", parsedQuery, idsJson, optionsJson(options))
        val allowed = Json.parseToJsonElement(checkNotNull(json)).jsonArray.map { it.jsonPrimitive.content }.toSet()
        return ids.filter { it in allowed }
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
        put(
            "sort",
            when (options.sort) {
                SearchSort.BEST -> "best"
                SearchSort.DATE -> "date"
                SearchSort.RELEVANCE -> "relevance"
            },
        )
        put("limit", options.limit)
        put("includeTrash", options.includeTrash)
        put("now", now())
        putJsonArray("myEmails") { (options.myEmails + ownEmails()).distinct().forEach { add(it.lowercase()) } }
        putJsonArray("langs") { options.languages.ifEmpty { defaultLanguages() }.forEach { add(it) } }
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
            // core search (sort "best"): the best rows are already on top
            bestCount = (result["bestIds"] as? JsonArray)?.size ?: 0,
            relevantRows = (result["relevant"] as? JsonArray)?.toString() ?: "[]",
            hasMore = result["more"]?.jsonPrimitive?.booleanOrNull == true,
            age = (result["age"] as? JsonObject)?.toString() ?: "{}",
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
