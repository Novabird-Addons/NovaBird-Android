package org.novabird.feature.vectors.ai

/**
 * Text for the Helper's voice (Qwen3-TTS): numbers, dates, times, amounts and abbreviations written out, only for
 * speaking
 * (port of ai/spoken.js; German and English, other languages unchanged). Not needed for Android's own text-to-speech.
 */
@Suppress("MagicNumber", "TooManyFunctions")
object SpokenText {
    fun text(sentence: String, lang: String): String = when (lang.take(2).lowercase()) {
        "de" -> german(sentence)
        "en" -> english(sentence)
        else -> sentence
    }

    // ---- German -----------------------------------------------------------------------------------------------------
    private val DE_UNITS = listOf(
        "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn", "elf", "zwölf",
        "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn",
    )
    private val DE_TENS =
        listOf("", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig")
    private val DE_MONTHS = listOf(
        "Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November",
        "Dezember",
    )
    private val DE_DAYS = mapOf(
        "Mo" to "Montag",
        "Di" to "Dienstag",
        "Mi" to "Mittwoch",
        "Do" to "Donnerstag",
        "Fr" to "Freitag",
        "Sa" to "Samstag",
        "So" to "Sonntag",
    )
    private val EINS_END = Regex("eins$")
    private fun deEin(w: String) = w.replace(EINS_END, "ein")

    private fun de100(n: Int): String {
        if (n < 20) return DE_UNITS[n]
        val t = DE_TENS[n / 10]
        return if (n % 10 != 0) deEin(DE_UNITS[n % 10]) + "und" + t else t
    }

    private fun de1000(n: Int): String {
        val h = n / 100
        val r = n % 100
        return (if (h != 0) deEin(DE_UNITS[h]) + "hundert" else "") + (if (r != 0 || h == 0) de100(r) else "")
    }

    fun deNumber(n: Long): String {
        if (n < 0 || n >= 1_000_000_000) return deDigits(n.toString())
        if (n < 1000) return de1000(n.toInt())
        val m = (n / 1_000_000).toInt()
        val t = (n % 1_000_000 / 1000).toInt()
        val r = (n % 1000).toInt()
        val mio = if (m != 0) {
            (if (m == 1) "eine Million" else deEin(de1000(m)) + " Millionen") + (if (t != 0 || r != 0) " " else "")
        } else {
            ""
        }
        return mio + (if (t != 0) deEin(de1000(t)) + "tausend" else "") + (if (r != 0) de1000(r) else "")
    }

    private fun deNum(s: String) = s.toLongOrNull()?.let { deNumber(it) } ?: deDigits(s)
    private fun deDigits(s: String) = s.map {
        if (it in
            '0'..'9'
        ) {
            DE_UNITS[it - '0']
        } else {
            it.toString()
        }
    }.joinToString(" ")

    /** Ordinal: form "ten" (am vierten), "ter" or "te" (der vierte). */
    fun deOrdinal(n: Int, form: String = "ten"): String {
        val special = mapOf(1 to "erste", 3 to "dritte", 7 to "siebte", 8 to "achte")
        val stem = special[n] ?: if (n < 20) deNumber(n.toLong()) + "te" else deNumber(n.toLong()) + "ste"
        return if (form == "te") stem else stem + if (form == "ter") "r" else "n"
    }

    private fun deYear(y: Int) =
        if (y in
            1100..1999
        ) {
            de100(y / 100) + "hundert" + (
                if (y % 100 !=
                    0
                ) {
                    de100(y % 100)
                } else {
                    ""
                }
                )
        } else {
            deNumber(y.toLong())
        }

    private fun fullYear(y: String) = if (y.length == 2) 2000 + y.toInt() else y.toInt()
    private val DER_BEFORE = JsRegex.of("\\b(der|Der)\\s*$")
    private fun deForm(before: String) = if (DER_BEFORE.containsMatchIn(before)) "te" else "ten"
    private fun deDate(d: String, m: String, y: String, before: String) =
        "${deOrdinal(d.toInt(), deForm(before))} ${DE_MONTHS[m.toInt() - 1]}" +
            (if (y.isNotEmpty()) " " + deYear(fullYear(y)) else "")

    private fun deDecimal(i: String, f: String) = "${deNum(i)} Komma ${deDigits(f)}"

    private fun deMoney(i: String, c: String, cur: String): String {
        val name = when {
            Regex("^(\\$|USD|Dollar)$", RegexOption.IGNORE_CASE).matches(cur) -> "Dollar"
            Regex("^(£|GBP|Pfund)$", RegexOption.IGNORE_CASE).matches(cur) -> "Pfund"
            Regex("^(CHF|Franken)$", RegexOption.IGNORE_CASE).matches(cur) -> "Franken"
            else -> "Euro"
        }
        val cents = if (c.isNotEmpty() && c.toInt() != 0) " " + deNum(c.padEnd(2, '0')) else ""
        return "${deNum(i.replace(".", ""))} $name$cents"
    }

    private val DE_ABBR = listOf(
        "\\bz\\.\\s?B\\." to "zum Beispiel", "\\bd\\.\\s?h\\." to "das heißt", "\\bu\\.\\s?a\\." to "unter anderem",
        "\\bu\\.\\s?U\\." to "unter Umständen", "\\bggf\\." to "gegebenenfalls", "\\bbzw\\." to "beziehungsweise",
        "\\bca\\." to "circa", "\\busw\\." to "und so weiter", "\\binkl\\." to "inklusive", "\\bexkl\\." to "exklusive",
        "\\bevtl\\." to "eventuell", "\\bvgl\\." to "vergleiche", "\\bNr\\.(?=\\s*\\d)" to "Nummer",
        "\\bTel\\.(?=\\s|:)" to "Telefon", "\\bStr\\.(?=\\s|,|$)" to "Straße", "\\bDr\\.(?=\\s)" to "Doktor",
        "\\bProf\\.(?=\\s)" to "Professor", "\\bHr\\.(?=\\s)" to "Herr", "\\bMwSt\\.?" to "Mehrwertsteuer",
        "\\bz\\.\\s?Hd\\." to "zu Händen", "\\bab\\s+sofort\\b" to "ab sofort", "&" to " und ",
        "°C\\b" to " Grad Celsius",
        "\\bkm\\b" to "Kilometer", "\\bkg\\b" to "Kilogramm",
    ).map { (p, w) -> JsRegex.of(p) to w }

    private val DE_WEEKDAY = JsRegex.of("\\b(Mo|Di|Mi|Do|Fr|Sa|So)\\.(?=,?\\s*\\d)")
    private val ISO_DATE = JsRegex.of("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")
    private val DE_DATE = JsRegex.of("(?<![\\d.])(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}|\\d{2}(?!\\d))?")
    private val DE_DAY_MONTH = JsRegex.of("(\\d{1,2})\\.\\s*(${DE_MONTHS.joinToString("|")})")
    private val DE_TIME = JsRegex.of("\\b([01]?\\d|2[0-3])[:.]([0-5]\\d)(\\s*Uhr)?\\b")
    private const val AMOUNT = "(\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:,(\\d{1,2}|-|–))?"
    private const val CUR = "(€|EUR|Euro|\\$|USD|Dollar|£|GBP|CHF)"
    private val DE_AMOUNT_CUR = JsRegex.of("$AMOUNT\\s*$CUR(?![\\p{L}])")
    private val DE_CUR_AMOUNT = JsRegex.of("$CUR\\s*$AMOUNT")
    private val DE_PERCENT = JsRegex.of("(\\d+)(?:,(\\d+))?\\s*%")
    private val DE_THOUSANDS = JsRegex.of("\\b(\\d{1,3}(?:\\.\\d{3})+)(?![.\\d])")
    private val DE_DECIMAL = JsRegex.of("(\\d+),(\\d+)")
    private val DOTTED = JsRegex.of("(\\d+)\\.(\\d+)")
    private val DIGITS = JsRegex.of("\\d+")
    private val DIGIT = Regex("\\d")
    private val SPACES = JsRegex.of("\\s{2,}")

    private fun german(s: String): String {
        var t = s
        for ((re, word) in DE_ABBR) t = re.replace(t) { word }
        t = DE_WEEKDAY.replaceJs(t) { _, g, _ -> DE_DAYS.getValue(g[1]) }
        t = ISO_DATE.replaceJs(t) { m, g, before ->
            if (g[2].toInt() in 1..12 && g[3].toInt() in 1..31) deDate(g[3], g[2], g[1], before) else m.value
        }
        t = DE_DATE.replaceJs(t) { m, g, before ->
            if (g[1].toInt() in 1..31 && g[2].toInt() in 1..12) deDate(g[1], g[2], g[3], before) else m.value
        }
        t = DE_DAY_MONTH.replaceJs(t) { m, g, before ->
            if (g[1].toInt() in 1..31) "${deOrdinal(g[1].toInt(), deForm(before))} ${g[2]}" else m.value
        }
        t = DE_TIME.replaceJs(t) { m, g, _ ->
            if (g[3].isEmpty() && m.value.contains('.')) {
                m.value
            } else {
                "${deNumber(g[1].toLong())} Uhr" + (if (g[2].toInt() != 0) " " + deNumber(g[2].toLong()) else "")
            }
        }
        t = DE_AMOUNT_CUR.replaceJs(t) { _, g, _ -> deMoney(g[1], if (DIGIT.containsMatchIn(g[2])) g[2] else "", g[3]) }
        t = DE_CUR_AMOUNT.replaceJs(t) { _, g, _ -> deMoney(g[2], if (DIGIT.containsMatchIn(g[3])) g[3] else "", g[1]) }
        t =
            DE_PERCENT.replaceJs(t) { _, g, _ ->
                (if (g[2].isNotEmpty()) deDecimal(g[1], g[2]) else deNum(g[1])) +
                    " Prozent"
            }
        t = DE_THOUSANDS.replaceJs(t) { _, g, _ -> deNum(g[1].replace(".", "")) }
        t = DE_DECIMAL.replaceJs(t) { _, g, _ -> deDecimal(g[1], g[2]) }
        t = DOTTED.replaceJs(t) { _, g, _ -> "${deNum(g[1])} Punkt ${deNum(g[2])}" }
        t = DIGITS.replaceJs(t) { m, _, before ->
            val d = m.value
            val v = d.toLongOrNull() ?: -1
            val word = when {
                d.length >= 7 || (d.length > 1 && d[0] == '0') -> deDigits(d)
                d.length == 4 && v in 1100..1999 -> deYear(v.toInt())
                else -> deNum(d)
            }
            word + letterGap(before.length + d.length, t)
        }
        return SPACES.replace(t, " ").trim()
    }

    // "5b" → "fünf b": a space when a letter follows the number
    private fun letterGap(next: Int, all: String) = if (all.getOrNull(next)?.isLetter() == true) " " else ""

    // ---- English ----------------------------------------------------------------------------------------------------
    private val EN_UNITS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val EN_TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val EN_MONTHS = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November",
        "December",
    )

