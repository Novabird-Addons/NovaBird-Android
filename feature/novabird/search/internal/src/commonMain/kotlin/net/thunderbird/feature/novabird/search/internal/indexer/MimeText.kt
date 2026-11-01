package net.thunderbird.feature.novabird.search.internal.indexer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Decodes [bytes] in the charset [charset]; unknown charsets fall back to UTF-8. Never throws. */
internal expect fun decodeCharset(bytes: ByteArray, charset: String): String

/**
 * Small MIME helpers for the indexer: K-9 stores body parts still transfer-encoded (`message_parts.encoding`) and the
 * raw part header (`message_parts.header`).
 *
 * Decoding follows the rules of the PC (jsmime / WHATWG) where they differ from K-9: `iso-8859-1` and `us-ascii` are
 * read as `windows-1252`, and `format=flowed` text is not joined.
 */
internal object MimeText {

    /** Body text of a part: transfer decoding, then charset. */
    fun decodeBody(data: ByteArray, transferEncoding: String?, charset: String?): String {
        val bytes = decodeTransfer(data, transferEncoding)
        return decodeCharset(bytes, whatwgCharset(charset))
    }

    fun decodeTransfer(data: ByteArray, transferEncoding: String?): ByteArray {
        return when (transferEncoding?.trim()?.lowercase()) {
            "base64" -> decodeBase64(data)
            "quoted-printable" -> decodeQuotedPrintable(data)
            else -> data
        }
    }

