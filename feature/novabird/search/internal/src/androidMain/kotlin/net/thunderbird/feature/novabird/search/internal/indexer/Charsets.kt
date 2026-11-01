package net.thunderbird.feature.novabird.search.internal.indexer

import java.nio.charset.Charset

internal actual fun decodeCharset(bytes: ByteArray, charset: String): String {
    val cs = runCatching { Charset.forName(charset) }.getOrNull() ?: Charsets.UTF_8
    return String(bytes, cs)
}
