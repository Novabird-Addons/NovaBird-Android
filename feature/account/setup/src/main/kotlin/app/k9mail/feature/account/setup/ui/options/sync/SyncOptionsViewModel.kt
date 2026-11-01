package app.k9mail.feature.account.setup.ui.options.sync

import app.k9mail.feature.account.common.domain.AccountDomainContract
import app.k9mail.feature.account.setup.AccountSetupExternalContract.MessageDisplayCountProvider
import app.k9mail.feature.account.setup.domain.entity.EmailDisplayCount
import app.k9mail.feature.account.setup.ui.options.sync.SyncOptionsContract.Effect
import app.k9mail.feature.account.setup.ui.options.sync.SyncOptionsContract.Event
import app.k9mail.feature.account.setup.ui.options.sync.SyncOptionsContract.State
import app.k9mail.feature.account.setup.ui.options.sync.SyncOptionsContract.ViewModel
import net.thunderbird.core.ui.contract.mvi.BaseViewModel

internal class SyncOptionsViewModel(
    private val accountStateRepository: AccountDomainContract.AccountStateRepository,
    private val messageDisplayCountProvider: MessageDisplayCountProvider =
        MessageDisplayCountProvider { EmailDisplayCount.DEFAULT },
    initialState: State? = null,
) : BaseViewModel<State, Event, Effect>(
    initialState = initialState ?: accountStateRepository.getState().toSyncOptionsState(
        defaultMessageDisplayCount = messageDisplayCountProvider.getDefaultMessageDisplayCount(),
    ),
),
    ViewModel {

    override fun event(event: Event) {
        when (event) {
            Event.LoadAccountState -> handleOneTimeEvent(event, ::loadAccountState)

            is Event.OnCheckFrequencyChanged -> updateState {
                it.copy(
                    checkFrequency = event.checkFrequency,
                )
            }

            is Event.OnMessageDisplayCountChanged -> updateState { state ->
                state.copy(
                    messageDisplayCount = event.messageDisplayCount,
                )
            }

            is Event.OnShowNotificationChanged -> updateState { state ->
                state.copy(
                    showNotification = event.showNotification,
                )
            }

            Event.OnNextClicked -> submit()

            Event.OnBackClicked -> navigateBack()
        }
    }

    private fun loadAccountState() {
        updateState {
            accountStateRepository.getState().toSyncOptionsState(
                defaultMessageDisplayCount = messageDisplayCountProvider.getDefaultMessageDisplayCount(),
            )
        }
    }

    private fun submit() {
        accountStateRepository.setSyncOptions(state.value.toAccountSyncOptions())
        navigateNext()
    }

    private fun navigateBack() = emitEffect(Effect.NavigateBack)

    private fun navigateNext() = emitEffect(Effect.NavigateNext)
}
