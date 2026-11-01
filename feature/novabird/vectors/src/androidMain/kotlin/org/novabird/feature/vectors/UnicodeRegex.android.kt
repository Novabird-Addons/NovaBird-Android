package org.novabird.feature.vectors

// Android's regex engine (ICU) already treats \s as Unicode whitespace
internal actual fun unicodeRegex(pattern: String): Regex = Regex(pattern)
