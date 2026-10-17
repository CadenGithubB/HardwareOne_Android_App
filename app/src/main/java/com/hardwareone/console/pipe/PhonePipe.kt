package com.hardwareone.console.pipe

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.util.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide view of the BLE relay pipe and the handle the UI uses to control it. The
 * [PhonePipeService] owns the running pipe and publishes here; screens only observe and send
 * intents. Secrets never travel in intents: the UI stores them through [PipeStore] and asks a
 * running pipe to reload. The destination appears only in [UiState.configured] for the pipe page
 * (never in the notification or logs).
 */
object PhonePipe {
    /** What is stored. [target] is the display form of the approved destination. */
    data class Configured(val target: String?, val hasSecret: Boolean, val lastAuthenticatedAt: Long) {
        val complete: Boolean get() = target != null && hasSecret
    }

    data class UiState(
        val running: Boolean = false,
        val bluetoothOn: Boolean? = null,
        val serverOpen: Boolean = false,
        val advertising: Boolean = false,
        val advertiseError: String? = null,
        val configured: Configured? = null,
        /** A Bluetooth prerequisite (Nearby devices) currently stops the BLE service; the pipe stays enabled. */
        val paused: String? = null,
        /** null = not tracking (service stopped). */
        val vpnAvailable: Boolean? = null,
        /** The GrapheneOS Network permission; without it every relay connection is refused. */
        val networkPermission: Boolean? = null,
        val coolingDownSeconds: Int = 0,
        val stats: PipeStats = PipeStats(),
        /** Why the pipe is not running when the user expected it to be. */
        val error: String? = null,
        /** Battery "Unrestricted" (ignoring battery optimisations); null = unknown. */
        val batteryUnrestricted: Boolean? = null,
        /** Battery "Restricted": Android stops the pipe in the background. */
        val backgroundRestricted: Boolean? = null,
        /** A passphrase is stored but cannot be read (e.g. its Keystore key is gone): enter it again. */
        val secretUnreadable: Boolean = false,
        /** GATT callbacks dropped because they reached a retired or replaced server instance. */
        val droppedCallbacks: Long = 0,
    ) {
        val isConfigured: Boolean get() = configured?.complete == true
    }

    /**
     * Every pipe store write, from the UI and the service, runs here: one owner, so a Forget and a
     * save never interleave inside the Keystore, and a Keystore failure becomes an error, not a crash.
     */
    private val storeIo = Executors.newSingleThreadExecutor { r -> Thread(r, "hw1-pipe-store").apply { isDaemon = true } }

    internal fun storeWrite(block: () -> Unit) {
        storeIo.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.w("PhonePipe", "pipe store write failed: ${e.javaClass.simpleName}")
                update { it.copy(error = "Could not save the pipe settings on this phone. Try again.") }
            }
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    internal fun update(block: (UiState) -> UiState) = _state.update(block)

    /**
     * Refreshes what is stored while the service is not running (fresh process, after a reboot), and
     * restarts a pipe the user left enabled (FW-1: opening the app needs no Start tap). Keystore work
     * happens off the main thread.
     */
    fun load(context: Context) {
        val app = context.applicationContext
        refreshBattery(app)
        if (state.value.running) return
        storeIo.execute {
            val store = PipeStore(app)
            val view = runCatching { configuredView(store) }.getOrNull()
            val unreadable = view?.hasSecret == false && store.hasSecret()
            update { if (it.running) it else it.copy(configured = view, secretUnreadable = unreadable) }
            if (store.enabled && view?.complete == true && missingBlePrerequisite(app) == null) {
                // BOOT, not START: the service re-checks `enabled`, so a racing STOP is never undone.
                startForeground(app, Intent(app, PhonePipeService::class.java).setAction(PhonePipeService.ACTION_BOOT))
            }
        }
    }

    /** What is stored; a secret counts only if it can still be read. Keystore work: not on the main thread. */
    internal fun configuredView(store: PipeStore): Configured? {
        val target = store.config()?.target?.display()
        val hasSecret = store.secret()?.let { it.fill(0); true } ?: false
        if (target == null && !hasSecret) return null
        return Configured(target, hasSecret, store.lastAuthenticatedAt)
    }

