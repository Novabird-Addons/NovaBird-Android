package org.novabird.feature.protection

import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.novabird.feature.protection.resources.Res

/** Loads the bundled JavaScript: the add-on core (see `novabird-phishing/SOURCE.md`) and the Android glue. */
object ProtectionScripts {
    private const val CORE_DIR = "novabird-phishing"
    private val GLUE_FILES = listOf(ProtectionEngine.ENTRY_MODULE, "novabird-protection/polyfills.js")

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): Map<String, String> {
        val manifest = Res.readBytes("files/$CORE_DIR/manifest.txt").decodeToString()
        val coreFiles = manifest.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { "$CORE_DIR/$it" }
        return (coreFiles + GLUE_FILES).associateWith { name ->
            Res.readBytes("files/$name").decodeToString()
        }
    }
}
