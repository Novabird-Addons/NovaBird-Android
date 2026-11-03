package org.novabird.feature.ai.settings

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long the model stays in memory after computing on the phone (about 1.2 GB while loaded). */
@Suppress("MagicNumber")
enum class KeepLoaded(val minutes: Int?) {
    ALWAYS(null),
    WITH_APP(null),
    MIN_5(5),
    MIN_10(10),
    MIN_15(15),
}

data class AiSettings(
    /**
     * Compute on the phone (model download on Wi-Fi): semantic search also when the Helper is not reachable,
     * and without Helper the index vectors (only while charging). On by default.
     */
    val localEnabled: Boolean = true,
    val keepLoaded: KeepLoaded = KeepLoaded.WITH_APP,
    /** CPU threads for the model; 0 = automatic ([defaultThreads]). */
    val threads: Int = 0,
    /** Address of the NovaBird Helper as typed ("192.168.1.20", "pc:47531"); empty = no Helper. */
    val helperAddress: String = "",
    /** Search queries via the Helper (the Helper always computes the index vectors when paired). */
    val helperSearch: Boolean = true,
    /** Client id for the Helper (one per installation). */
    val clientId: String = "",
    /** Summary and "Was gibt's Neues" with the Helper's language model. */
    val helperChat: Boolean = true,
    /** Read aloud with the Helper's voice when it is reachable; otherwise (or off) with the phone's voice. */
    val helperSpeech: Boolean = true,
    /** Reading speed, pitch stays (one of [SPEECH_RATES], like the desktop). */
    val speechRate: Float = 1f,
    /** Last choice in "Was gibt's Neues": period in hours and only unread mails. */
    val newsHours: Int = 24,
    val newsUnreadOnly: Boolean = true,
    /** Name of this phone in the Helper's device list; empty = "NovaBird <model>". */
    val deviceName: String = "",
) {
    /** The name sent with every Helper request (the Helper shows the last one it got, at most 40 characters). */
    fun effectiveDeviceName(): String = deviceName.trim().ifEmpty { defaultDeviceName() }.take(MAX_DEVICE_NAME)

    fun effectiveThreads(cores: Int = Runtime.getRuntime().availableProcessors()) =
        if (threads > 0) threads.coerceAtMost(cores) else defaultThreads(cores)

    companion object {
        /** Half of the cores: 8 threads 0.8 s, 4 threads 1.15 s per 512 tokens on a Dimensity 9400 (8 cores). */
        fun defaultThreads(cores: Int) = (cores / 2).coerceAtLeast(1)

        fun defaultDeviceName() = "NovaBird ${Build.MODEL}".take(MAX_DEVICE_NAME)

        const val MAX_DEVICE_NAME = 40

        val SPEECH_RATES = listOf(0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.35f, 1.5f)
        val NEWS_HOURS = listOf(12, 24, 72, 168)
    }
}

interface AiSettingsRepository {
    val settings: StateFlow<AiSettings>

    fun update(transform: (AiSettings) -> AiSettings)
}

/** Stores the NovaBird AI settings in their own SharedPreferences file (the Helper key is in [HelperKeyVault]). */
internal class SharedPreferencesAiSettingsRepository(context: Context) : AiSettingsRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(load())
    override val settings: StateFlow<AiSettings> = mutableSettings.asStateFlow()

    @Synchronized
    override fun update(transform: (AiSettings) -> AiSettings) {
        val s = transform(mutableSettings.value)
        preferences.edit {
            putBoolean(KEY_LOCAL, s.localEnabled)
            putString(KEY_KEEP, s.keepLoaded.name)
            putInt(KEY_THREADS, s.threads)
            putString(KEY_HELPER_ADDRESS, s.helperAddress)
            putBoolean(KEY_HELPER_SEARCH, s.helperSearch)
            putString(KEY_CLIENT_ID, s.clientId)
            putBoolean(KEY_HELPER_CHAT, s.helperChat)
            putBoolean(KEY_HELPER_SPEECH, s.helperSpeech)
            putFloat(KEY_SPEECH_RATE, s.speechRate)
            putInt(KEY_NEWS_HOURS, s.newsHours)
            putBoolean(KEY_NEWS_UNREAD, s.newsUnreadOnly)
            putString(KEY_DEVICE_NAME, s.deviceName)
        }
        mutableSettings.value = s
    }

    private fun load(): AiSettings {
        var clientId = preferences.getString(KEY_CLIENT_ID, null).orEmpty()
        if (clientId.isEmpty()) {
            clientId = "android-" + Random.nextBytes(CLIENT_ID_BYTES).joinToString("") { "%02x".format(it) }
            preferences.edit { putString(KEY_CLIENT_ID, clientId) }
        }
        return AiSettings(
            localEnabled = preferences.getBoolean(KEY_LOCAL, true),
            keepLoaded = preferences.getString(KEY_KEEP, null)
                ?.let { name -> KeepLoaded.entries.firstOrNull { it.name == name } } ?: KeepLoaded.WITH_APP,
            threads = preferences.getInt(KEY_THREADS, 0),
            helperAddress = preferences.getString(KEY_HELPER_ADDRESS, null).orEmpty(),
            helperSearch = preferences.getBoolean(KEY_HELPER_SEARCH, true),
            clientId = clientId,
            helperChat = preferences.getBoolean(KEY_HELPER_CHAT, true),
            helperSpeech = preferences.getBoolean(KEY_HELPER_SPEECH, true),
            speechRate = preferences.getFloat(KEY_SPEECH_RATE, 1f).takeIf { it in AiSettings.SPEECH_RATES } ?: 1f,
            newsHours = preferences.getInt(KEY_NEWS_HOURS, DEFAULT_NEWS_HOURS).takeIf { it in AiSettings.NEWS_HOURS }
                ?: DEFAULT_NEWS_HOURS,
            newsUnreadOnly = preferences.getBoolean(KEY_NEWS_UNREAD, true),
            deviceName = preferences.getString(KEY_DEVICE_NAME, null).orEmpty(),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "novabird_ai"
        const val KEY_LOCAL = "localEnabled"
        const val KEY_KEEP = "keepLoaded"
        const val KEY_THREADS = "threads"
        const val KEY_HELPER_ADDRESS = "helperAddress"
        const val KEY_HELPER_SEARCH = "helperSearch"
        const val KEY_CLIENT_ID = "clientId"
        const val KEY_HELPER_CHAT = "helperChat"
        const val KEY_HELPER_SPEECH = "helperSpeech"
        const val KEY_SPEECH_RATE = "speechRate"
        const val KEY_NEWS_HOURS = "newsHours"
        const val KEY_NEWS_UNREAD = "newsUnreadOnly"
        const val KEY_DEVICE_NAME = "deviceName"
        const val DEFAULT_NEWS_HOURS = 24
        const val CLIENT_ID_BYTES = 6
    }
}
