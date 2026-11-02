package org.novabird.feature.vectors.ai

/**
 * Text helpers of the desktop KI functions (ai/ai.js): sentences for reading aloud, language guess, only the new part
 * of a mail.
 */
@Suppress("MagicNumber")
object AiText {
    /** Longest mail text sent to the language model (desktop: 16 384 tokens context × 3 − 1500). */
    const val MAX_CHARS = 16_384 * 3 - 1500

    /** Language names of the Helper's voice by language code. */
    val TTS_LANG = mapOf(
        "de" to "german", "en" to "english", "fr" to "french", "es" to "spanish", "it" to "italian",
        "pt" to "portuguese", "ru" to "russian", "zh" to "chinese", "ja" to "japanese", "ko" to "korean",
    )

    private val BOLD = Regex("\\*\\*")
    private val NEWLINE = Regex("\\r?\\n")
    private val BULLET = JsRegex.of("^\\s*(?:#+|[*•–-]|\\d+[.)])\\s+")
    private val SPLIT = JsRegex.of(
        "(?<=[.!?…])(?<!(?:^|[\\s(])\\p{L}\\.)(?<!\\d\\.)" +
            "(?<!\\b(?:Nr|Dr|Prof|ca|bzw|Hr|Fr|evtl|ggf|inkl|usw|vgl|Mr|Mrs|Ms|St|Tel|Abs|Str|etc)\\.)" +
            "\\s+(?=[\\p{Lu}\\d„\"«(])",
    )
    private val END_MARK = Regex("[.!?…:;]$")

    /**
     * The model's text as sentences: without Markdown and bullets; very short pieces join the next, long ones are split
     * at
     * commas (the Helper takes at most 600 characters; shorter sentences also sound more natural and the first comes
     * sooner).
     */
    fun sentences(text: String): List<String> {
        val parts = mutableListOf<String>()
        for (line in text.replace(BOLD, "").split(NEWLINE)) {
            val l = BULLET.replaceFirst(line, "").trim()
            if (l.isEmpty()) continue
            for (piece in l.split(SPLIT)) {
                var s = piece.trim()
                if (s.isNotEmpty() && !END_MARK.containsMatchIn(s)) s += "."
                if (s.isNotEmpty()) parts += s
            }
        }
        val out = mutableListOf<String>()
        for (p in parts) {
            if (out.isNotEmpty() && out.last().length < 25) out[out.size - 1] = out.last() + " " + p else out += p
        }
        return out.flatMap { whole ->
            var s = whole
            val pieces = mutableListOf<String>()
            while (s.length > 280) {
                var cut = maxOf(s.lastIndexOf(", ", 240), s.lastIndexOf("; ", 240))
                if (cut < 80) cut = s.lastIndexOf(" ", 240)
                if (cut < 80) cut = 240
                pieces += s.substring(0, cut + 1).trim()
                s = s.substring(cut + 1).trim()
            }
            if (s.isNotEmpty()) pieces + s else pieces
        }
    }

    private val LANG_WORDS = linkedMapOf(
        "de" to Regex("^(der|die|das|und|ist|nicht|mit|für|ein|eine|auf|sich|den|dem|zu|bis|wird|bitte)$"),
        "en" to Regex("^(the|and|is|for|with|not|you|to|of|a|an|on|will|please|by|this)$"),
        "fr" to Regex("^(le|la|les|et|est|pour|vous|des|du|une|un|pas|avec|sur|au|ce)$"),
        "es" to Regex("^(el|la|los|las|y|es|para|que|con|una|un|por|del|se|su|no)$"),
        "it" to Regex("^(il|lo|gli|le|e|è|per|che|con|una|un|non|della|del|di|sono)$"),
    )
    private val WORD = Regex("\\p{L}+")

    /** Language of a text from frequent words (at least 3 hits), else [fallback]. */
    fun guessLang(text: String, fallback: String): String {
        val score = LANG_WORDS.keys.associateWith { 0 }.toMutableMap()
        for (w in WORD.findAll(text.lowercase())) {
            for ((k, re) in LANG_WORDS) if (re.matches(w.value)) score[k] = score.getValue(k) + 1
        }
        val best = score.entries.maxBy { it.value } // first of equal scores, like a stable sort
        return if (best.value >= 3) best.key else fallback
    }

