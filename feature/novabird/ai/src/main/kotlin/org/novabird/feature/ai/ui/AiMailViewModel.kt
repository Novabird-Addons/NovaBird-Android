package org.novabird.feature.ai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.novabird.feature.ai.assist.AiAssistant
import org.novabird.feature.ai.speech.SpeechPlayer
import org.novabird.feature.search.MailForAi
import org.novabird.feature.search.MessageLocation
import org.novabird.feature.vectors.ai.AiText

internal data class AiMailUiState(
    val mail: MailForAi? = null,
    val missing: Boolean = false,
    val summaryAvailable: Boolean = false,
    val summary: String = "",
    val summarizing: Boolean = false,
    val error: String? = null,
)

/** One mail: AI summary (Helper) and reading aloud of the summary or the mail. */
internal class AiMailViewModel(
    private val location: MessageLocation,
    private val assistant: AiAssistant,
    val speech: SpeechPlayer,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AiMailUiState(summaryAvailable = assistant.available()))
    val state: StateFlow<AiMailUiState> = mutableState.asStateFlow()
    private var summaryJob: Job? = null
    private var stopSummary = false

    /** Loads the mail and starts [AiMailActivity.MODE_SUMMARY] or [AiMailActivity.MODE_READ]. */
    fun start(mode: String) {
        if (mutableState.value.mail != null) return
        viewModelScope.launch {
            val mail = assistant.mail(location)
            mutableState.update { it.copy(mail = mail, missing = mail == null) }
            if (mail == null) return@launch
            // without the Helper the summary screen only says so (reading aloud stays one tap away)
            if (mode == AiMailActivity.MODE_READ) readMail() else summarize()
        }
    }

    fun summarize() {
        val mail = mutableState.value.mail ?: return
        if (!assistant.available()) return
        summaryJob?.cancel()
        stopSummary = false
        mutableState.update { it.copy(summary = "", summarizing = true, error = null) }
        summaryJob = viewModelScope.launch {
            val result = runCatching {
                assistant.summarize(mail, AiAssistant.uiLang(), cancelled = { stopSummary }) { text ->
                    mutableState.update { it.copy(summary = text) }
                }
            }
            mutableState.update {
                it.copy(
                    summary = result.getOrNull() ?: it.summary,
                    summarizing = false,
                    error = result.exceptionOrNull()?.takeUnless { stopSummary }?.let(::errorCode),
                )
            }
        }
    }

    /** Reads the summary when there is one, else the mail (only the new part). */
    fun read() {
        val s = mutableState.value
        if (s.summary.isNotBlank() && !s.summarizing) {
            speech.speak(KEY_SUMMARY, s.summary, AiAssistant.uiLang())
        } else {
            readMail()
        }
    }

    private fun readMail() {
        val mail = mutableState.value.mail ?: return
        val text = mail.subject + ".\n" + assistant.textOf(mail)
        speech.speak(KEY_MAIL, text, AiText.guessLang(text, AiAssistant.uiLang()))
    }

    fun stopReading() = speech.stop()

    override fun onCleared() {
        stopSummary = true
        speech.stop()
    }

    companion object {
        const val KEY_SUMMARY = "summary"
        const val KEY_MAIL = "mail"

        /** Short error code for the screens: "helper" (not paired), "code", "unreachable" or the Helper's message. */
        fun errorCode(e: Throwable): String = when {
            e.message == "helper" -> "helper"
            e.message == "code" -> "code"
            e is java.io.IOException || e.message.orEmpty().startsWith("http") -> "unreachable"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
