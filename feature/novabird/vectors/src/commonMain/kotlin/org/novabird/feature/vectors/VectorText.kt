package org.novabird.feature.vectors

/**
 * Text of a mail for vectors, as NovaBird-Search on the desktop (`semantic/main.js`, `textOf`/`clip`): subject, newline,
 * cleaned body, clipped to [MAX_CHARS] without cutting a surrogate pair; lone surrogates become U+FFFD.
 * Local and Helper vectors must be computed from this same text.
 */
object VectorText {
    const val MAX_CHARS = 1200

    fun of(subject: String, cleanedBody: String): String = clip(clip("$subject\n") + cleanedBody)

    fun clip(text: String): String {
        var t = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        if (t.isNotEmpty() && t.last().isHighSurrogate()) t = t.dropLast(1)
        return wellFormed(t)
    }

    private fun wellFormed(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    sb.append(c).append(text[i + 1])
                    i++
                }

                c.isSurrogate() -> sb.append('�')

                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