    // ---- Only the current mail: leave out the quoted history (forwarded mails stay whole) ---------------------------
    private val FORWARD_SUBJECT = JsRegex.of("^\\s*(fwd?|wg|tr|rv|enc)\\s*:", ignoreCase = true)
    private val ATTRIBUTION_START = JsRegex.of("^\\s*(am|on|le|el)\\s", ignoreCase = true)
    private val ATTRIBUTION =
        JsRegex.of(
            "^\\s*(am|on|le|el)\\s.{4,250}\\b(schrieb|wrote|a écrit|escribió)\\b[^\\n]{0,80}$",
            ignoreCase = true,
        )
    private val ORIGINAL = JsRegex.of(
        "^\\s*-{2,}\\s*(original message|ursprüngliche nachricht|message d'origine|mensaje original)\\s*-{2,}\\s*$",
        ignoreCase = true,
    )
    private val HEADER_FROM = JsRegex.of("^\\s*\\*?(von|from|de|da)\\s*:\\*?\\s+\\S", ignoreCase = true)
    private val HEADER_SENT = JsRegex.of(
        "^\\s*\\*?(gesendet|sent|envoyé|enviado|datum|date|fecha)\\s*:\\*?\\s+\\S",
        ignoreCase = true,
    )
    private val QUOTED = JsRegex.of("^\\s*>")
    private val MANY_NEWLINES = Regex("\\n{3,}")
    private val TRAILING_SPACE = Regex("[ \\t\\u00A0]+\\n")

    /** Plain text without the quoted history; the text itself if nothing quoted was found. */
    fun stripQuotedText(text: String): String {
        val lines = text.split("\n").toMutableList()
        var cut = lines.size
        for (i in lines.indices) {
            val l = lines[i]
            val outlook = HEADER_FROM.containsMatchIn(l) &&
                lines.subList(minOf(i + 1, lines.size), minOf(i + 4, lines.size)).any {
                    HEADER_SENT.containsMatchIn(it)
                }
            if (ORIGINAL.containsMatchIn(l) || outlook) {
                cut = i // Outlook: everything below is the old mail (without ">")
                break
            }
            val single = ATTRIBUTION.containsMatchIn(l)
            val two = if (ATTRIBUTION_START.containsMatchIn(l) && i + 1 < lines.size) "$l ${lines[i + 1]}" else null
            if (single ||
                (two != null && !ATTRIBUTION.containsMatchIn(lines[i + 1]) && ATTRIBUTION.containsMatchIn(two))
            ) {
                // "Am … schrieb …:" – with ">" quotes below, replies in between stay; otherwise everything below is old
                val rest = lines.drop(i + if (single) 1 else 2).filter { it.isNotBlank() }
                if (rest.none { QUOTED.containsMatchIn(it) }) {
                    cut = i
                    break
                }
                lines[i] = ""
                if (!single) lines[i + 1] = ""
            }
        }
        return lines.take(cut).filterNot {
            QUOTED.containsMatchIn(it)
        }.joinToString("\n").replace(MANY_NEWLINES, "\n\n").trim()
    }

    /**
     * Mail text for the language model like ai/ai.js mailText(): with [onlyNew] without the quoted history (unless the
     * subject says it is forwarded), tidy white space, at most [limit] characters.
     */
    fun mailText(
        plain: String,
        subject: String,
        onlyNew: Boolean = true,
        limit: Int = MAX_CHARS,
        newPart: String = plain,
    ): String {
        var text = plain.trim()
        if (onlyNew && !FORWARD_SUBJECT.containsMatchIn(subject)) {
            val whole = text.replace("\r", "")
            val fresh = stripQuotedText(newPart.trim().replace("\r", ""))
            if (fresh.length >= 30 && fresh.length < whole.trim().length - 20) text = fresh
        }
        text = text.replace("\r", "").replace(TRAILING_SPACE, "\n").replace(MANY_NEWLINES, "\n\n").trim()
        return if (text.length > limit) text.substring(0, limit) else text
    }
}
