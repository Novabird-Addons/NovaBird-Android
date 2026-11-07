package org.novabird.feature.ai.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.thunderbird.core.logging.Logger
import org.novabird.feature.ai.AiVectorBackend
import org.novabird.feature.ai.settings.AiSettingsRepository
import org.novabird.feature.vectors.HelperClient
import org.novabird.feature.vectors.ai.AiText
import org.novabird.feature.vectors.ai.SpeechChunks
import org.novabird.feature.vectors.ai.SpokenText

/**
 * Reads text aloud like the desktop (ai/speech.js): sentence by sentence with the NovaBird Helper's voice (Qwen3-TTS
 * on its
 * graphics card, WAV per sentence, the next one is fetched while the previous one plays), otherwise with the phone's
 * own
 * text-to-speech. The speed keeps the pitch (AudioTrack playback parameters / speech rate).
 */
class SpeechPlayer(
    private val context: Context,
    private val backend: AiVectorBackend,
    private val settings: AiSettingsRepository,
    private val logger: Logger,
) {
    enum class Status { IDLE, LOADING, PLAYING }
    enum class Engine { HELPER, PHONE }

    data class State(
        val key: String? = null,
        val status: Status = Status.IDLE,
        val engine: Engine? = null,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var job: Job? = null

    // the Helper's calls block, so a stopped run can still be busy while the next one reads; only the latest may show
    @Volatile
    private var run = 0

    /** Starts reading [text] (language code [lang], e.g. "de"); [key] names what is read (shown by the screens). */
    fun speak(key: String, text: String, lang: String) {
        stop()
        val sentences = SpeechChunks.quickStart(AiText.sentences(text))
        if (sentences.isEmpty()) return
        val current = run
        mutableState.value = State(key, Status.LOADING)
        job = scope.launch {
            val rate = settings.settings.value.speechRate
            val useHelper = backend.isHelperPaired() && settings.settings.value.helperSpeech
            val result = runCatching {
                val read = if (useHelper) helperPart(current, key, sentences, lang, rate) else 0
                if (read < sentences.size) speakWithPhone(current, key, sentences.drop(read), lang, rate)
            }
            val error = result.exceptionOrNull()?.takeUnless { it is kotlinx.coroutines.CancellationException }
            show(current, State(error = error?.let { it.message ?: it.javaClass.simpleName }))
        }
    }

    fun stop() {
        run++
        job?.cancel()
        job = null
        mutableState.value = State()
    }

    private fun show(current: Int, state: State) {
        if (run == current) mutableState.value = state
    }

    private suspend fun helperPart(
        current: Int,
        key: String,
        sentences: List<String>,
        lang: String,
        rate: Float,
    ): Int = runCatching { speakWithHelper(current, key, sentences, lang, rate) }
        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        .onFailure { logger.warn(TAG, it) { "Helper voice failed, using the phone's voice" } }
        .getOrDefault(0)

    // the number of sentences read; the phone reads the rest (all of them if the Helper could not read the first one)
    private suspend fun speakWithHelper(
        current: Int,
        key: String,
        sentences: List<String>,
        lang: String,
        rate: Float,
    ): Int = coroutineScope {
        val client = backend.helper(TTS_TIMEOUT_MS) ?: return@coroutineScope 0
        // a Helper that cannot be reached (e.g. away from home) would only be noticed after the long wait below
        if (backend.helperStatus().isFailure) return@coroutineScope 0
        val voiceLang = AiText.TTS_LANG[lang] ?: "english"
        fun fetch(sentence: String) =
            Wav.parse(client.tts(SpokenText.text(sentence, lang).take(HelperClient.MAX_TTS_CHARS), voiceLang))
        val first = fetch(sentences.first())
        // the next sentences are fetched ahead (a few, the Helper also serves other devices)
        val queue = Channel<Wav>(capacity = AHEAD)
        launch(Dispatchers.IO) {
            // a failure only ends the queue: what was fetched is still read, then the phone goes on from there
            val failure = runCatching { for (s in sentences.drop(1)) queue.send(fetch(s)) }.exceptionOrNull()
            queue.close(failure?.takeUnless { it is kotlinx.coroutines.CancellationException })
        }
        val track = first.newTrack(rate)
        try {
            track.play()
            show(current, State(key, Status.PLAYING, Engine.HELPER))
            var frames = 0L
            var read = 0
            var wav: Wav? = first
            while (wav != null) {
                ensureActive()
                frames += wav.writeTo(track) { isActive }
                frames += first.writeSilence(track, PAUSE_S / rate)
                read++
                wav = queue.receiveCatching().getOrNull()
            }
            // until the last sentence has been played
            while (isActive && track.playbackHeadPosition.toLong() and UINT_MASK < frames) delay(POLL_MS)
            read
        } finally {
            track.pause()
            track.flush()
            track.release()
        }
    }

    private suspend fun speakWithPhone(current: Int, key: String, sentences: List<String>, lang: String, rate: Float) {
        val tts = suspendCancellableCoroutine<TextToSpeech> { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { status ->
                if (status ==
                    TextToSpeech.SUCCESS
                ) {
                    cont.resume(engine!!)
                } else {
                    cont.resumeWith(Result.failure(IllegalStateException("tts")))
                }
            }
            cont.invokeOnCancellation { engine.shutdown() }
        }
        try {
            tts.setLanguage(Locale.forLanguageTag(lang))
            tts.setSpeechRate(rate)
            tts.setAudioAttributes(attributes())
            suspendCancellableCoroutine { cont ->
                val last = "s${sentences.size - 1}"
                tts.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            show(current, State(key, Status.PLAYING, Engine.PHONE))
                        }

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == last && cont.isActive) cont.resume(Unit)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("tts")))
                        }
                    },
                )
                sentences.forEachIndexed { i, s ->
                    if (tts.speak(s, TextToSpeech.QUEUE_ADD, null, "s$i") == TextToSpeech.ERROR && cont.isActive) {
                        cont.resumeWith(Result.failure(IllegalStateException("tts")))
                    }
                }
                cont.invokeOnCancellation { tts.stop() }
            }
        } finally {
            tts.shutdown()
        }
    }

    private companion object {
        const val TAG = "NovaBirdSpeech"

        // the Helper may first start its voice (up to 2 minutes, Speech.cs StartWait), then reads the sentence;
        // a shorter wait made the phone's voice read whenever the Helper's voice was not loaded yet
        const val TTS_TIMEOUT_MS = 150_000
        const val AHEAD = 3
        const val PAUSE_S = 0.25f
        const val POLL_MS = 100L
        const val UINT_MASK = 0xFFFFFFFFL // the play position is an unsigned 32 bit counter
        const val BUFFER_FACTOR = 4

        fun attributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        fun Wav.newTrack(rate: Float): AudioTrack {
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val format = AudioFormat.Builder().setSampleRate(
                sampleRate,
            ).setEncoding(encoding).setChannelMask(channelMask).build()
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            return AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuffer * BUFFER_FACTOR)
                .build()
                .apply { playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f) }
        }
    }
}
