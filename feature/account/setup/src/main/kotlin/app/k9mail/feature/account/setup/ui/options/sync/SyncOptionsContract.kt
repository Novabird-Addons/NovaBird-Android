package app.k9mail.feature.account.setup.ui.options.sync

import app.k9mail.feature.account.setup.domain.entity.EmailCheckFrequency
import app.k9mail.feature.account.setup.domain.entity.EmailDisplayCount
import app.k9mail.feature.account.setup.domain.entity.EmailSyncPeriod
import net.thunderbird.core.ui.contract.mvi.UnidirectionalViewModel

interface SyncOptionsContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect>

    data class State(
        val checkFrequency: EmailCheckFrequency = EmailCheckFrequency.DEFAULT,
        val messageDisplayCount: EmailDisplayCount = EmailDisplayCount.DEFAULT,
        val showNotification: Boolean = true,
        /** null: the app doesn't ask for the sync period. */
        val syncPeriod: EmailSyncPeriod? = null,
    )

    sealed interface Event {
        data class OnCheckFrequencyChanged(val checkFrequency: EmailCheckFrequency) : Event
        data class OnMessageDisplayCountChanged(val messageDisplayCount: EmailDisplayCount) : Event
        data class OnShowNotificationChanged(val showNotification: Boolean) : Event
        data class OnSyncPeriodChanged(val syncPeriod: EmailSyncPeriod) : Event

        data object LoadAccountState : Event

        data object OnNextClicked : Event
        data object OnBackClicked : Event
    }

    sealed interface Effect {
        object NavigateNext : Effect
        object NavigateBack : Effect
    }
}
