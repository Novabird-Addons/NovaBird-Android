package org.novabird.feature.search

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

    /**
     * [text] without the words the chip [chipId] of [previous] came from, so they leave the search field too;
     * null if they cannot be found (the text then stays as it is).
     */
    suspend fun textWithoutChip(
        text: String,
        previous: SearchResult,
        chipId: String,
        options: SearchOptions = SearchOptions(),
    ): String? = null

    /**
     * Adds messages that match [text] by meaning (semantic search) to [result]; they are marked [SearchHit.isSimilar].
     * Returns [result] unchanged if no vector backend is available or there are no vectors yet.
     */
    suspend fun addSimilar(text: String, result: SearchResult, options: SearchOptions = SearchOptions()): SearchResult =
        result
}

/**
 * Computes vectors for the semantic search: NovaBird Helper over the network or the model on the phone.
 * Bound by the app (optional); without it the search stays keyword-only.
 */
interface NovaBirdVectorBackend {
    /** Model id; vectors of another model are not compared. */
    val modelId: String

    /** True if vectors should be collected (Helper paired or computing on the phone switched on). */
    fun isEnabled(): Boolean

    /** True if computing index vectors is expensive here (on the phone), so it should only run while charging. */
    val indexOnlyWhileCharging: Boolean get() = false

    /** Vector for a search query, or null if no backend is reachable right now. */
    suspend fun embedQuery(text: String): FloatArray?

    /**
     * Vectors for messages to index (at most [MAX_INDEX_BATCH]); null if indexing isn't possible right now (Helper not
     * reachable, model not downloaded, …), then the indexer tries again later.
     */
    suspend fun embedForIndex(items: List<VectorItem>): List<FloatArray>?

    companion object {
        const val MAX_INDEX_BATCH = 64
    }
}

/** A message to compute a vector for: Message-ID, text hash and the text (see org.novabird.feature.vectors.VectorText). */
data class VectorItem(val messageId: String, val textHash: String, val text: String)

/** State of the vector index, for the settings screen. */
data class VectorIndexStats(val withVector: Int, val pending: Int, val messages: Int)

/** Vector index of the semantic search; filled from the texts the keyword indexer collects. */
interface NovaBirdVectorIndexer {
    /** Computes vectors for queued messages until the queue is empty or the backend is unavailable. */
    suspend fun indexVectors(shouldStop: () -> Boolean = { false }): VectorIndexStats

    suspend fun stats(): VectorIndexStats

    /**
     * Makes the next index run read all messages once more to collect their texts. Call when vectors get switched on
     * (Helper paired, computing on the phone enabled): messages indexed while vectors were off were never queued.
     */
    suspend fun recollectTexts()
}

/** Starts index runs in the background (WorkManager); used when settings change. */
interface NovaBirdIndexTrigger {
    /** Keyword index run (also collects texts for vectors). */
    fun requestIndexing()

    /** Vector run for queued messages. */
    fun requestVectors()
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
    /**
     * Query languages, first one is used for chip labels and exclusions. Empty: the search's default languages
     * (the app's language and English, like the desktop).
     */
    val languages: List<String> = emptyList(),
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
    /** Number of hits added by [NovaBirdSearch.addSimilar]. */
    val similarCount: Int = 0,
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
    /** Found by meaning (semantic search), not by the words of the query. */
    val isSimilar: Boolean = false,
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