    private fun en100(n: Int) = if (n <
        20
    ) {
        EN_UNITS[n]
    } else {
        EN_TENS[n / 10] + (if (n % 10 != 0) "-" + EN_UNITS[n % 10] else "")
    }
    private fun en1000(n: Int): String {
        val h = n / 100
        val r = n % 100
        return (if (h != 0) EN_UNITS[h] + " hundred" + (if (r != 0) " and " else "") else "") +
            (if (r != 0 || h == 0) en100(r) else "")
    }

    fun enNumber(n: Long): String {
        if (n < 0 || n >= 1_000_000_000) return enDigits(n.toString())
        val parts = mutableListOf<String>()
        val m = (n / 1_000_000).toInt()
        val t = (n % 1_000_000 / 1000).toInt()
        val r = (n % 1000).toInt()
        if (m != 0) parts += en1000(m) + " million"
        if (t != 0) parts += en1000(t) + " thousand"
        if (r != 0 || parts.isEmpty()) parts += en1000(r)
        return parts.joinToString(" ")
    }

    private fun enNum(s: String) = s.toLongOrNull()?.let { enNumber(it) } ?: enDigits(s)
    private fun enDigits(s: String) = s.map {
        if (it in
            '0'..'9'
        ) {
            EN_UNITS[it - '0']
        } else {
            it.toString()
        }
    }.joinToString(" ")
    private val LAST_WORD = Regex("[a-z]+$")

