package org.novabird.feature.protection

/** A contact of the device address book: display names and email addresses (lower case). */
data class DeviceContact(val names: List<String>, val emails: List<String>)

/** Contacts of the device; empty if the app may not read them. */
fun interface ContactsSource {
    fun contacts(): List<DeviceContact>
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

    /** Addresses the user trusts ("Absender vertrauen"); passed to the add-on as `trustedSenders`. */
    val trustedSenders: List<String>

    /** Months of mail that count for the sender history (add-on: 1..18, default 12). */
    val historyMonths: Int

    fun setEnabled(enabled: Boolean, consentVersion: Int, consentAt: Long)

    fun updateTrustedSenders(addresses: List<String>)
}

/** [ProtectionSettings] in memory (tests, previews). */
class InMemoryProtectionSettings(
    override var enabled: Boolean = false,
    override var consentVersion: Int = 0,
    override var consentAt: Long = 0,
    override var trustedSenders: List<String> = emptyList(),
    override var historyMonths: Int = HISTORY_MONTHS_DEFAULT,
) : ProtectionSettings {
    override fun setEnabled(enabled: Boolean, consentVersion: Int, consentAt: Long) {
        this.enabled = enabled
        this.consentVersion = consentVersion
        this.consentAt = consentAt
    }

    override fun updateTrustedSenders(addresses: List<String>) {
        trustedSenders = addresses
    }
}

/** Default and maximum months of history, as in the add-on's lib/history.js. */
const val HISTORY_MONTHS_DEFAULT = 12
const val HISTORY_MONTHS_MAX = 18
