package org.novabird.feature.vectors.ai

/**
 * HTML of a mail as plain text for the language model (like ai/ai.js htmlToText without a DOM): with [onlyNew] the quoted
 * blocks of the common programs are left out (Thunderbird/Apple, Gmail, Outlook on the web, Yahoo).
 */
object HtmlText {
    private val QUOTE_START = Regex(
        "<(blockquote)\\b[^>]*\\btype\\s*=\\s*[\"']?cite[^>]*>|" +
            "<(div|blockquote|span)\\b[^>]*\\bclass\\s*=\\s*[\"'][^\"']*" +
            "\\b(?:gmail_quote|gmail_quote_container|yahoo_quoted|moz-cite-prefix)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val REPLY_START =
        Regex("<[a-z]+\\b[^>]*\\bid\\s*=\\s*[\"']?(?:divRplyFwdMsg|appendonsend)\\b", RegexOption.IGNORE_CASE)
    private val HIDDEN = Regex("<(script|style|head|title)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val COMMENT = Regex("<!--[\\s\\S]*?-->")
    private val BR = Regex("<br\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val BLOCK_END = Regex("</(p|div|tr|li|h[1-6]|blockquote)\\s*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val NAMED = mapOf(
        "nbsp" to "\u00A0", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "shy" to "",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "euro" to "€", "auml" to "ä", "ouml" to "ö", "uuml" to "ü",
        "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß", "laquo" to "«", "raquo" to "»",
        "bdquo" to "„", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "copy" to "©",
    )

    fun toText(html: String, onlyNew: Boolean = false): String {
        var h = COMMENT.replace(html, "")
        if (onlyNew) h = withoutQuotes(h)
        h = HIDDEN.replace(h, "")
        h = BR.replace(h, "\n")
        h = BLOCK_END.replace(h) { it.value + "\n" }
        h = TAG.replace(h, "")
        return ENTITY.replace(h) { m -> entity(m.groupValues[1]) ?: m.value }
    }

    private fun entity(name: String): String? = when {
        name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(HEX)?.let(::codePoint)
        name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
        else -> NAMED[name]
    }

    @Suppress("MagicNumber")
    private fun codePoint(cp: Int): String? = when {
        cp in 0..0xFFFF -> cp.toChar().toString()

        cp in 0x10000..0x10FFFF -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }

        else -> null
    }

    // removes each quoted element with everything inside (nested elements of the same name are counted), and everything
    // from Outlook's reply header on
    private fun withoutQuotes(html: String): String {
        var h = REPLY_START.find(html)?.let { html.substring(0, it.range.first) } ?: html
        while (true) {
            val m = QUOTE_START.find(h) ?: break
            val name = (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase()
            val tag = Regex("<(/?)$name\\b[^>]*>", RegexOption.IGNORE_CASE)
            var depth = 0
            var end = h.length
            for (t in tag.findAll(h, m.range.first)) {
                depth += if (t.groupValues[1].isEmpty()) 1 else -1
                if (depth == 0) {
                    end = t.range.last + 1
                    break
                }
            }
            h = h.substring(0, m.range.first) + h.substring(end)
        }
        return h
    }

    private const val HEX = 16
}
