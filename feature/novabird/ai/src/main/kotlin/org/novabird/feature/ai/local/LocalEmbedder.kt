package org.novabird.feature.ai.local

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.thunderbird.core.logging.Logger
import org.novabird.feature.ai.settings.AiSettingsRepository
import org.novabird.feature.ai.settings.KeepLoaded
import org.novabird.feature.vectors.BpeTokenizer
import org.novabird.feature.vectors.EmbedDevice
import org.novabird.feature.vectors.OnnxEmbedder

private const val TAG = "NovaBirdLocalEmbedder"

/**
 * The embedding model on the phone (ONNX Runtime on the CPU). Loaded on first use; unloaded according to
 * [KeepLoaded]: never, when the app goes to the background, or after some idle minutes.
 */
class LocalEmbedder(
    private val files: ModelFiles,
    private val settings: AiSettingsRepository,
    private val logger: Logger,
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tokenizer: BpeTokenizer? = null
    private var embedder: OnnxEmbedder? = null
    private var loadedThreads = 0
    private var unloadJob: Job? = null

    @Volatile
    private var inForeground = false

    init {
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        inForeground = true
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        inForeground = false
                        if (settings.settings.value.keepLoaded == KeepLoaded.WITH_APP) scope.launch { unload() }
                    }
                },
            )
        }
    }

    fun isAvailable() = settings.settings.value.localEnabled && files.isComplete()

    /** Vectors for [texts] (one model run per text), or null if the model isn't there. */
    suspend fun embed(texts: List<String>): List<FloatArray>? = withContext(Dispatchers.Default) {
        if (!isAvailable()) return@withContext null
        mutex.withLock {
            val threads = settings.settings.value.effectiveThreads()
            if (embedder == null || loadedThreads != threads) load(threads)
            val tok = tokenizer!!
            val emb = embedder!!
            val maxTokens = files.model.maxTokens
            texts.map { emb.embed(tok.encode(it, maxTokens)) }
        }.also { scheduleUnload() }
    }

    private fun load(threads: Int) {
        embedder?.close()
        if (tokenizer == null) tokenizer = BpeTokenizer(files.file("tokenizer.json").readText())
        // without arena/memory patterns: about 530 MB instead of 1.2 GB after a 512-token text, same speed (measured)
        embedder = OnnxEmbedder(files.file(files.model.onnxPath), EmbedDevice.CPU, threads, lowMemory = true)
        loadedThreads = threads
        logger.info(TAG) { "Model loaded ($threads threads)" }
    }

    private fun scheduleUnload() {
        val keep = settings.settings.value.keepLoaded
        // used in the background (vector worker) while set to "while open": don't keep 1.2 GB until the next app start
        if (keep == KeepLoaded.WITH_APP && !inForeground) {
            scope.launch { unload() }
            return
        }
        val minutes = keep.minutes ?: return
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(minutes * MILLIS_PER_MINUTE)
            unload()
        }
    }

    suspend fun unload() = mutex.withLock {
        if (embedder != null) logger.info(TAG) { "Model unloaded" }
        // the tokenizer (a few MB) stays: loading it again would take half a second of the 0.9 s reload
        embedder?.close()
        embedder = null
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
