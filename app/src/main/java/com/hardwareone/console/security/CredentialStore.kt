package com.hardwareone.console.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the BLE login credentials with hardware-backed, authentication-gated encryption.
 *
 * The password is encrypted with an AES-256-GCM key kept in the Android Keystore —
 * StrongBox (e.g. the Pixel Titan M2) when the device has it, otherwise the TEE. The key
 * is created with [KeyGenParameterSpec.Builder.setUserAuthenticationRequired], so every
 * encrypt/decrypt must be authorised through [androidx.biometric.BiometricPrompt] with a
 * biometric **or** the device credential (PIN/pattern/password). Only the ciphertext, its
 * IV, and the (low-sensitivity) username are persisted, in app-private storage.
 *
 * NOTE: this protects the at-rest copy on the phone only. The BLE link is currently
 * unencrypted, so the credential is still sent in cleartext over the air on each login.
 *
 * Crypto here is pure (no UI). The caller wraps [encryptCipher]/[decryptCipher] in a
 * BiometricPrompt CryptoObject, then hands the authorised cipher to [saveCredentials] /
 * [readPassword].
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var autoLogin: Boolean
        get() = prefs.getBoolean(KEY_AUTOLOGIN, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTOLOGIN, value).apply() }

    val savedUsername: String?
        get() = prefs.getString(KEY_USERNAME, null)

    fun hasStoredPassword(): Boolean =
        prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV) && keyExists()

    /** The authenticators a prompt may use: biometric, plus device credential on API 30+. */
    fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    /** Whether the device can satisfy such a prompt (a credential/biometric is enrolled). */
    fun canAuthenticate(): Boolean =
        BiometricManager.from(appContext)
            .canAuthenticate(allowedAuthenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    // --- cipher factories (run through a BiometricPrompt CryptoObject before use) ---

    fun encryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }

    /** Returns null if nothing is stored or the key was invalidated (then it self-clears). */
    fun decryptCipher(): Cipher? {
        val iv = prefs.getString(KEY_IV, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val key = existingKey() ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Biometrics/credential changed since the key was created — drop the stale data.
            clear()
            null
        }
    }

    /** Encrypt + persist using a cipher already authorised by BiometricPrompt. */
    fun saveCredentials(authedCipher: Cipher, username: String, password: String) {
        val ciphertext = authedCipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(authedCipher.iv, Base64.NO_WRAP))
            .apply()
    }

    /** Decrypt the stored password using a cipher already authorised by BiometricPrompt. */
    fun readPassword(authedCipher: Cipher): String? {
        val ct = prefs.getString(KEY_CIPHERTEXT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return String(authedCipher.doFinal(ct), Charsets.UTF_8)
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_USERNAME)
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .remove(KEY_AUTOLOGIN)
            .apply()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    // --- key management ---

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun keyExists(): Boolean = runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    private fun existingKey(): SecretKey? = runCatching {
        (keyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey = existingKey() ?: generateKey(strongBox = hasStrongBox())

    private fun hasStrongBox(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private fun generateKey(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 0 = authenticate on every use; allow biometric or device credential.
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return try {
            generator.init(builder.build())
            generator.generateKey()
        } catch (e: Exception) {
            // Some devices advertise StrongBox but fail to provision — fall back to the TEE.
            if (strongBox) generateKey(strongBox = false) else throw e
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "hw_credential_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PREFS = "hw_secure_prefs"
        const val KEY_USERNAME = "cred_username"
        const val KEY_CIPHERTEXT = "cred_ciphertext"
        const val KEY_IV = "cred_iv"
        const val KEY_AUTOLOGIN = "auto_login"
    }
}