    fun refreshBattery(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        val unrestricted = runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull()
        val restricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted }.getOrNull()
        } else {
            null
        }
        update { it.copy(batteryUnrestricted = unrestricted, backgroundRestricted = restricted) }
    }

    /** The Nearby devices permission, without which Android refuses the GATT server and advertising. */
    fun missingBlePrerequisite(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) return "allow the Nearby devices permission"
        }
        return null
    }

    /**
     * GrapheneOS exposes INTERNET as a per-app "Network" toggle and leaves it OFF for an app that
     * gains the permission in an update. The BLE pipe still works; every relay connection is refused.
     */
    fun hasNetworkPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    fun start(context: Context) {
        missingBlePrerequisite(context)?.let { problem ->
            update { it.copy(error = "To start the pipe, ${problem}.") }
            return
        }
        if (!state.value.isConfigured) {
            update { it.copy(error = "Set the pipe passphrase and the destination first.") }
            return
        }
        startForeground(context, Intent(context, PhonePipeService::class.java).setAction(PhonePipeService.ACTION_START))
    }

    fun stop(context: Context) {
        // Cleared here first, so a [load] racing the service's own STOP never restarts it.
        PipeStore(context).enabled = false
        PhonePipeService.cancelResumeNotification(context)
        if (state.value.running) runCatching { context.startService(Intent(context, PhonePipeService::class.java).setAction(PhonePipeService.ACTION_STOP)) }
    }

    /**
     * Stores a new secret ([normalized] from [PipeSecret.normalize]; wiped here). A running pipe
     * treats it as an authority change: its session ends and the new PSK is derived before it
     * advertises again.
     */
    fun saveSecret(context: Context, normalized: ByteArray) {
        val app = context.applicationContext
        val copy = normalized.copyOf()
        normalized.fill(0)
        storeWrite {
            val store = PipeStore(app)
            try {
                store.saveSecret(copy)
            } finally {
                copy.fill(0)
            }
            update { it.copy(error = null, configured = configuredView(store), secretUnreadable = false) }
            reload(app)
        }
    }

    /** Replaces the approved destination. A running pipe keeps its channel and ends streams to the old one. */
    fun saveTarget(context: Context, url: String) {
        val app = context.applicationContext
        storeWrite {
            val target = try {
                PipeTarget.parseUrl(url.trim())
            } catch (e: PipeTarget.Invalid) {
                update { it.copy(error = e.reason) }
                return@storeWrite
            }
            val store = PipeStore(app)
            // The same destination again changes nothing (no stream is ended for it).
            if (store.config()?.target != target) {
                store.saveConfig(PipeConfig(target))
                reload(app)
            }
            update { it.copy(error = null, configured = configuredView(store)) }
        }
    }

    /** Deletes the secret and destination on this phone and stops the pipe. Nothing on the P4 changes. */
    fun forget(context: Context) {
        PhonePipeService.cancelResumeNotification(context)
        if (state.value.running) {
            runCatching { context.startService(Intent(context, PhonePipeService::class.java).setAction(PhonePipeService.ACTION_FORGET)) }
        } else {
            val app = context.applicationContext
            storeWrite {
                val store = PipeStore(app)
                store.enabled = false
                store.clear()
                update { it.copy(configured = null, error = null, secretUnreadable = false) }
            }
        }
    }

    private fun reload(context: Context) {
        if (state.value.running) runCatching { context.startService(Intent(context, PhonePipeService::class.java).setAction(PhonePipeService.ACTION_RELOAD)) }
    }

    private fun startForeground(context: Context, intent: Intent) {
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { update { it.copy(error = "Android did not allow the pipe to start. Open the app and try again.") } }
    }

    /** One line for the Settings card and the notification. Contains no endpoint or address. */
    fun summary(s: UiState): String {
        val st = s.stats
        return when {
            !s.running -> s.error ?: if (!s.isConfigured) "Off · not configured" else "Off"
            s.secretUnreadable -> "Enter the pipe passphrase again (the stored one cannot be read)"
            s.paused != null -> "Paused · ${s.paused}"
            s.bluetoothOn == false -> "Paused · Bluetooth off"
            !s.isConfigured -> "Not configured"
            st.stage == "ready" -> {
                // Local readiness until the P4 shows progress with its first OPEN (FW-3).
                val base = when {
                    st.opens == 0 -> "Ready (phone side)"
                    st.liveStreams == 0 -> "Ready"
                    else -> "Ready · ${st.liveStreams} stream${if (st.liveStreams == 1) "" else "s"}"
                }
                if (s.vpnAvailable == false || s.networkPermission == false) "$base · relay network unavailable" else base
            }
            st.stage == "secure" -> "Secure channel up"
            st.stage == "handshake" -> "Authenticating"
            s.coolingDownSeconds > 0 -> "Authentication failed or timed out · retry in ${s.coolingDownSeconds} s"
            s.advertising -> "Waiting for HardwareOne"
            else -> s.advertiseError ?: "Starting…"
        }
    }
}
