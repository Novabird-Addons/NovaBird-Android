package net.thunderbird.feature.novabird.search.internal

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.thunderbird.feature.novabird.search.MessageLocation
import net.thunderbird.feature.novabird.search.NovaBirdSearch
import net.thunderbird.feature.novabird.search.SearchChip
import net.thunderbird.feature.novabird.search.SearchHit
import net.thunderbird.feature.novabird.search.SearchOptions
import net.thunderbird.feature.novabird.search.SearchResult
import net.thunderbird.feature.novabird.search.SearchSort
import net.thunderbird.feature.novabird.search.internal.db.SearchIndexDatabase

/**
 * [NovaBirdSearch] backed by the NovaBird-Search core (`search.js`) under QuickJS.
 *
 * @param ownEmails own addresses of all accounts, for "von mir" / "an mich" (merged with [SearchOptions.myEmails]).
 * @param now current time in ms; relative dates ("letzte Woche") are resolved against it.
 */
class NovaBirdSearchService @OptIn(ExperimentalTime::class) constructor(
    private val core: NovaBirdSearchCore,
    private val ownEmails: () -> List<String> = { emptyList() },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
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

    private fun JsonObject.text(key: String): String {
        val value: JsonElement = this[key] ?: JsonNull
        return if (value is JsonPrimitive && value !is JsonNull) value.content else ""
    }

    private fun JsonObject.flag(key: String): Boolean {
        val value = this[key] as? JsonPrimitive ?: return false
        return value.booleanOrNull ?: (value.longOrNull == 1L)
    }
}
