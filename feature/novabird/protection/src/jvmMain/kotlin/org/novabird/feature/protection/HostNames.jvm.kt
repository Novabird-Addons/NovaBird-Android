package org.novabird.feature.protection

import java.net.IDN

internal actual object HostNames {
    actual fun toAscii(host: String): String? {
        val lower = host.trim().lowercase()
        if (lower.startsWith("[")) return lower
        return try {
            IDN.toASCII(lower, IDN.ALLOW_UNASSIGNED).lowercase().takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
