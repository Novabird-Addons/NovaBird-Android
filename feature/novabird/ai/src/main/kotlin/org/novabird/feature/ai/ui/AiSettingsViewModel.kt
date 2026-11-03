package org.novabird.feature.ai.ui

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.novabird.feature.ai.AiVectorBackend
import org.novabird.feature.ai.FoundHelper
import org.novabird.feature.ai.HelperDiscovery
import org.novabird.feature.ai.helper.HelperKeyVault
import org.novabird.feature.ai.local.DownloadState
import org.novabird.feature.ai.local.LocalEmbedder
import org.novabird.feature.ai.local.ModelDownloads
import org.novabird.feature.ai.local.ModelFiles
import org.novabird.feature.ai.settings.AiSettings
import org.novabird.feature.ai.settings.AiSettingsRepository
import org.novabird.feature.ai.settings.KeepLoaded
import org.novabird.feature.search.NovaBirdIndexTrigger
import org.novabird.feature.search.NovaBirdVectorIndexer
import org.novabird.feature.search.VectorIndexStats
import org.novabird.feature.vectors.HelperClient
import org.novabird.feature.vectors.HelperInfo
import org.novabird.feature.vectors.HelperPairing

/** Connection state of the Helper shown on the settings screen. */
sealed interface HelperStatus {
    data object NotPaired : HelperStatus
    data object Checking : HelperStatus
    data class Connected(val info: HelperInfo) : HelperStatus
    data class Unreachable(val reason: String) : HelperStatus
}

data class AiSettingsUiState(
    val settings: AiSettings = AiSettings(),
    val helper: HelperStatus = HelperStatus.NotPaired,
    val pairing: Boolean = false,
    val pairingError: String? = null,
    val search: HelperSearch = HelperSearch.Idle,
    val autoPair: AutoPair? = null,
    val modelReady: Boolean = false,
    val download: DownloadState = DownloadState.Idle,
    val stats: VectorIndexStats? = null,
)

/** Search for Helpers in pairing mode. */
sealed interface HelperSearch {
    data object Idle : HelperSearch
    data object Searching : HelperSearch
    data class Done(val found: List<FoundHelper>) : HelperSearch
}

/** Pairing without the code, as far as it got. */
sealed interface AutoPair {
    data object Starting : AutoPair
    data class Compare(val helperName: String, val number: String) : AutoPair
    data class Failed(val reason: String) : AutoPair
}

/** What the settings screen can do (implemented by [AiSettingsViewModel]). */
@Suppress("TooManyFunctions") // one function per control of the settings screen
internal interface AiSettingsActions {
    fun checkHelper()
    fun pair(address: String, code: String)
    fun searchHelpers()
    fun pairWith(url: String)
    fun cancelAutoPair()
    fun unpair()
    fun setHelperSearch(enabled: Boolean)
    fun setHelperChat(enabled: Boolean)
    fun setHelperSpeech(enabled: Boolean)
    fun setSpeechRate(rate: Float)
    fun setLocalEnabled(enabled: Boolean)
    fun setKeepLoaded(keep: KeepLoaded)
    fun setThreads(threads: Int)
    fun startDownload(allowMobileData: Boolean)
    fun cancelDownload()
    fun deleteModel()
    fun refreshIndex()
}

