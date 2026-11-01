package org.novabird.feature.search.internal.script

import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.novabird.feature.search.internal.resources.Res

/** Loads the bundled JavaScript: the NovaBird-Search core (see `novabird-search/SOURCE.md`) and the Android glue. */
internal object ScriptSources {
    private const val CORE_DIR = "novabird-search"
    private val GLUE_FILES = listOf(NovaBirdScriptEngine.ENTRY_MODULE, "novabird-android/polyfills.js")

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): Map<String, String> {
        val manifest = Res.readBytes("files/$CORE_DIR/manifest.txt").decodeToString()
        val coreFiles = manifest.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { "$CORE_DIR/$it" }
        return (coreFiles + GLUE_FILES).associateWith { name ->
            Res.readBytes("files/$name").decodeToString()
        }
    }
}
