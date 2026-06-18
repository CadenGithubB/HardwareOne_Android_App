package com.hardwareone.console.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareone.console.ble.BleManager
import com.hardwareone.console.ble.BleMessage
import com.hardwareone.console.ble.ChatMessage
import com.hardwareone.console.ble.ConnectionState
import com.hardwareone.console.ble.DiscoveredDevice
import com.hardwareone.console.security.CredentialStore
import com.hardwareone.console.security.LogVault
import com.hardwareone.console.security.SavedLog
import com.hardwareone.console.security.SecretBox
import com.hardwareone.console.security.SecureChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.crypto.Cipher

/** One console line, tagged so the UI can colour it. */
data class LogEntry(val text: String, val kind: Kind) {
    enum class Kind { OUTGOING, INCOMING, INFO, ERROR }
}

class ConsoleViewModel(app: Application) : AndroidViewModel(app) {

    private val ble = BleManager(app)
    private val credentials = CredentialStore(app)
    private val logVault = LogVault(app)
    private val channelSecret = SecretBox(app, alias = "hw_channel_key", prefsName = "hw_channel_prefs")

    val connectionState: StateFlow<ConnectionState> = ble.state
    val scanResults: StateFlow<List<DiscoveredDevice>> = ble.scanResults
    val authenticated: StateFlow<Boolean> = ble.authenticated
    val bleTraffic: StateFlow<com.hardwareone.console.ble.BleTraffic> = ble.traffic

    /** Username of the active session (shown in the status chip), or null when not logged in. */
    private val _currentUser = MutableStateFlow<String?>(null)
    val currentUser: StateFlow<String?> = _currentUser.asStateFlow()

    /** Last username we attempted to log in with; committed to [_currentUser] on auth success. */
    private var pendingUser: String? = null
    val deviceInfo: StateFlow<com.hardwareone.console.ble.DeviceInfo?> = ble.deviceInfo

    // --- Device status page (`status json`, captured off-console) ---
    private val _deviceStatus = MutableStateFlow<com.hardwareone.console.ble.DeviceStatus?>(null)
    val deviceStatus: StateFlow<com.hardwareone.console.ble.DeviceStatus?> = _deviceStatus.asStateFlow()

    private val _statusError = MutableStateFlow<String?>(null)
    val statusError: StateFlow<String?> = _statusError.asStateFlow()

    private val _statusLoading = MutableStateFlow(false)
    val statusLoading: StateFlow<Boolean> = _statusLoading.asStateFlow()

    // Battery telemetry (`batterystatus json`) — fetched alongside the status poll, gated on
    // `available` so boards with no battery hardware never show the card.
    private val _battery = MutableStateFlow<com.hardwareone.console.ble.BatteryInfo?>(null)
    val battery: StateFlow<com.hardwareone.console.ble.BatteryInfo?> = _battery.asStateFlow()

    // --- On-device LLM chat ---
    private val _llmStatus = MutableStateFlow<com.hardwareone.console.ble.LlmStatus?>(null)
    val llmStatus: StateFlow<com.hardwareone.console.ble.LlmStatus?> = _llmStatus.asStateFlow()

    // Optimistic load/unload feedback — `llmload` can block silently for seconds, so we show the
    // transition immediately on tap and clear it when the device reports a settled state.
    private val _llmLoadingModel = MutableStateFlow<String?>(null)
    val llmLoadingModel: StateFlow<String?> = _llmLoadingModel.asStateFlow()
    private val _llmUnloading = MutableStateFlow(false)
    val llmUnloading: StateFlow<Boolean> = _llmUnloading.asStateFlow()

    private val _llmModels = MutableStateFlow<List<String>>(emptyList())
    val llmModels: StateFlow<List<String>> = _llmModels.asStateFlow()

    private val _llmMessages = MutableStateFlow<List<com.hardwareone.console.ble.ChatMessage>>(emptyList())
    val llmMessages: StateFlow<List<com.hardwareone.console.ble.ChatMessage>> = _llmMessages.asStateFlow()

    private val _llmGenerating = MutableStateFlow(false)
    val llmGenerating: StateFlow<Boolean> = _llmGenerating.asStateFlow()

    // Recent console commands (newest first, deduped) for the input-bar history recall.
    private val _commandHistory = MutableStateFlow<List<String>>(emptyList())
    val commandHistory: StateFlow<List<String>> = _commandHistory.asStateFlow()

    private var llmOffset = 0
    private var llmActive = false // a generation is in progress (drives the result poll loop)
    private var llmDoTurn = false // current turn is a `Do:` command-suggestion turn

    // --- File browser (BLE file commands; require secure channel + admin) ---
    private val _filesPath = MutableStateFlow("/")
    val filesPath: StateFlow<String> = _filesPath.asStateFlow()
    private val _fileListing = MutableStateFlow<com.hardwareone.console.ble.FileListing?>(null)
    val fileListing: StateFlow<com.hardwareone.console.ble.FileListing?> = _fileListing.asStateFlow()
    private val _fileStats = MutableStateFlow<com.hardwareone.console.ble.FileStats?>(null)
    val fileStats: StateFlow<com.hardwareone.console.ble.FileStats?> = _fileStats.asStateFlow()
    private val _filesBusy = MutableStateFlow(false)
    val filesBusy: StateFlow<Boolean> = _filesBusy.asStateFlow()
    private val _fileViewer = MutableStateFlow<com.hardwareone.console.ble.FileViewerState?>(null)
    val fileViewer: StateFlow<com.hardwareone.console.ble.FileViewerState?> = _fileViewer.asStateFlow()
    private val _fileTransfer = MutableStateFlow<com.hardwareone.console.ble.FileTransfer?>(null)
    val fileTransfer: StateFlow<com.hardwareone.console.ble.FileTransfer?> = _fileTransfer.asStateFlow()
    // A finished download's (filename, bytes) waiting for the UI to save it to the phone.
    private val _downloadReady = MutableStateFlow<Pair<String, ByteArray>?>(null)
    val downloadReady: StateFlow<Pair<String, ByteArray>?> = _downloadReady.asStateFlow()

    // Read-loop state (viewing/download).
    private var readPath = ""
    private var readOffset = 0L
    private var readForDownload = false
    private var fileOpCancelled = false // user stopped an in-flight upload/download
    private val readBuf = java.io.ByteArrayOutputStream()
    // Write-loop state (upload).
    private var writePath = ""
    private var writeBytes: ByteArray? = null
    private var writeOffset = 0L
    private var writeSentFinal = false

    // I²C device list (`devices json`), lazily loaded when the user expands the I²C card.
    // null = not loaded yet; empty list = loaded, none present.
    private val _i2cDevices = MutableStateFlow<List<com.hardwareone.console.ble.I2cDevice>?>(null)
    val i2cDevices: StateFlow<List<com.hardwareone.console.ble.I2cDevice>?> = _i2cDevices.asStateFlow()

    private val _i2cLoading = MutableStateFlow(false)
    val i2cLoading: StateFlow<Boolean> = _i2cLoading.asStateFlow()

    // --- Sensors page (`sensors json` viewing + `features <id> on/off` control) ---
    private val _sensors = MutableStateFlow<List<com.hardwareone.console.ble.SensorEntry>?>(null)
    val sensors: StateFlow<List<com.hardwareone.console.ble.SensorEntry>?> = _sensors.asStateFlow()

    private val _sensorsError = MutableStateFlow<String?>(null)
    val sensorsError: StateFlow<String?> = _sensorsError.asStateFlow()

    private val _sensorsLoading = MutableStateFlow(false)
    val sensorsLoading: StateFlow<Boolean> = _sensorsLoading.asStateFlow()

    // Generic per-sensor settings controls (`controls json <module>`).
    private val _controlModules = MutableStateFlow<Set<String>>(emptySet())
    val controlModules: StateFlow<Set<String>> = _controlModules.asStateFlow()

    private val _controls = MutableStateFlow<Map<String, com.hardwareone.console.ble.ControlsModule>>(emptyMap())
    val controls: StateFlow<Map<String, com.hardwareone.console.ble.ControlsModule>> = _controls.asStateFlow()

    private val _controlsLoading = MutableStateFlow<Set<String>>(emptySet())
    val controlsLoading: StateFlow<Set<String>> = _controlsLoading.asStateFlow()

