package com.hardwareone.console.pipe

import android.content.Context
import com.hardwareone.console.security.SecretBox

/**
 * Persistence for the relay pipe (R11/R12): the dedicated pipe secret and the approved
 * destination, each encrypted under its own non-exportable Keystore key, plus plain flags. No P4
 * address or P4 enrollment marker is kept: proving the secret is the authority, and nothing the
 * phone stores says the P4 committed anything.
 *
 * The keys need no unlocked device or user authentication, so a restart while the phone is locked
 * (after its first unlock) can still read them: deliberate, for unattended recovery (FW-1).
 */
class PipeStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secretBox = SecretBox(appContext, alias = "hw_pipe_key", prefsName = "hw_pipe_key")
    private val configBox = SecretBox(appContext, alias = "hw_pipe_config", prefsName = "hw_pipe_config")
    /** The bonded design's record (address + destination); only its destination is migrated. */
    private val legacyBox = SecretBox(appContext, alias = "hw_pipe_enrollment", prefsName = "hw_pipe_secret")

    /** The user wants the pipe running (survives process death, reboot and updates). */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Wall-clock time of the last authenticated pipe (HELLO_ACK completed locally); 0 = never. */
    var lastAuthenticatedAt: Long
        get() = prefs.getLong(KEY_LAST_AUTH, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTH, value).apply()

    /** Changes whenever the secret is replaced or cleared: a running pipe compares it on reload. */
    val secretVersion: Long get() = prefs.getLong(KEY_SECRET_VERSION, 0L)
    /** Changes whenever the destination is replaced or cleared. */
    val configVersion: Long get() = prefs.getLong(KEY_CONFIG_VERSION, 0L)

    /** The approved destination, migrating a bonded-design record once. */
    fun config(): PipeConfig? {
        configBox.get()?.let { return PipeConfig.fromJson(it) }
        if (!legacyBox.hasCiphertext()) return null
        val migrated = legacyBox.get()?.let { PipeConfig.fromLegacyEnrollment(it) }
        legacyBox.clear()
        migrated?.let { saveConfig(it) }
        return migrated
    }

    fun saveConfig(config: PipeConfig) {
        configBox.put(config.toJson())
        bump(KEY_CONFIG_VERSION)
    }

    /** The normalised secret bytes ([PipeSecret.normalize]); the caller wipes them. */
    fun secret(): ByteArray? = secretBox.getBytes()

    fun hasSecret(): Boolean = secretBox.hasCiphertext()

    fun saveSecret(normalized: ByteArray) {
        secretBox.putBytes(normalized)
        bump(KEY_SECRET_VERSION)
    }

    /** Forget: the secret, the destination and the last-authenticated time (Android state only). */
    fun clear() {
        secretBox.clear()
        configBox.clear()
        legacyBox.clear()
        prefs.edit().remove(KEY_LAST_AUTH).apply()
        bump(KEY_SECRET_VERSION)
        bump(KEY_CONFIG_VERSION)
    }

    private fun bump(key: String) {
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1).apply()
    }

    private companion object {
        const val PREFS = "hw_pipe_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_AUTH = "last_auth_at"
        const val KEY_SECRET_VERSION = "secret_version"
        const val KEY_CONFIG_VERSION = "config_version"
    }
}
