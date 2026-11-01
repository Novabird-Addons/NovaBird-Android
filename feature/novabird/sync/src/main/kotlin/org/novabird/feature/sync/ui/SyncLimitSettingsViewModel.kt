package org.novabird.feature.sync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.novabird.feature.sync.backfill.PartialMessageBackfillScheduler
import org.novabird.feature.sync.settings.SyncLimitSettingsRepository
import org.novabird.feature.sync.ui.SyncLimitSettingsContract.Event
import org.novabird.feature.sync.ui.SyncLimitSettingsContract.State

internal class SyncLimitSettingsViewModel(
    private val repository: SyncLimitSettingsRepository,
    private val backfillScheduler: PartialMessageBackfillScheduler,
) : ViewModel() {
    val state: StateFlow<State> = repository.settings
        .map { State(settings = it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, State(repository.getSettings()))

    fun event(event: Event) {
        val settings = repository.getSettings()
        when (event) {
            is Event.MeteredLimitChanged -> repository.update(settings.copy(meteredLimit = event.limit))

            is Event.UnmeteredLimitChanged -> {
                repository.update(settings.copy(unmeteredLimit = event.limit))
                // A higher limit may allow more partial messages to be downloaded.
                backfillScheduler.scheduleBackfill()
            }
        }
    }
}
