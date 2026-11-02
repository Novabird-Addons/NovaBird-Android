package org.novabird.feature.vectors.ai

/**
 * Regular expressions written like the desktop JavaScript (ai/ai.js, ai/spoken.js) with the same meaning on the JVM and
 * on
 * Android (ICU): `\b` and `\d` are ASCII as in JavaScript, `\s` is Unicode white space as in JavaScript.
 */
internal object JsRegex {
    private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val W = "A-Za-z0-9_"
    private val BOUNDARY = "(?:(?<=[$W])(?![$W])|(?<![$W])(?=[$W]))"

    fun of(pattern: String, ignoreCase: Boolean = false): Regex {
        val out = StringBuilder()
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\' && i + 1 < pattern.length) {
                val n = pattern[i + 1]
                out.append(
                    when {
                        n == 'b' && !inClass -> BOUNDARY
                        n == 's' -> if (inClass) WS else "[$WS]"
                        n == 'S' && !inClass -> "[^$WS]"
                        n == 'd' -> if (inClass) "0-9" else "[0-9]"
                        else -> "\\$n"
                    },
                )
                i += 2
                continue
            }
            if (c == '[' && !inClass) {
                inClass = true
            } else if (c == ']' && inClass) {
                inClass = false
            }
            out.append(c)
            i++
        }
        return if (ignoreCase) Regex(out.toString(), RegexOption.IGNORE_CASE) else Regex(out.toString())
    }
}

/**
 * JavaScript `str.replace(re, fn)` with a global regex: [fn] gets the match, its groups ("" if unmatched) and the text
 * before it.
 */
internal fun Regex.replaceJs(input: String, fn: (m: MatchResult, g: List<String>, before: String) -> String): String =
    replace(input) { m -> fn(m, m.groupValues, input.substring(0, m.range.first)) }
