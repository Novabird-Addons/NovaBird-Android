package org.novabird.feature.protection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A contact of the device address book: display names and email addresses (lower case). */
data class DeviceContact(val names: List<String>, val emails: List<String>)

/** Contacts of the device; empty if the app may not read them. */
fun interface ContactsSource {
    fun contacts(): List<DeviceContact>
}

/** How a sender got onto the trusted list (add-on 0.9.1: `trustMeta.via`). */
enum class TrustedVia(val id: String) {
    /** "Absender vertrauen" in the warning of a mail. */
    BANNER("banner"),

    /** Typed into the settings. */
    SETTINGS("settings"),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: SETTINGS
    }
}

/**
 * A sender the user trusts: no more warnings for this address, except forgery hints (the add-on keeps DMARC, SPF,
 * DKIM, look-alike domains, disguised attachments and extortion).
 *
 * @property since when it was trusted (epoch millis).
 * @property score score and [level] of the mail it was trusted from, `null` if typed in by hand.
 * @property reasons the warning's reasons at that time, for the list in the settings.
 */
data class TrustedSender(
    val address: String,
    val since: Long,
    val via: TrustedVia,
    val score: Int? = null,
    val level: ProtectionLevel? = null,
    val reasons: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put("address", JsonPrimitive(address))
            put("since", JsonPrimitive(since))
            put("via", JsonPrimitive(via.id))
            score?.let { put("score", JsonPrimitive(it)) }
            level?.let { put("level", JsonPrimitive(it.id)) }
            if (reasons.isNotEmpty()) put("reasons", JsonArray(reasons.map(::JsonPrimitive)))
        },
    )

    companion object {
        fun fromJson(json: JsonObject): TrustedSender? {
            val address = json["address"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            if (address.isNullOrEmpty()) return null
            return TrustedSender(
                address = address,
                since = json["since"]?.jsonPrimitive?.longOrNull ?: 0L,
                via = TrustedVia.fromId(json["via"]?.jsonPrimitive?.contentOrNull),
                score = json["score"]?.jsonPrimitive?.intOrNull,
                level = json["level"]?.jsonPrimitive?.contentOrNull?.let(ProtectionLevel::fromId),
                reasons = json["reasons"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
        }

        fun listToJson(list: List<TrustedSender>): String = JsonArray(list.map { it.toJson() }).toString()

        fun listFromJson(text: String?): List<TrustedSender> {
            if (text.isNullOrBlank()) return emptyList()
            return runCatching {
                Json.parseToJsonElement(text).jsonArray.mapNotNull { fromJson(it.jsonObject) }
            }.getOrDefault(emptyList())
        }
    }
}

/**
 * Settings of the phishing protection (`novabird_protection`).
 *
 * Nothing is checked while [enabled] is false. It may only be switched on after the user accepted the notice
 * ([consentVersion] is the version of the notice text they accepted, `0` = never).
 */
interface ProtectionSettings {
    val enabled: Boolean
    val consentVersion: Int
    val consentAt: Long

    /** Senders the user trusts ("Absender vertrauen"); their addresses go to the add-on as `trustedSenders`. */
    val trustedSenders: List<TrustedSender>

    /** Months of mail that count for the sender history (add-on: 1..18, default 12). */
    val historyMonths: Int

    fun setEnabled(enabled: Boolean, consentVersion: Int, consentAt: Long)

    fun updateTrustedSenders(senders: List<TrustedSender>)
}

/** [ProtectionSettings] in memory (tests, previews). */
class InMemoryProtectionSettings(
    override var enabled: Boolean = false,
    override var consentVersion: Int = 0,
    override var consentAt: Long = 0,
    override var trustedSenders: List<TrustedSender> = emptyList(),
    override var historyMonths: Int = HISTORY_MONTHS_DEFAULT,
) : ProtectionSettings {
    override fun setEnabled(enabled: Boolean, consentVersion: Int, consentAt: Long) {
        this.enabled = enabled
        this.consentVersion = consentVersion
        this.consentAt = consentAt
    }

    override fun updateTrustedSenders(senders: List<TrustedSender>) {
        trustedSenders = senders
    }
}

/** Default and maximum months of history, as in the add-on's lib/history.js. */
const val HISTORY_MONTHS_DEFAULT = 12
const val HISTORY_MONTHS_MAX = 18

/** Version of the notice the user must accept before switching the protection on (bump when the text changes). */
const val CONSENT_VERSION = 1