    // --- ESP-NOW page (Phase 1: read-only status + peer list via the `... json` commands) ---
    private val _espNowMode = MutableStateFlow<com.hardwareone.console.ble.EspNowMode?>(null)
    val espNowMode: StateFlow<com.hardwareone.console.ble.EspNowMode?> = _espNowMode.asStateFlow()
    private val _espNowEnc = MutableStateFlow<com.hardwareone.console.ble.EspNowEnc?>(null)
    val espNowEnc: StateFlow<com.hardwareone.console.ble.EspNowEnc?> = _espNowEnc.asStateFlow()
    private val _espNowMeshRole = MutableStateFlow<com.hardwareone.console.ble.EspNowMeshRole?>(null)
    val espNowMeshRole: StateFlow<com.hardwareone.console.ble.EspNowMeshRole?> = _espNowMeshRole.asStateFlow()
    private val _espNowDeviceInfo = MutableStateFlow<com.hardwareone.console.ble.EspNowDeviceInfo?>(null)
    val espNowDeviceInfo: StateFlow<com.hardwareone.console.ble.EspNowDeviceInfo?> = _espNowDeviceInfo.asStateFlow()
    private val _espNowPaired = MutableStateFlow<com.hardwareone.console.ble.EspNowPaired?>(null)
    val espNowPaired: StateFlow<com.hardwareone.console.ble.EspNowPaired?> = _espNowPaired.asStateFlow()
    private val _espNowMesh = MutableStateFlow<com.hardwareone.console.ble.EspNowMeshStatus?>(null)
    val espNowMesh: StateFlow<com.hardwareone.console.ble.EspNowMeshStatus?> = _espNowMesh.asStateFlow()
    private val _espNowLoading = MutableStateFlow(false)
    val espNowLoading: StateFlow<Boolean> = _espNowLoading.asStateFlow()

    // Per-peer message feed (device detail screen). Raw records are accumulated (deduped by seq);
    // the displayed lines are derived by reassembling chunked messages. Sent + received both come
    // from shared device history, so there's no optimistic local echo to reconcile.
    private val _espNowFeed = MutableStateFlow<List<com.hardwareone.console.ble.EspNowChatLine>>(emptyList())
    val espNowFeed: StateFlow<List<com.hardwareone.console.ble.EspNowChatLine>> = _espNowFeed.asStateFlow()
    private var espNowFeedMac = ""
    private var espNowFeedSince = 0L
    private var espNowFeedActive = false   // drain loop runs while the device screen is RESUMED
    private var espNowPollInFlight = false // at most ONE espnowmessages request outstanding
    private val espNowRecords = LinkedHashMap<Long, com.hardwareone.console.ble.EspNowMessages.Message>()

    // Remote command runner (device detail → Command tab).
    private val _espNowRemoteBusy = MutableStateFlow(false)
    val espNowRemoteBusy: StateFlow<Boolean> = _espNowRemoteBusy.asStateFlow()
    private val _espNowRemoteError = MutableStateFlow<String?>(null)
    val espNowRemoteError: StateFlow<String?> = _espNowRemoteError.asStateFlow()
    private val _espNowRemoteResult = MutableStateFlow<String?>(null)
    val espNowRemoteResult: StateFlow<String?> = _espNowRemoteResult.asStateFlow()
    private var espNowRemoteReqId = 0L
    private var espNowRemoteLastProgressMs = 0L

    // --- Credential storage state ---
    /** Device can present a biometric/PIN prompt (a credential is enrolled). */
    val canUseCredentialStore: Boolean get() = credentials.canAuthenticate()

    private val _autoLogin = MutableStateFlow(credentials.autoLogin)
    val autoLogin: StateFlow<Boolean> = _autoLogin.asStateFlow()

    private val _hasSavedCredentials = MutableStateFlow(credentials.hasStoredPassword())
    val hasSavedCredentials: StateFlow<Boolean> = _hasSavedCredentials.asStateFlow()

    private val _savedUsername = MutableStateFlow(credentials.savedUsername)
    val savedUsername: StateFlow<String?> = _savedUsername.asStateFlow()

    /** Manual login dialog visibility — hoisted so the Activity can open it (e.g. after a
     *  cancelled biometric prompt) as well as the LOGIN button. */
    private val _loginDialogVisible = MutableStateFlow(false)
    val loginDialogVisible: StateFlow<Boolean> = _loginDialogVisible.asStateFlow()

    fun showLoginDialog() { _loginDialogVisible.value = true }
    fun hideLoginDialog() { _loginDialogVisible.value = false }

    fun allowedAuthenticators(): Int = credentials.allowedAuthenticators()

    private val _log = MutableStateFlow<List<LogEntry>>(
        listOf(LogEntry("HardwareOne console — tap SCAN to begin.", LogEntry.Kind.INFO)),
    )
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()
    // Monotonic count of all lines ever appended — lets the UI measure "new lines while paused"
    // even though `log` is capped (its size stays constant at the cap).
    private val _logTotal = MutableStateFlow(0L)
    val logTotal: StateFlow<Long> = _logTotal.asStateFlow()

    val bluetoothSupported: Boolean get() = ble.isBluetoothSupported
    val bluetoothEnabled: Boolean get() = ble.isBluetoothEnabled

