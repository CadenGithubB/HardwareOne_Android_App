package com.hardwareone.console.bridge

import android.content.Context
import com.hardwareone.console.security.SecretBox

/**
 * Persistence for the bridge: non-secret settings in private prefs, the bridge CA's private key
 * in a Keystore-encrypted [SecretBox]. The home relay CA and the bridge CA certificate are public.
 */
class BridgeStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val caKeyBox = SecretBox(appContext, alias = "hw_bridge_ca_key", prefsName = "hw_bridge_secret")

    /** The user wants the bridge running (survives process death; the service honours it on restart). */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var relayHost: String
        get() = prefs.getString(KEY_RELAY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RELAY_HOST, value.trim()).apply()

    var relayPort: Int
        get() = prefs.getInt(KEY_RELAY_PORT, BridgeConfig.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_RELAY_PORT, value).apply()

    var listenPort: Int
        get() = prefs.getInt(KEY_LISTEN_PORT, BridgeConfig.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_LISTEN_PORT, value).apply()

    var listenAddressOverride: String?
        get() = prefs.getString(KEY_LISTEN_OVERRIDE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_LISTEN_OVERRIDE, value?.trim()?.takeIf { it.isNotEmpty() }).apply()

    /** The home relay's public CA (ca-cert.pem), as imported. */
    var relayCaPem: String?
        get() = prefs.getString(KEY_RELAY_CA, null)
        set(value) = prefs.edit().putString(KEY_RELAY_CA, value).apply()

    /** The device URL the user last pushed/acknowledged, to flag when the hotspot address moves. */
    var configuredDeviceUrl: String?
        get() = prefs.getString(KEY_CONFIGURED_URL, null)
        set(value) = prefs.edit().putString(KEY_CONFIGURED_URL, value).apply()

    fun config(): BridgeConfig? {
        val ca = relayCaPem ?: return null
        if (relayHost.isBlank()) return null
        return BridgeConfig(relayHost, relayPort, ca, listenPort, listenAddressOverride)
    }

    /** The bridge CA, created on first use. Returns null only if the Keystore is unusable. */
    fun loadOrCreateCa(): BridgeCa? {
        val pem = prefs.getString(KEY_BRIDGE_CA, null)
        if (pem != null) {
            val key = caKeyBox.get()
            if (key != null) {
                runCatching { BridgeCa.restore(pem, key) }.getOrNull()?.let { return it }
            }
            // Stored pair is unusable (e.g. Keystore wiped by a reinstall): start over, visibly.
        }
        return regenerateCa()
    }

    /** New CA: the device must be given the new certificate again. */
    fun regenerateCa(): BridgeCa? = runCatching {
        val ca = BridgeCa.generate()
        caKeyBox.put(ca.privateKeyPkcs8Base64())
        prefs.edit().putString(KEY_BRIDGE_CA, ca.certificatePem).remove(KEY_CONFIGURED_URL).apply()
        ca
    }.getOrNull()

    fun hasBridgeCa(): Boolean = prefs.contains(KEY_BRIDGE_CA) && caKeyBox.has()

    private companion object {
        const val PREFS = "hw_bridge_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_RELAY_HOST = "relay_host"
        const val KEY_RELAY_PORT = "relay_port"
        const val KEY_LISTEN_PORT = "listen_port"
        const val KEY_LISTEN_OVERRIDE = "listen_override"
        const val KEY_RELAY_CA = "relay_ca_pem"
        const val KEY_BRIDGE_CA = "bridge_ca_pem"
        const val KEY_CONFIGURED_URL = "configured_device_url"
    }
}
