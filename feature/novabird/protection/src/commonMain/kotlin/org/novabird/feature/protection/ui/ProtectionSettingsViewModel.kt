package org.novabird.feature.protection.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.novabird.feature.protection.CONSENT_VERSION
import org.novabird.feature.protection.ProtectionService
import org.novabird.feature.protection.TrustedSender
import org.novabird.feature.protection.TrustedVia

/** State and actions of the protection settings: switch with the notice, trusted senders with undo. */
class ProtectionSettingsViewModel(
    private val service: ProtectionService,
    @OptIn(ExperimentalTime::class)
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    enum class Page { MAIN, CONSENT, TRUSTED }

    data class State(
        val enabled: Boolean = false,
        val page: Page = Page.MAIN,
        val consentChecked: Boolean = false,
        val trusted: List<TrustedSender> = emptyList(),
        val newAddress: String = "",
        val newAddressInvalid: Boolean = false,
        /** Entry removed a moment ago; "Rückgängig" puts it back. */
        val undo: TrustedSender? = null,
    )

    private val _state = MutableStateFlow(State(enabled = service.isEnabled, trusted = service.trustedSenders))
    val state: StateFlow<State> = _state
    private var undoJob: Job? = null

    fun onSwitch(on: Boolean) {
        if (on == _state.value.enabled) return
        if (on) {
            _state.update { it.copy(page = Page.CONSENT, consentChecked = false) }
        } else {
            viewModelScope.launch {
                service.disable()
                _state.update { it.copy(enabled = false) }
            }
        }
    }

    fun onConsentChecked(checked: Boolean) = _state.update { it.copy(consentChecked = checked) }

    fun onConsentAccept() {
        if (!_state.value.consentChecked) return
        viewModelScope.launch {
            service.enable(CONSENT_VERSION)
            _state.update { it.copy(enabled = true, page = Page.MAIN) }
        }
    }

    fun onConsentCancel() = _state.update { it.copy(page = Page.MAIN, consentChecked = false) }

    fun openTrusted() = _state.update { it.copy(page = Page.TRUSTED, trusted = service.trustedSenders) }

    /** Back inside the screen; `false` if already on the main page (the activity closes then). */
    fun onBack(): Boolean {
        if (_state.value.page == Page.MAIN) return false
        _state.update { it.copy(page = Page.MAIN, consentChecked = false) }
        return true
    }

    fun onNewAddressChange(text: String) = _state.update { it.copy(newAddress = text, newAddressInvalid = false) }

    fun addTrusted() {
        val address = _state.value.newAddress.trim().lowercase()
        if (!ADDRESS.matches(address)) {
            _state.update { it.copy(newAddressInvalid = true) }
            return
        }
        viewModelScope.launch {
            service.trust(TrustedSender(address = address, since = now(), via = TrustedVia.SETTINGS))
            _state.update { it.copy(trusted = service.trustedSenders, newAddress = "", newAddressInvalid = false) }
        }
    }

    fun remove(sender: TrustedSender) {
        viewModelScope.launch {
            service.untrust(sender.address)
            _state.update { it.copy(trusted = service.trustedSenders, undo = sender) }
            undoJob?.cancel()
            undoJob = launch {
                delay(UNDO_MILLIS)
                _state.update { if (it.undo == sender) it.copy(undo = null) else it }
            }
        }
    }

    fun undoRemove() {
        val sender = _state.value.undo ?: return
        undoJob?.cancel()
        viewModelScope.launch {
            service.trust(sender)
            _state.update { it.copy(trusted = service.trustedSenders, undo = null) }
        }
    }

    private companion object {
        val ADDRESS = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
        const val UNDO_MILLIS = 6_000L
    }
}