    init {
        viewModelScope.launch {
            ble.incoming.collect { append(LogEntry(it.trimEnd('\n'), LogEntry.Kind.INCOMING)) }
        }
        viewModelScope.launch {
            ble.messages.collect { msg ->
                append(LogEntry(msg.text, msg.kind.toLogKind()))
            }
        }
        // Tie the displayed username to real auth success: commit the pending login name when
        // authenticated flips true, clear it on logout/disconnect.
        viewModelScope.launch {
            ble.authenticated.collect { authed ->
                _currentUser.value = if (authed) pendingUser else null
                if (authed) refreshBattery() // battery may need auth — fetch right after login
            }
        }
        // Fetch battery the instant a device is Ready (no auth) so it's there on connect, not
        // a poll-interval later. The auth branch above covers the login-required case.
        viewModelScope.launch {
            ble.state.collect { st -> if (st is ConnectionState.Ready) refreshBattery() }
        }
        // Route captured (off-console) command replies to their pages.
        viewModelScope.launch {
            ble.captures.collect { capture ->
                val tag = capture.tag
                // A single malformed/mis-routed reply must never crash the collector — that would
                // silently kill ALL capture routing (every page goes dead). Isolate each handler.
                try {
                    when {
                        tag == TAG_STATUS -> onStatusCapture(capture.text, capture.timedOut)
                        tag == TAG_DEVICES -> onDevicesCapture(capture.text, capture.timedOut)
                        tag == TAG_SENSORS -> onSensorsCapture(capture.text, capture.timedOut)
                        tag == TAG_CONTROL_MODULES -> onControlModulesCapture(capture.text)
                        tag == TAG_BATTERY -> onBatteryCapture(capture.text, capture.timedOut)
                        tag == TAG_LLM_STATUS -> onLlmStatusCapture(capture.text)
                        tag == TAG_LLM_MODELS -> com.hardwareone.console.ble.LlmStatus.parseModels(capture.text)?.let { _llmModels.value = it }
                        tag == TAG_LLM_GEN -> onLlmGenStart(capture.text, capture.timedOut)
                        tag == TAG_LLM_RESULT -> onLlmResult(capture.text, capture.timedOut)
                        tag == TAG_FILES -> onFilesCapture(capture.text, capture.timedOut)
                        tag == TAG_FILE_STATS -> onFileStatsCapture(capture.text)
                        tag == TAG_FILE_READ -> onFileReadCapture(capture.text, capture.timedOut)
                        tag == TAG_FILE_WRITE -> onFileWriteCapture(capture.text, capture.timedOut)
                        tag == TAG_EN_MODE -> { if (!capture.timedOut) com.hardwareone.console.ble.EspNowMode.parse(capture.text)?.let { _espNowMode.value = it } }
                        tag == TAG_EN_ENC -> { if (!capture.timedOut) com.hardwareone.console.ble.EspNowEnc.parse(capture.text)?.let { _espNowEnc.value = it } }
                        tag == TAG_EN_MESHROLE -> { if (!capture.timedOut) com.hardwareone.console.ble.EspNowMeshRole.parse(capture.text)?.let { _espNowMeshRole.value = it } }
                        tag == TAG_EN_DEVINFO -> { if (!capture.timedOut) com.hardwareone.console.ble.EspNowDeviceInfo.parse(capture.text)?.let { _espNowDeviceInfo.value = it } }
                        tag == TAG_EN_LIST -> { if (!capture.timedOut) com.hardwareone.console.ble.EspNowPaired.parse(capture.text)?.let { _espNowPaired.value = it } }
                        tag == TAG_EN_MESH -> { _espNowLoading.value = false; if (!capture.timedOut) com.hardwareone.console.ble.EspNowMeshStatus.parse(capture.text)?.let { _espNowMesh.value = it } }
                        tag == TAG_EN_MSGS -> onEspNowFeedMessages(capture.text, capture.timedOut)
                        tag == TAG_EN_REMOTE_ACK -> onEspNowRemoteAck(capture.text, capture.timedOut)
                        tag.startsWith(TAG_CONTROLS_PREFIX) ->
                            onControlsCapture(tag.removePrefix(TAG_CONTROLS_PREFIX), capture.text)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ConsoleVM", "capture handler failed for tag=$tag", e)
                    // If a generation poll's handler blew up, keep the stream alive.
                    if (tag == TAG_LLM_RESULT && llmActive) pollLlmResult()
                }
            }
        }
        // Declare secure mode synchronously (before the async key load) so a connection waits
        // for the PSK instead of racing it to plaintext.
        ble.setSecureExpected(channelSecret.hasCiphertext())
        // Load any saved secure-channel passphrase and arm the PSK (off the main thread).
        viewModelScope.launch(Dispatchers.Default) {
            val pass = channelSecret.get()
            when {
                pass != null -> ble.setPsk(SecureChannel.derivePsk(pass))
                // Configured, but the stored secret can't be decrypted (e.g. the Keystore key
                // was lost on reinstall). Don't silently fall back to plaintext — flag it so the
                // user is told to re-enter, instead of a baffling "device ignores everything".
                channelSecret.hasCiphertext() -> {
                    _secureChannelLocked.value = true
                    reportError(
                        "🔒 Secure channel is configured but its saved passphrase couldn't be " +
                            "unlocked (this can happen after reinstalling the app). Re-enter it " +
                            "in Settings → Secure channel to use encryption.",
                    )
                }
            }
        }
    }

    // --- User actions ---------------------------------------------------------------

    fun startScan() = ble.startScan()
    fun stopScan() = ble.stopScan()
    fun connect(device: DiscoveredDevice) = ble.connect(device.address)
    fun disconnect() = ble.disconnect()
    fun reconnect() = ble.reconnect()
    fun readStatus() = ble.readStatus()

    /** Push the phone's current UTC time to the device: `timeset <epoch_seconds>`. */
    fun syncClock() {
        val epoch = System.currentTimeMillis() / 1000
        append(LogEntry("> timeset $epoch  (sync clock)", LogEntry.Kind.OUTGOING))
        ble.sendCommand("timeset $epoch")
    }

    /** Request a fresh device-status snapshot (`status json`), captured off the console. */
    fun refreshStatus() {
        if (connectionState.value !is ConnectionState.Ready) {
            _statusError.value = "Not connected."
            return
        }
        _statusLoading.value = true
        ble.sendCaptured("status json", TAG_STATUS)
    }

    /** Drop the cached status snapshot (call when leaving the status page). */
    fun clearStatus() {
        _deviceStatus.value = null
        _statusError.value = null
        _statusLoading.value = false
        _i2cDevices.value = null
        _i2cLoading.value = false
        _battery.value = null
    }

    /** Fetch battery telemetry (`batterystatus json`), captured off-console. */
    fun refreshBattery() {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCaptured("batterystatus json", TAG_BATTERY)
    }

    private fun onBatteryCapture(text: String, timedOut: Boolean) {
        if (timedOut || text.isBlank()) return // keep the last reading
        com.hardwareone.console.ble.BatteryInfo.parse(text)?.let { _battery.value = it }
    }

    /**
     * Fetch the I²C device list (`devices json`). The status page calls this each poll with
     * [silent] = true so the "found" count reflects the honest detected list; an explicit
     * card expand calls it non-silent to show a spinner on the first load.
     */
    fun loadI2cDevices(silent: Boolean = false) {
        if (connectionState.value !is ConnectionState.Ready) return
        if (!silent || _i2cDevices.value == null) _i2cLoading.value = true
        ble.sendCaptured("devices json", TAG_DEVICES)
    }

    private fun onDevicesCapture(text: String, timedOut: Boolean) {
        _i2cLoading.value = false
        if (timedOut || text.isBlank()) return // keep whatever we had; user can retry
        com.hardwareone.console.ble.I2cDevice.parseList(text)?.let { _i2cDevices.value = it }
    }

    // --- ESP-NOW page ----------------------------------------------------------------

    /** Load the full ESP-NOW snapshot (config + paired list + live mesh). Used on open + refresh. */
    fun loadEspNow() {
        if (connectionState.value !is ConnectionState.Ready) return
        _espNowLoading.value = true
        ble.sendCaptured("espnowmode json", TAG_EN_MODE)
        ble.sendCaptured("espnowencstatus json", TAG_EN_ENC)
        ble.sendCaptured("espnowmeshrole json", TAG_EN_MESHROLE)
        ble.sendCaptured("espnowdeviceinfo json", TAG_EN_DEVINFO)
        ble.sendCaptured("espnowlist json", TAG_EN_LIST)
        ble.sendCaptured("espnowmeshstatus json", TAG_EN_MESH) // sent last → clears the busy flag
    }

    /** Poll just the dynamic bits (paired liveness + mesh peers) on a timer. */
    fun refreshEspNowPeers() {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCaptured("espnowlist json", TAG_EN_LIST)
        ble.sendCaptured("espnowmeshstatus json", TAG_EN_MESH)
    }

    fun clearEspNow() {
        _espNowMode.value = null
        _espNowEnc.value = null
        _espNowMeshRole.value = null
        _espNowDeviceInfo.value = null
        _espNowPaired.value = null
        _espNowMesh.value = null
        _espNowLoading.value = false
    }

    // --- ESP-NOW per-peer message feed (device detail screen) ---

    /** Begin a fresh feed for [mac]: clear, reset the seq cursor, and pull what's buffered. Resuming
     *  the SAME peer (e.g. a lifecycle re-entry) keeps the forward cursor instead of restarting at
     *  since=0 — restarting mid-pull re-reads the rolling ring and replays a prior run's output
     *  interleaved with the new one (serialize contract rule 4). A genuinely new peer resets. */
    fun openEspNowFeed(mac: String) {
        if (mac != espNowFeedMac) {
            espNowFeedMac = mac
            espNowFeedSince = 0L
            espNowRecords.clear()
            _espNowFeed.value = emptyList()
            clearEspNowRemote()
        }
        espNowFeedActive = true
        schedulePoll(0) // start (or resume) the reply-driven drain loop
    }

    /** Stop the drain loop without discarding the cursor/records (the screen left RESUMED). */
    fun pauseEspNowFeed() { espNowFeedActive = false }

    /**
     * Reply-driven paging: at most ONE `espnowmessages` request is outstanding. The next page is
     * fired only after the previous reply is processed (in [onEspNowFeedMessages]) — so a forward
     * pull never re-issues a page it's already fetching. The old fixed-interval timer queued the
     * same `since` many times over (each an 8-record BLE round trip), which is what made a memreport
     * pull crawl for ~40s instead of ~6s.
     */
    private fun schedulePoll(delayMs: Long) {
        if (!espNowFeedActive || espNowPollInFlight) return
        espNowPollInFlight = true
        viewModelScope.launch {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            if (!espNowFeedActive || connectionState.value !is ConnectionState.Ready || espNowFeedMac.isEmpty()) {
                espNowPollInFlight = false
                return@launch
            }
            ble.sendCaptured("espnowmessages json $espNowFeedSince $espNowFeedMac", TAG_EN_MSGS)
            // espNowPollInFlight is cleared when the reply (or capture timeout) lands.
        }
    }

    /** Nudge the drain loop (e.g. after sending a chat message) if it happens to be idle. */
    fun pollEspNowFeed() = schedulePoll(0)

    /** Send a text message to the peer (`espnowsend <mac> <message>`). The send is recorded in
     *  shared device history, so it comes back through the feed (no optimistic echo to de-dupe). */
    fun sendEspNowMessage(mac: String, text: String) {
        if (connectionState.value !is ConnectionState.Ready || text.isBlank()) return
        ble.sendCommand("espnowsend $mac $text")
    }

    fun clearEspNowFeed() {
        espNowFeedActive = false
        espNowFeedMac = ""
        espNowFeedSince = 0L
        espNowRecords.clear()
        _espNowFeed.value = emptyList()
    }

    private fun onEspNowFeedMessages(text: String, timedOut: Boolean) {
        espNowPollInFlight = false
        if (!espNowFeedActive) return
        if (timedOut) { schedulePoll(500); return } // dropped reply — retry the same cursor shortly
        val parsed = com.hardwareone.console.ble.EspNowMessages.parse(text)
        if (parsed == null || parsed.error != null) { schedulePoll(500); return }
        if (parsed.messages.isEmpty()) {
            // Empty page ({"messages":[]}) = the device is caught up: the pull terminator (serialize
            // contract rule 3). Once the command's output has been collected, this ends the pull.
            if (_espNowRemoteBusy.value && _espNowRemoteResult.value != null) _espNowRemoteBusy.value = false
            schedulePoll(1_200) // idle cadence — watch for new chat / late-arriving output
            return
        }
        var changed = false
        for (m in parsed.messages) {
            if (m.seq > espNowFeedSince) espNowFeedSince = m.seq
            if (espNowRecords.put(m.seq, m) == null) changed = true
        }
        // Cap the raw buffer (keep the newest by seq).
        if (espNowRecords.size > 300) {
            espNowRecords.keys.sorted().take(espNowRecords.size - 300).forEach { espNowRecords.remove(it) }
        }
        if (changed) {
            // New records = progress; keep the remote-command watchdog alive while output flows.
            if (_espNowRemoteBusy.value) espNowRemoteLastProgressMs = android.os.SystemClock.elapsedRealtime()
            recomputeEspNowFeed()
        }
        schedulePoll(0) // full page received — drain the next one immediately (forward paging)
    }

    /** Reassemble accumulated records: chunked messages (reqId != 0) are grouped and concatenated
     *  in piece order; unsolicited messages (reqId 0) stand alone. Command results (received, with
     *  a nonzero reqId) are routed to the Command tab via [_espNowRemoteResult], not the chat feed. */
    private fun recomputeEspNowFeed() {
        val sorted = espNowRecords.values.sortedBy { it.seq }

        // Command result: the records carrying the issued command's reqId (serialize contract rule
        // 5), in seq order. The firmware tags streamed output (memreport/logs) with the command's
        // reqId too, so this captures the full body. Not gated on the busy flag, so a late-paged
        // frame keeps appending; the empty-page terminator just stops the spinner.
        if (espNowRemoteReqId != 0L) {
            val out = sorted.filter { !it.sent && it.reqId == espNowRemoteReqId }
            if (out.isNotEmpty()) _espNowRemoteResult.value = out.joinToString("") { it.msg }
        }

        // Chat feed: group/reassemble chunked messages (reqId != 0); reqId-0 stand alone. Received
        // records carrying a nonzero reqId are remote-command output → Command tab, not chat.
        val groups = LinkedHashMap<String, MutableList<com.hardwareone.console.ble.EspNowMessages.Message>>()
        for (m in sorted) {
            val key = if (m.reqId != 0L) "r${m.reqId}" else "s${m.seq}"
            groups.getOrPut(key) { mutableListOf() }.add(m)
        }
        val chat = mutableListOf<com.hardwareone.console.ble.EspNowChatLine>()
        for (group in groups.values) {
            val ordered = group.sortedBy { it.piece }
            val first = ordered.first()
            val isCommandResponse = !first.sent && first.reqId != 0L
            if (!isCommandResponse) {
                chat.add(
                    com.hardwareone.console.ble.EspNowChatLine(
                        from = first.name.ifEmpty { first.mac },
                        text = ordered.joinToString("") { it.msg },
                        outgoing = first.sent,
                        state = if (first.sent) first.sendState else -1,
                    ),
                )
            }
        }
        _espNowFeed.value = chat.takeLast(200)
    }

    // --- ESP-NOW remote command runner (device detail → Command tab) ---

    /** Run a CLI command on a peer (`espnowremote … json`); the result arrives via the feed and is
     *  matched by reqId into [espNowRemoteResult]. Requires ESP-NOW encryption + the peer's creds. */
    fun runEspNowRemote(mac: String, user: String, pass: String, command: String) {
        if (connectionState.value !is ConnectionState.Ready || command.isBlank()) return
        _espNowRemoteError.value = null
        _espNowRemoteResult.value = null
        espNowRemoteReqId = 0L
        _espNowRemoteBusy.value = true
        ble.sendCaptured("espnowremote $mac $user $pass $command json", TAG_EN_REMOTE_ACK)
    }

    private fun onEspNowRemoteAck(text: String, timedOut: Boolean) {
        if (timedOut) { _espNowRemoteBusy.value = false; _espNowRemoteError.value = "Timed out sending."; return }
        val ack = com.hardwareone.console.ble.EspNowAck.parse(text)
        if (ack == null) { _espNowRemoteBusy.value = false; _espNowRemoteError.value = "Bad reply."; return }
        if (!ack.ok) { _espNowRemoteBusy.value = false; _espNowRemoteError.value = ack.error ?: "Failed."; return }
        // Sent OK — page the peer's output in by reqId. The pull terminates on the empty
        // {"messages":[]} page (handled in onEspNowFeedMessages); this watchdog is only a fallback
        // for a peer that never answers at all (bad creds / unreachable): if nothing arrives after a
        // quiet gap, clear the spinner and surface the error. Progress (new records) keeps it alive.
        espNowRemoteReqId = ack.reqId
        espNowRemoteLastProgressMs = android.os.SystemClock.elapsedRealtime()
        pollEspNowFeed()
        viewModelScope.launch {
            while (_espNowRemoteBusy.value) {
                kotlinx.coroutines.delay(1_000)
                if (!_espNowRemoteBusy.value) break
                if (android.os.SystemClock.elapsedRealtime() - espNowRemoteLastProgressMs > 5_000) {
                    _espNowRemoteBusy.value = false
                    if (_espNowRemoteResult.value == null) _espNowRemoteError.value = "No response from peer."
                    break
                }
            }
        }
    }

    fun clearEspNowRemote() {
        _espNowRemoteBusy.value = false
        _espNowRemoteError.value = null
        _espNowRemoteResult.value = null
        espNowRemoteReqId = 0L
    }

    // --- ESP-NOW management + this-device config (text setters; reload after) ---

    fun unpairEspNow(mac: String) { if (connectionState.value is ConnectionState.Ready) ble.sendCommand("espnowunpair $mac") }
    fun forgetEspNow(mac: String) { if (connectionState.value is ConnectionState.Ready) ble.sendCommand("espnowforget $mac") }
    fun pairEspNow(mac: String, name: String, secure: Boolean) {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCommand((if (secure) "espnowpairsecure " else "espnowpair ") + "$mac ${q(name)}")
        viewModelScope.launch { kotlinx.coroutines.delay(500); refreshEspNowPeers() }
    }

    private fun espNowSetter(command: String) {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCommand(command)
        viewModelScope.launch { kotlinx.coroutines.delay(400); loadEspNow() }
    }
    fun setEspNowName(v: String) = espNowSetter("espnowsetname ${q(v)}")
    fun setEspNowFriendlyName(v: String) = espNowSetter("espnowfriendlyname ${if (v.isEmpty()) "clear" else q(v)}")
    fun setEspNowRoom(v: String) = espNowSetter("espnowroom ${if (v.isEmpty()) "clear" else q(v)}")
    fun setEspNowZone(v: String) = espNowSetter("espnowzone ${if (v.isEmpty()) "clear" else q(v)}")
    fun setEspNowTags(v: String) = espNowSetter("espnowtags ${if (v.isEmpty()) "clear" else q(v)}")
    fun setEspNowStationary(on: Boolean) = espNowSetter("espnowstationary ${if (on) "1" else "0"}")
    fun setEspNowMode(mode: String) = espNowSetter("espnowmode $mode")
    fun setEspNowMeshRole(role: String) = espNowSetter("espnowmeshrole $role")
    fun setEspNowEnabled(on: Boolean) = espNowSetter(if (on) "openespnow" else "closeespnow")

    // --- Sensors page ----------------------------------------------------------------

    /** Poll the sensor snapshot (`sensors json`), captured off-console. */
    fun refreshSensors() {
        if (connectionState.value !is ConnectionState.Ready) {
            _sensorsError.value = "Not connected."
            return
        }
        _sensorsLoading.value = true
        ble.sendCaptured("sensors json", TAG_SENSORS)
    }

    /** Drop the cached snapshot (call when leaving the page). */
    fun clearSensors() {
        _sensors.value = null
        _sensorsError.value = null
        _sensorsLoading.value = false
        _controlModules.value = emptySet()
        _controls.value = emptyMap()
        _controlsLoading.value = emptySet()
    }

    /** Enable/disable a sensor. Most use `features <id> on|off`; a few need dedicated verbs. */
    fun toggleSensor(id: String, enable: Boolean) {
        // Optimistic local flip so the switch responds instantly; the next poll reconciles.
        _sensors.value = _sensors.value?.map { if (it.id == id) it.copy(enabled = enable) else it }
        ble.sendCommand(enableCommandFor(id, enable))
        viewModelScope.launch {
            kotlinx.coroutines.delay(900) // give the firmware a moment to apply, then confirm
            refreshSensors()
        }
    }

    /**
     * The live power command for a sensor's toggle: `open<id>`/`close<id>`, which actually
     * starts/stops the sensor task. The firmware's `sensors json` `enabled` reflects this live
     * running state, so the toggle binds to reality. (`features <id> on` / `<id>AutoStart` is only
     * the persisted *boot* autostart — a separate knob in the Settings panel, not this toggle.)
     * Ids now match their verbs (e.g. "input" → openinput), so no per-sensor token overrides.
     */
    private fun enableCommandFor(id: String, enable: Boolean): String =
        (if (enable) "open" else "close") + id

    /** Run a per-sensor action verb (e.g. `fmradiotune 101.5`), then re-poll to reflect it. */
    fun sensorAction(command: String) {
        ble.sendCommand(command)
        viewModelScope.launch {
            kotlinx.coroutines.delay(700)
            refreshSensors()
        }
    }

    private fun onSensorsCapture(text: String, timedOut: Boolean) {
        _sensorsLoading.value = false
        if (timedOut || text.isBlank()) {
            if (_sensors.value == null) _sensorsError.value = "No response from device."
            return
        }
        val snapshot = com.hardwareone.console.ble.SensorSnapshot.parse(text)
        if (snapshot == null) {
            // Likely the firmware doesn't implement `sensors json` yet (the plain `sensors`
            // catalog isn't JSON). Don't clobber a previous good snapshot.
            if (_sensors.value == null) {
                _sensorsError.value = "Sensor readings aren't available yet (device firmware pending)."
            }
            return
        }
        _sensors.value = snapshot.sensors
        _sensorsError.value = null
    }

    // --- Per-sensor controls (`controls json`) ---------------------------------------

    /** Discover which modules expose adjustable settings (fetched once on page open). */
    fun loadControlModules() {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCaptured("controls json", TAG_CONTROL_MODULES)
    }

    /** Fetch one module's controls + live values (lazy, when its panel is opened). */
    fun loadControls(moduleId: String) {
        if (connectionState.value !is ConnectionState.Ready) return
        _controlsLoading.value = _controlsLoading.value + moduleId
        ble.sendCaptured("controls json $moduleId", "$TAG_CONTROLS_PREFIX$moduleId")
    }

    /**
     * Set one control: `<key> <token>` (case-insensitive on the device). [token] is already
     * formatted by the UI (e.g. "on"/"off", "500", "0.2", an enum value). Re-fetches the module
     * shortly after so the displayed value reflects the firmware's validated result.
     */
    fun setControl(moduleId: String, key: String, token: String) {
        ble.sendCommand("$key $token")
        viewModelScope.launch {
            kotlinx.coroutines.delay(700)
            loadControls(moduleId)
        }
    }

    private fun onControlModulesCapture(text: String) {
        com.hardwareone.console.ble.ControlsModule.parseModuleList(text)?.let {
            _controlModules.value = it.toSet()
        }
    }

    // --- File browser -------------------------------------------------------------------
    //
    // BLE file commands require an established Secure Channel + admin login. JSON-reply commands
    // (files/fileread/filewrite) are captured; the text-reply verbs (mkdir/filecreate/filerename/
    // filedelete) reply on the console, so we reload the listing to reflect the result.

    private val readWindow = 2048

    fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    // The firmware now requires EVERY file-command path to be a double-quoted token (unquoted
    // paths error). Quoting also lets paths contain spaces. A literal " in a name is unsupported
    // by the device tokenizer, so callers must reject such names before building a command.
    private fun q(path: String): String = "\"" + path + "\""
    private fun hasIllegalQuote(name: String): Boolean = name.contains('"')

    private fun parentPath(path: String): String {
        if (path == "/" || path.isEmpty()) return "/"
        val trimmed = path.trimEnd('/')
        val cut = trimmed.lastIndexOf('/')
        return if (cut <= 0) "/" else trimmed.substring(0, cut)
    }

    fun loadFiles(path: String = _filesPath.value) {
        if (connectionState.value !is ConnectionState.Ready) return
        _filesPath.value = path
        _fileListing.value = null
        _filesBusy.value = true
        ble.sendCaptured("files json ${q(path)}", TAG_FILES)
        ble.sendCaptured("files stats json ${q(path)}", TAG_FILE_STATS)
    }

    fun openDir(name: String) = loadFiles(joinPath(_filesPath.value, name))
    fun navigateUp() { if (_filesPath.value != "/") loadFiles(parentPath(_filesPath.value)) }

    private fun onFilesCapture(text: String, timedOut: Boolean) {
        _filesBusy.value = false
        if (timedOut) {
            _fileListing.value = com.hardwareone.console.ble.FileListing(
                false, 0, emptyList(),
                "No reply — the file browser needs the Secure Channel and an admin login.",
            )
            return
        }
        _fileListing.value = com.hardwareone.console.ble.FileListing.parse(text)
            ?: com.hardwareone.console.ble.FileListing(false, 0, emptyList(), "Unexpected reply.")
    }

    private fun onFileStatsCapture(text: String) {
        com.hardwareone.console.ble.FileStats.parse(text)?.let { _fileStats.value = it }
    }

    // Read — pull bounded windows until eof, then assemble (for viewing or downloading).
    fun openFile(name: String) {
        if (connectionState.value !is ConnectionState.Ready || _filesBusy.value) return
        readForDownload = false
        startRead(name)
    }

    /** Read a file off the device for saving to the phone (UI then shows a "Save as" picker). */
    fun downloadFile(name: String) {
        if (connectionState.value !is ConnectionState.Ready || _filesBusy.value) return
        readForDownload = true
        _fileTransfer.value = com.hardwareone.console.ble.FileTransfer(name, 0, 0, upload = false)
        startRead(name)
    }

    private fun startRead(name: String) {
        readPath = joinPath(_filesPath.value, name)
        readOffset = 0L
        readBuf.reset()
        fileOpCancelled = false
        _filesBusy.value = true
        ble.sendCaptured("fileread ${q(readPath)} 0 $readWindow", TAG_FILE_READ)
    }

    fun closeFileViewer() { _fileViewer.value = null }
    fun clearDownload() { _downloadReady.value = null }

    /** Stop an in-flight upload or download. Any in-flight reply is then ignored. */
    fun cancelTransfer() {
        fileOpCancelled = true
        writeBytes = null
        readForDownload = false
        readBuf.reset()
        _filesBusy.value = false
        _fileTransfer.value = null
        if (connectionState.value is ConnectionState.Ready) loadFiles(_filesPath.value) // a partial upload may have written
    }

    private fun failRead(msg: String) {
        _filesBusy.value = false
        if (readForDownload) _fileTransfer.value = _fileTransfer.value?.copy(finished = true, error = msg)
        else reportError(msg)
    }

    private fun onFileReadCapture(text: String, timedOut: Boolean) {
        if (fileOpCancelled) return
        if (timedOut) { failRead("File read timed out."); return }
        val r = com.hardwareone.console.ble.FileReadChunk.parse(text)
            ?: run { failRead("Unexpected file-read reply."); return }
        if (!r.success) { failRead("Read failed: ${r.error}"); return }
        val bytes = if (r.enc == "b64") {
            runCatching { android.util.Base64.decode(r.data, android.util.Base64.DEFAULT) }.getOrDefault(ByteArray(0))
        } else {
            r.data.toByteArray(Charsets.UTF_8)
        }
        readBuf.write(bytes)
        readOffset += r.len
        if (readForDownload) {
            _fileTransfer.value = _fileTransfer.value?.copy(done = readOffset, total = r.size)
        }
        if (r.eof || r.len == 0) {
            val raw = readBuf.toByteArray()
            _filesBusy.value = false
            if (readForDownload) {
                _downloadReady.value = readPath.substringAfterLast('/') to raw
                _fileTransfer.value = null // hand off to the save picker
            } else {
                val binary = raw.any { it == 0.toByte() }
                _fileViewer.value = com.hardwareone.console.ble.FileViewerState(
                    path = readPath,
                    text = if (binary) "" else String(raw, Charsets.UTF_8),
                    binary = binary,
                    size = r.size,
                )
            }
        } else {
            ble.sendCaptured("fileread ${q(readPath)} $readOffset $readWindow", TAG_FILE_READ)
        }
    }

    // Write (upload) — sequential base64 chunks sized to one secure frame.
    fun uploadFile(path: String, bytes: ByteArray) {
        if (connectionState.value !is ConnectionState.Ready || _filesBusy.value) return
        val name = path.substringAfterLast('/')
        if (hasIllegalQuote(path)) {
            _fileTransfer.value = com.hardwareone.console.ble.FileTransfer(
                name, 0, bytes.size.toLong(), finished = true,
                error = "File name can't contain a double-quote (\").",
            )
            return
        }
        if (bytes.size > 256 * 1024) {
            _fileTransfer.value = com.hardwareone.console.ble.FileTransfer(
                name, 0, bytes.size.toLong(), finished = true,
                error = "File too large for BLE (256 KB max) — use the web browser.",
            )
            return
        }
        if (bytes.isEmpty()) { createFile(path); return }
        writePath = path
        writeBytes = bytes
        writeOffset = 0L
        writeSentFinal = false
        fileOpCancelled = false
        _filesBusy.value = true
        _fileTransfer.value = com.hardwareone.console.ble.FileTransfer(name, 0, bytes.size.toLong())
        sendNextWriteChunk()
    }

    fun dismissTransfer() { _fileTransfer.value = null }

    private fun chunkSizeFor(path: String): Int {
        val cap = ble.secureCommandCapacity()
        // +2 for the surrounding quotes now required around the path.
        val overhead = "filewrite ".length + path.length + 2 + 1 + 14 + 1 + " final".length + 4
        val b64budget = (cap - overhead).coerceAtLeast(40)
        return ((b64budget / 4) * 3).coerceAtLeast(48) // raw bytes whose base64 fits the budget
    }

    private fun sendNextWriteChunk() {
        val bytes = writeBytes ?: return
        val off = writeOffset.toInt().coerceIn(0, bytes.size)
        val end = minOf(off + chunkSizeFor(writePath), bytes.size)
        val chunk = bytes.copyOfRange(off, end)
        writeSentFinal = end >= bytes.size
        val b64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
        val cmd = "filewrite ${q(writePath)} $off $b64" + if (writeSentFinal) " final" else ""
        ble.sendCaptured(cmd, TAG_FILE_WRITE)
    }

    private fun onFileWriteCapture(text: String, timedOut: Boolean) {
        if (fileOpCancelled) return
        if (timedOut) { failWrite("Upload timed out."); return }
        val r = com.hardwareone.console.ble.FileWriteResult.parse(text)
            ?: run { failWrite("Bad write reply."); return }
        if (!r.success) {
            if (r.error?.startsWith("Offset mismatch") == true) {
                writeOffset = r.size // resync to the device's actual size and resume
                sendNextWriteChunk()
            } else {
                failWrite(r.error ?: "Write failed.")
            }
            return
        }
        writeOffset = r.size
        _fileTransfer.value = _fileTransfer.value?.copy(done = r.size)
        if (writeSentFinal || r.final) {
            _fileTransfer.value = _fileTransfer.value?.copy(finished = true)
            writeBytes = null
            _filesBusy.value = false
            loadFiles(_filesPath.value) // refresh listing + stats
        } else {
            sendNextWriteChunk()
        }
    }

    private fun failWrite(msg: String) {
        _fileTransfer.value = _fileTransfer.value?.copy(finished = true, error = msg)
        writeBytes = null
        _filesBusy.value = false
    }

    // Text-reply verbs (reply lands on the console; reload to reflect the result). Every path is a
    // quoted token; bare trailing flags (confirm) stay unquoted.
    fun makeDir(name: String) {
        if (rejectQuotedName(name)) return
        fileVerb("mkdir ${q(joinPath(_filesPath.value, name))}")
    }
    fun createFile(pathOrName: String) {
        if (rejectQuotedName(pathOrName)) return
        val path = if (pathOrName.startsWith("/")) pathOrName else joinPath(_filesPath.value, pathOrName)
        fileVerb("filecreate ${q(path)}")
    }
    fun renameFile(oldName: String, newName: String) {
        if (rejectQuotedName(newName)) return
        fileVerb("filerename ${q(joinPath(_filesPath.value, oldName))} ${q(newName)}")
    }
    fun deleteFile(name: String) = fileVerb("filedelete ${q(joinPath(_filesPath.value, name))} confirm")

    /** Reject a user-typed name with a " (the firmware tokenizer can't represent it). */
    private fun rejectQuotedName(name: String): Boolean {
        if (!hasIllegalQuote(name)) return false
        reportError("Name can't contain a double-quote (\").")
        return true
    }

    private fun fileVerb(command: String) {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCommand(command)
        viewModelScope.launch { kotlinx.coroutines.delay(500); loadFiles(_filesPath.value) }
    }

    private fun onControlsCapture(moduleId: String, text: String) {
        _controlsLoading.value = _controlsLoading.value - moduleId
        com.hardwareone.console.ble.ControlsModule.parse(text)?.let {
            _controls.value = _controls.value + (moduleId to it)
        }
    }

    // --- On-device LLM chat ----------------------------------------------------------

    fun refreshLlmStatus() {
        if (connectionState.value is ConnectionState.Ready) ble.sendCaptured("llmstatus json", TAG_LLM_STATUS)
    }

    fun refreshLlmModels() {
        if (connectionState.value is ConnectionState.Ready) ble.sendCaptured("llmmodels json", TAG_LLM_MODELS)
    }

    // Fire-and-forget on the console (NOT captured): a captured command holds the single capture
    // slot until its reply arrives, and a plain-text reply (older firmware) is never claimed → it
    // would stall the whole queue for 5 s. State is read back via the status re-poll. Any JSON
    // reply that gets claimed by an in-flight capture is tolerated by the robust collector.
    fun loadLlmModel(name: String) {
        _llmLoadingModel.value = name
        _llmUnloading.value = false
        ble.sendCommand("llmload $name")
        viewModelScope.launch { kotlinx.coroutines.delay(800); refreshLlmStatus() }
        // Safety: never let the "loading…" UI stick if a settled status never arrives.
        viewModelScope.launch {
            kotlinx.coroutines.delay(45_000)
            if (_llmLoadingModel.value == name) _llmLoadingModel.value = null
        }
    }

    fun unloadLlmModel() {
        _llmUnloading.value = true
        _llmLoadingModel.value = null
        // Reset both LLM panels to a blank UNLOADED state immediately so the model/tok-s don't
        // linger stale until the next poll — the chat ModelBar (llmstatus) and the Status page's
        // "On-device LLM" card (status json connectivity.llm). Polling then keeps them accurate.
        _llmStatus.value = com.hardwareone.console.ble.LlmStatus(state = "UNLOADED", model = "", tokPerSec = 0.0, error = "")
        _deviceStatus.value = _deviceStatus.value?.let { ds ->
            val c = ds.connectivity ?: return@let ds
            val l = c.llm ?: return@let ds
            ds.copy(connectivity = c.copy(llm = l.copy(state = "UNLOADED", model = "", psramKb = 0, tokPerSec = 0.0)))
        }
        ble.sendCommand("llmunload")
        viewModelScope.launch { kotlinx.coroutines.delay(400); refreshLlmStatus() }
        viewModelScope.launch { kotlinx.coroutines.delay(15_000); _llmUnloading.value = false }
    }

    fun stopLlmGeneration() {
        llmActive = false
        _llmGenerating.value = false
        ble.sendCommand("llmstop")
    }

    fun clearLlmChat() {
        val wasGenerating = _llmGenerating.value
        llmActive = false
        _llmGenerating.value = false
        _llmMessages.value = emptyList()
        // Reset the device-side conversation too. `llmclear` is refused mid-generation, so stop
        // first. (Both are harmless no-ops if the firmware predates these commands.)
        if (connectionState.value is ConnectionState.Ready) {
            if (wasGenerating) ble.sendCommand("llmstop")
            ble.sendCommand("llmclear")
        }
    }

    /** Regenerate the last assistant reply (`llmretry` → session → stream like a normal turn). */
    fun retryLlm() {
        if (llmActive || connectionState.value !is ConnectionState.Ready) return
        val msgs = _llmMessages.value.toMutableList()
        val i = msgs.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (i < 0) return
        msgs[i] = msgs[i].copy(text = "") // clear the bubble; the new reply streams into it
        _llmMessages.value = msgs
        llmOffset = 0
        llmActive = true
        _llmGenerating.value = true
        ble.sendCaptured("llmretry", TAG_LLM_GEN) // {session} reply → onLlmGenStart → poll
    }

    /** Send a chat prompt: append the turn, start async generation, then poll for tokens. */
    fun sendLlmPrompt(prompt: String) {
        val p = prompt.trim()
        if (p.isEmpty() || llmActive) return
        if (connectionState.value !is ConnectionState.Ready) return
        _llmMessages.value = _llmMessages.value +
            ChatMessage(ChatMessage.Role.USER, p) + ChatMessage(ChatMessage.Role.ASSISTANT, "")
        llmDoTurn = false
        startLlmTurn("Q: $p\nA:") // firmware frames nothing — supply Q:/A: ourselves (web parity)
    }

    /**
     * `Do:` turn — ask the LLM to produce a CLI command for an intent (the trailing `Do:` token
     * puts the firmware in command-suggestion mode). The reply is post-processed into a single
     * command and rendered with a Run button rather than as prose.
     */
    fun sendLlmDo(intent: String) {
        val p = intent.trim()
        if (p.isEmpty() || llmActive) return
        if (connectionState.value !is ConnectionState.Ready) return
        _llmMessages.value = _llmMessages.value +
            ChatMessage(ChatMessage.Role.USER, "Do: $p") + ChatMessage(ChatMessage.Role.ASSISTANT, "")
        llmDoTurn = true
        startLlmTurn("Q: $p\nDo:") // newline is normalized to a space over BLE; trailing Do: matters
    }

    private fun startLlmTurn(framedPrompt: String) {
        llmOffset = 0
        llmActive = true
        _llmGenerating.value = true
        ble.sendCaptured("llmgenerate json $framedPrompt", TAG_LLM_GEN)
    }

    /** Run a `Do:`-suggested command on the device (output lands in the Console log). */
    fun runDoCommand(command: String) {
        val c = command.trim()
        if (c.isEmpty() || connectionState.value !is ConnectionState.Ready) return
        ble.sendCommand(c)
        _llmMessages.value = _llmMessages.value +
            ChatMessage(ChatMessage.Role.ASSISTANT, "▶ ran \"$c\" — output in Console")
    }

    private fun onLlmStatusCapture(text: String) {
        val s = com.hardwareone.console.ble.LlmStatus.parse(text) ?: return
        _llmStatus.value = s
        // Clear the optimistic load once the device confirms the requested model is ready (or errored).
        val loading = _llmLoadingModel.value
        if (loading != null && (s.errored || (s.ready && s.model.equals(loading, ignoreCase = true)))) {
            _llmLoadingModel.value = null
        }
        if (_llmUnloading.value && !s.loaded) _llmUnloading.value = false
    }

    private fun onLlmGenStart(text: String, timedOut: Boolean) {
        android.util.Log.d("HW1LLM", "genStart timedOut=$timedOut text='${text.take(60)}'")
        if (timedOut) { failLlm("No response starting generation."); return }
        val o = runCatching { org.json.JSONObject(text) }.getOrNull()
            ?: run { failLlm("LLM streaming isn't supported by this firmware yet."); return }
        if (!o.optBoolean("ok", true) || (!o.has("session") && o.has("error"))) {
            failLlm(o.optString("error", "Couldn't start generation.")); return
        }
        pollLlmResult()
    }

    private fun pollLlmResult() {
        android.util.Log.d("HW1LLM", "poll off=$llmOffset active=$llmActive")
        if (llmActive) ble.sendCaptured("llmresult json $llmOffset", TAG_LLM_RESULT)
    }

    private fun onLlmResult(text: String, timedOut: Boolean) {
        if (!llmActive) return
        if (timedOut) { failLlm("LLM stream timed out."); return }
        val o = runCatching { org.json.JSONObject(text) }.getOrNull()
        if (o == null) { failLlm("LLM streaming isn't supported by this firmware yet."); return }
        if (!o.has("text") && !o.has("done")) {
            // Valid JSON but not a result reply (e.g. a stray/mis-routed `llmstatus`) — don't kill
            // the stream over it; just poll again.
            viewModelScope.launch { kotlinx.coroutines.delay(200); pollLlmResult() }
            return
        }
        val r = com.hardwareone.console.ble.LlmResult(
            text = o.optString("text"),
            done = o.optBoolean("done", false),
            len = o.optInt("len", 0),
        )
        if (r.text.isNotEmpty()) appendAssistant(r.text)
        // Each poll returns ≤512 bytes from `offset`; `len` is the total. Advance by what was
        // actually returned (min(512, remaining)) and keep draining until offset == len, even
        // after `done` flips true — the firmware's engine buffer keeps the tail readable.
        llmOffset = minOf(llmOffset + 512, r.len)
        when {
            llmOffset < r.len -> pollLlmResult() // more is buffered — drain it now, no delay
            r.done -> {
                llmActive = false
                _llmGenerating.value = false
                if (llmDoTurn) finishDoTurn()
            }
            else -> viewModelScope.launch { kotlinx.coroutines.delay(350); pollLlmResult() }
        }
    }

    /** Turn the raw `Do:` reply into a single runnable command and mark the bubble as a command. */
    private fun finishDoTurn() {
        val msgs = _llmMessages.value.toMutableList()
        val i = msgs.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (i < 0) return
        val cmd = extractCommand(msgs[i].text)
        msgs[i] = if (cmd.isEmpty()) {
            msgs[i].copy(text = "(no command suggested)")
        } else {
            msgs[i].copy(text = cmd, command = true)
        }
        _llmMessages.value = msgs
    }

    /**
     * Reduce the model's `Do:` output to a bare command — drop anything from the first sentence
     * punctuation and strip trailing explanation words. Mirrors the firmware web UI's extraction.
     */
    private fun extractCommand(raw: String): String {
        var c = raw.trim().takeWhile { it !in ".,;!?" }.trim()
        val stop = "to|for|and|the|is|it|that|this|which|will|can|shows|displays|checks|reads"
        c = c.replace(Regex("\\s+($stop)\\b.*", RegexOption.IGNORE_CASE), "")
        return c.trim()
    }

    private fun appendAssistant(chunk: String) {
        val msgs = _llmMessages.value.toMutableList()
        val i = msgs.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (i >= 0) msgs[i] = msgs[i].copy(text = msgs[i].text + chunk)
        _llmMessages.value = msgs
    }

    /** End generation with an error — keep any partial text, else show the message in the bubble. */
    private fun failLlm(message: String) {
        llmActive = false
        _llmGenerating.value = false
        val msgs = _llmMessages.value.toMutableList()
        val i = msgs.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (i >= 0 && msgs[i].text.isBlank()) msgs[i] = msgs[i].copy(text = "⚠ $message")
        _llmMessages.value = msgs
    }

    private fun onStatusCapture(text: String, timedOut: Boolean) {
        _statusLoading.value = false
        if (timedOut || text.isBlank()) {
            _statusError.value = "No response from device."
            return
        }
        val parsed = com.hardwareone.console.ble.DeviceStatus.parse(text)
        when {
            parsed == null -> _statusError.value = "Couldn't parse device status."
            parsed.error != null -> _statusError.value = "Device busy (${parsed.error}); retrying…"
            else -> {
                _deviceStatus.value = parsed
                _statusError.value = null
            }
        }
    }

    /** Send whatever the user typed. Login lines have their password masked in the log. */
    fun send(rawInput: String) {
        val command = rawInput.trim()
        if (command.isEmpty()) return
        // Capture the username from a typed `login <user> <pass>` so the status chip can show it.
        val parts = command.split(Regex("\\s+"))
        if (parts.size >= 3 && parts[0].equals("login", ignoreCase = true)) pendingUser = parts[1]
        // Recall history — never store a `login …` line (it carries the password).
        if (!parts[0].equals("login", ignoreCase = true)) {
            _commandHistory.value = (listOf(command) + _commandHistory.value.filter { it != command }).take(20)
        }
        append(LogEntry("> ${maskIfLogin(command)}", LogEntry.Kind.OUTGOING))
        ble.sendCommand(command)
    }

    /** Convenience login from the dialog (keeps the password out of the visible log). */
    fun login(username: String, password: String) {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            append(LogEntry("Username and password are required.", LogEntry.Kind.ERROR))
            return
        }
        pendingUser = user
        append(LogEntry("> login $user ********", LogEntry.Kind.OUTGOING))
        ble.sendCommand("login $user $password")
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    /** Surface an Activity-level message (e.g. permission denied) in the console. */
    fun reportError(message: String) {
        append(LogEntry(message, LogEntry.Kind.ERROR))
    }

    fun reportInfo(message: String) {
        append(LogEntry(message, LogEntry.Kind.INFO))
    }

    // --- Credential storage facade (crypto is pure; the Activity supplies the prompt) ---

    fun setAutoLogin(enabled: Boolean) {
        credentials.autoLogin = enabled
        _autoLogin.value = enabled
    }

    fun forgetCredentials() {
        credentials.clear()
        refreshCredentialState()
        reportInfo("Saved credentials cleared.")
    }

    /** Cipher to authorise via BiometricPrompt before [commitCredentials]. */
    fun encryptCipherOrNull(): Cipher? = runCatching { credentials.encryptCipher() }.getOrNull()

    /** Cipher to authorise via BiometricPrompt before [readStoredPassword]. */
    fun decryptCipherOrNull(): Cipher? = credentials.decryptCipher()

    fun commitCredentials(authedCipher: Cipher, username: String, password: String) {
        runCatching { credentials.saveCredentials(authedCipher, username, password) }
            .onSuccess {
                credentials.autoLogin = true
                refreshCredentialState()
                reportInfo("Credentials saved (hardware-encrypted, auth required).")
            }
            .onFailure { reportError("Failed to save credentials: ${it.message}") }
    }

    fun readStoredPassword(authedCipher: Cipher): String? =
        runCatching { credentials.readPassword(authedCipher) }.getOrNull()

    private fun refreshCredentialState() {
        _autoLogin.value = credentials.autoLogin
        _hasSavedCredentials.value = credentials.hasStoredPassword()
        _savedUsername.value = credentials.savedUsername
    }

    // --- Encrypted log storage ---------------------------------------------------------

    val canSaveLogs: Boolean get() = logVault.canAuthenticate()

    /** On-device path where the encrypted saved logs live (for the info dialog). */
    val logStorageLocation: String get() = logVault.storageLocation()
    fun logAllowedAuthenticators(): Int = logVault.allowedAuthenticators()

    private val _autoSaveLogs = MutableStateFlow(logVault.autoSave)
    val autoSaveLogs: StateFlow<Boolean> = _autoSaveLogs.asStateFlow()

    private val _savedLogs = MutableStateFlow(logVault.list())
    val savedLogs: StateFlow<List<SavedLog>> = _savedLogs.asStateFlow()

    fun setAutoSaveLogs(enabled: Boolean) {
        logVault.autoSave = enabled
        _autoSaveLogs.value = enabled
    }

    fun refreshSavedLogs() { _savedLogs.value = logVault.list() }

    private fun currentLogText(): String = _log.value.joinToString("\n") { it.text }

    /** Manual snapshot of the current console log to an encrypted file. */
    fun saveCurrentLog() {
        val text = currentLogText()
        if (text.isBlank()) { reportError("Nothing to save."); return }
        val ok = logVault.save(text, logVault.timestampedFileName(System.currentTimeMillis()))
        if (ok) { refreshSavedLogs(); reportInfo("Log saved (encrypted).") }
        else reportError("Failed to save log.")
    }

    private var lastAutoSaveHash: Int? = null

    /**
     * Silent auto-save (called when the app is backgrounded). Writes a new, distinct
     * timestamped file — but skips when the log is unchanged since the last auto-save, so
     * repeated backgrounding without new output doesn't pile up duplicate files.
     */
    fun autoSaveLogIfEnabled() {
        if (!_autoSaveLogs.value) return
        val text = currentLogText()
        if (text.isBlank()) return
        val hash = text.hashCode()
        if (hash == lastAutoSaveHash) return
        if (logVault.save(text, logVault.autoFileName(System.currentTimeMillis()))) {
            lastAutoSaveHash = hash
            refreshSavedLogs()
        }
    }

    fun deleteSavedLog(fileName: String) {
        logVault.delete(fileName)
        refreshSavedLogs()
    }

    /** RSA decrypt cipher to authorise via BiometricPrompt, or null if none saved. */
    fun logDecryptCipherOrNull(): Cipher? = logVault.decryptCipher()

    fun finishLogDecrypt(fileName: String, authedCipher: Cipher): String? =
        logVault.finishDecrypt(fileName, authedCipher)

    /** Replace the live console with a previously saved (decrypted) log. */
    fun loadLogIntoConsole(text: String) {
        val lines = text.split("\n").map { LogEntry(it, LogEntry.Kind.INFO) }
        _log.value = (listOf(LogEntry("— restored saved log —", LogEntry.Kind.INFO)) + lines)
            .let { if (it.size > MAX_LOG_LINES) it.takeLast(MAX_LOG_LINES) else it }
    }

    // --- Internals ------------------------------------------------------------------

    private fun maskIfLogin(command: String): String {
        val parts = command.split(Regex("\\s+"))
        return if (parts.size >= 3 && parts[0].equals("login", ignoreCase = true)) {
            "login ${parts[1]} ********"
        } else {
            command
        }
    }

    private fun append(entry: LogEntry) {
        // Keep the log bounded so very long sessions don't grow unbounded.
        val next = (_log.value + entry)
        _log.value = if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
        _logTotal.value = _logTotal.value + 1
    }

    // --- Secure channel (app-layer encryption) passphrase ------------------------------

    private val _secureChannelConfigured = MutableStateFlow(channelSecret.has())
    val secureChannelConfigured: StateFlow<Boolean> = _secureChannelConfigured.asStateFlow()

    /** Configured but the saved passphrase couldn't be decrypted — user must re-enter it. */
    private val _secureChannelLocked = MutableStateFlow(false)
    val secureChannelLocked: StateFlow<Boolean> = _secureChannelLocked.asStateFlow()

    fun setChannelPassphrase(passphrase: String) {
        val pass = passphrase.trim()
        if (pass.length < 8) {
            reportError("Secure-channel passphrase must be at least 8 characters.")
            return
        }
        channelSecret.put(pass)
        _secureChannelConfigured.value = true
        _secureChannelLocked.value = false
        ble.setSecureExpected(true)
        viewModelScope.launch(Dispatchers.Default) { ble.setPsk(SecureChannel.derivePsk(pass)) }
        reportInfo("Secure-channel passphrase saved. It applies on the next connect.")
    }

    fun clearChannelPassphrase() {
        channelSecret.clear()
        ble.setPsk(null)
        ble.setSecureExpected(false)
        _secureChannelConfigured.value = false
        _secureChannelLocked.value = false
        reportInfo("Secure channel disabled (passphrase cleared).")
    }

    override fun onCleared() {
        super.onCleared()
        ble.release()
    }

    private fun BleMessage.Kind.toLogKind(): LogEntry.Kind = when (this) {
        BleMessage.Kind.INCOMING -> LogEntry.Kind.INCOMING
        BleMessage.Kind.INFO -> LogEntry.Kind.INFO
        BleMessage.Kind.ERROR -> LogEntry.Kind.ERROR
    }

    private companion object {
        const val MAX_LOG_LINES = 2000
        const val TAG_STATUS = "status"
        const val TAG_DEVICES = "devices"
        const val TAG_SENSORS = "sensors"
        const val TAG_CONTROL_MODULES = "controlmods"
        const val TAG_CONTROLS_PREFIX = "controls:"
        const val TAG_BATTERY = "battery"
        const val TAG_LLM_STATUS = "llmstatus"
        const val TAG_LLM_MODELS = "llmmodels"
        const val TAG_LLM_GEN = "llmgen"
        const val TAG_LLM_RESULT = "llmresult"
        const val TAG_LLM_CMD = "llmcmd" // one-shot load/unload/stop/clear; reply captured & ignored
        const val TAG_FILES = "files"
        const val TAG_FILE_STATS = "filestats"
        const val TAG_FILE_READ = "fileread"
        const val TAG_FILE_WRITE = "filewrite"
        const val TAG_EN_MODE = "enmode"
        const val TAG_EN_ENC = "enenc"
        const val TAG_EN_MESHROLE = "enmeshrole"
        const val TAG_EN_DEVINFO = "endevinfo"
        const val TAG_EN_LIST = "enlist"
        const val TAG_EN_MESH = "enmesh"
        const val TAG_EN_MSGS = "enmsgs"
        const val TAG_EN_REMOTE_ACK = "enremoteack"
    }
}
