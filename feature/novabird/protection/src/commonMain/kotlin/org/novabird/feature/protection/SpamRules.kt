package org.novabird.feature.protection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Settings of the spam detection. It uses the phishing check's result, so it only runs while the protection is on
 * (one notice for both).
 *
 * @property serverFlag take over the server's spam mark (`X-Spam-Flag`, `X-Spam-Status`).
 * @property authFail mails from unknown senders that fail the own server's sender check (DMARC, SPF) are spam.
 * @property phishing mails rated "dangerous" also go to the spam folder (default off: better a banner than hiding).
 * @property blocked addresses or domains whose mails are spam; [allowed] ones never are.
 */
data class SpamSettings(
    val enabled: Boolean = false,
    val serverFlag: Boolean = true,
    val authFail: Boolean = true,
    val phishing: Boolean = false,
    val blocked: List<String> = emptyList(),
    val allowed: List<String> = emptyList(),
) {
    fun toJson(): String = JsonObject(
        mapOf(
            "enabled" to JsonPrimitive(enabled),
            "serverFlag" to JsonPrimitive(serverFlag),
            "authFail" to JsonPrimitive(authFail),
            "phishing" to JsonPrimitive(phishing),
            "blocked" to JsonArray(blocked.map(::JsonPrimitive)),
            "allowed" to JsonArray(allowed.map(::JsonPrimitive)),
        ),
    ).toString()

    companion object {
        fun fromJson(text: String?): SpamSettings {
            if (text.isNullOrBlank()) return SpamSettings()
            return runCatching {
                val json = Json.parseToJsonElement(text).jsonObject
                fun flag(name: String, default: Boolean) = json[name]?.jsonPrimitive?.booleanOrNull ?: default
                fun list(name: String) = json[name]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                SpamSettings(
                    enabled = flag("enabled", false),
                    serverFlag = flag("serverFlag", true),
                    authFail = flag("authFail", true),
                    phishing = flag("phishing", false),
                    blocked = list("blocked"),
                    allowed = list("allowed"),
                )
            }.getOrDefault(SpamSettings())
        }
    }
}

/** Why a mail counts as spam; the first rule that applies decides (plan section 6). */
enum class SpamReason(val id: String) {
    /** Sender or domain on the blocked list; detail = the entry. */
    BLOCKED("blocked"),

    /** The server marked it as spam. */
    SERVER("server"),

    /** Unknown sender, the own server's sender check failed; detail = `dmarc` or `spf`. */
    AUTH("auth"),

    /** Rated "dangerous" by the phishing check. */
    PHISHING("phishing"),

    /** Signs of money spam (random domain, investment bait, filler text …); detail = the signal ids. */
    MONEY("money"),
    ;

    companion object {
        fun fromId(id: String?): SpamReason? = entries.firstOrNull { it.id == id }
    }
}

data class SpamDecision(val reason: SpamReason, val detail: String = "")

/** Moves a mail of the K-9 database (folder id and server uid); `false` if the account has no such folder. */
interface SpamMover {
    suspend fun moveToSpam(accountUuid: String, folderId: Long, uid: String): Boolean

    suspend fun moveToInbox(accountUuid: String, folderId: Long, uid: String): Boolean
}

/** What the message list shows for one mail: the phishing level and whether it counts as spam. */
data class ListMark(val level: ProtectionLevel, val spam: Boolean)

/** The spam rules: fixed, no learning. */
object SpamRules {
    /** Money-spam signals of the add-on with their weights (`lib/config.js`); spam from two of them and [MONEY_THRESHOLD]. */
    val MONEY_WEIGHTS = mapOf(
        "randomDomain" to 20,
        "investmentPromo" to 25,
        "fillerText" to 20,
        "invisiblePadding" to 8,
        "imageOnly" to 8,
        "badTld" to 8,
    )
    const val MONEY_THRESHOLD = 35

    private val ADDRESS = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    private val DOMAIN = Regex("""^[\p{L}\p{N}-]+(\.[\p{L}\p{N}-]+)+$""")

    /**
     * An entry for the blocked or allowed list from what the user typed: an address, or a domain (a leading `@` is
     * dropped). `null` if it is neither.
     */
    fun normalizeEntry(text: String): String? {
        val entry = text.trim().lowercase().removePrefix("@")
        return entry.takeIf { ADDRESS.matches(it) || DOMAIN.matches(it) }
    }

    /** Does list [entry] cover [address]: the same address, or the domain (with its subdomains)? */
    fun matches(entry: String, address: String): Boolean {
        val a = address.lowercase()
        if ('@' in entry) return a == entry
        val domain = a.substringAfterLast('@', "")
        return domain == entry || domain.endsWith(".$entry")
    }

    /**
     * The spam decision for one mail, or `null`.
     *
     * @param known the sender is a device contact or someone the user wrote to.
     * @param trusted the sender is on the phishing protection's trusted list.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun decide(
        sender: String,
        headers: Map<String, List<String>>,
        verdict: Verdict,
        known: Boolean,
        trusted: Boolean,
        settings: SpamSettings,
    ): SpamDecision? {
        if (!settings.enabled) return null
        val address = sender.lowercase()
        // 1. never spam
        if (known || trusted || (address.isNotEmpty() && settings.allowed.any { matches(it, address) })) return null
        // 2. blocked
        if (address.isNotEmpty()) {
            settings.blocked.firstOrNull { matches(it, address) }?.let { return SpamDecision(SpamReason.BLOCKED, it) }
        }
        // 3. marked by the server
        if (settings.serverFlag && serverMarked(headers)) return SpamDecision(SpamReason.SERVER)
        // 4. sender check failed (DMARC, or a hard SPF fail; DKIM alone breaks too easily on forwarding)
        if (settings.authFail) {
            if (verdict.signals.any { it.id == "dmarcFail" }) return SpamDecision(SpamReason.AUTH, "dmarc")
            if (verdict.signals.any { it.id == "spfFail" && it.params.firstOrNull() == "fail" }) {
                return SpamDecision(SpamReason.AUTH, "spf")
            }
        }
        // 5. phishing
        if (settings.phishing && verdict.level == ProtectionLevel.DANGEROUS) return SpamDecision(SpamReason.PHISHING)
        // 6. money spam
        val money = verdict.signals.map { it.id }.filter { it in MONEY_WEIGHTS }.distinct()
        if (money.size >= 2 && money.sumOf { MONEY_WEIGHTS.getValue(it) } >= MONEY_THRESHOLD) {
            return SpamDecision(SpamReason.MONEY, money.joinToString(","))
        }
        return null
    }

    /** `X-Spam-Flag: YES` or `X-Spam-Status: Yes, …` (SpamAssassin and compatible filters). */
    fun serverMarked(headers: Map<String, List<String>>): Boolean =
        headers["x-spam-flag"].orEmpty().any { it.trim().equals("yes", ignoreCase = true) } ||
            headers["x-spam-status"].orEmpty().any { it.trim().startsWith("yes", ignoreCase = true) }
}
