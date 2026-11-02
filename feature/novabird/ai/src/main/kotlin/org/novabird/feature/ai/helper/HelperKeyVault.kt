package org.novabird.feature.ai.helper

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the Helper key (derived from the pairing code) encrypted with a key in the Android Keystore.
 * The pairing code itself is never stored.
 */
class HelperKeyVault(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cached: ByteArray? = null

    fun hasKey(): Boolean = preferences.contains(KEY_WRAPPED)

    @Synchronized
    fun load(): ByteArray? = cached ?: preferences.getString(KEY_WRAPPED, null)?.let { stored ->
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        }.getOrNull()?.also { cached = it }
    }

    @Synchronized
    fun save(key: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val wrapped = cipher.iv + cipher.doFinal(key)
        preferences.edit { putString(KEY_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP)) }
        cached = key.copyOf()
    }

    @Synchronized
    fun clear() {
        preferences.edit { remove(KEY_WRAPPED) }
        cached = null
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "novabird_ai_helper"
        const val KEY_WRAPPED = "wrappedKey"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "novabird_helper_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
        const val KEY_BITS = 256
    }
}
