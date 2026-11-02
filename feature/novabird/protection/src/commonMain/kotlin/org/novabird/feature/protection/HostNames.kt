package org.novabird.feature.protection

/** Host names as a browser's `URL` gives them: lower case, international names in Punycode. */
internal expect object HostNames {
    /** [host] in ASCII form, or `null` if it is not a valid host name. IPv6 literals in brackets stay as they are. */
    fun toAscii(host: String): String?
}