// one function per action of the settings screen (AiSettingsActions)
@Suppress("TooManyFunctions")
internal class AiSettingsViewModel(
    private val repository: AiSettingsRepository,
    private val keyVault: HelperKeyVault,
    private val backend: AiVectorBackend,
    private val files: ModelFiles,
    private val downloads: ModelDownloads,
    private val local: LocalEmbedder,
    private val vectorIndexer: NovaBirdVectorIndexer,
    private val indexTrigger: NovaBirdIndexTrigger,
    private val discovery: HelperDiscovery,
) : ViewModel(), AiSettingsActions {
    private var autoPairJob: Job? = null
    private val mutableState = MutableStateFlow(
        AiSettingsUiState(settings = repository.settings.value, modelReady = files.isComplete()),
    )
    val state: StateFlow<AiSettingsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { repository.settings.collect { s -> mutableState.update { it.copy(settings = s) } } }
        viewModelScope.launch {
            downloads.state.collect { d ->
                val ready = files.isComplete()
                mutableState.update { it.copy(download = if (ready) DownloadState.Idle else d, modelReady = ready) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                val stats = runCatching { vectorIndexer.stats() }.getOrNull()
                mutableState.update { it.copy(stats = stats) }
                delay(STATS_INTERVAL_MS)
            }
        }
        checkHelper()
    }

    override fun checkHelper() {
        if (!backend.isHelperPaired()) {
            mutableState.update { it.copy(helper = HelperStatus.NotPaired) }
            return
        }
        mutableState.update { it.copy(helper = HelperStatus.Checking) }
        viewModelScope.launch {
            val result = backend.helperStatus()
            mutableState.update {
                it.copy(
                    helper = result.fold(
                        onSuccess = { info -> HelperStatus.Connected(info) },
                        onFailure = { e -> HelperStatus.Unreachable(e.message ?: e::class.simpleName.orEmpty()) },
                    ),
                )
            }
        }
    }

    /** Pairs with the Helper at [address] using the pairing [code] typed by the user (only the derived key is kept). */
    override fun pair(address: String, code: String) {
        if (address.isBlank() || !HelperClient.isValidCode(code)) return
        viewModelScope.launch { connect(address, code) }
    }

    /** Looks for Helpers in pairing mode in the local network. */
    override fun searchHelpers() {
        mutableState.update { it.copy(search = HelperSearch.Searching, autoPair = null) }
        viewModelScope.launch {
            val found = runCatching { discovery.find() }.getOrDefault(emptyList())
            mutableState.update { it.copy(search = HelperSearch.Done(found)) }
        }
    }

    /**
     * Pairing without the code: key exchange with the Helper at [url], show the number to compare, wait until the admin
     * allows it at the Helper (at most 5 minutes), then connect with the code the Helper sent.
     */
    override fun pairWith(url: String) {
        autoPairJob?.cancel()
        mutableState.update { it.copy(autoPair = AutoPair.Starting, pairingError = null) }
        autoPairJob = viewModelScope.launch {
            val base = HelperClient.baseUrl(url)
            val pairing = HelperPairing(base, deviceLabel(), PAIR_TIMEOUT_MS)
            val outcome = runCatching {
                val started = withContext(Dispatchers.IO) { pairing.start() }
                mutableState.update { it.copy(autoPair = AutoPair.Compare(started.helperName, started.number)) }
                waitForApproval(pairing, started)
            }
            when (val result = outcome.getOrElse { e -> AutoPair.Failed(e.message ?: e::class.simpleName.orEmpty()) }) {
                is HelperPairing.PollResult.Ok -> {
                    mutableState.update { it.copy(autoPair = null, search = HelperSearch.Idle) }
                    connect(base, result.code)
                }

                HelperPairing.PollResult.Denied -> mutableState.update { it.copy(autoPair = AutoPair.Failed("denied")) }

                HelperPairing.PollResult.Expired -> mutableState.update {
                    it.copy(autoPair = AutoPair.Failed("expired"))
                }

                is AutoPair.Failed -> mutableState.update { it.copy(autoPair = result) }

                else -> mutableState.update { it.copy(autoPair = null) }
            }
        }
    }

    override fun cancelAutoPair() {
        autoPairJob?.cancel()
        autoPairJob = null
        mutableState.update { it.copy(autoPair = null) }
    }

    private suspend fun waitForApproval(pairing: HelperPairing, started: HelperPairing.Started): Any {
        val until = System.currentTimeMillis() + HelperPairing.MAX_WAIT_MS
        while (System.currentTimeMillis() < until) {
            delay(HelperPairing.POLL_INTERVAL_MS)
            val result = withContext(Dispatchers.IO) { pairing.poll(started) }
            if (result != HelperPairing.PollResult.Pending) return result
        }
        return HelperPairing.PollResult.Expired
    }

    private fun deviceLabel() = "NovaBird ${Build.MODEL}".take(MAX_LABEL)

    /** Checks the Helper at [address] with [code]; on success only the derived key is kept, never the code. */
    private suspend fun connect(address: String, code: String) {
        mutableState.update { it.copy(pairing = true, pairingError = null) }
        run {
            val s = repository.settings.value
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val key = HelperClient.deriveKey(code)
                    val info = HelperClient(HelperClient.baseUrl(address), key, s.clientId, "NovaBird", PAIR_TIMEOUT_MS)
                        .hello()
                    check(info.modelMatch) { "model" }
                    key to info
                }
            }
            result.onSuccess { (key, info) ->
                keyVault.save(key)
                repository.update { it.copy(helperAddress = address.trim()) }
                mutableState.update { it.copy(pairing = false, helper = HelperStatus.Connected(info)) }
                startCollecting()
            }.onFailure { e ->
                mutableState.update { it.copy(pairing = false, pairingError = e.message ?: e::class.simpleName) }
            }
        }
    }

    override fun unpair() {
        keyVault.clear()
        repository.update { it.copy(helperAddress = "") }
        mutableState.update { it.copy(helper = HelperStatus.NotPaired) }
    }

    override fun setHelperSearch(enabled: Boolean) = repository.update { it.copy(helperSearch = enabled) }

    override fun setHelperChat(enabled: Boolean) = repository.update { it.copy(helperChat = enabled) }

    override fun setHelperSpeech(enabled: Boolean) = repository.update { it.copy(helperSpeech = enabled) }

    override fun setSpeechRate(rate: Float) = repository.update { it.copy(speechRate = rate) }

    override fun setLocalEnabled(enabled: Boolean) {
        repository.update { it.copy(localEnabled = enabled) }
        if (enabled) {
            viewModelScope.launch { startCollecting() }
        } else {
            viewModelScope.launch { local.unload() }
        }
    }

    override fun setKeepLoaded(keep: KeepLoaded) = repository.update { it.copy(keepLoaded = keep) }

    override fun setThreads(threads: Int) = repository.update { it.copy(threads = threads) }

    override fun startDownload(allowMobileData: Boolean) = downloads.start(allowMobileData)

    override fun cancelDownload() = downloads.cancel()

    override fun deleteModel() {
        viewModelScope.launch {
            local.unload()
            withContext(Dispatchers.IO) { files.delete() }
            mutableState.update { it.copy(modelReady = false) }
        }
    }

    private suspend fun startCollecting() {
        runCatching { vectorIndexer.recollectTexts() }
        indexTrigger.requestIndexing()
        indexTrigger.requestVectors()
    }

    override fun refreshIndex() {
        indexTrigger.requestIndexing()
        indexTrigger.requestVectors()
    }

    private companion object {
        const val MAX_LABEL = 40
        const val STATS_INTERVAL_MS = 3_000L
        const val PAIR_TIMEOUT_MS = 10_000
    }
}
