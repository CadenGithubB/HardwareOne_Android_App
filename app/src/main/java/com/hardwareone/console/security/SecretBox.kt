package com.hardwareone.console.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encrypted storage for one small secret (the secure-channel passphrase), using a
 * **non-auth** AES-256-GCM Keystore key (StrongBox/TEE). No biometric prompt — the app must
 * read it automatically at connect time — but the key is non-exportable and only the
 * ciphertext lands in app-private prefs (plus the OS file-based encryption at rest).
 */
class SecretBox(context: Context, private val alias: String, prefsName: String) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun has(): Boolean = prefs.contains(KEY_CT) && keyExists()

    /** A ciphertext blob is stored, regardless of whether it can still be decrypted. */
    fun hasCiphertext(): Boolean = prefs.contains(KEY_CT)

    fun put(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun get(): String? {
        val ct = prefs.getString(KEY_CT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: run { Log.w(TAG, "get[$alias]: no ciphertext in prefs"); return null }
        val iv = prefs.getString(KEY_IV, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: run { Log.w(TAG, "get[$alias]: no iv in prefs"); return null }
        val key = existingKey()
            ?: run { Log.w(TAG, "get[$alias]: keystore key missing/unreadable"); return null }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "get[$alias]: decrypt failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_CT).remove(KEY_IV).apply()
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private fun keyExists(): Boolean = runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)
    private fun existingKey(): SecretKey? =
        try {
            (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (e: Exception) {
            Log.w(TAG, "existingKey[$alias]: ${e.javaClass.simpleName}: ${e.message}")
            null
        }

    private fun getOrCreateKey(): SecretKey = existingKey() ?: generateKey(strongBox = hasStrongBox())

    private fun hasStrongBox(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private fun generateKey(strongBox: Boolean): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(true)
        return try {
            gen.init(builder.build()); gen.generateKey().also {
                Log.i(TAG, "generateKey[$alias]: created (strongBox=$strongBox)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "generateKey[$alias]: strongBox=$strongBox failed: ${e.javaClass.simpleName}: ${e.message}")
            if (strongBox) generateKey(strongBox = false) else throw e
        }
    }

    private companion object {
        const val TAG = "SecretBox"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_CT = "secret_ct"
        const val KEY_IV = "secret_iv"
    }
}
