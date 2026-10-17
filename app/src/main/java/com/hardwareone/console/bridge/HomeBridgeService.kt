package com.hardwareone.console.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hardwareone.console.MainActivity
import com.hardwareone.console.R
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Foreground service that keeps the bridge alive while the screen is locked. It owns one
 * [BridgeRuntime]; the UI talks to it through [HomeBridge]. The persistent notification is the
 * user's handle on the fact that the phone is accepting hotspot connections and holding the
 * INTERNET permission in use.
 */
class HomeBridgeService : Service() {
    private var runtime: BridgeRuntime? = null
    private var lastNotificationText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Home AI bridge", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while the phone forwards HardwareOne AI requests to the home relay."
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = BridgeStore(this)
        when (intent?.action) {
            ACTION_STOP -> {
                store.enabled = false
                stopRuntime()
                HomeBridge.update { HomeBridge.UiState() }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROBE -> {
                runtime?.probeNow()
                return START_STICKY
            }
        }
        // ACTION_START, or a sticky restart after the process died (intent == null).
        if (intent == null && !store.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        // GrapheneOS exposes INTERNET as a per-app "Network" toggle and leaves it OFF for an app
        // that gains the permission in an update. Every socket would then fail; say so up front.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            HomeBridge.update {
                it.copy(running = false, error = "This app has no network permission. On GrapheneOS: Settings → Apps → HardwareOne → Permissions → Network, then start again.")
            }
            store.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        val config = store.config()
        val problems = config?.problems() ?: listOf("Configure the home relay first.")
        if (config == null || problems.isNotEmpty()) {
            HomeBridge.update { it.copy(running = false, error = problems.joinToString(" ")) }
            store.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        store.enabled = true
        if (runtime == null) {
            val ca = store.loadOrCreateCa()
            if (ca == null) {
                HomeBridge.update { it.copy(running = false, error = "Could not create the bridge certificate authority (Keystore unavailable).") }
                store.enabled = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            HomeBridge.update { HomeBridge.UiState(running = true, listener = ListenerState.Waiting("Starting…")) }
            runtime = BridgeRuntime(this, config, ca) { refreshNotification() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRuntime()
        super.onDestroy()
    }

    private fun stopRuntime() {
        runtime?.stop()
        runtime = null
    }

    private fun enterForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(HomeBridge.summary(HomeBridge.state.value)), type)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (API 31+) or a policy SecurityException.
        Log.w(TAG, "startForeground refused: ${e.javaClass.simpleName}")
        HomeBridge.update { it.copy(running = false, error = "Android did not allow the bridge to start from the background. Open the app and tap Start again.") }
        false
    }

    private fun refreshNotification() {
        val text = HomeBridge.summary(HomeBridge.state.value)
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_BRIDGE, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, HomeBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("Home AI bridge")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "HomeBridgeService"
        const val ACTION_START = "com.hardwareone.console.bridge.START"
        const val ACTION_STOP = "com.hardwareone.console.bridge.STOP"
        const val ACTION_PROBE = "com.hardwareone.console.bridge.PROBE"
        private const val CHANNEL = "home_bridge"
        private const val NOTIFICATION_ID = 7101
    }
}

/**
 * Everything the running bridge needs on Android: watches the hotspot interface and rebinds the
 * listener when its address appears/changes, tracks the VPN network for the home leg, probes the
 * relay, holds a wake lock only while a request is in flight, and mirrors state into [HomeBridge].
 */
class BridgeRuntime(
    private val context: Context,
    private val config: BridgeConfig,
    private val ca: BridgeCa,
    private val onStateChanged: () -> Unit,
) : BridgeObserver {
    private val limits = BridgeLimits()
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "hw1-bridge-runtime").apply { isDaemon = true }
    }
    private val vpn = VpnNetworkTracker(cm) { executor.execute { onVpnChanged() } }
    private val connector = VpnBoundConnector(vpn)
    private val relayClient = RelayClient(config, limits, connector)
    private var server: BridgeServer? = null
    private var serverBroken = false
    private var tickTask: ScheduledFuture<*>? = null
    private var probeTask: ScheduledFuture<*>? = null
    private var stopped = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var inFlight = 0
    private val tetherReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { executor.execute { tick() } }
    }

    fun start() {
        vpn.start()
        runCatching {
            ContextCompat.registerReceiver(context, tetherReceiver, IntentFilter(TETHER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        executor.execute { tick() }
        tickTask = executor.scheduleWithFixedDelay({ tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
        probeTask = executor.schedule({ probe() }, 1_500, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        stopped = true
        tickTask?.cancel(false)
        probeTask?.cancel(false)
        runCatching { context.unregisterReceiver(tetherReceiver) }
        vpn.stop()
        executor.execute {
            server?.close()
            server = null
            releaseWakeLock(force = true)
        }
        executor.shutdown()
        runCatching { executor.awaitTermination(3, TimeUnit.SECONDS) }
    }

    fun probeNow() {
        probeTask?.cancel(false)
        probeTask = executor.schedule({ probe() }, 0, TimeUnit.MILLISECONDS)
    }

    // --- Hotspot listener ----------------------------------------------------------------

    private fun tick() {
        if (stopped) return
        val scan = HotspotInterfaces.scan(cm)
        val candidates = scan.candidates
        val chosen = HotspotInterfaces.choose(candidates, config.listenAddressOverride)
        HomeBridge.update { it.copy(hotspotCandidates = candidates.map { c -> c.label }) }
        if (chosen == null) {
            closeServer()
            val seen = if (scan.skipped.isEmpty()) "" else " Interfaces seen but not usable: ${scan.skipped.joinToString(", ")}."
            val detail = config.listenAddressOverride?.let {
                "The fixed listen address $it is not present. Turn on the Wi-Fi hotspot, or clear the fixed address to detect it automatically.$seen"
            } ?: "No hotspot interface found. Turn on the phone's Wi-Fi hotspot; the ESP32 joins it and the bridge listens there. (Hotspot auto-off must be disabled for unattended use.)$seen"
            HomeBridge.update { it.copy(listener = ListenerState.Waiting(detail), hotspotSubnet = null, replyPath = null) }
            onStateChanged()
            return
        }
        val current = server
        if (current == null || serverBroken || current.listeningAddress != chosen.address.hostAddress) {
            closeServer()
            val identity = ca.issueServerIdentity(chosen.address)
            try {
                server = BridgeServer(chosen.address, config.listenPort, chosen.name, identity, relayClient, limits, this).also { it.start() }
                serverBroken = false
            } catch (e: IOException) {
                HomeBridge.update {
                    it.copy(listener = ListenerState.Failed("Could not listen on ${chosen.address.hostAddress}:${config.listenPort} (${e.javaClass.simpleName}). Another app may be using the port; it is retried automatically."))
                }
                onStateChanged()
            }
        }
        refreshReplyPath(chosen)
    }

    /**
     * A bound listener is not enough: the kernel also has to send replies *out of the hotspot*.
     * Asks it where a reply to a hotspot client would go (no packet is sent) and publishes the
     * answer, so the page can explain a VPN that swallows them instead of showing a healthy
     * listener nothing can reach. Rechecked every tick and on every VPN change.
     */
    private fun refreshReplyPath(hotspot: LocalInterface) {
        val path = ReplyPathCheck.probe(hotspot, vpn.addresses())
        val subnet = hotspot.subnet
        val previous = HomeBridge.state.value
        if (path == previous.replyPath && subnet == previous.hotspotSubnet) return
        Log.i(TAG, "replies to hotspot clients ($subnet): $path")
        HomeBridge.update { it.copy(replyPath = path, hotspotSubnet = subnet) }
        onStateChanged()
    }

    private fun closeServer() {
        server?.close()
        server = null
        serverBroken = false
    }

    // --- VPN / relay ---------------------------------------------------------------------

    private fun onVpnChanged() {
        if (stopped) return
        val available = vpn.current() != null
        HomeBridge.update {
            it.copy(
                vpnAvailable = available,
                vpnInterface = vpn.interfaceName(),
                relay = if (!available) RelayState(RelayReachability.NO_VPN, "No VPN network is visible to this app. Connect WireGuard and keep HardwareOne inside its tunnel.", System.currentTimeMillis()) else it.relay,
            )
        }
        onStateChanged()
        // The tunnel's routes decide whether hotspot replies escape it; re-evaluate right away.
        tick()
        if (available) {
            probeTask?.cancel(false)
            probeTask = executor.schedule({ probe() }, 2_000, TimeUnit.MILLISECONDS)
        }
    }

    private fun probe() {
        if (stopped) return
        HomeBridge.update { it.copy(probing = true, vpnAvailable = vpn.current() != null, vpnInterface = vpn.interfaceName()) }
        val result = relayClient.probe()
        val now = System.currentTimeMillis()
        val state = if (result.reachable) {
            RelayState(RelayReachability.REACHABLE, result.detail, now)
        } else {
            RelayState.fromFailure(result.failure ?: FailureKind.RELAY_ERROR, result.detail, now)
        }
        HomeBridge.update { it.copy(probing = false, relay = state) }
        onStateChanged()
    }

    // --- BridgeObserver ------------------------------------------------------------------

    override fun onListener(state: ListenerState) {
        if (state is ListenerState.Failed) serverBroken = true
        if (state is ListenerState.Stopped && stopped) return
        HomeBridge.update { it.copy(listener = state) }
        onStateChanged()
    }

    override fun onRelay(state: RelayState) {
        HomeBridge.update { it.copy(relay = state) }
        onStateChanged()
    }

    override fun onRequest(record: RequestRecord) {
        val ok = record.outcome == RequestOutcome.COMPLETED
        HomeBridge.update {
            it.copy(
                lastRequest = record,
                completed = it.completed + if (ok) 1 else 0,
                failed = it.failed + if (ok) 0 else 1,
            )
        }
        onStateChanged()
    }

    override fun onConnectionOpened(deviceAddress: String) {
        synchronized(this) {
            if (inFlight++ == 0) {
                val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HardwareOne:bridge").apply { setReferenceCounted(false) }
                lock.acquire(limits.chatTotalTimeoutMs + 15_000)
                wakeLock = lock
            }
        }
        HomeBridge.update { it.copy(activeConnections = it.activeConnections + 1, lastDeviceAddress = deviceAddress, lastDeviceSeenMs = System.currentTimeMillis()) }
        onStateChanged()
    }

    override fun onConnectionClosed(deviceAddress: String) {
        synchronized(this) {
            if (inFlight > 0) inFlight--
            if (inFlight == 0) releaseWakeLock(force = false)
        }
        HomeBridge.update { it.copy(activeConnections = (it.activeConnections - 1).coerceAtLeast(0), lastDeviceSeenMs = System.currentTimeMillis()) }
        onStateChanged()
    }

    private fun releaseWakeLock(force: Boolean) {
        synchronized(this) {
            if (force) inFlight = 0
            val lock = wakeLock ?: return
            if (lock.isHeld) runCatching { lock.release() }
            wakeLock = null
        }
    }

    private companion object {
        const val TAG = "BridgeRuntime"
        const val TICK_MS = 5_000L
        const val TETHER_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
    }
}
