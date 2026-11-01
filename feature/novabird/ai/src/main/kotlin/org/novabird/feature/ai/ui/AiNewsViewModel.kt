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
import org.novabird.feature.ai.assist.Overview
import org.novabird.feature.ai.settings.AiSettingsRepository
import org.novabird.feature.ai.speech.SpeechPlayer

internal data class AiNewsUiState(
    val hours: Int = 24,
    val unreadOnly: Boolean = true,
    val available: Boolean = false,
    val running: Boolean = false,
    val done: Int = 0,
    val all: Int = 0,
    val overview: Overview? = null,
    val error: String? = null,
)

/** "Was gibt's Neues?": the newest inbox mails sorted by the Helper (to do / for information / advertising). */
internal class AiNewsViewModel(
    private val assistant: AiAssistant,
    private val settings: AiSettingsRepository,
    val speech: SpeechPlayer,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        settings.settings.value.let { AiNewsUiState(it.newsHours, it.newsUnreadOnly, assistant.available()) },
    )
    val state: StateFlow<AiNewsUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    @Volatile private var generation = 0

    fun setHours(hours: Int) {
        settings.update { it.copy(newsHours = hours) }
        mutableState.update { it.copy(hours = hours) }
        run()
    }

    fun setUnreadOnly(unreadOnly: Boolean) {
        settings.update { it.copy(newsUnreadOnly = unreadOnly) }
        mutableState.update { it.copy(unreadOnly = unreadOnly) }
        run()
    }

    fun run() {
        if (!assistant.available()) return
        // a new run replaces the previous one; the old one stops at its next mail (the Helper call itself is blocking)
        val run = ++generation
        job?.cancel()
        val s = mutableState.value
        mutableState.update { it.copy(running = true, done = 0, all = 0, error = null) }
        job = viewModelScope.launch {
            val result = runCatching {
                assistant.overview(AiAssistant.uiLang(), s.hours, s.unreadOnly, cancelled = {
                    run != generation
                }) { done, all ->
                    if (run == generation) mutableState.update { it.copy(done = done, all = all) }
                }
            }
            if (run != generation) return@launch
            mutableState.update {
                it.copy(
                    running = false,
                    overview = result.getOrNull() ?: it.overview,
                    error = result.exceptionOrNull()?.let(AiMailViewModel::errorCode),
                )
            }
        }
    }

    /** Reads the overview (without the mail numbers). */
    fun read() {
        val text = mutableState.value.overview?.text ?: return
        speech.speak(KEY, text.replace(NUMBERS, ""), AiAssistant.uiLang())
    }

    fun stopReading() = speech.stop()

    override fun onCleared() {
        generation++
        speech.stop()
    }

    private companion object {
        const val KEY = "news"
        val NUMBERS = Regex("\\s*\\[[\\d,\\s]+]")
    }
}
