package org.novabird.feature.ai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.novabird.feature.ai.AiVectorBackend
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
    val modelReady: Boolean = false,
    val download: DownloadState = DownloadState.Idle,
    val stats: VectorIndexStats? = null,
)

/** What the settings screen can do (implemented by [AiSettingsViewModel]). */
internal interface AiSettingsActions {
    fun checkHelper()
    fun pair(address: String, code: String)
    fun unpair()
    fun setHelperSearch(enabled: Boolean)
    fun setLocalEnabled(enabled: Boolean)
    fun setKeepLoaded(keep: KeepLoaded)
    fun setThreads(threads: Int)
    fun startDownload(allowMobileData: Boolean)
    fun cancelDownload()
    fun deleteModel()
    fun refreshIndex()
}

internal class AiSettingsViewModel(
    private val repository: AiSettingsRepository,
    private val keyVault: HelperKeyVault,
    private val backend: AiVectorBackend,
    private val files: ModelFiles,
    private val downloads: ModelDownloads,
    private val local: LocalEmbedder,
    private val vectorIndexer: NovaBirdVectorIndexer,
    private val indexTrigger: NovaBirdIndexTrigger,
) : ViewModel(), AiSettingsActions {
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
        mutableState.update { it.copy(pairing = true, pairingError = null) }
        viewModelScope.launch {
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
                indexTrigger.requestIndexing()
                indexTrigger.requestVectors()
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

    override fun setLocalEnabled(enabled: Boolean) {
        repository.update { it.copy(localEnabled = enabled) }
        if (enabled) {
            indexTrigger.requestIndexing()
            indexTrigger.requestVectors()
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

    override fun refreshIndex() {
        indexTrigger.requestIndexing()
        indexTrigger.requestVectors()
    }

    private companion object {
        const val STATS_INTERVAL_MS = 3_000L
        const val PAIR_TIMEOUT_MS = 10_000
    }
}
