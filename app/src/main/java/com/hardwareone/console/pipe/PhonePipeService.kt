package com.hardwareone.console.pipe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hardwareone.console.MainActivity
import com.hardwareone.console.R
import com.hardwareone.console.bridge.VpnNetworkTracker
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service (connectedDevice) that keeps the phone's half of the BLE relay pipe alive
 * while the screen is locked. It owns one [PipeRuntime]; the UI talks to it through [PhonePipe].
 *
 * Recovery (FW-1): started from the UI, from [PipeBootReceiver] after a reboot or an app update,
 * or by a sticky restart after process death, in every automatic case only while the user left the
 * pipe enabled. A transient problem (no Nearby devices permission, Bluetooth off, no VPN) pauses
 * the pipe without disabling it. If Android refuses a background start, the pipe stays enabled and
 * a "tap to resume" notification is posted; only Stop and Forget disable it.
 */
class PhonePipeService : Service() {
    private var runtime: PipeRuntime? = null
    private var lastNotificationText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "BLE relay pipe", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while the phone carries HardwareOne's encrypted relay traffic over Bluetooth."
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = PipeStore(this)
        when (intent?.action) {
            ACTION_STOP -> {
                store.enabled = false
                return shutDown(clearConfigured = false)
            }
            ACTION_FORGET -> {
                store.enabled = false
                runtime?.forget()
                // Off the main thread, serialised with every other pipe store write.
                PhonePipe.storeWrite { store.clear() }
                return shutDown(clearConfigured = true)
            }
            ACTION_RELOAD -> {
                val r = runtime ?: run {
                    // Not running (the UI's view was stale): nothing to reload, do not linger.
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                r.reload()
                return START_STICKY
            }
        }
        // ACTION_START, ACTION_BOOT, or a sticky restart after process death (intent == null). Only
        // the sticky restart was not started with startForegroundService(), so only it may stop
        // without entering the foreground first.
        if (intent == null && !store.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        // startForegroundService() obliges startForeground() before any stopSelf(), or Android kills
        // the app. connectedDevice is satisfied by CHANGE_NETWORK_STATE even without Bluetooth
        // permissions, so enter the foreground first; missing permissions only pause the pipe.
        if (!enterForeground()) {
            // A redundant start reaching a live pipe must not stop it.
            if (runtime != null) return START_STICKY
            postResumeNotification(this)
            stopSelf()
            return START_NOT_STICKY
        }
        cancelResumeNotification(this)
        // Disabled since the boot receiver looked, or nothing to serve (e.g. a stale resume tap
        // after Forget): stop rather than run, and never re-enable an unconfigured pipe.
        if ((intent?.action == ACTION_BOOT && !store.enabled) || !(store.hasSecret() && store.config() != null)) {
            store.enabled = false
            return shutDown(clearConfigured = false)
        }
        store.enabled = true
        if (runtime == null) {
            PhonePipe.update { it.copy(running = true, error = null) }
            runtime = PipeRuntime(this, store) { refreshNotification() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Every stop of ours clears the runtime first (shutDown), and the other stopSelf() paths
        // only run in an instance that never had one, so a live runtime here means Android stopped
        // the service itself, e.g. battery Restricted. `enabled` stays set: show the stop, and let
        // the notification or the next open resume it.
        val stoppedByAndroid = runtime != null
        stopRuntime()
        if (stoppedByAndroid) {
            PhonePipe.update { s ->
                PhonePipe.UiState(
                    configured = s.configured,
                    batteryUnrestricted = s.batteryUnrestricted,
                    backgroundRestricted = s.backgroundRestricted,
                    error = if (s.backgroundRestricted == true) {
                        "Battery is set to Restricted, so Android stopped the pipe. Set battery to Unrestricted, then open the app."
                    } else {
                        "Android stopped the pipe in the background. Tap its notification or open the app to resume."
                    },
                )
            }
            postResumeNotification(this)
        }
        super.onDestroy()
    }

    private fun shutDown(clearConfigured: Boolean): Int {
        cancelResumeNotification(this)
        stopRuntime()
        PhonePipe.update { s ->
            PhonePipe.UiState(
                configured = if (clearConfigured) null else s.configured,
                batteryUnrestricted = s.batteryUnrestricted,
                backgroundRestricted = s.backgroundRestricted,
            )
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun stopRuntime() {
        runtime?.stop()
        runtime = null
    }

    private fun enterForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(PhonePipe.summary(PhonePipe.state.value)), type)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (API 31+) or a policy SecurityException.
        Log.w(TAG, "startForeground refused: ${e.javaClass.simpleName}")
        PhonePipe.update { it.copy(running = false, error = "Android did not let the pipe restart in the background. Tap its notification or open the app to resume.") }
        false
    }


    private fun refreshNotification() {
        val text = PhonePipe.summary(PhonePipe.state.value)
        if (text == lastNotificationText) return
        lastNotificationText = text
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 11,
            // Own action: never filterEquals the widget's action-less MainActivity intents.
            Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_PAGE).putExtra(MainActivity.EXTRA_OPEN_PIPE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 12,
            Intent(this, PhonePipeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("BLE relay pipe")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "PhonePipeService"
        const val ACTION_START = "com.hardwareone.console.pipe.START"
        /**
         * From [PipeBootReceiver] and [PhonePipe.load]: start only if the pipe is still enabled,
         * re-checked here so a STOP that lands after the caller looked always wins.
         */
        const val ACTION_BOOT = "com.hardwareone.console.pipe.BOOT"
        const val ACTION_STOP = "com.hardwareone.console.pipe.STOP"
        /** The stored secret or destination changed; carries no data (secrets never travel in intents). */
        const val ACTION_RELOAD = "com.hardwareone.console.pipe.RELOAD"
        const val ACTION_FORGET = "com.hardwareone.console.pipe.FORGET"
        private const val ACTION_OPEN_PAGE = "com.hardwareone.console.pipe.OPEN_PAGE"
        private const val CHANNEL = "ble_pipe"
        private const val NOTIFICATION_ID = 7102
        private const val RESUME_NOTIFICATION_ID = 7103

        /**
         * The visible fallback after a refused background start (FW-1): a notification tap may
         * start a foreground service. Posted from the service or from [PipeBootReceiver].
         */
        fun postResumeNotification(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) == null) {
                runCatching {
                    manager.createNotificationChannel(NotificationChannel(CHANNEL, "BLE relay pipe", NotificationManager.IMPORTANCE_LOW))
                }
            }
            val resume = PendingIntent.getForegroundService(
                context, 13,
                Intent(context, PhonePipeService::class.java).setAction(ACTION_START),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_bridge)
                .setContentTitle("BLE relay pipe paused")
                .setContentText("Android stopped it in the background. Tap to resume.")
                .setContentIntent(resume)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
            runCatching { manager.notify(RESUME_NOTIFICATION_ID, n) }
        }

        fun cancelResumeNotification(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(RESUME_NOTIFICATION_ID) }
        }
    }
}

/**
 * After a reboot (once the user has unlocked: BOOT_COMPLETED) or an app update, restarts a pipe the
 * user left enabled. connectedDevice may start from BOOT_COMPLETED on Android 15+, and both
 * broadcasts carry a temporary foreground-service start allowance.
 */
class PipeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!PipeStore(context).enabled) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, PhonePipeService::class.java).setAction(PhonePipeService.ACTION_BOOT))
        }.onFailure {
            Log.w("PipeBootReceiver", "start refused: ${it.javaClass.simpleName}")
            PhonePipeService.postResumeNotification(context)
        }
    }
}

