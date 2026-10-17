package com.hardwareone.console

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deletes what the Home AI bridge (removed in 2.7.1) left on an upgraded phone: its settings,
 * its CA private key's ciphertext and Keystore key, and its notification channel. Nothing reads
 * them any more. Idempotent and cheap, so it simply runs once per process, off the main thread.
 */
object RemovedFeatureCleanup {
    private const val TAG = "RemovedFeatureCleanup"
    private const val BRIDGE_PREFS = "hw_bridge_prefs"
    private const val BRIDGE_SECRET_PREFS = "hw_bridge_secret"
    private const val BRIDGE_CA_KEY_ALIAS = "hw_bridge_ca_key"
    private const val BRIDGE_CHANNEL = "home_bridge"

    private val started = AtomicBoolean(false)

    fun run(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            runCatching {
                app.deleteSharedPreferences(BRIDGE_PREFS)
                app.deleteSharedPreferences(BRIDGE_SECRET_PREFS)
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (keyStore.containsAlias(BRIDGE_CA_KEY_ALIAS)) keyStore.deleteEntry(BRIDGE_CA_KEY_ALIAS)
                app.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(BRIDGE_CHANNEL)
            }.onFailure { Log.w(TAG, "bridge cleanup failed: ${it.javaClass.simpleName}") }
        }, "hw1-cleanup").start()
    }
}
