package app.k9mail.feature.onboarding.permissions.ui

import androidx.lifecycle.viewModelScope
import app.k9mail.core.android.permissions.Permission
import app.k9mail.core.android.permissions.PermissionState
import app.k9mail.feature.onboarding.permissions.domain.PermissionsDomainContract.UseCase
import app.k9mail.feature.onboarding.permissions.ui.PermissionsContract.Effect
import app.k9mail.feature.onboarding.permissions.ui.PermissionsContract.Event
import app.k9mail.feature.onboarding.permissions.ui.PermissionsContract.State
import app.k9mail.feature.onboarding.permissions.ui.PermissionsContract.UiPermissionState
import app.k9mail.feature.onboarding.permissions.ui.PermissionsContract.ViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.core.ui.contract.mvi.BaseViewModel

class PermissionsViewModel(
    private val checkPermission: UseCase.CheckPermission,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val checkBackgroundActivity: UseCase.CheckBackgroundActivity? = null,
    private val checkExactAlarms: UseCase.CheckExactAlarms? = null,
) : BaseViewModel<State, Event, Effect>(initialState = State(isLoading = true)), ViewModel {

    override fun event(event: Event) {
        when (event) {
            Event.LoadPermissionState -> handleOneTimeEvent(event, ::loadPermissionState)
            Event.AllowContactsPermissionClicked -> handleAllowContactsPermissionClicked()
            Event.AllowNotificationsPermissionClicked -> handleAllowNotificationsPermissionClicked()
            is Event.ContactsPermissionResult -> handleContactsPermissionResult(event.success)
            is Event.NotificationsPermissionResult -> handleNotificationsPermissionResult(event.success)
            Event.AllowBackgroundActivityClicked -> emitEffect(Effect.RequestBackgroundActivity)
            Event.OpenAppSettingsClicked -> emitEffect(Effect.OpenAppSettings)
            Event.BackgroundActivityResult -> handleBackgroundActivityResult()
            Event.AllowExactAlarmsClicked -> emitEffect(Effect.RequestExactAlarms)
            Event.ExactAlarmsResult -> handleExactAlarmsResult()
            Event.NextClicked -> handleNextClicked()
        }
    }

    private fun loadPermissionState() {
        viewModelScope.launch {
            val (contactsPermissionState, notificationsPermissionState) = withContext(backgroundDispatcher) {
                arrayOf(
                    checkPermission(Permission.Contacts),
                    checkPermission(Permission.Notifications),
                )
            }

            val contactsUiPermissionState = when (contactsPermissionState) {
                PermissionState.GrantedImplicitly -> error("Unexpected case")
                PermissionState.Granted -> UiPermissionState.Granted
                PermissionState.Denied -> UiPermissionState.Unknown
            }
            val notificationsUiPermissionState = when (notificationsPermissionState) {
                PermissionState.GrantedImplicitly -> UiPermissionState.Unknown
                PermissionState.Granted -> UiPermissionState.Granted
                PermissionState.Denied -> UiPermissionState.Unknown
            }
            val isNotificationsPermissionVisible = notificationsPermissionState != PermissionState.GrantedImplicitly
            val backgroundActivity = checkBackgroundActivity?.let { check ->
                withContext(backgroundDispatcher) { check() }
            }
            val exactAlarms = checkExactAlarms?.let { check ->
                withContext(backgroundDispatcher) { check() }
            }

            updateState { state ->
                state.copy(
                    isLoading = false,
                    contactsPermissionState = contactsUiPermissionState,
                    notificationsPermissionState = notificationsUiPermissionState,
                    isNotificationsPermissionVisible = isNotificationsPermissionVisible,
                    backgroundActivityState = if (backgroundActivity == true) {
                        UiPermissionState.Granted
                    } else {
                        UiPermissionState.Unknown
                    },
                    isBackgroundActivityVisible = backgroundActivity != null,
                    exactAlarmsState = if (exactAlarms ==
                        true
                    ) {
                        UiPermissionState.Granted
                    } else {
                        UiPermissionState.Unknown
                    },
                    isExactAlarmsVisible = exactAlarms != null,
                )
            }
            updateNextButtonState()
        }
    }

    private fun handleAllowContactsPermissionClicked() {
        emitEffect(Effect.RequestContactsPermission)
    }

    private fun handleAllowNotificationsPermissionClicked() {
        emitEffect(Effect.RequestNotificationsPermission)
    }

    private fun handleContactsPermissionResult(success: Boolean) {
        updateState { state ->
            state.copy(
                contactsPermissionState = if (success) UiPermissionState.Granted else UiPermissionState.Denied,
            )
        }
        updateNextButtonState()
    }

    private fun handleNotificationsPermissionResult(success: Boolean) {
        updateState { state ->
            state.copy(
                notificationsPermissionState = if (success) UiPermissionState.Granted else UiPermissionState.Denied,
            )
        }
        updateNextButtonState()
    }

    // the user comes back from the system dialog or settings: check again
    private fun handleBackgroundActivityResult() {
        val check = checkBackgroundActivity ?: return
        viewModelScope.launch {
            val allowed = withContext(backgroundDispatcher) { check() }
            updateState { state ->
                state.copy(
                    backgroundActivityState = if (allowed) UiPermissionState.Granted else UiPermissionState.Denied,
                )
            }
        }
    }

    // the user comes back from the alarm settings: check again
    private fun handleExactAlarmsResult() {
        val check = checkExactAlarms ?: return
        viewModelScope.launch {
            val allowed = withContext(backgroundDispatcher) { check() }
            updateState { state ->
                state.copy(exactAlarmsState = if (allowed) UiPermissionState.Granted else UiPermissionState.Denied)
            }
        }
    }

    // background activity and exact alarms are optional: they don't decide between "Next" and "Skip"
    private fun updateNextButtonState() {
        updateState { state ->
            val isContactsPermissionGranted = state.contactsPermissionState == UiPermissionState.Granted
            val isNotificationsPermissionGrantedOrHidden = !state.isNotificationsPermissionVisible ||
                state.notificationsPermissionState == UiPermissionState.Granted

            state.copy(
                isNextButtonVisible = isContactsPermissionGranted && isNotificationsPermissionGrantedOrHidden,
            )
        }
    }

    private fun handleNextClicked() {
        emitEffect(Effect.NavigateNext)
    }
}