    fun enOrdinal(n: Int): String {
        val special = mapOf(
            "one" to "first",
            "two" to "second",
            "three" to "third",
            "five" to "fifth",
            "eight" to "eighth",
            "nine" to "ninth",
            "twelve" to "twelfth",
        )
        val w = enNumber(n.toLong())
        val last = LAST_WORD.find(w)!!.value
        special[last]?.let { return w.dropLast(last.length) + it }
        return if (last.endsWith("y")) w.dropLast(1) + "ieth" else w + "th"
    }

    private fun enYear(y: Int) = when {
        y in 2000..2009 -> enNumber(y.toLong())

        y in 1100..2099 -> "${en100(y / 100)} " +
            if (y % 100 != 0) (if (y % 100 < 10) "oh " else "") + en100(y % 100) else "hundred"

        else -> enNumber(y.toLong())
    }

    private fun enMoney(i: String, c: String, cur: String): String {
        val name = when {
            Regex("^(€|EUR)$", RegexOption.IGNORE_CASE).matches(cur) -> "euros"
            Regex("^(£|GBP)$", RegexOption.IGNORE_CASE).matches(cur) -> "pounds"
            else -> "dollars"
        }
        val cents = if (c.isNotEmpty() && c.toInt() != 0) " " + enNum(c.padEnd(2, '0')) else ""
        return "${enNum(i.replace(",", ""))} $name$cents"
    }

