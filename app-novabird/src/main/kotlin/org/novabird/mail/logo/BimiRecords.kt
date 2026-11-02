package org.novabird.mail.logo

/** Parsing of BIMI DNS records (`default._bimi.<domain>`, RFC draft "brand indicators for message identification"). */
internal object BimiRecords {
    private val DOMAIN = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    // second-level labels of country domains like co.uk, com.au (organizational domain = three labels)
    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "ac", "gov", "edu", "or", "ne", "go")

    /** The domain of [emailAddress] in lower case, or null if it isn't a plain DNS name. */
    fun domainOf(emailAddress: String): String? {
        val domain = emailAddress.substringAfterLast('@', "").trim().trimEnd('.').lowercase()
        return domain.takeIf { it.length <= MAX_DOMAIN && DOMAIN.matches(it) }
    }

    /** The organizational domain (mail.instagram.com → instagram.com), where BIMI looks if the domain has no record. */
    fun organizationalDomain(domain: String): String {
        val labels = domain.split('.')
        val keep = if (labels.size >= 3 && labels.last().length == 2 &&
            labels[labels.size - 2] in SECOND_LEVEL
        ) {
            3
        } else {
            2
        }
        return labels.takeLast(keep).joinToString(".")
    }

    /** The logo URL (`l=`) of a BIMI record, only https; null if the record isn't BIMI1 or has no logo. */
    fun logoUrl(record: String): String? {
        val tags = record.split(';').mapNotNull { part ->
            val key = part.substringBefore('=', "").trim().lowercase()
            if (key.isEmpty()) null else key to part.substringAfter('=').trim()
        }.toMap()
        if (!tags["v"].equals("BIMI1", ignoreCase = true)) return null
        return tags["l"]?.takeIf { it.startsWith("https://", ignoreCase = true) }
    }

    private const val MAX_DOMAIN = 253
}
