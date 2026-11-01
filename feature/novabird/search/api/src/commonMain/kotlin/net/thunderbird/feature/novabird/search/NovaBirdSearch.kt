package net.thunderbird.feature.novabird.search

/**
 * NovaBird search: everyday-language queries ("von Petra im März mit PDF") over the NovaBird index.
 *
 * The index lives in its own database file and is filled by [NovaBirdSearchIndexer].
 */
interface NovaBirdSearch {
    /** Parses [text] and searches the index. */
    suspend fun search(text: String, options: SearchOptions = SearchOptions()): SearchResult

    /** Repeats the search of [previous] without the chip [chipId]. */
    suspend fun searchWithoutChip(
        previous: SearchResult,
        chipId: String,
        options: SearchOptions = SearchOptions(),
    ): SearchResult
}

/** Fills the NovaBird index from the locally stored messages. */
interface NovaBirdSearchIndexer {
    /** Indexes new and changed messages and removes deleted ones. Safe to call repeatedly. */
    suspend fun indexChanges(): IndexStats
}

data class SearchOptions(
    val sort: SearchSort = SearchSort.RELEVANCE,
    val limit: Int = 200,
    val includeTrash: Boolean = false,
    /** Own email addresses, needed for "von mir" / "an mich". */
    val myEmails: List<String> = emptyList(),
    /** Query languages, first one is used for chip labels. */
    val languages: List<String> = listOf("de"),
)

enum class SearchSort {
    DATE,
    RELEVANCE,
}

data class SearchResult(
    val hits: List<SearchHit>,
    val chips: List<SearchChip>,
    /** "Meinten Sie": corrected query text, if the query had no hits and a typo was likely. */
    val suggestion: String?,
    val isEmptyQuery: Boolean,
    /** Parsed query, opaque. Used by [NovaBirdSearch.searchWithoutChip]. */
    val parsedQuery: String,
)

data class SearchChip(
    val id: String,
    val kind: String,
    val label: String,
    val isAmbiguous: Boolean,
)

data class SearchHit(
    val headerMessageId: String,
    val folderName: String,
    val folderType: String,
    val date: Long,
    val authorName: String,
    val authorEmail: String,
    val subject: String,
    val hasAttachment: Boolean,
    val isRead: Boolean,
    val isFlagged: Boolean,
    val score: Double?,
    /** Where the message is stored locally; `null` if it is no longer known. */
    val location: MessageLocation?,
)

/** Identifies a locally stored message (account UUID, local folder ID, message UID). */
data class MessageLocation(
    val accountUuid: String,
    val folderId: Long,
    val uid: String,
)

data class IndexStats(
    val indexed: Int,
    val removed: Int,
    val total: Int,
)
