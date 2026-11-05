package org.novabird.feature.search.internal.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.thunderbird.core.logging.Logger
import net.thunderbird.core.ui.contract.mvi.BaseViewModel
import org.novabird.feature.search.NovaBirdSearch
import org.novabird.feature.search.SearchResult
import org.novabird.feature.search.internal.ui.NovaBirdSearchContract.Effect
import org.novabird.feature.search.internal.ui.NovaBirdSearchContract.Event
import org.novabird.feature.search.internal.ui.NovaBirdSearchContract.State

private const val TAG = "NovaBirdSearchViewModel"

/** Searches while typing (debounced) and on submit; chips can be removed, the "did you mean" text applied. */
internal class NovaBirdSearchViewModel(
    private val search: NovaBirdSearch,
    private val logger: Logger,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) : BaseViewModel<State, Event, Effect>(State()),
    NovaBirdSearchContract.ViewModel {

    private var searchJob: Job? = null

    override fun event(event: Event) {
        when (event) {
            is Event.QueryChanged -> {
                updateState { it.copy(query = event.query) }
                launchSearch(delayMillis = debounceMillis) { search.search(event.query) }
            }

            Event.Submit -> launchSearch(delayMillis = 0) { search.search(state.value.query) }

            is Event.RemoveChip -> {
                val previous = state.value.result ?: return
                val text = state.value.query
                launchSearch(delayMillis = 0) {
                    // the chip's words leave the search field too, so typing on does not bring the chip back
                    textWithoutChip(text, previous, event.chipId)?.let { newText ->
                        updateState { it.copy(query = newText) }
                    }
                    search.searchWithoutChip(previous, event.chipId)
                }
            }

            Event.ApplySuggestion -> {
                val suggestion = state.value.result?.suggestion ?: return
                updateState { it.copy(query = suggestion) }
                launchSearch(delayMillis = 0) { search.search(suggestion) }
            }

            is Event.OpenHit -> emitEffect(Effect.OpenMessage(event.location))

            Event.Back -> emitEffect(Effect.NavigateBack)
        }
    }

    private fun launchSearch(delayMillis: Long, block: suspend () -> SearchResult) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(delayMillis)
            updateState { it.copy(isSearching = true) }
            val result = runCatching { block() }
            result.exceptionOrNull()?.let { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                // only the type: messages of script errors may contain parts of the query
                logger.error(TAG) { "Search failed: ${e::class.simpleName}" }
            }
            updateState {
                it.copy(
                    result = result.getOrNull() ?: it.result,
                    isSearching = false,
                    hasError = result.isFailure,
                )
            }
            // similar messages (semantic search) after the keyword hits are shown, so typing stays fast
            val hits = result.getOrNull() ?: return@launch
            if (hits.isEmptyQuery) return@launch
            updateState { it.copy(isAddingSimilar = true) }
            val withSimilar = runCatching { search.addSimilar(state.value.query, hits) }
            withSimilar.exceptionOrNull()?.let { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.warn(TAG) { "Semantic search failed: ${e::class.simpleName}" }
            }
            updateState { it.copy(result = withSimilar.getOrNull() ?: it.result, isAddingSimilar = false) }
        }
    }

    private suspend fun textWithoutChip(text: String, previous: SearchResult, chipId: String): String? {
        val result = runCatching { search.textWithoutChip(text, previous, chipId) }
        result.exceptionOrNull()?.let { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger.warn(TAG) { "Chip text not removed: ${e::class.simpleName}" }
        }
        return result.getOrNull()
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 300L
    }
}
