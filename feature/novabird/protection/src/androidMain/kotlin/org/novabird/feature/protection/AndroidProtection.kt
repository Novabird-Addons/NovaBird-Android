package org.novabird.feature.protection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.edit

/** Contacts with email addresses from the device address book; empty without the contacts permission. */
class AndroidContactsSource(private val context: Context) : ContactsSource {
    override fun contacts(): List<DeviceContact> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val byContact = LinkedHashMap<Long, Pair<MutableSet<String>, MutableSet<String>>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val address = cursor.getString(2)?.trim()?.lowercase()
                if (address.isNullOrEmpty()) continue
                val (names, emails) = byContact.getOrPut(cursor.getLong(0)) { mutableSetOf<String>() to mutableSetOf() }
                cursor.getString(1)?.takeIf { it.isNotBlank() }?.let(names::add)
                emails += address
            }
        }
        return byContact.values.map { (names, emails) -> DeviceContact(names.toList(), emails.toList()) }
    }
}

/** [ProtectionSettings] in the shared preferences `novabird_protection`. */
class AndroidProtectionSettings(context: Context) : ProtectionSettings {
    private val preferences = context.getSharedPreferences("novabird_protection", Context.MODE_PRIVATE)

    override val enabled: Boolean get() = preferences.getBoolean(KEY_ENABLED, false)
    override val consentVersion: Int get() = preferences.getInt(KEY_CONSENT_VERSION, 0)
    override val consentAt: Long get() = preferences.getLong(KEY_CONSENT_AT, 0)
    override val trustedSenders: List<String>
        get() = preferences.getStringSet(KEY_TRUSTED, emptySet()).orEmpty().sorted()
    override val historyMonths: Int
        get() = preferences.getInt(KEY_HISTORY_MONTHS, HISTORY_MONTHS_DEFAULT).coerceIn(1, HISTORY_MONTHS_MAX)

    override fun setEnabled(enabled: Boolean, consentVersion: Int, consentAt: Long) {
        preferences.edit {
            putBoolean(KEY_ENABLED, enabled)
            putInt(KEY_CONSENT_VERSION, consentVersion)
            putLong(KEY_CONSENT_AT, consentAt)
        }
    }

    override fun updateTrustedSenders(addresses: List<String>) {
        preferences.edit { putStringSet(KEY_TRUSTED, addresses.map { it.trim().lowercase() }.toSet()) }
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_CONSENT_VERSION = "consent_version"
        const val KEY_CONSENT_AT = "consent_at"
        const val KEY_TRUSTED = "trusted_senders"
        const val KEY_HISTORY_MONTHS = "history_months"
    }
}
