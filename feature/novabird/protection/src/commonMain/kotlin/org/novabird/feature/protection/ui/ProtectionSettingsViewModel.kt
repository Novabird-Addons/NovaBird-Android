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
import org.novabird.feature.protection.SpamRules
import org.novabird.feature.protection.SpamSettings
import org.novabird.feature.protection.TrustedSender
import org.novabird.feature.protection.TrustedVia

/** State and actions of the protection settings: switch with the notice, trusted senders with undo. */
class ProtectionSettingsViewModel(
    private val service: ProtectionService,
    @OptIn(ExperimentalTime::class)
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    enum class Page { MAIN, CONSENT, TRUSTED, BLOCKED, ALLOWED }

    data class State(
        val enabled: Boolean = false,
        val useAi: Boolean = true,
        val aiAvailable: Boolean = false,
        val scanImages: Boolean = true,
        val spam: SpamSettings = SpamSettings(),
        /** Typed address or domain on the blocked/allowed page. */
        val newEntry: String = "",
        val newEntryInvalid: Boolean = false,
        /** Entry removed a moment ago from the blocked/allowed page; "Rückgängig" puts it back. */
        val undoEntry: String? = null,
        /** The app may read the device contacts (`null`: unknown, not Android). */
        val contactsGranted: Boolean? = null,
        val page: Page = Page.MAIN,
        val consentChecked: Boolean = false,
        val trusted: List<TrustedSender> = emptyList(),
        val newAddress: String = "",
        val newAddressInvalid: Boolean = false,
        /** Entry removed a moment ago; "Rückgängig" puts it back. */
        val undo: TrustedSender? = null,
    )

    private val _state = MutableStateFlow(
        State(
            enabled = service.isEnabled,
            useAi = service.useAi,
            aiAvailable = service.isAiAvailable,
            scanImages = service.scanImages,
            spam = service.spamSettings,
            trusted = service.trustedSenders,
        ),
    )
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

    fun setContactsGranted(granted: Boolean) {
        _state.update { it.copy(contactsGranted = granted) }
        if (granted) service.invalidateContext()
    }

    fun onSpamEnabled(on: Boolean) = changeSpam { it.copy(enabled = on) }

    fun onSpamServerFlag(on: Boolean) = changeSpam { it.copy(serverFlag = on) }

    fun onSpamAuthFail(on: Boolean) = changeSpam { it.copy(authFail = on) }

    fun onSpamPhishing(on: Boolean) = changeSpam { it.copy(phishing = on) }

    fun openBlocked() = _state.update {
        it.copy(page = Page.BLOCKED, newEntry = "", newEntryInvalid = false, undoEntry = null)
    }

    fun openAllowed() = _state.update {
        it.copy(page = Page.ALLOWED, newEntry = "", newEntryInvalid = false, undoEntry = null)
    }

    fun onNewEntryChange(text: String) = _state.update { it.copy(newEntry = text, newEntryInvalid = false) }

    /** Adds the typed address or domain to the list of the current page (and removes it from the other list). */
    fun addEntry() {
        val entry = SpamRules.normalizeEntry(_state.value.newEntry)
        if (entry == null) {
            _state.update { it.copy(newEntryInvalid = true) }
            return
        }
        val blocked = _state.value.page == Page.BLOCKED
        changeSpam {
            if (blocked) {
                it.copy(blocked = (it.blocked + entry).distinct(), allowed = it.allowed - entry)
            } else {
                it.copy(allowed = (it.allowed + entry).distinct(), blocked = it.blocked - entry)
            }
        }
        _state.update { it.copy(newEntry = "", newEntryInvalid = false) }
    }

    fun removeEntry(entry: String) {
        val blocked = _state.value.page == Page.BLOCKED
        changeSpam { if (blocked) it.copy(blocked = it.blocked - entry) else it.copy(allowed = it.allowed - entry) }
        _state.update { it.copy(undoEntry = entry) }
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            delay(UNDO_MILLIS)
            _state.update { if (it.undoEntry == entry) it.copy(undoEntry = null) else it }
        }
    }

    fun undoRemoveEntry() {
        val entry = _state.value.undoEntry ?: return
        undoJob?.cancel()
        val blocked = _state.value.page == Page.BLOCKED
        changeSpam {
            if (blocked) {
                it.copy(
                    blocked = (it.blocked + entry).distinct(),
                )
            } else {
                it.copy(allowed = (it.allowed + entry).distinct())
            }
        }
        _state.update { it.copy(undoEntry = null) }
    }

    private fun changeSpam(transform: (SpamSettings) -> SpamSettings) {
        viewModelScope.launch {
            service.updateSpam(transform)
            _state.update { it.copy(spam = service.spamSettings) }
        }
    }

    fun onScanImages(on: Boolean) {
        service.updateScanImages(on)
        _state.update { it.copy(scanImages = on) }
    }

    fun onUseAi(on: Boolean) {
        service.updateUseAi(on)
        _state.update { it.copy(useAi = on) }
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
        _state.update { it.copy(page = Page.MAIN, consentChecked = false, undoEntry = null) }
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
        const val UNDO_MILLIS = 10_000L
    }
}
