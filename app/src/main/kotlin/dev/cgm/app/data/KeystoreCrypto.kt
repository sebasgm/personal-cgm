package dev.cgm.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM backed by the Android Keystore.
 *
 * Written by hand rather than using Jetpack Security: androidx.security-crypto
 * (EncryptedSharedPreferences) was deprecated in April 2025, and the migration
 * Google points at is exactly this — Keystore-held key, ciphertext stored in
 * ordinary preferences or DataStore.
 *
 * The key never leaves the Keystore, so a stolen backup or an adb pull of the
 * app's files yields ciphertext only.
 */
internal object KeystoreCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "personal-cgm.credentials.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(plaintext.toByteArray())
        // Prepend the IV; GCM needs a unique one per message and it is not secret.
        val packed = cipher.iv + ciphertext
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    /** @return the plaintext, or null if the blob is corrupt or the key is gone. */
    fun decrypt(encoded: String): String? = try {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_LENGTH_BITS, packed, 0, IV_LENGTH),
            )
        }
        String(cipher.doFinal(packed, IV_LENGTH, packed.size - IV_LENGTH))
    } catch (e: Exception) {
        // A wiped Keystore (factory reset, some OEM backup restores) invalidates
        // the key. Treating that as "no stored credential" sends the user back
        // through setup, which beats crashing on every launch.
        null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // Deliberately not setUserAuthenticationRequired: the polling
                    // service must decrypt while the device is locked.
                    .build()
            )
        }.generateKey()
    }
}
