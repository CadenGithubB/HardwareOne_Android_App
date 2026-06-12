package com.hardwareone.console.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Metadata for one saved log file. */
data class SavedLog(
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isAuto: Boolean,
)

/**
 * Encrypted storage for console logs, using envelope encryption so that **saving needs no
 * prompt** but **reading requires a biometric / device-credential prompt**.
 *
 * An RSA key pair lives in the Android Keystore (StrongBox when available) with
 * per-use authentication required. Android only gates *private-key* operations, so:
 *  - Save: a fresh random AES-256 key encrypts the log (AES-GCM); that AES key is wrapped
 *    with the RSA **public** key — no authentication needed.
 *  - Open: the wrapped AES key is unwrapped with the RSA **private** key, which the caller
 *    must authorise through BiometricPrompt; then the body is AES-decrypted.
 *
 * Files live in app-private storage (filesDir/logs), additionally protected by the OS's
 * file-based encryption, excluded from backups, and removed on uninstall.
 */
class LogVault(context: Context) {

    private val appContext = context.applicationContext
    private val dir: File by lazy { File(appContext.filesDir, "logs").apply { mkdirs() } }
    private val prefs = appContext.getSharedPreferences("hw_console_prefs", Context.MODE_PRIVATE)

    var autoSave: Boolean
        get() = prefs.getBoolean("log_autosave", false)
        set(value) { prefs.edit().putBoolean("log_autosave", value).apply() }

    fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    fun canAuthenticate(): Boolean =
        BiometricManager.from(appContext)
            .canAuthenticate(allowedAuthenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    /** Absolute on-device path of the (encrypted) saved-log directory, for info display. */
    fun storageLocation(): String = dir.absolutePath

    // --- listing / deletion (no prompt) ---

    fun list(): List<SavedLog> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }
            ?.map {
                SavedLog(
                    fileName = it.name,
                    sizeBytes = it.length(),
                    lastModified = it.lastModified(),
                    isAuto = it.name.endsWith(AUTO_SUFFIX),
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

    fun delete(fileName: String): Boolean = safeFile(fileName)?.delete() ?: false

    // --- save (public-key wrap — no prompt) ---

    /** Encrypt [text] into [fileName]. Returns true on success. */
    fun save(text: String, fileName: String): Boolean = runCatching {
        val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val bodyCipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, aesKey)
        }
        val ciphertext = bodyCipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val iv = bodyCipher.iv

        val wrapCipher = Cipher.getInstance(RSA_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKeyPairPublic())
        }
        val wrappedKey = wrapCipher.doFinal(aesKey.encoded)

        DataOutputStream(File(dir, fileName).outputStream().buffered()).use { out ->
            out.writeInt(FORMAT_VERSION)
            out.writeInt(wrappedKey.size); out.write(wrappedKey)
            out.writeInt(iv.size); out.write(iv)
            out.writeInt(ciphertext.size); out.write(ciphertext)
        }
        true
    }.getOrDefault(false)

    /** Distinct file name for a manual snapshot. */
    fun timestampedFileName(timeMillis: Long): String = "$timeMillis$EXT"

    /** Distinct file name for an auto-capture (tagged so it can be labelled "auto"). */
    fun autoFileName(timeMillis: Long): String = "$timeMillis$AUTO_SUFFIX"

    // --- open (private-key unwrap — requires an authorised RSA cipher) ---

    /** RSA decrypt cipher to authorise via BiometricPrompt, or null if no key exists. */
    fun decryptCipher(): Cipher? {
        val privateKey = runCatching {
            (keyStore().getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.privateKey
        }.getOrNull() ?: return null
        return runCatching {
            Cipher.getInstance(RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, privateKey) }
        }.getOrNull()
    }

    /** With an authorised RSA cipher, unwrap the key and decrypt the file body. */
    fun finishDecrypt(fileName: String, authedRsaCipher: Cipher): String? = runCatching {
        val file = safeFile(fileName) ?: return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            input.readInt() // format version
            val wrapped = ByteArray(input.readInt()).also { input.readFully(it) }
            val iv = ByteArray(input.readInt()).also { input.readFully(it) }
            val ciphertext = ByteArray(input.readInt()).also { input.readFully(it) }

            val aesKeyBytes = authedRsaCipher.doFinal(wrapped)
            val aesKey = SecretKeySpec(aesKeyBytes, "AES")
            val bodyCipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(bodyCipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    }.getOrNull()

    // --- key management ---

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKeyPairPublic(): java.security.PublicKey {
        keyStore().getCertificate(ALIAS)?.publicKey?.let { return it }
        generateKeyPair(strongBox = hasStrongBox())
        return keyStore().getCertificate(ALIAS).publicKey
    }

    private fun hasStrongBox(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private fun generateKeyPair(strongBox: Boolean) {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(2048)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        try {
            generator.initialize(builder.build())
            generator.generateKeyPair()
        } catch (e: Exception) {
            if (strongBox) generateKeyPair(strongBox = false) else throw e
        }
    }

    /** Guard against path traversal: only files directly inside [dir] with our extension. */
    private fun safeFile(fileName: String): File? {
        if (!fileName.endsWith(EXT) || fileName.contains('/') || fileName.contains("..")) return null
        val f = File(dir, fileName)
        return if (f.exists()) f else null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "hw_log_key"
        const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val FORMAT_VERSION = 1
        const val EXT = ".hwlog"
        const val AUTO_SUFFIX = "-auto.hwlog"
    }
}
