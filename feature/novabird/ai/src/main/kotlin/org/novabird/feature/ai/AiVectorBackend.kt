package org.novabird.feature.ai

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.core.logging.Logger
import org.novabird.feature.ai.helper.HelperKeyVault
import org.novabird.feature.ai.local.LocalEmbedder
import org.novabird.feature.ai.settings.AiSettingsRepository
import org.novabird.feature.search.NovaBirdVectorBackend
import org.novabird.feature.search.VectorItem
import org.novabird.feature.vectors.EmbeddingModel
import org.novabird.feature.vectors.HelperClient
import org.novabird.feature.vectors.HelperInfo
import org.novabird.feature.vectors.VectorKey

private const val TAG = "NovaBirdAiBackend"

/**
 * Vectors from the NovaBird Helper (GPU on the PC, shared with all phones of the group) or from the phone.
 *
 * Index vectors: if a Helper is paired, only the Helper computes them (the Helper keeps only vectors it computed, so
 * vectors from the phone couldn't be shared); without a Helper the phone computes them if switched on.
 * Query vectors: Helper first, the phone as fallback (e.g. away from home).
 */
class AiVectorBackend(
    private val settings: AiSettingsRepository,
    private val keyVault: HelperKeyVault,
    private val local: LocalEmbedder,
    private val logger: Logger,
    private val model: EmbeddingModel = EmbeddingModel.JINA_DE,
) : NovaBirdVectorBackend {
    override val modelId: String = "${model.id}@${model.revision.take(REVISION_CHARS)}"

    fun isHelperPaired() = settings.settings.value.helperAddress.isNotBlank() && keyVault.hasKey()

    override fun isEnabled() = isHelperPaired() || settings.settings.value.localEnabled

    /** Helper client for the paired Helper, or null. */
    fun helper(timeoutMs: Int = HelperClient.DEFAULT_TIMEOUT_MS): HelperClient? {
        val s = settings.settings.value
        val key = if (s.helperAddress.isBlank()) null else keyVault.load()
        return key?.let {
            HelperClient(HelperClient.baseUrl(s.helperAddress), it, s.clientId, label(), timeoutMs, model)
        }
    }

    suspend fun helperStatus(): Result<HelperInfo> = withContext(Dispatchers.IO) {
        runCatching { helper(STATUS_TIMEOUT_MS)?.hello() ?: error("not paired") }
    }

    override suspend fun embedQuery(text: String): FloatArray? {
        if (isHelperPaired() && settings.settings.value.helperSearch) {
            val vector = withContext(Dispatchers.IO) {
                runCatching { helper(QUERY_TIMEOUT_MS)?.embed(listOf(text), query = true)?.single() }
                    .onFailure { logger.info(TAG) { "Helper not reachable for query: ${it::class.simpleName}" } }
                    .getOrNull()
            }
            if (vector != null) return vector
        }
        return local.embed(listOf(text))?.single()
    }

    override suspend fun embedForIndex(items: List<VectorItem>): List<FloatArray>? = when {
        items.isEmpty() -> emptyList()
        isHelperPaired() -> withContext(Dispatchers.IO) { embedViaHelper(items) }
        else -> local.embed(items.map { it.text })
    }

    // vectors other phones of the group already computed first (vlookup), then embed the rest with keys, so the Helper
    // keeps them for the others
    private fun embedViaHelper(items: List<VectorItem>): List<FloatArray>? = runCatching {
        val client = helper() ?: return null
        val keys = items.map { VectorKey(it.messageId, it.textHash) }
        val (found, shared) = client.lookup(keys)
        val out = arrayOfNulls<FloatArray>(items.size)
        found.forEachIndexed { i, index -> out[index] = shared[i] }
        val missing = items.indices.filter { out[it] == null }
        if (missing.isNotEmpty()) {
            val vectors = client.embed(missing.map { items[it].text }, keys = missing.map { keys[it] })
            missing.forEachIndexed { i, index -> out[index] = vectors[i] }
        }
        out.map { it!! }
    }.onFailure { logger.info(TAG) { "Helper not reachable for index: ${it::class.simpleName}" } }.getOrNull()

    private fun label() = "NovaBird ${Build.MODEL}"

    private companion object {
        const val REVISION_CHARS = 7
        const val QUERY_TIMEOUT_MS = 4_000
        const val STATUS_TIMEOUT_MS = 5_000
    }
}