    /** WHATWG Encoding Standard labels that browsers (and jsmime) treat as windows-1252. */
    fun whatwgCharset(charset: String?): String {
        val name = charset?.trim()?.trim('"')?.lowercase().orEmpty()
        return when (name) {
            "", "us-ascii", "ascii", "iso-8859-1", "iso8859-1", "latin1", "l1", "iso_8859-1", "cp819", "ibm819",
            "windows-1252", "cp1252", "x-cp1252",
            -> "windows-1252"

            else -> name
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64(data: ByteArray): ByteArray {
        val clean = ByteArray(data.size)
        var n = 0
        for (b in data) {
            val c = b.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/') clean[n++] = b
        }
        // ignore padding and incomplete trailing quantum, like lenient MIME decoders do
        val usable = n - n % 4 + if (n % 4 >= 2) n % 4 else 0
        return runCatching {
            Base64.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(clean, 0, usable)
        }.getOrDefault(ByteArray(0))
    }

    private fun decodeQuotedPrintable(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var n = 0
        var i = 0
        while (i < data.size) {
            val b = data[i]
            if (b == '='.code.toByte()) {
                // soft line break "=\r\n" or "=\n"
                if (i + 1 < data.size && data[i + 1] == '\n'.code.toByte()) {
                    i += 2
                    continue
                }
                if (i + 2 < data.size && data[i + 1] == '\r'.code.toByte() && data[i + 2] == '\n'.code.toByte()) {
                    i += 3
                    continue
                }
                val hi = if (i + 1 < data.size) hexValue(data[i + 1]) else -1
                val lo = if (i + 2 < data.size) hexValue(data[i + 2]) else -1
                if (hi >= 0 && lo >= 0) {
                    out[n++] = (hi * 16 + lo).toByte()
                    i += 3
                    continue
                }
            }
            out[n++] = b
            i++
        }
        return out.copyOf(n)
    }

    private fun hexValue(b: Byte): Int = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }

    // ------------------------------------------------------------ headers

    /** Parses a raw header block into lower-case name to values (unfolded, in order). */
    fun parseHeaders(raw: String?): Map<String, List<String>> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val unfolded = raw.replace(Regex("\r?\n[ \t]+"), " ")
        val headers = LinkedHashMap<String, MutableList<String>>()
        for (line in unfolded.split(Regex("\r?\n"))) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers.getOrPut(line.substring(0, colon).trim().lowercase()) { mutableListOf() } +=
                line.substring(colon + 1).trim()
        }
        return headers
    }

    /** Parameters of a structured header value (`attachment; filename="a.pdf"`), RFC 2231 and RFC 2047 decoded. */
    fun parameters(value: String?): Map<String, String> {
        if (value.isNullOrEmpty()) return emptyMap()
        val params = LinkedHashMap<String, String>()
        val continuations = LinkedHashMap<String, String>()
        for ((name, raw) in Regex(""";\s*([^=;\s]+)\s*=\s*("(?:[^"\\]|\\.)*"|[^;]*)""").findAll(value).map {
            it.groupValues[1].lowercase() to it.groupValues[2].trim()
        }) {
            val unquoted = if (raw.startsWith(
                    '"',
                )
            ) {
                raw.substring(1, raw.length - 1).replace(Regex("""\\(.)"""), "$1")
            } else {
                raw
            }
            val continuation = Regex("""^(.+)\*(\d+)\*?$""").find(name)
            when {
                continuation != null -> {
                    val key = continuation.groupValues[1] + "*" + continuation.groupValues[2].padStart(3, '0')
                    continuations[key] = if (name.endsWith("*")) "*$unquoted" else unquoted
                }

                name.endsWith("*") -> params[name.dropLast(1)] = decodeRfc2231(unquoted)

                else -> params.putIfAbsent(name, decodeEncodedWords(unquoted))
            }
        }
        continuations.entries.sortedBy { it.key }.groupBy { it.key.substringBefore('*') }.forEach { (name, parts) ->
            val extended = parts.first().value.startsWith("*")
            val joined = parts.joinToString("") { it.value.removePrefix("*") }
            params[name] = if (extended) decodeRfc2231(joined) else decodeEncodedWords(joined)
        }
        return params
    }

    /** `utf-8'de'%C3%BC.pdf` -> `ü.pdf` */
    private fun decodeRfc2231(value: String): String {
        val parts = value.split('\'', limit = 3)
        if (parts.size != 3) return percentDecode(value, "utf-8")
        return percentDecode(parts[2], parts[0].ifEmpty { "utf-8" })
    }

    private fun percentDecode(value: String, charset: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 <= value.lastIndex &&
                hexValue(value[i + 1].code.toByte()) >= 0 && hexValue(value[i + 2].code.toByte()) >= 0
            ) {
                bytes += (hexValue(value[i + 1].code.toByte()) * 16 + hexValue(value[i + 2].code.toByte())).toByte()
                i += 3
            } else {
                c.toString().encodeToByteArray().forEach { bytes += it }
                i++
            }
        }
        return decodeCharset(bytes.toByteArray(), whatwgCharset(charset))
    }

    private val ENCODED_WORD = Regex("""=\?([^?]+)\?([bBqQ])\?([^?]*)\?=""")

    /** RFC 2047 encoded words (`=?utf-8?Q?Gr=C3=BC=C3=9Fe?=`); whitespace between encoded words is dropped. */
    @OptIn(ExperimentalEncodingApi::class)
    fun decodeEncodedWords(value: String): String {
        if (!value.contains("=?")) return value
        val joined = value.replace(Regex("""(\?=)\s+(=\?)"""), "$1$2")
        return ENCODED_WORD.replace(joined) { m ->
            val charset = whatwgCharset(m.groupValues[1].substringBefore('*'))
            val text = m.groupValues[3]
            val bytes = if (m.groupValues[2].equals("b", ignoreCase = true)) {
                decodeBase64(text.encodeToByteArray())
            } else {
                decodeQuotedPrintable(text.replace('_', ' ').encodeToByteArray())
            }
            decodeCharset(bytes, charset)
        }
    }

    /** Message-IDs in References / In-Reply-To values, without angle brackets. */
    fun messageIds(values: List<String>): List<String> =
        values.flatMap { v -> Regex("<([^<>\\s]+)>").findAll(v).map { it.groupValues[1] }.toList() }
}
