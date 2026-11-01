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
                launchSearch(delayMillis = 0) { search.searchWithoutChip(previous, event.chipId) }
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
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 300L
    }
}
