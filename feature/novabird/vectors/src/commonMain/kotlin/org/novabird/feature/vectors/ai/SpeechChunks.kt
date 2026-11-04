package org.novabird.feature.vectors.ai

/**
 * Android only (not in the desktop speech.js): the first sound of "Vorlesen" comes once the first piece is computed,
 * and a sentence from [AiText.sentences] can be up to 280 characters. A long first sentence is therefore split at a
 * comma (or, if it has none, at a space) so reading starts sooner; the other sentences stay as they are.
 */
object SpeechChunks {
    private const val FIRST_MAX = 100
    private const val FIRST_MIN = 25
    private const val SPLIT_AT_SPACE_FROM = 160

    fun quickStart(sentences: List<String>): List<String> {
        val first = sentences.firstOrNull() ?: return sentences
        if (first.length <= FIRST_MAX) return sentences
        var cut = first.lastIndexOf(", ", FIRST_MAX)
        if (cut < FIRST_MIN && first.length >= SPLIT_AT_SPACE_FROM) cut = first.lastIndexOf(' ', FIRST_MAX)
        if (cut < FIRST_MIN) return sentences
        val head = first.substring(0, cut + 1).trim()
        val tail = first.substring(cut + 1).trim()
        return listOf(head, tail) + sentences.drop(1)
    }
}
