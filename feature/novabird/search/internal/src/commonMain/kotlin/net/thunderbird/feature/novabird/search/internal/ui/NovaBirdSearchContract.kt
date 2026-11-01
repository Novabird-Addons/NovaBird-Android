package net.thunderbird.feature.novabird.search.internal.ui

import net.thunderbird.core.ui.contract.mvi.UnidirectionalViewModel
import net.thunderbird.feature.novabird.search.MessageLocation
import net.thunderbird.feature.novabird.search.SearchResult

internal interface NovaBirdSearchContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect>

    data class State(
        val query: String = "",
        val result: SearchResult? = null,
        val isSearching: Boolean = false,
        val hasError: Boolean = false,
    )

    sealed interface Event {
        data class QueryChanged(val query: String) : Event
        data object Submit : Event
        data class RemoveChip(val chipId: String) : Event
        data object ApplySuggestion : Event
        data class OpenHit(val location: MessageLocation) : Event
        data object Back : Event
    }

    sealed interface Effect {
        data class OpenMessage(val location: MessageLocation) : Effect
        data object NavigateBack : Effect
    }
}