    private val EN_ABBR = listOf(
        JsRegex.of("\\be\\.\\s?g\\.", ignoreCase = true) to "for example",
        JsRegex.of("\\bi\\.\\s?e\\.", ignoreCase = true) to "that is",
        JsRegex.of("\\betc\\.") to "et cetera",
        JsRegex.of("\\bNo\\.(?=\\s*\\d)") to "number",
        JsRegex.of("\\bapprox\\.") to "approximately",
        JsRegex.of("&") to " and ",
    )
    private val EN_MONTH_NAMES = EN_MONTHS.joinToString("|")
    private val EN_MONTH_DAY = JsRegex.of("\\b($EN_MONTH_NAMES)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s+(\\d{4}))?")
    private val EN_DAY_MONTH = JsRegex.of("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+($EN_MONTH_NAMES)\\b")
    private val EN_TIME_AMPM = JsRegex.of(
        "\\b(1[0-2]|0?\\d):([0-5]\\d)\\s*(am|pm|a\\.m\\.|p\\.m\\.)",
        ignoreCase = true,
    )
    private val EN_TIME = JsRegex.of("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")
    private val EN_CUR_AMOUNT = JsRegex.of("([\$€£])\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?")
    private val EN_AMOUNT_CUR = JsRegex.of("(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?\\s*(EUR|USD|GBP)\\b")
    private val EN_PERCENT = JsRegex.of("(\\d+)(?:\\.(\\d+))?\\s*%")
    private val EN_THOUSANDS = JsRegex.of("\\b(\\d{1,3}(?:,\\d{3})+)\\b")

    private fun oh(mi: Int) = (if (mi < 10) "oh " else "") + enNumber(mi.toLong())

    private fun english(s: String): String {
        var t = s
        for ((re, word) in EN_ABBR) t = re.replace(t) { word }
        t = ISO_DATE.replaceJs(t) { m, g, _ ->
            if (g[2].toInt() in
                1..12
            ) {
                "${EN_MONTHS[g[2].toInt() - 1]} ${enOrdinal(g[3].toInt())}, ${enYear(g[1].toInt())}"
            } else {
                m.value
            }
        }
        t = EN_MONTH_DAY.replaceJs(t) { _, g, _ ->
            "${g[1]} ${enOrdinal(g[2].toInt())}" + (if (g[3].isNotEmpty()) ", " + enYear(g[3].toInt()) else "")
        }
        t = EN_DAY_MONTH.replaceJs(t) { _, g, _ -> "the ${enOrdinal(g[1].toInt())} of ${g[2]}" }
        t = EN_TIME_AMPM.replaceJs(t) { _, g, _ ->
            val mi = g[2].toInt()
            enNumber(g[1].toLong()) + (if (mi != 0) " " + oh(mi) else "") +
                if (g[3].startsWith("a", ignoreCase = true)) " a m" else " p m"
        }
        t = EN_TIME.replaceJs(t) { _, g, _ ->
            val mi = g[2].toInt()
            "${enNumber(g[1].toLong())} " + if (mi != 0) oh(mi) else "hundred"
        }
        t = EN_CUR_AMOUNT.replaceJs(t) { _, g, _ -> enMoney(g[2], g[3], g[1]) }
        t = EN_AMOUNT_CUR.replaceJs(t) { _, g, _ -> enMoney(g[1], g[2], g[3]) }
        t = EN_PERCENT.replaceJs(t) { _, g, _ ->
            (if (g[2].isNotEmpty()) "${enNum(g[1])} point ${enDigits(g[2])}" else enNum(g[1])) + " percent"
        }
        t = EN_THOUSANDS.replaceJs(t) { _, g, _ -> enNum(g[1].replace(",", "")) }
        t = DOTTED.replaceJs(t) { _, g, _ -> "${enNum(g[1])} point ${enDigits(g[2])}" }
        t = DIGITS.replaceJs(t) { m, _, before ->
            val d = m.value
            val word = if (d.length >= 7 || (d.length > 1 && d[0] == '0')) enDigits(d) else enNum(d)
            word + letterGap(before.length + d.length, t)
        }
        return SPACES.replace(t, " ").trim()
    }
}
