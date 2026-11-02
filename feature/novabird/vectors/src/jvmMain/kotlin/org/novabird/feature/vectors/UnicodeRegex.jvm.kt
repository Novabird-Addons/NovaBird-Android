package org.novabird.feature.vectors

import java.util.regex.Pattern

internal actual fun unicodeRegex(pattern: String): Regex =
    Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS).toRegex()