/**
 * Everything the running pipe needs on Android: the GATT server and advertiser, the engine and its
 * VPN-bound connector, the PSK, the cooldown, and the adapter receiver. Broadcast, VPN, engine-end
 * and stage callbacks hop onto one runtime thread; GATT callbacks call the engine directly (it only
 * queues). Holds a partial wake lock from admission until READY and while streams are live.
 */
@SuppressLint("MissingPermission")
class PipeRuntime(
    private val context: Context,
    private val store: PipeStore,
    private val onStateChanged: () -> Unit,
) : PipeGattHost {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)
    // Delayed tasks are dropped at shutdown, so Stop never waits out a pending timer.
    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "hw1-pipe-runtime").apply { isDaemon = true } }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }
    private val vpn = VpnNetworkTracker(cm) { post { onVpnChanged() } }
    @Volatile private var config: PipeConfig? = null
    private val pskLock = Any()
    private var psk: ByteArray? = null // pskLock
    private var secretVersion = -1L
    private var configVersion = -1L
    /** Bumped per loaded key; a session ends against the key it was admitted with (cooldown, L2). */
    private var keyEpoch = 0L // pskLock
    private var admittedEpoch = -1L // pskLock
    /** A secret is stored but cannot be read or derived: the user must enter it again. */
    @Volatile private var secretUnreadable = false
    private val cooldown = PipeCooldown()
    @Volatile private var paused: String? = null
    private var vpnNetwork: Network? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeForStreams = false
    private var wakeBytes = -1L
    private var wakeOpened = -1
    /** A short lock taken synchronously, from any thread, the moment work starts (R5, A-1). */
    private val kickLock: PowerManager.WakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HardwareOne:pipe-start").apply {
        setReferenceCounted(false)
    }
    private val publishQueued = AtomicBoolean(false)
    private val log = PipeLog(context)
    /** Retired-instance callbacks counted at the previous session end, for each session's own share. */
    private val droppedAtLastEnd = java.util.concurrent.atomic.AtomicLong(0)
    private var tickTask: ScheduledFuture<*>? = null
    @Volatile private var stopped = false

    private val gate = object : PipeGate {
        override fun allowed(target: PipeTarget): Boolean = config?.target == target
        // GrapheneOS can withdraw the Network permission at runtime; then the network is "down".
        override fun vpnAvailable(): Boolean = vpn.current() != null && PhonePipe.hasNetworkPermission(context)
        override fun routes(address: java.net.InetAddress): Boolean = vpn.routes(address)
    }
    private val engine: PipeEngine = PipeEngine(
        VpnPipeConnector(vpn), gate,
        // Stats change per frame; the UI only needs a few refreshes a second.
        onStats = { publishSoon() },
        onEnd = { end -> post { onSessionEnd(end) } },
        // Logged here, not on the runtime thread, so the session that Stop ends is recorded too.
        onSessionRecord = { record ->
            val dropped = gatt.droppedCallbacks
            log.append(PipeLog.sessionLine(record, dropped - droppedAtLastEnd.getAndSet(dropped)))
        },
        onStage = { _, st -> post { onStage(st) } },
        onWake = { wake() },
        // Called under the engine lock: hand the record to the runtime thread at once.
        onStreamRecord = { record -> log.append(PipeLog.streamLine(record)) },
        clock = { SystemClock.elapsedRealtime() },
    )
    private val gatt: PipeGattServer = PipeGattServer(context, engine, this)

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val state = i?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ?: return
            post { onAdapterState(state) }
        }
    }

    // ---- PipeGattHost --------------------------------------------------------------------

    override fun ready(): Boolean = config != null && synchronized(pskLock) { psk != null }

    override fun psk(): ByteArray? = if (config == null) null else synchronized(pskLock) {
        admittedEpoch = keyEpoch
        psk?.copyOf()
    }

    override fun wake() {
        runCatching { kickLock.acquire(KICK_WAKE_MS) }
    }

    override fun coolingDown(): Boolean = synchronized(cooldown) { cooldown.active(now()) }

    override fun post(delayMs: Long, block: () -> Unit) {
        if (stopped) return
        runCatching { executor.schedule({ if (!stopped) block() }, delayMs, TimeUnit.MILLISECONDS) }
    }

    override fun onChange() = publishSoon()

    // ---- lifecycle -----------------------------------------------------------------------

    fun start() {
        log.append(PipeLog.runLine(now(), appVersion(), lastExit()))
        vpn.start()
        // This broadcast comes from the Bluetooth process (uid bluetooth), which Android does not
        // deliver to NOT_EXPORTED receivers. It is a protected broadcast, so no app can forge it.
        runCatching {
            ContextCompat.registerReceiver(context, adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        }
        post {
            vpnNetwork = vpn.current()
            // PBKDF2 runs here, before advertising and never in a Bluetooth callback (R11).
            loadSecret()
            loadConfig()
            paused = PhonePipe.missingBlePrerequisite(context)
            if (paused == null && bluetoothOn()) gatt.open()
            publish()
        }
        tickTask = runCatching { executor.scheduleWithFixedDelay({ tick() }, 1, 1, TimeUnit.SECONDS) }.getOrNull()
    }

    fun stop() {
        stopped = true
        tickTask?.cancel(false)
        runCatching { context.unregisterReceiver(adapterReceiver) }
        vpn.stop()
        // Best-effort RESET 14 through the still-open server, then close it; dispose, not close: a
        // task still queued on the runtime thread must not reopen anything.
        engine.shutdown()
        gatt.dispose()
        wipePsk()
        runCatching { executor.execute { releaseWakeLock() } }
        executor.shutdown()
        runCatching { executor.awaitTermination(3, TimeUnit.SECONDS) }
        runCatching { if (kickLock.isHeld) kickLock.release() }
    }

    /** The UI stored a new secret or destination. */
    fun reload() = post {
        val sv = store.secretVersion
        val cv = store.configVersion
        if (sv != secretVersion) {
            // An authority change ends the session (FW-3). The old key stops admitting before the
            // new one is derived, and the cooldown clears only once the new key is in place.
            wipePsk()
            gatt.refreshAdvertising()
            engine.cancel()
            loadSecret()
            synchronized(cooldown) { cooldown.clear() }
        }
        if (cv != configVersion) {
            configVersion = cv
            val next = store.config()
            if (next?.target != config?.target) {
                // Target only: the channel stays; the allowlist swap, the generation step and the
                // end of every stream to the old destination happen as one step (FW-3).
                engine.retarget { config = next }
            } else {
                config = next // the same destination again: nothing to end
            }
        }
        gatt.refreshAdvertising()
        publish()
    }

    /** Forget: end the session and delete this phone's secret and destination. */
    fun forget() {
        engine.cancel()
        store.clear()
        config = null
        wipePsk()
    }

    private fun loadSecret() {
        secretVersion = store.secretVersion
        val secret = runCatching { store.secret() }.getOrNull()
        val derived = secret?.let { s -> runCatching { PipeSecret.derivePsk(s) }.getOrNull().also { s.fill(0) } }
        secretUnreadable = derived == null && store.hasSecret()
        synchronized(pskLock) {
            psk?.fill(0)
            // Stop/Forget may have run during the PBKDF2: never leave a fresh key behind (R4).
            if (stopped) {
                derived?.fill(0)
                psk = null
                return
            }
            psk = derived
            keyEpoch++
        }
    }

    private fun loadConfig() {
        configVersion = store.configVersion
        config = store.config()
    }

    private fun wipePsk() = synchronized(pskLock) {
        psk?.fill(0)
        psk = null
    }

    private fun onSessionEnd(end: PipeEnd) {
        // The cooldown is charged before the slot is freed, so advertising never resumes in between;
        // a failure against a key that has since been replaced charges nothing (the user fixed it).
        val currentKey = synchronized(pskLock) { admittedEpoch == keyEpoch }
        if (end.kind == PipeEndKind.AUTH && currentKey) synchronized(cooldown) { cooldown.charge(now()) }
        gatt.sessionEnded(end)
        publish()
    }

    private fun onStage(st: String) {
        if (st == "ready") store.lastAuthenticatedAt = System.currentTimeMillis()
        publish()
    }

    private fun bluetoothOn(): Boolean = bluetooth?.adapter?.isEnabled == true

    private fun onAdapterState(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_ON -> if (paused == null) gatt.open()
            // BLE_ON is reported as OFF: the first OFF (or TURNING_OFF) is final; callbacks may
            // never arrive after the stack cleans up, so the session ends here.
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> gatt.close()
        }
        publish()
    }

    private fun onVpnChanged() {
        val current = vpn.current()
        if (vpnNetwork != null && current != vpnNetwork) {
            // Lost, or replaced by a different network: sockets bound to the old one are invalid.
            // The authenticated pipe stays READY; new OPENs get OPEN_ERR 5 until a route returns (F5).
            engine.vpnLost()
        } else if (current != null) {
            // Same network, possibly new routes: a stream whose address left the tunnel ends.
            engine.recheckRoutes { vpn.routes(it) }
        }
        vpnNetwork = current
        publish()
    }

    private fun tick() {
        if (stopped) return
        val missing = PhonePipe.missingBlePrerequisite(context)
        if (missing != paused) {
            paused = missing
            if (missing != null) gatt.close() else if (bluetoothOn()) gatt.open()
        }
        if (missing == null) gatt.tick(bluetoothOn())
        PhonePipe.refreshBattery(context)
        publish()
    }

    private fun publish() {
        if (stopped) return
        val stats = engine.currentStats
        updateWakeLock(stats)
        val c = config
        val cooling = synchronized(cooldown) { cooldown.remainingMs(now()) }
        PhonePipe.update {
            it.copy(
                running = true,
                bluetoothOn = bluetooth?.adapter?.isEnabled,
                serverOpen = gatt.isOpen,
                advertising = gatt.isAdvertising,
                advertiseError = gatt.advertiseError ?: gatt.openError,
                configured = PhonePipe.Configured(c?.target?.display(), !secretUnreadable && (ready() || store.hasSecret()), store.lastAuthenticatedAt)
                    .takeIf { v -> v.target != null || v.hasSecret },
                secretUnreadable = secretUnreadable,
                droppedCallbacks = gatt.droppedCallbacks,
                paused = paused,
                vpnAvailable = vpn.current() != null,
                networkPermission = PhonePipe.hasNetworkPermission(context),
                coolingDownSeconds = ((cooling + 999) / 1000).toInt(),
                stats = stats,
            )
        }
        onStateChanged()
    }

    /**
     * Partial wake lock from admission to READY (the handshake's deadlines must keep counting while
     * locked, R5) and while streams are live. The streams' timeout is a real cap: after it expires
     * it is taken again only if stream bytes moved meanwhile, so a stream left open without traffic
     * cannot keep the CPU awake indefinitely.
     */
    private fun updateWakeLock(stats: PipeStats) {
        // The handshake, and a teardown's last timers, are short and bounded (ENG-5).
        val handshake = stats.stage == "handshake" || stats.stage == "secure" || stats.stage == "ending"
        val streams = stats.liveStreams > 0
        if (!handshake && !streams) {
            releaseWakeLock()
            return
        }
        val bytes = stats.bytesUp + stats.bytesDown
        if (wakeLock?.isHeld == true && wakeForStreams == streams) return
        // An expired stream lock is taken again only for traffic or a newly opened stream.
        if (streams && !handshake && wakeLock != null && wakeLock?.isHeld != true && bytes == wakeBytes && stats.opened == wakeOpened) return
        releaseWakeLock()
        wakeBytes = bytes
        wakeOpened = stats.opened
        wakeForStreams = streams
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HardwareOne:pipe").apply {
            setReferenceCounted(false)
            acquire(if (streams) STREAM_WAKE_MS else HANDSHAKE_WAKE_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    /** Why this app's previous process ended (API 30+); no description, which may hold app text. */
    private fun lastExit(): org.json.JSONObject? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            am?.getHistoricalProcessExitReasons(context.packageName, 0, 1)?.firstOrNull()?.let { e ->
                org.json.JSONObject()
                    .put("reason", exitReasonName(e.reason))
                    .put("code", e.reason)
                    .put("importance", e.importance)
                    .put("at_wall_ms", e.timestamp)
            }
        }.getOrNull()
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        android.app.ApplicationExitInfo.REASON_ANR -> "anr"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        android.app.ApplicationExitInfo.REASON_FREEZER -> "freezer"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        android.app.ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "unknown"
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    /** Coalesced before anything is queued, so a burst of stats changes costs one task. */
    private fun publishSoon() {
        if (!publishQueued.compareAndSet(false, true)) return
        post(PUBLISH_COALESCE_MS) { publishQueued.set(false); publish() }
    }

    private companion object {
        /** Safety net only; streams are bounded by the device's own 300 s chat limit. */
        const val STREAM_WAKE_MS = 10 * 60 * 1000L
        /** Covers the 4.5 s handshake and the 3 s inner HELLO with margin. */
        const val HANDSHAKE_WAKE_MS = 15_000L
        /** Bridges the gap until the stage- or stream-driven lock above takes over. */
        const val KICK_WAKE_MS = 5_000L
        const val PUBLISH_COALESCE_MS = 250L
    }
}
