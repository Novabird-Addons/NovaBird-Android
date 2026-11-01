package org.novabird.feature.sync.ui

import org.novabird.feature.sync.settings.SyncLimitSettings

interface SyncLimitSettingsContract {
    data class State(
        val settings: SyncLimitSettings = SyncLimitSettings(),
    )

    sealed interface Event {
        data class MeteredLimitChanged(val limit: Int) : Event
        data class UnmeteredLimitChanged(val limit: Int) : Event
    }
}
