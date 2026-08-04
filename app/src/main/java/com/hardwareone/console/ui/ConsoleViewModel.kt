package com.hardwareone.console.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareone.console.ble.BleManager
import com.hardwareone.console.ble.BleMessage
import com.hardwareone.console.ble.ChatMessage
import com.hardwareone.console.ble.CameraStatus
import com.hardwareone.console.ble.ConnectionState
import com.hardwareone.console.ble.DiscoveredDevice
import com.hardwareone.console.ble.FileReadChunk
import com.hardwareone.console.ble.ImageItem
import com.hardwareone.console.ble.ImageListing
import com.hardwareone.console.ble.MediaBlob
import com.hardwareone.console.ble.MicStatus
import com.hardwareone.console.ble.OtaBundle
import com.hardwareone.console.ble.OtaDeviceStatus
import com.hardwareone.console.ble.OtaStep
import com.hardwareone.console.ble.OtaUiState
import com.hardwareone.console.ble.RecordingItem
import com.hardwareone.console.security.CredentialStore
import com.hardwareone.console.security.LogVault
import com.hardwareone.console.security.SavedLog
import com.hardwareone.console.security.SecretBox
import com.hardwareone.console.security.SecureChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    /** Secure-handshake failure reason for the Devices-page dialog (null = nothing to show). */
    val secureFailure: StateFlow<String?> = ble.secureFailure

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

    // --- Perf / memory profiler (Perf screen; mirrors the OLED Perf mode) ---
    private val _memReport = MutableStateFlow<com.hardwareone.console.ble.MemReport?>(null)
    val memReport: StateFlow<com.hardwareone.console.ble.MemReport?> = _memReport.asStateFlow()
    private val _fsUsage = MutableStateFlow<com.hardwareone.console.ble.FsUsage?>(null)
    val fsUsage: StateFlow<com.hardwareone.console.ble.FsUsage?> = _fsUsage.asStateFlow()
    private val _perfTop = MutableStateFlow<com.hardwareone.console.ble.PerfTop?>(null)
    val perfTop: StateFlow<com.hardwareone.console.ble.PerfTop?> = _perfTop.asStateFlow()
    private val _taskStats = MutableStateFlow<com.hardwareone.console.ble.TaskStats?>(null)
    val taskStats: StateFlow<com.hardwareone.console.ble.TaskStats?> = _taskStats.asStateFlow()

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
    private enum class WriteOwner { FILE_BROWSER, OTA_FIRMWARE, OTA_MANIFEST }
    private var writeOwner = WriteOwner.FILE_BROWSER

    // --- Signed offline OTA (local .hw1ota bundle -> secure BLE -> recovery updater) ---
    private val _ota = MutableStateFlow(OtaUiState())
    val ota: StateFlow<OtaUiState> = _ota.asStateFlow()
    private var otaAwaitingReturn = false
    private var otaReconnectJob: Job? = null
    private var otaPollJob: Job? = null
    private var otaStatusInFlight = false

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

    // --- Camera + microphone media pages ---
    // Live pixels/audio are HTTP-only; the app drives capture-to-file + tuning + a saved-file
    // browser, pulling JPEG/WAV via an independent windowed binary fileread (see media-read below).
    private val _cameraStatus = MutableStateFlow<CameraStatus?>(null)
    val cameraStatus: StateFlow<CameraStatus?> = _cameraStatus.asStateFlow()
    private val _gallery = MutableStateFlow<List<ImageItem>?>(null)
    val gallery: StateFlow<List<ImageItem>?> = _gallery.asStateFlow()
    private val _viewedImage = MutableStateFlow<MediaBlob?>(null)
    val viewedImage: StateFlow<MediaBlob?> = _viewedImage.asStateFlow()

    private val _micStatus = MutableStateFlow<MicStatus?>(null)
    val micStatus: StateFlow<MicStatus?> = _micStatus.asStateFlow()
    private val _micLevel = MutableStateFlow(0)
    val micLevel: StateFlow<Int> = _micLevel.asStateFlow()
    private val _recordings = MutableStateFlow<List<RecordingItem>?>(null)
    val recordings: StateFlow<List<RecordingItem>?> = _recordings.asStateFlow()
    private val _audioClip = MutableStateFlow<MediaBlob?>(null)
    val audioClip: StateFlow<MediaBlob?> = _audioClip.asStateFlow()

    private val _mediaBusy = MutableStateFlow(false)
    val mediaBusy: StateFlow<Boolean> = _mediaBusy.asStateFlow()
    private val _mediaError = MutableStateFlow<String?>(null)
    val mediaError: StateFlow<String?> = _mediaError.asStateFlow()

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
    private val _espNowDevices = MutableStateFlow<com.hardwareone.console.ble.EspNowMeshDevices?>(null)
    val espNowDevices: StateFlow<com.hardwareone.console.ble.EspNowMeshDevices?> = _espNowDevices.asStateFlow()
    private val _espNowBond = MutableStateFlow<com.hardwareone.console.ble.EspNowBond?>(null)
    val espNowBond: StateFlow<com.hardwareone.console.ble.EspNowBond?> = _espNowBond.asStateFlow()

    // --- Automations (device-level; `automationlist json` + control verbs) ---
    private val _automations = MutableStateFlow<com.hardwareone.console.ble.AutomationList?>(null)
    val automations: StateFlow<com.hardwareone.console.ble.AutomationList?> = _automations.asStateFlow()
    private val _automationsBusy = MutableStateFlow(false)
    val automationsBusy: StateFlow<Boolean> = _automationsBusy.asStateFlow()
    private val _automationsSystemOn = MutableStateFlow<Boolean?>(null) // global enable; null = unknown
    val automationsSystemOn: StateFlow<Boolean?> = _automationsSystemOn.asStateFlow()
    private val _automationStatus = MutableStateFlow<String?>(null) // transient run result (error shown)
    val automationStatus: StateFlow<String?> = _automationStatus.asStateFlow()
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
    private var espNowFeedEpoch = 0L       // bumped each open; invalidates a prior open's in-flight reply
    private var espNowInFlightEpoch = 0L   // epoch the outstanding request was issued under
    private val espNowRecords = LinkedHashMap<Long, com.hardwareone.console.ble.EspNowMessages.Message>()
    // reqIds of remote commands / file listings THIS app issued. Post the chunked-store rework, every
    // real peer text record also carries a (nonzero) reqId — its message-group id — so reqId alone no
    // longer separates chat from command output. A RECEIVED record is command/files output only if its
    // reqId is one we issued; any other nonzero reqId is peer text. Reset on peer switch with the buffer.
    private val espNowIssuedReqIds = HashSet<Long>()

    // Remote command runner (device detail → Command tab).
    private val _espNowRemoteBusy = MutableStateFlow(false)
    val espNowRemoteBusy: StateFlow<Boolean> = _espNowRemoteBusy.asStateFlow()
    private val _espNowRemoteError = MutableStateFlow<String?>(null)
    val espNowRemoteError: StateFlow<String?> = _espNowRemoteError.asStateFlow()
    private val _espNowRemoteResult = MutableStateFlow<String?>(null)
    val espNowRemoteResult: StateFlow<String?> = _espNowRemoteResult.asStateFlow()
    private var espNowRemoteReqId = 0L
    private var espNowRemoteLastProgressMs = 0L

    // --- ESP-NOW remote file browser (peer's files via `espnowremote <mac> <user> <pass> files json`) ---
    // The peer runs the SAME `files json` builder as local, so the result parses with the local
    // FileListing parser and renders with the local Files UI. The JSON streams back through the
    // espnowmessages feed reqId-matched (same path as a remote command), assembled on the empty page.
    private val _espNowFilesListing = MutableStateFlow<com.hardwareone.console.ble.FileListing?>(null)
    val espNowFilesListing: StateFlow<com.hardwareone.console.ble.FileListing?> = _espNowFilesListing.asStateFlow()
    private val _espNowFilesPath = MutableStateFlow("/")
    val espNowFilesPath: StateFlow<String> = _espNowFilesPath.asStateFlow()
    private val _espNowFilesBusy = MutableStateFlow(false)
    val espNowFilesBusy: StateFlow<Boolean> = _espNowFilesBusy.asStateFlow()
    private val _espNowFilesError = MutableStateFlow<String?>(null)
    val espNowFilesError: StateFlow<String?> = _espNowFilesError.asStateFlow()
    private var espNowFilesReqId = 0L
    private var espNowFilesUser = ""
    private var espNowFilesPass = ""

    // Fetch a peer file onto THIS (gateway) device (`espnowfetch`). The transfer lands at
    // /espnow/received/<peerMacToken>/<name>. Completion is the terminal type:4 (recv-success) /
    // type:5 (recv-failed) feed record — NOT reqId-matched, so we correlate by peer + filename.
    private val _espNowFetchBusy = MutableStateFlow(false)
    val espNowFetchBusy: StateFlow<Boolean> = _espNowFetchBusy.asStateFlow()
    private val _espNowFetchStatus = MutableStateFlow<String?>(null)
    val espNowFetchStatus: StateFlow<String?> = _espNowFetchStatus.asStateFlow()
    private var espNowFetchTarget = ""   // basename we're waiting for a type:4/5 event about
    private var espNowFetchMac = ""

    // --- Maps page (map/maplist JSON + waypoint/gpstrack text verbs + .hwmap fetch/render) ---
    private val _mapInfo = MutableStateFlow<com.hardwareone.console.ble.MapInfo?>(null)
    val mapInfo: StateFlow<com.hardwareone.console.ble.MapInfo?> = _mapInfo.asStateFlow()
    private val _mapList = MutableStateFlow<List<String>>(emptyList())
    val mapList: StateFlow<List<String>> = _mapList.asStateFlow()
    private val _mapsBusy = MutableStateFlow(false)
    val mapsBusy: StateFlow<Boolean> = _mapsBusy.asStateFlow()
    private val _mapsError = MutableStateFlow<String?>(null)
    val mapsError: StateFlow<String?> = _mapsError.asStateFlow()
    private val _mapsNotice = MutableStateFlow<String?>(null) // transient text-verb feedback
    val mapsNotice: StateFlow<String?> = _mapsNotice.asStateFlow()

    // The parsed map the app renders (fetched via fileread, cached on the phone) + its device path.
    private val _mapFile = MutableStateFlow<com.hardwareone.console.ble.HwMapFile?>(null)
    val mapFile: StateFlow<com.hardwareone.console.ble.HwMapFile?> = _mapFile.asStateFlow()
    private val _mapFilePath = MutableStateFlow<String?>(null)
    val mapFilePath: StateFlow<String?> = _mapFilePath.asStateFlow()
    private val _mapDownload = MutableStateFlow<com.hardwareone.console.ble.FileTransfer?>(null)
    val mapDownload: StateFlow<com.hardwareone.console.ble.FileTransfer?> = _mapDownload.asStateFlow()

    private val _mapWaypoints = MutableStateFlow<List<com.hardwareone.console.ble.Waypoint>>(emptyList())
    val mapWaypoints: StateFlow<List<com.hardwareone.console.ble.Waypoint>> = _mapWaypoints.asStateFlow()
    private val _mapTarget = MutableStateFlow(-1) // nav-target slot index, -1 = none
    val mapTarget: StateFlow<Int> = _mapTarget.asStateFlow()

    private val _mapGps = MutableStateFlow<com.hardwareone.console.ble.GpsFix?>(null)
    val mapGps: StateFlow<com.hardwareone.console.ble.GpsFix?> = _mapGps.asStateFlow()

    // App-side GPS polling controls (persisted). The map's satellite HUD button flips [mapGpsUpdates];
    // [mapGpsIntervalMs] is the poll cadence, chosen in the Maps "GPS updates" settings.
    private val mapsPrefs = getApplication<Application>()
        .getSharedPreferences("hw_console_prefs", android.content.Context.MODE_PRIVATE)
    private val _mapGpsUpdates = MutableStateFlow(mapsPrefs.getBoolean("map_gps_updates", true))
    val mapGpsUpdates: StateFlow<Boolean> = _mapGpsUpdates.asStateFlow()
    private val _mapGpsIntervalMs = MutableStateFlow(mapsPrefs.getInt("map_gps_interval_ms", 5_000))
    val mapGpsIntervalMs: StateFlow<Int> = _mapGpsIntervalMs.asStateFlow()

    fun setMapGpsUpdates(on: Boolean) {
        _mapGpsUpdates.value = on
        mapsPrefs.edit().putBoolean("map_gps_updates", on).apply()
        if (on) mapGpsMisses = 0 // re-arm the backoff so it starts polling again immediately
    }

    fun setMapGpsInterval(ms: Int) {
        _mapGpsIntervalMs.value = ms.coerceIn(1_000, 60_000)
        mapsPrefs.edit().putInt("map_gps_interval_ms", _mapGpsIntervalMs.value).apply()
    }

    private val _mapTrack = MutableStateFlow<com.hardwareone.console.ble.GpsTrack?>(null)
    val mapTrack: StateFlow<com.hardwareone.console.ble.GpsTrack?> = _mapTrack.asStateFlow()
    private val _mapTrackFiles = MutableStateFlow<List<String>>(emptyList()) // absolute device paths
    val mapTrackFiles: StateFlow<List<String>> = _mapTrackFiles.asStateFlow()
    private val _mapLiveTracking = MutableStateFlow(false)
    val mapLiveTracking: StateFlow<Boolean> = _mapLiveTracking.asStateFlow()

    private val _mapTrackFilesLoading = MutableStateFlow(false)
    val mapTrackFilesLoading: StateFlow<Boolean> = _mapTrackFilesLoading.asStateFlow()

    // Windowed-fileread loop for map/sidecar/track fetches (independent of the Files page's loop).
    // All of this state is confined to the MAIN dispatcher (the capture collector's thread) —
    // background parsers hop back before touching it.
    private var mapReadPath = ""
    private var mapReadOffset = 0L
    private var mapReadRetried = false
    private var mapReadPurpose = MAP_READ_NONE
    private var mapReadExpected = 0L
    private val mapReadBuf = java.io.ByteArrayOutputStream()
    private val mapReadCandidates = ArrayDeque<String>() // fallback paths for the map download
    // Discovery fallback: browse /maps via `files json` when the reconstructed paths all 404.
    private var mapDiscoverTried = false
    private val mapDiscoverDirs = ArrayDeque<String>()
    private val mapDiscoverFound = mutableListOf<String>()
    private var mapDiscoverCurrentDir = ""
    private val mapSidecarCandidates = ArrayDeque<String>()
    // A sidecar fileread that TIMES OUT (vs. replying "not found") means this session can't use
    // fileread at all (needs Secure Channel + admin) — stop auto-probing so the background fetch
    // doesn't hog the single capture slot for 5 s per candidate after every waypoint refresh.
    private var mapSidecarUnavailable = false
    private var mapGpsMisses = 0 // stop polling gpsread after 3 straight timeouts (no GPS sensor)
    private val liveTrackPoints = mutableListOf<com.hardwareone.console.ble.TrackPoint>()
    private val mapTrackDirs = ArrayDeque<String>()
    private var mapTrackCurrentDir = ""
    private var mapTrackDirDenied = false
    private val mapTrackFilesTmp = mutableListOf<String>()
    private var waypointScrapeJob: kotlinx.coroutines.Job? = null
    // Serialises the text-verb scrapes: two live scrapes would both claim the first matching
    // console line, reporting one verb's verdict for the other.
    private val mapScrapeMutex = kotlinx.coroutines.sync.Mutex()

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
                if (authed && otaAwaitingReturn && connectionState.value is ConnectionState.Ready) {
                    requestOtaMonitor()
                }
            }
        }
        // Fetch battery the instant a device is Ready (no auth) so it's there on connect, not
        // a poll-interval later. The auth branch above covers the login-required case.
        // On losing Ready, unwedge multi-step transfer machines whose in-flight capture was
        // dropped by the disconnect: the Files busy latch/upload buffer and the maps read loop.
        viewModelScope.launch {
            ble.state.collect { st ->
                if (st is ConnectionState.Ready) {
                    refreshBattery()
                    if (otaAwaitingReturn) {
                        if (authenticated.value) requestOtaMonitor()
                        else _ota.value = _ota.value.copy(
                            step = OtaStep.WAITING_FOR_LOGIN,
                            message = "Device is back. Log in to verify the OTA result.",
                            error = null,
                        )
                    }
                } else {
                    _filesBusy.value = false
                    if (writeOwner != WriteOwner.FILE_BROWSER && writeBytes != null) {
                        failOta("Bluetooth disconnected while the OTA package was being staged. Reconnect and restart the upload.")
                    }
                    writeBytes = null
                    _mapsBusy.value = false
                    _mapTrackFilesLoading.value = false
                    mapSidecarCandidates.clear()
                    mapReadCandidates.clear()
                    if (mapReadPurpose != MAP_READ_NONE) {
                        mapReadPurpose = MAP_READ_NONE
                        _mapDownload.value = _mapDownload.value?.copy(finished = true, error = "Disconnected.")
                    }
                    if (otaAwaitingReturn) {
                        _ota.value = _ota.value.copy(
                            step = OtaStep.WAITING_FOR_DEVICE,
                            message = "Firmware is updating. Waiting for HardwareOne to return over Bluetooth…",
                            error = null,
                        )
                        ensureOtaReconnect()
                    }
                }
            }
        }
        // Keep the home/lock-screen widget's last-known snapshot fresh (connection + battery +
        // identity). The widget can't drive BLE itself, so it just renders what we persist here on
        // each change; `updatedLabel` lets it show how stale the glance is. `deviceInfo` carries the
        // address (the widget's reconnect target) and firmware; push() keeps the last non-empty
        // identity, so it survives a disconnect and a process restart.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(connectionState, battery, deviceInfo) { st, bat, info ->
                Triple(st, bat, info)
            }.collect { pushWidgetSnapshot() }
        }
        // Heartbeat: while connected, re-stamp the widget snapshot every couple of minutes so a live
        // but idle link (no battery/state change) doesn't age into "stale". When the app is killed the
        // heartbeat stops, so the widget greys itself out on its own update period.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(120_000)
                if (connectionState.value is ConnectionState.Ready) pushWidgetSnapshot()
            }
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
                        tag.startsWith(TAG_SENSOR_READ) -> onSensorRead(tag.removePrefix(TAG_SENSOR_READ), capture.text, capture.timedOut)
                        tag == TAG_CONTROL_MODULES -> onControlModulesCapture(capture.text)
                        tag == TAG_BATTERY -> onBatteryCapture(capture.text, capture.timedOut)
                        tag == TAG_MEMREPORT -> { if (!capture.timedOut) com.hardwareone.console.ble.MemReport.parse(capture.text)?.let { _memReport.value = it } }
                        tag == TAG_FSUSAGE -> { if (!capture.timedOut) com.hardwareone.console.ble.FsUsage.parse(capture.text)?.let { _fsUsage.value = it } }
                        tag == TAG_PERFTOP -> { if (!capture.timedOut) com.hardwareone.console.ble.PerfTop.parse(capture.text)?.let { _perfTop.value = it } }
                        tag == TAG_TASKSTATS -> { if (!capture.timedOut) com.hardwareone.console.ble.TaskStats.parse(capture.text)?.let { _taskStats.value = it } }
                        tag == TAG_LLM_STATUS -> onLlmStatusCapture(capture.text)
                        tag == TAG_LLM_MODELS -> com.hardwareone.console.ble.LlmStatus.parseModels(capture.text)?.let { _llmModels.value = it }
                        tag == TAG_LLM_GEN -> onLlmGenStart(capture.text, capture.timedOut)
                        tag == TAG_LLM_RESULT -> onLlmResult(capture.text, capture.timedOut)
                        tag == TAG_FILES -> onFilesCapture(capture.text, capture.timedOut)
                        tag == TAG_FILE_STATS -> onFileStatsCapture(capture.text)
                        tag == TAG_FILE_READ -> onFileReadCapture(capture.text, capture.timedOut)
                        tag == TAG_FILE_WRITE -> onFileWriteCapture(capture.text, capture.timedOut)
                        tag.startsWith(TAG_OTA_PREFIX) -> onOtaStatusCapture(tag, capture.text, capture.timedOut)
                        tag == TAG_EN_MODE -> { if (!capture.timedOut) com.hardwareone.console.ble.EspNowMode.parse(capture.text)?.let { _espNowMode.value = it } }
                        tag == TAG_EN_ENC -> { if (!capture.timedOut) com.hardwareone.console.ble.EspNowEnc.parse(capture.text)?.let { _espNowEnc.value = it } }
                        tag == TAG_EN_MESHROLE -> { if (!capture.timedOut) com.hardwareone.console.ble.EspNowMeshRole.parse(capture.text)?.let { _espNowMeshRole.value = it } }
                        tag == TAG_EN_DEVINFO -> { if (!capture.timedOut) com.hardwareone.console.ble.EspNowDeviceInfo.parse(capture.text)?.let { _espNowDeviceInfo.value = it } }
                        tag == TAG_EN_LIST -> { if (!capture.timedOut) com.hardwareone.console.ble.EspNowPaired.parse(capture.text)?.let { _espNowPaired.value = it } }
                        tag == TAG_EN_MESH -> { _espNowLoading.value = false; if (!capture.timedOut) com.hardwareone.console.ble.EspNowMeshStatus.parse(capture.text)?.let { _espNowMesh.value = it } }
                        tag == TAG_EN_DEVICES -> { if (!capture.timedOut) com.hardwareone.console.ble.EspNowMeshDevices.parse(capture.text)?.let { _espNowDevices.value = it } }
                        tag == TAG_EN_BOND -> { if (!capture.timedOut) com.hardwareone.console.ble.EspNowBond.parse(capture.text)?.let { _espNowBond.value = it } }
                        tag == TAG_AUTOMATIONS -> { _automationsBusy.value = false; if (!capture.timedOut) com.hardwareone.console.ble.AutomationList.parse(capture.text)?.let { _automations.value = it } }
                        tag == TAG_AUTOMATION_SYS -> {
                            // `automation system status|enable|disable json` → {"schema":1,"enabled":bool}.
                            if (!capture.timedOut) runCatching { org.json.JSONObject(capture.text) }.getOrNull()
                                ?.takeIf { it.has("enabled") }?.let { _automationsSystemOn.value = it.optBoolean("enabled") }
                        }
                        tag == TAG_EN_MSGS -> onEspNowFeedMessages(capture.text, capture.timedOut)
                        tag == TAG_EN_REMOTE_ACK -> onEspNowRemoteAck(capture.text, capture.timedOut)
                        tag == TAG_EN_FILES_ACK -> onEspNowFilesAck(capture.text, capture.timedOut)
                        tag == TAG_EN_FETCH_ACK -> onEspNowFetchAck(capture.text, capture.timedOut)
                        tag == TAG_MAP_INFO -> onMapInfoCapture(capture.text, capture.timedOut)
                        tag == TAG_MAP_LIST -> { if (!capture.timedOut) com.hardwareone.console.ble.MapListing.parse(capture.text)?.let { _mapList.value = it.maps } }
                        tag == TAG_MAP_GPS -> onMapGpsCapture(capture.text, capture.timedOut)
                        tag == TAG_MAP_READ -> onMapReadCapture(capture.text, capture.timedOut)
                        tag == TAG_MAP_DISCOVER -> onMapDiscoverCapture(capture.text, capture.timedOut)
                        tag == TAG_MAP_TRACKDIR -> onMapTrackDirCapture(capture.text, capture.timedOut)
                        tag == TAG_CAM_STATUS -> { if (!capture.timedOut) CameraStatus.parse(capture.text)?.let { _cameraStatus.value = it } }
                        tag == TAG_IMAGES_LFS -> onImagesCapture(capture.text, "LittleFS")
                        tag == TAG_IMAGES_SD -> onImagesCapture(capture.text, "SD")
                        // micread json carries the live level too, so one poll updates both.
                        tag == TAG_MIC_STATUS -> { if (!capture.timedOut) MicStatus.parse(capture.text)?.let { _micStatus.value = it; _micLevel.value = it.level } }
                        tag == TAG_MIC_LEVEL -> { if (!capture.timedOut) MicStatus.parseLevel(capture.text)?.let { _micLevel.value = it } }
                        tag == TAG_MIC_LIST -> { if (!capture.timedOut) RecordingItem.parse(capture.text)?.let { _recordings.value = it } }
                        tag == TAG_MEDIA_READ -> onMediaReadCapture(capture.text, capture.timedOut)
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
    /** Connect by MAC — used by the widget's tap-to-reconnect (the address is persisted, unlike the
     *  in-memory `lastDevice` that [reconnect] relies on, so this still works after a cold start). */
    fun connectAddress(address: String) = ble.connect(address)

    /** Persist the current device snapshot to the home/lock-screen widget (connection, battery,
     *  identity, timestamp). Called on every state change and on the connected heartbeat. */
    private fun pushWidgetSnapshot() {
        val st = connectionState.value
        val bat = battery.value
        val info = deviceInfo.value
        val updatedLabel = android.text.format.DateFormat
            .getTimeFormat(getApplication()).format(java.util.Date())
        com.hardwareone.console.widget.HardwareOneWidgetProvider.push(
            context = getApplication(),
            connected = st is ConnectionState.Ready,
            name = info?.name ?: com.hardwareone.console.ble.deviceNameOrNull(st),
            address = info?.address,
            batteryPct = bat?.percentage ?: -1,
            charging = bat?.charging == true,
            hasBattery = bat?.available == true,
            voltage = bat?.voltage ?: 0.0,
            firmware = info?.firmware,
            updatedLabel = updatedLabel,
        )
    }
    fun disconnect() = ble.disconnect()
    fun reconnect() = ble.reconnect()
    fun dismissSecureFailure() = ble.dismissSecureFailure()
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

    // --- Perf / memory profiler ------------------------------------------------------

    /** Memory page: heap/PSRAM (`memreport json`) + filesystem (`fsusage json`). */
    fun refreshMemory() {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCaptured("memreport json", TAG_MEMREPORT)
        ble.sendCaptured("fsusage json", TAG_FSUSAGE)
    }

    /** CPU page: loop health + per-task CPU% (`perftop json` — samples ~0.75 s on-device). */
    fun refreshPerfCpu() {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCaptured("perftop json", TAG_PERFTOP)
    }

    /** Stack page: per-task state/prio/stack min-free (`taskstats json`). */
    fun refreshPerfStack() {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCaptured("taskstats json", TAG_TASKSTATS)
    }

    /** Drop cached perf snapshots (call when leaving the Perf page). */
    fun clearPerf() {
        _memReport.value = null
        _fsUsage.value = null
        _perfTop.value = null
        _taskStats.value = null
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
        ble.sendCaptured("espnowdevices json", TAG_EN_DEVICES) // peer smart-home metadata + liveness
        ble.sendCaptured("espnowmeshstatus json", TAG_EN_MESH) // sent last → clears the busy flag
    }

    /** Fetch the peer metadata snapshot (`espnowdevices json`) — reads the gateway's CACHED store. */
    fun refreshEspNowDevices() {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCaptured("espnowdevices json", TAG_EN_DEVICES)
    }

    // --- ESP-NOW bond (this device + its bonded peer, from `bondstatus json`) ---

    /** Load bond status (`bondstatus json`) — reports both sides from the gateway's live view. */
    fun loadEspNowBond() {
        if (connectionState.value is ConnectionState.Ready) ble.sendCaptured("bondstatus json", TAG_EN_BOND)
    }

    private fun bondCmdThenReload(cmd: String) {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCommand(cmd)
        viewModelScope.launch { kotlinx.coroutines.delay(1_000); loadEspNowBond() }
    }

    fun bondConnect(peer: String) { if (peer.isNotEmpty()) bondCmdThenReload("bondconnect $peer") }
    fun bondDisconnect() = bondCmdThenReload("bonddisconnect")
    fun bondResync() = bondCmdThenReload("bondresync")

    /**
     * Swap master/worker on BOTH devices (mirrors the web's bond role swap). Flip the PEER first via
     * the bond session (`remote:bondrole …`, routed to the bonded device, no creds), then flip
     * locally — the order matters: doing local first races the handshake (worker would send CAP_REQ
     * before the peer became master). Fire-and-forget like the web's per-step commands, then reload.
     */
    fun swapBondRoles() {
        if (connectionState.value !is ConnectionState.Ready) return
        val cur = _espNowBond.value ?: return
        if (!cur.enabled) return
        val localMaster = cur.role == "master"
        val peerNew = if (localMaster) "master" else "worker"
        val localNew = if (localMaster) "worker" else "master"
        viewModelScope.launch {
            ble.sendCommand("remote:bondrole $peerNew") // peer first (avoids the handshake race)
            kotlinx.coroutines.delay(700)
            ble.sendCommand("bondrole $localNew")        // then local
            kotlinx.coroutines.delay(1_200)
            loadEspNowBond()
        }
    }

    /** Sync a peer's metadata like the web's "Sync Metadata" button: force a fresh pull with
     *  `espnowrequestmeta <mac>`, then re-read the cache a few times as the peer's reply lands
     *  (the cache is stale/partial until the peer pushes — that's why a plain read shows old fields). */
    fun syncEspNowPeerMeta(mac: String) {
        if (connectionState.value !is ConnectionState.Ready || mac.isEmpty()) return
        ble.sendCommand("espnowrequestmeta $mac")
        viewModelScope.launch {
            repeat(4) {
                kotlinx.coroutines.delay(800)
                refreshEspNowDevices()
            }
        }
    }

    /** Edit a PEER's smart-home metadata by running the same setter the local config uses, but ON
     *  the peer via espnowremote (needs the peer's creds + ESP-NOW encryption). Re-fetches after. */
    fun editEspNowPeerMeta(mac: String, user: String, pass: String, field: String, value: String) {
        if (connectionState.value !is ConnectionState.Ready) return
        val setter = when (field) {
            "name" -> "espnowsetname ${q(value)}"
            "friendlyName" -> "espnowfriendlyname ${if (value.isEmpty()) "clear" else q(value)}"
            "room" -> "espnowroom ${if (value.isEmpty()) "clear" else q(value)}"
            "zone" -> "espnowzone ${if (value.isEmpty()) "clear" else q(value)}"
            "tags" -> "espnowtags ${if (value.isEmpty()) "clear" else q(value)}"
            "stationary" -> "espnowstationary ${if (value == "1") "1" else "0"}"
            else -> return
        }
        ble.sendCommand("espnowremote $mac $user $pass $setter")
        // The setter mutates the peer's gSettings; the gateway's cache only updates when the peer
        // pushes, so force a fresh pull (sync) rather than reading the stale cache.
        viewModelScope.launch { kotlinx.coroutines.delay(800); syncEspNowPeerMeta(mac) }
    }

    /** Poll just the dynamic bits (paired liveness + mesh peers + metadata) on a timer. */
    fun refreshEspNowPeers() {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCaptured("espnowlist json", TAG_EN_LIST)
        ble.sendCaptured("espnowdevices json", TAG_EN_DEVICES)
        ble.sendCaptured("espnowmeshstatus json", TAG_EN_MESH)
    }

    fun clearEspNow() {
        _espNowMode.value = null
        _espNowEnc.value = null
        _espNowMeshRole.value = null
        _espNowDeviceInfo.value = null
        _espNowPaired.value = null
        _espNowMesh.value = null
        _espNowDevices.value = null
        _espNowLoading.value = false
    }

    // --- Automations (read + control; the create/edit builder is a future tier) ---

    /** Load the automation list (`automationlist json`) + the global on/off (`automation system status`). */
    fun loadAutomations() {
        if (connectionState.value !is ConnectionState.Ready) return
        _automationsBusy.value = true
        ble.sendCaptured("automation system status json", TAG_AUTOMATION_SYS) // {"schema":1,"enabled":bool}
        ble.sendCaptured("automationlist json", TAG_AUTOMATIONS) // sent last → clears busy
    }

    private fun reloadAutomationsSoon() {
        viewModelScope.launch { kotlinx.coroutines.delay(500); loadAutomations() }
    }

    /** Run now. `automationrun id=` replies text ("OK" / "Error: …"), which the JSON capture can't
     *  claim — so grab the first OK/Error line off the incoming stream, scoped to right after the
     *  send, and surface a failure message (e.g. "automation not found"). */
    fun runAutomation(id: Long) {
        if (connectionState.value !is ConnectionState.Ready) return
        _automationStatus.value = "Running…"
        viewModelScope.launch {
            ble.sendCommand("automationrun id=$id")
            val line = kotlinx.coroutines.withTimeoutOrNull(2500L) {
                ble.incoming.first { val t = it.trim(); t == "OK" || t.startsWith("Error:") }
            }?.trim()
            _automationStatus.value = if (line != null && line.startsWith("Error:")) line else null
            loadAutomations()
        }
    }
    fun triggerAutomation(id: Long) { sendAutomationCmd("automationtrigger id=$id") }
    fun setAutomationEnabled(id: Long, on: Boolean) {
        sendAutomationCmd("automation ${if (on) "enable" else "disable"} id=$id"); reloadAutomationsSoon()
    }
    fun deleteAutomation(id: Long) { sendAutomationCmd("automation delete id=$id"); reloadAutomationsSoon() }
    fun setAutomationsSystem(on: Boolean) {
        // `… enable json` / `… disable json` apply AND return {"enabled":…} — one round trip, the
        // capture reply updates the toggle (no separate status re-read needed).
        if (connectionState.value is ConnectionState.Ready) {
            ble.sendCaptured("automation system ${if (on) "enable" else "disable"} json", TAG_AUTOMATION_SYS)
        }
    }

    private fun sendAutomationCmd(cmd: String) {
        if (connectionState.value is ConnectionState.Ready) ble.sendCommand(cmd)
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
            espNowIssuedReqIds.clear()
            _espNowFeed.value = emptyList()
            clearEspNowRemote()
            clearEspNowFiles()
        }
        // New open: invalidate any request still in flight from a previous open so its reply can't
        // hijack this session's cursor (it would page forward from the stale `since` and skip the
        // history we just reset to 0). The stale reply lands, is discarded, and restarts cleanly.
        espNowFeedEpoch++
        espNowFeedActive = true
        // Force-clear the in-flight latch before (re)scheduling. Without this the drain loop can wedge
        // permanently: if open() fires twice in quick succession (RESUMED re-entry / recomposition),
        // the 2nd schedulePoll() bails on the still-set latch while the 1st poll's coroutine then aborts
        // on the bumped epoch WITHOUT sending — leaving nothing in flight AND nothing scheduled, so the
        // feed silently stops polling (past messages stay, new ones never arrive). The epoch bump above
        // already invalidates any genuinely in-flight reply, so clearing the latch here is safe.
        espNowPollInFlight = false
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
        val epoch = espNowFeedEpoch
        viewModelScope.launch {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            if (!espNowFeedActive || epoch != espNowFeedEpoch ||
                connectionState.value !is ConnectionState.Ready || espNowFeedMac.isEmpty()) {
                espNowPollInFlight = false
                return@launch
            }
            espNowInFlightEpoch = epoch
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
        espNowFeedEpoch++ // any reply still in flight now belongs to a closed session — discard it
        espNowFeedMac = ""
        espNowFeedSince = 0L
        espNowRecords.clear()
        espNowIssuedReqIds.clear()
        _espNowFeed.value = emptyList()
        clearEspNowFiles()
    }

    private fun onEspNowFeedMessages(text: String, timedOut: Boolean) {
        espNowPollInFlight = false
        if (!espNowFeedActive) return
        if (espNowInFlightEpoch != espNowFeedEpoch) {
            // Reply from a previous open of this screen — discard its data (it carries a stale cursor)
            // and restart this session's paging cleanly.
            schedulePoll(0); return
        }
        if (timedOut) { schedulePoll(500); return } // dropped reply — retry the same cursor shortly
        val parsed = com.hardwareone.console.ble.EspNowMessages.parse(text)
        if (parsed == null || parsed.error != null) { schedulePoll(500); return }
        if (parsed.messages.isEmpty()) {
            // Empty page ({"messages":[]}) = the device is caught up: the pull terminator (serialize
            // contract rule 3). Once the command's output has been collected, this ends the pull.
            if (_espNowRemoteBusy.value && _espNowRemoteResult.value != null) _espNowRemoteBusy.value = false
            completeEspNowFilesIfReady() // assemble a pending remote `files json` result
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
        checkEspNowFetchEvent(parsed.messages) // terminal type:4/5 for an in-flight fetch
        schedulePoll(0) // full page received — drain the next one immediately (forward paging)
    }

    /** Reassemble accumulated records: chunked messages are grouped by reqId and concatenated in
     *  piece order. Command/file-listing results (received records whose reqId WE issued) are routed
     *  to the Command/Files tabs, not the chat feed; peer text (any other reqId) is a chat line. */
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

        // Chat feed: group/reassemble chunked messages by reqId; reqId-0 records stand alone by seq.
        val groups = LinkedHashMap<String, MutableList<com.hardwareone.console.ble.EspNowMessages.Message>>()
        for (m in sorted) {
            val key = if (m.reqId != 0L) "r${m.reqId}" else "s${m.seq}"
            groups.getOrPut(key) { mutableListOf() }.add(m)
        }
        val chat = mutableListOf<com.hardwareone.console.ble.EspNowChatLine>()
        for (group in groups.values) {
            val ordered = group.sortedBy { it.piece }
            val first = ordered.first()
            // Chat = plain-text records only. The firmware tags every NON-chat record with a distinct
            // `type` — 1-5 file-transfer events, 6 command-result (remote command output), 7 system
            // event (BOOT/metadata/mesh) — so "type != TYPE_TEXT ⇒ not chat" is the durable rule: it
            // survives an app reload, unlike reqId (which we only know for commands issued this session).
            val isNonChatType = first.type != com.hardwareone.console.ble.EspNowMessages.TYPE_TEXT
            // Transitional fallback for a gateway still on older firmware that stored command/system
            // output as plain TEXT (type 0) tagged only by reqId: a received record whose reqId we
            // issued this session (live command/listing output) or reqId==0 (legacy metadata/system) is
            // also not chat. Redundant — and harmless — once every gateway tags types; remove later.
            val isLegacyCmdOrSystem = !first.sent && (first.reqId in espNowIssuedReqIds || first.reqId == 0L)
            if (!isNonChatType && !isLegacyCmdOrSystem) {
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
        // Render the FULL loaded history (the LazyColumn composes only visible rows, so this is cheap)
        // so the user can scroll up through everything that's been paged in. It's already bounded by the
        // raw record cap (espNowRecords keeps the newest ~300) and the firmware ring (250 per peer).
        _espNowFeed.value = chat
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
        espNowIssuedReqIds.add(ack.reqId) // so its output is routed to the Command tab, not chat
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

    // --- ESP-NOW remote file browser ---

    /** Browse a peer's directory: run `files json "<path>"` on it via espnowremote. The result
     *  arrives through the espnowmessages feed reqId-matched and is assembled on the empty page
     *  (see [completeEspNowFilesIfReady]); it parses with the local [FileListing] parser. Requires
     *  ESP-NOW encryption + the peer's credentials (same as a remote command). */
    fun browseEspNowFiles(mac: String, user: String, pass: String, path: String) {
        if (connectionState.value !is ConnectionState.Ready || mac.isEmpty()) return
        espNowFilesUser = user
        espNowFilesPass = pass
        _espNowFilesPath.value = path
        _espNowFilesListing.value = null
        _espNowFilesError.value = null
        _espNowFilesBusy.value = true
        espNowFilesReqId = 0L
        ble.sendCaptured("espnowremote $mac $user $pass files json ${q(path)}", TAG_EN_FILES_ACK)
    }

    fun openEspNowDir(mac: String, name: String) =
        browseEspNowFiles(mac, espNowFilesUser, espNowFilesPass, joinPath(_espNowFilesPath.value, name))

    fun espNowFilesUp(mac: String) {
        if (_espNowFilesPath.value != "/") browseEspNowFiles(mac, espNowFilesUser, espNowFilesPass, parentPath(_espNowFilesPath.value))
    }

    private fun clearEspNowFiles() {
        _espNowFilesBusy.value = false
        _espNowFilesError.value = null
        _espNowFilesListing.value = null
        _espNowFilesPath.value = "/"
        espNowFilesReqId = 0L
        _espNowFetchBusy.value = false
        _espNowFetchStatus.value = null
        espNowFetchTarget = ""
        espNowFetchMac = ""
    }

    /** Download a peer file onto THIS (gateway) device via `espnowfetch`. [name] is an entry in the
     *  currently-browsed directory; it lands at /espnow/received/<peerMacToken>/<name>. Uses the
     *  same credentials the browse was opened with. */
    fun fetchEspNowFile(mac: String, name: String) {
        if (connectionState.value !is ConnectionState.Ready || mac.isEmpty() || name.isEmpty()) return
        if (_espNowFetchBusy.value) return // one transfer at a time
        val path = joinPath(_espNowFilesPath.value, name)
        espNowFetchMac = mac
        espNowFetchTarget = name
        _espNowFetchBusy.value = true
        _espNowFetchStatus.value = "Fetching $name…"
        ble.sendCaptured("espnowfetch $mac $espNowFilesUser $espNowFilesPass ${q(path)}", TAG_EN_FETCH_ACK)
    }

    private fun onEspNowFetchAck(text: String, timedOut: Boolean) {
        if (timedOut) { _espNowFetchBusy.value = false; _espNowFetchStatus.value = "Timed out sending fetch."; return }
        val ack = com.hardwareone.console.ble.EspNowAck.parse(text)
        if (ack == null) { _espNowFetchBusy.value = false; _espNowFetchStatus.value = "Bad reply."; return }
        if (!ack.ok) { _espNowFetchBusy.value = false; _espNowFetchStatus.value = ack.error ?: "Fetch failed."; return }
        // The terminal type:4/5 event arrives via the feed (handled in onEspNowFeedMessages). Binary
        // transfers have no progress events, so just bound the spinner; a late event still updates the
        // status (espNowFetchTarget stays set until the next fetch).
        val target = espNowFetchTarget
        viewModelScope.launch {
            kotlinx.coroutines.delay(60_000)
            if (_espNowFetchBusy.value && espNowFetchTarget == target) {
                _espNowFetchBusy.value = false
                _espNowFetchStatus.value = "No completion event for $target after 60s — check the device's /espnow/received folder."
            }
        }
    }

    /** Scan a freshly-paged feed for the fetch outcome (correlate by peer + filename). Two ways a
     *  fetch can end: a terminal transfer event (type 4 received-ok / type 5 received-failed), OR — when
     *  the sender refuses up front (e.g. the file exceeds the device's transfer cap) — a plain-text
     *  command-result verdict. The firmware owns that decision and writes the reason in plain English
     *  ("Error: '…' is N bytes — exceeds the 128 KB … limit; not sent"); we just surface it verbatim
     *  instead of spinning to the 60s timeout. Per contract, failure verdicts begin with "Error". */
    private fun checkEspNowFetchEvent(msgs: List<com.hardwareone.console.ble.EspNowMessages.Message>) {
        if (espNowFetchTarget.isEmpty()) return
        for (m in msgs) {
            if (m.sent) continue
            if (!m.msg.contains(espNowFetchTarget)) continue
            val status = when {
                m.type == 4 -> "Saved to /espnow/received/${espNowFetchMac.replace(":", "").uppercase()}/$espNowFetchTarget"
                m.type == 5 -> "Failed to receive $espNowFetchTarget"
                // Sender-side refusal: no transfer ever started, so this verdict is the only signal.
                m.msg.trimStart().startsWith("Error", ignoreCase = true) -> m.msg.trim()
                else -> null // e.g. "File sent successfully …" echo — not terminal; await the type:4 event
            } ?: continue
            _espNowFetchStatus.value = status
            _espNowFetchBusy.value = false
            espNowFetchTarget = ""
            return
        }
    }

    private fun onEspNowFilesAck(text: String, timedOut: Boolean) {
        if (timedOut) { _espNowFilesBusy.value = false; _espNowFilesError.value = "Timed out sending."; return }
        val ack = com.hardwareone.console.ble.EspNowAck.parse(text)
        if (ack == null) { _espNowFilesBusy.value = false; _espNowFilesError.value = "Bad reply."; return }
        if (!ack.ok) { _espNowFilesBusy.value = false; _espNowFilesError.value = ack.error ?: "Failed."; return }
        espNowFilesReqId = ack.reqId
        espNowIssuedReqIds.add(ack.reqId) // so the listing's records are routed to Files, not chat
        // A directory listing is small (a few pages), so a flat deadline is enough — unlike the
        // command runner's progress-based watchdog (memreport floods many pages).
        val reqId = ack.reqId
        viewModelScope.launch {
            kotlinx.coroutines.delay(8_000)
            if (_espNowFilesBusy.value && espNowFilesReqId == reqId && _espNowFilesListing.value == null) {
                _espNowFilesBusy.value = false
                _espNowFilesError.value = "No response from peer."
            }
        }
    }

    /** On the feed's empty-page terminator, assemble the reqId-matched records into the complete
     *  `files json` and parse it. No-op until the result has actually paged in. */
    private fun completeEspNowFilesIfReady() {
        if (!_espNowFilesBusy.value || espNowFilesReqId == 0L) return
        val recs = espNowRecords.values
            .filter { !it.sent && it.reqId == espNowFilesReqId }
            .sortedBy { it.seq }
        if (recs.isEmpty()) return // result hasn't arrived yet — wait for the next empty page
        // The reqId tags both the JSON (now stored as chunked piece/of records that share the reqId)
        // AND the peer's "[CMD] … -> OK" echo line. Concatenate the chunks in seq order, then pull
        // the balanced JSON object out — robust to the echo sitting before/after and to trailing junk.
        val assembled = recs.joinToString("") { it.msg }
        if (!assembled.contains('{')) return // JSON hasn't started arriving — wait
        val json = extractJsonObject(assembled)
        if (json == null) {
            _espNowFilesError.value = "Couldn't read the folder listing (result was truncated or malformed)."
            _espNowFilesBusy.value = false
            return
        }
        val listing = com.hardwareone.console.ble.FileListing.parse(json)
        when {
            listing == null -> _espNowFilesError.value = "Couldn't read the folder listing (result was truncated or malformed)."
            !listing.success -> { _espNowFilesError.value = listing.error ?: "Folder error."; _espNowFilesListing.value = null }
            else -> _espNowFilesListing.value = listing
        }
        _espNowFilesBusy.value = false
    }

    /** Extract the first balanced top-level JSON object from [s] (string-aware brace matching).
     *  Returns null if there's no complete `{…}` (e.g. a truncated result). Tolerates a leading
     *  status line and any trailing text. */
    private fun extractJsonObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
        }
        return null // never closed — truncated/incomplete
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

    /**
     * Refresh sensors. Over BLE the aggregate `sensors json` can exceed the ~2 KB result ceiling
     * and truncate, so we use the firmware's brief enumeration (`sensors json brief` — state only,
     * bounded) to discover what's live, then pull each live sensor's reading individually via its
     * `<x>read json` (each well under the ceiling). Readings fill in as they arrive.
     */
    fun refreshSensors() {
        if (connectionState.value !is ConnectionState.Ready) {
            _sensorsError.value = "Not connected."
            return
        }
        _sensorsLoading.value = true
        ble.sendCaptured("sensors json brief", TAG_SENSORS)
    }

    /** id → per-sensor `<x>read json` command (firmware sensor contract). */
    private fun sensorReadCommand(id: String): String? = when (id) {
        "imu" -> "imuread json"
        "tof" -> "tofread json"
        "gps" -> "gpsread json"
        "presence" -> "presenceread json"
        "fmradio" -> "fmradioread json"
        "rtc" -> "rtcread json"
        "input" -> "gamepadread json"
        "apds" -> "apdsread json"
        "thermal" -> "thermalread json"
        "mic" -> "micread json"
        "anoencoder" -> "anoencoderread json"
        else -> null
    }

    /** Merge a per-sensor `<x>read json` reply into its entry; skip non-JSON (e.g. "Unknown command"). */
    private fun onSensorRead(id: String, text: String, timedOut: Boolean) {
        if (timedOut) return
        val rd = com.hardwareone.console.ble.SensorSnapshot.parseReadData(text) ?: return
        _sensors.value = _sensors.value?.map { e ->
            if (e.id != id) e
            else e.copy(hasData = true, dataValid = rd.valid, readings = rd.readings, numbers = rd.numbers, flags = rd.flags)
        }
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
        // Merge the brief (state-only) into the previous snapshot so existing readings stay on
        // screen through a refresh and update in place when each per-sensor read lands — instead of
        // flashing "No readings yet". Carry old data over only when the brief itself didn't inline it.
        val prev = _sensors.value
        _sensors.value = snapshot.sensors.map { fresh ->
            if (fresh.hasData) return@map fresh
            val old = prev?.firstOrNull { it.id == fresh.id && it.hasData } ?: return@map fresh
            fresh.copy(
                hasData = true, dataValid = old.dataValid,
                readings = old.readings, numbers = old.numbers, flags = old.flags,
            )
        }
        _sensorsError.value = null
        // Pull each live sensor's reading individually (the aggregate truncates over BLE). Fan out by
        // the FRESH brief state (pre-carry-over !hasData) so carried-over placeholders still re-fetch;
        // an old firmware that inlines data in the brief isn't re-fetched redundantly.
        for (e in snapshot.sensors) {
            if (e.connected && e.enabled && !e.hasData) {
                sensorReadCommand(e.id)?.let { ble.sendCaptured(it, TAG_SENSOR_READ + e.id) }
            }
        }
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
        if (writeOwner != WriteOwner.FILE_BROWSER) {
            cancelOtaTransfer()
            return
        }
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
        // Correlation guard: drop a late/cross-claimed window (e.g. from the Maps page's
        // identical fileread loop) and re-request the one we actually need.
        if (r.offset != readOffset || (r.path.isNotEmpty() && r.path != readPath)) {
            ble.sendCaptured("fileread ${q(readPath)} $readOffset $readWindow", TAG_FILE_READ)
            return
        }
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
        // Storage-based cap (mirrors the web upload): allow anything that fits, up to 90% of the
        // device's free space (10% headroom for filesystem metadata). Best-effort early check —
        // the firmware is the real arbiter (its write short-writes if the volume fills). If free
        // space isn't known yet (stats not loaded), let it through and rely on the firmware.
        val freeBytes = _fileStats.value?.free
        if (freeBytes != null && bytes.size.toLong() > freeBytes * 9 / 10) {
            _fileTransfer.value = com.hardwareone.console.ble.FileTransfer(
                name, 0, bytes.size.toLong(), finished = true,
                error = "Not enough space: ${bytes.size / 1024} KB won't fit in ${freeBytes / 1024} KB free.",
            )
            return
        }
        if (bytes.isEmpty()) { createFile(path); return }
        writePath = path
        writeBytes = bytes
        writeOffset = 0L
        writeSentFinal = false
        writeOwner = WriteOwner.FILE_BROWSER
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
        when (writeOwner) {
            WriteOwner.FILE_BROWSER -> _fileTransfer.value = _fileTransfer.value?.copy(done = r.size)
            WriteOwner.OTA_FIRMWARE -> _ota.value = _ota.value.copy(progressDone = r.size)
            WriteOwner.OTA_MANIFEST -> _ota.value = _ota.value.copy(
                progressDone = (_ota.value.bundle?.imageSize?.toLong() ?: 0L) + r.size,
            )
        }
        if (writeSentFinal || r.final) {
            val completedOwner = writeOwner
            writeBytes = null
            _filesBusy.value = false
            writeOwner = WriteOwner.FILE_BROWSER
            when (completedOwner) {
                WriteOwner.FILE_BROWSER -> {
                    _fileTransfer.value = _fileTransfer.value?.copy(finished = true)
                    loadFiles(_filesPath.value) // refresh listing + stats
                }
                WriteOwner.OTA_FIRMWARE -> startOtaManifestUpload()
                WriteOwner.OTA_MANIFEST -> verifyStagedOta()
            }
        } else {
            sendNextWriteChunk()
        }
    }

    private fun failWrite(msg: String) {
        val owner = writeOwner
        writeBytes = null
        _filesBusy.value = false
        writeOwner = WriteOwner.FILE_BROWSER
        if (owner == WriteOwner.FILE_BROWSER) {
            _fileTransfer.value = _fileTransfer.value?.copy(finished = true, error = msg)
        } else {
            failOta(msg)
        }
    }

    // --- Signed offline OTA -----------------------------------------------------------

    /** Parse and authenticate a local .hw1ota bundle without network access. */
    fun importOtaBundle(name: String, bytes: ByteArray) {
        if (_ota.value.transferActive || otaAwaitingReturn) return
        _ota.value = _ota.value.copy(
            step = OtaStep.CHECKING_PACKAGE,
            packageName = name,
            bundle = null,
            progressDone = 0,
            progressTotal = 0,
            message = "Checking package structure, signature, image digest, and board identity…",
            error = null,
        )
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { OtaBundle.parse(bytes) }
                .onSuccess { bundle ->
                    val device = _ota.value.device
                    val mismatch = device?.takeIf {
                        it.board.isNotEmpty() && (it.board != bundle.board || it.layout != bundle.layout)
                    }
                    if (mismatch != null) {
                        _ota.value = _ota.value.copy(
                            step = OtaStep.FAILED,
                            packageName = name,
                            bundle = bundle,
                            message = "Package does not match the connected HardwareOne.",
                            error = "Package is ${bundle.board} / ${bundle.layout}; device is ${mismatch.board} / ${mismatch.layout}.",
                        )
                    } else {
                        _ota.value = _ota.value.copy(
                            step = OtaStep.READY,
                            packageName = name,
                            bundle = bundle,
                            progressDone = 0,
                            progressTotal = bundle.imageSize.toLong() + bundle.manifestJson.size,
                            message = "Package checked locally. The device will independently verify its embedded release trust root before installing it.",
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    _ota.value = _ota.value.copy(
                        step = OtaStep.FAILED,
                        packageName = name,
                        bundle = null,
                        message = "This file cannot be used for HardwareOne OTA.",
                        error = error.message ?: "Invalid OTA package.",
                    )
                }
        }
    }

    fun clearOtaPackage() {
        if (_ota.value.transferActive || otaAwaitingReturn) return
        _ota.value = OtaUiState(device = _ota.value.device)
    }

    fun reportOtaImportError(message: String) {
        if (_ota.value.transferActive || otaAwaitingReturn) return
        _ota.value = _ota.value.copy(
            step = OtaStep.FAILED,
            message = "The selected package could not be opened.",
            error = message,
        )
    }

    fun refreshOtaStatus() {
        if (connectionState.value !is ConnectionState.Ready) {
            _ota.value = _ota.value.copy(error = "Connect to HardwareOne to read OTA status.")
            return
        }
        if (otaStatusInFlight) return
        otaStatusInFlight = true
        ble.sendCaptured("otastatus json", TAG_OTA_STATUS, OTA_STATUS_TIMEOUT_MS)
    }

    /** Store the recovery-only WPA2/HTTP fallback credential; never persist or console-log it. */
    fun setOtaRecoveryPassword(value: String) {
        if (!otaSessionReady()) return
        if (value.length !in 12..63 || value.any { it.code !in 0x20..0x7e }) {
            _ota.value = _ota.value.copy(error = "Recovery password must be 12–63 printable ASCII characters.")
            return
        }
        _ota.value = _ota.value.copy(
            step = OtaStep.SETTING_RECOVERY_PASSWORD,
            message = "Saving the recovery fallback credential on the device…",
            error = null,
        )
        // Deliberately bypass ConsoleViewModel.send(): credentials must never enter the log/history.
        ble.sendCommand("otapin $value")
        ble.sendCaptured("otastatus json", TAG_OTA_CREDENTIAL, OTA_STATUS_TIMEOUT_MS)
    }

    fun startOtaInstall() {
        val bundle = _ota.value.bundle ?: run {
            _ota.value = _ota.value.copy(error = "Choose a valid .hw1ota package first.")
            return
        }
        val status = _ota.value.device ?: run {
            _ota.value = _ota.value.copy(error = "Refresh device OTA status before installing.")
            return
        }
        if (!otaSessionReady()) return
        when {
            !status.available -> failOta("This firmware does not have the native recovery OTA layout.")
            status.board != bundle.board || status.layout != bundle.layout -> failOta(
                "Package is ${bundle.board} / ${bundle.layout}; device is ${status.board} / ${status.layout}.",
            )
            !status.recoveryCredentialConfigured -> failOta("Set the recovery fallback password before installing.")
            status.resultPending -> failOta("A prior OTA result must be reviewed and acknowledged before another update.")
            status.phase !in OTA_STARTABLE_PHASES -> failOta("Device OTA state is '${status.phase}', so a new update cannot be started.")
            else -> {
                otaAwaitingReturn = false
                otaPollJob?.cancel()
                otaReconnectJob?.cancel()
                _ota.value = _ota.value.copy(
                    step = OtaStep.UPLOADING_FIRMWARE,
                    progressDone = 0,
                    progressTotal = bundle.imageSize.toLong() + bundle.manifestJson.size,
                    message = "Uploading signed firmware over the secure Bluetooth channel. Keep the phone near the device.",
                    error = null,
                )
                startOtaWrite("/system/ota/candidate.part", bundle.firmware, WriteOwner.OTA_FIRMWARE)
            }
        }
    }

    fun cancelOtaTransfer() {
        if (!_ota.value.transferActive) return
        fileOpCancelled = true
        writeBytes = null
        writeOffset = 0
        writeOwner = WriteOwner.FILE_BROWSER
        _filesBusy.value = false
        _ota.value = _ota.value.copy(
            step = OtaStep.READY,
            progressDone = 0,
            message = "Upload stopped. Starting again will truncate and replace the partial staging files.",
            error = null,
        )
    }

    fun acknowledgeOtaResult() {
        val status = _ota.value.device ?: return
        if (!status.resultPending || status.resultSequence <= 0 || !otaSessionReady()) return
        _ota.value = _ota.value.copy(message = "Acknowledging OTA result ${status.resultSequence}…", error = null)
        ble.sendCommand("otaack ${status.resultSequence} confirm")
        otaStatusInFlight = true
        ble.sendCaptured("otastatus json", TAG_OTA_ACK, OTA_STATUS_TIMEOUT_MS)
    }

    private fun startOtaWrite(path: String, bytes: ByteArray, owner: WriteOwner) {
        if (bytes.isEmpty()) {
            failOta("OTA package contains an empty staging member.")
            return
        }
        writePath = path
        writeBytes = bytes
        writeOffset = 0
        writeSentFinal = false
        writeOwner = owner
        fileOpCancelled = false
        _filesBusy.value = true
        sendNextWriteChunk()
    }

    private fun startOtaManifestUpload() {
        val bundle = _ota.value.bundle ?: return failOta("OTA package was released while uploading.")
        _ota.value = _ota.value.copy(
            step = OtaStep.UPLOADING_MANIFEST,
            message = "Firmware uploaded. Sending its signed manifest…",
            error = null,
        )
        startOtaWrite("/system/ota/manifest.part", bundle.manifestJson, WriteOwner.OTA_MANIFEST)
    }

    private fun verifyStagedOta() {
        _ota.value = _ota.value.copy(
            step = OtaStep.VERIFYING_STAGED,
            progressDone = _ota.value.progressTotal,
            message = "Device is checking the manifest signature, image signature, digest, compatibility, and rollback policy…",
            error = null,
        )
        ble.sendCommand("otastage confirm")
        otaStatusInFlight = true
        ble.sendCaptured("otastatus json", TAG_OTA_STAGED, OTA_LONG_STATUS_TIMEOUT_MS)
    }

    private fun armOtaUpdate() {
        _ota.value = _ota.value.copy(
            step = OtaStep.ARMING_RECOVERY,
            message = "Device is revalidating the staged image and checking power safety before reboot…",
            error = null,
        )
        otaAwaitingReturn = true
        ble.sendCommand("otaupdate confirm")
        otaStatusInFlight = true
        ble.sendCaptured("otastatus json", TAG_OTA_ARMED, OTA_LONG_STATUS_TIMEOUT_MS)
    }

    private fun onOtaStatusCapture(tag: String, text: String, timedOut: Boolean) {
        otaStatusInFlight = false
        if (timedOut || text.isBlank()) {
            if (otaAwaitingReturn) {
                _ota.value = _ota.value.copy(
                    step = OtaStep.WAITING_FOR_DEVICE,
                    message = "Firmware is updating. Waiting for HardwareOne to return over Bluetooth…",
                    error = null,
                )
                ensureOtaReconnect()
            } else {
                failOta("No JSON OTA status reply. Log in with a superadmin account and confirm this device has OTA-layout firmware.")
            }
            return
        }
        val status = OtaDeviceStatus.parse(text) ?: run {
            failOta("Device returned an invalid OTA status document.")
            return
        }
        _ota.value = _ota.value.copy(device = status)

        val bundle = _ota.value.bundle
        if (bundle != null && status.board.isNotEmpty() &&
            (status.board != bundle.board || status.layout != bundle.layout)
        ) {
            failOta("Connected device identity changed and no longer matches this OTA package.")
            return
        }

        when (tag) {
            TAG_OTA_STATUS -> {
                val message = when {
                    !status.available -> "Native recovery OTA is unavailable on this firmware."
                    status.resultPending -> "A prior OTA result is waiting for acknowledgement."
                    !status.recoveryCredentialConfigured -> "Set a recovery fallback password before installing."
                    bundle == null -> "Device is ready. Choose a signed .hw1ota package stored on this phone."
                    else -> "Device and package are ready for secure Bluetooth staging."
                }
                val next = when {
                    status.phase == "succeeded" -> OtaStep.SUCCEEDED
                    status.phase == "failed" -> OtaStep.FAILED
                    bundle != null -> OtaStep.READY
                    else -> OtaStep.IDLE
                }
                _ota.value = _ota.value.copy(step = next, message = message, error = null)
            }
            TAG_OTA_CREDENTIAL -> {
                if (status.recoveryCredentialConfigured) {
                    _ota.value = _ota.value.copy(
                        step = if (bundle != null) OtaStep.READY else OtaStep.IDLE,
                        message = "Recovery fallback password is configured. It is not stored by the app.",
                        error = null,
                    )
                } else failOta("The device did not store the recovery fallback password.")
            }
            TAG_OTA_STAGED -> {
                if (status.phase == "requested" && status.candidateStaged) armOtaUpdate()
                else failOta(status.resultDetail.ifBlank { "Device rejected the staged OTA package." })
            }
            TAG_OTA_ARMED -> {
                if (status.phase == "requested") {
                    // The device remained in the main image: most commonly its power-safety gate refused.
                    otaAwaitingReturn = false
                    failOta("Device did not arm recovery. Check external power/battery state, then retry.")
                } else {
                    _ota.value = _ota.value.copy(
                        step = OtaStep.WAITING_FOR_DEVICE,
                        message = "Recovery updater is installing the image. Waiting for HardwareOne to return…",
                        error = null,
                    )
                    ensureOtaReconnect()
                }
            }
            TAG_OTA_MONITOR -> handleOtaMonitor(status)
            TAG_OTA_ACK -> {
                if (status.resultPending) failOta("The OTA result is still pending; refresh and retry acknowledgement.")
                else _ota.value = _ota.value.copy(
                    step = if (status.phase == "succeeded") OtaStep.SUCCEEDED else OtaStep.IDLE,
                    message = "OTA result acknowledged. The device is ready for a future update.",
                    error = null,
                )
            }
        }
    }

    private fun handleOtaMonitor(status: OtaDeviceStatus) {
        when (status.phase) {
            "succeeded" -> {
                otaAwaitingReturn = false
                otaReconnectJob?.cancel()
                otaPollJob?.cancel()
                _ota.value = _ota.value.copy(
                    step = OtaStep.SUCCEEDED,
                    progressDone = _ota.value.progressTotal,
                    message = if (status.resultPending)
                        "Update succeeded. Review and acknowledge result ${status.resultSequence}."
                    else "Update succeeded and the new firmware is running.",
                    error = null,
                )
            }
            "failed", "canceled" -> {
                otaAwaitingReturn = false
                failOta(status.resultDetail.ifBlank { "The recovery updater reported ${status.phase}." })
            }
            "trial_running", "trial_boot_armed" -> {
                _ota.value = _ota.value.copy(
                    step = OtaStep.PROBATION,
                    message = "New firmware is running its 60-second health probation. Keep Bluetooth connected…",
                    error = null,
                )
                scheduleOtaMonitor()
            }
            else -> {
                _ota.value = _ota.value.copy(
                    step = OtaStep.WAITING_FOR_DEVICE,
                    message = "OTA state is '${status.phase}'. Waiting for the device to finish…",
                    error = null,
                )
                scheduleOtaMonitor()
            }
        }
    }

    private fun requestOtaMonitor() {
        if (!otaAwaitingReturn || otaStatusInFlight || connectionState.value !is ConnectionState.Ready) return
        if (!authenticated.value) {
            _ota.value = _ota.value.copy(
                step = OtaStep.WAITING_FOR_LOGIN,
                message = "Device is back. Log in to verify the OTA result.",
                error = null,
            )
            return
        }
        otaStatusInFlight = true
        ble.sendCaptured("otastatus json", TAG_OTA_MONITOR, OTA_STATUS_TIMEOUT_MS)
    }

    private fun scheduleOtaMonitor() {
        otaPollJob?.cancel()
        otaPollJob = viewModelScope.launch {
            delay(OTA_POLL_INTERVAL_MS)
            requestOtaMonitor()
        }
    }

    private fun ensureOtaReconnect() {
        if (!otaAwaitingReturn || otaReconnectJob?.isActive == true) return
        otaReconnectJob = viewModelScope.launch {
            repeat(OTA_RECONNECT_ATTEMPTS) {
                if (!otaAwaitingReturn) return@launch
                when (connectionState.value) {
                    ConnectionState.Disconnected, is ConnectionState.Failed -> ble.reconnect()
                    is ConnectionState.Ready -> {
                        requestOtaMonitor()
                        return@launch
                    }
                    else -> Unit
                }
                delay(OTA_RECONNECT_INTERVAL_MS)
            }
            if (otaAwaitingReturn) {
                otaAwaitingReturn = false
                failOta("HardwareOne did not return over Bluetooth within three minutes. The updater may still be recoverable through its local recovery mode.")
            }
        }
    }

    private fun otaSessionReady(): Boolean {
        val failure = when {
            connectionState.value !is ConnectionState.Ready -> "Connect to HardwareOne first."
            deviceInfo.value?.secure != true -> "OTA requires the app's encrypted Bluetooth channel."
            !authenticated.value -> "Log in with a superadmin account before using OTA."
            _filesBusy.value && writeOwner == WriteOwner.FILE_BROWSER -> "Finish the current file operation before starting OTA."
            else -> null
        }
        if (failure != null) _ota.value = _ota.value.copy(error = failure)
        return failure == null
    }

    private fun failOta(message: String) {
        otaAwaitingReturn = false
        otaStatusInFlight = false
        otaPollJob?.cancel()
        otaReconnectJob?.cancel()
        writeBytes = null
        if (writeOwner != WriteOwner.FILE_BROWSER) {
            writeOwner = WriteOwner.FILE_BROWSER
            _filesBusy.value = false
        }
        _ota.value = _ota.value.copy(
            step = OtaStep.FAILED,
            message = "OTA could not continue.",
            error = message,
        )
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

    // --- Camera + microphone media pages ------------------------------------------------
    //
    // Camera/mic are stream sensors: live pixels/audio only exist on the firmware's HTTP
    // interface, never over CLI/BLE. So the app drives capture-to-file (camerasave / micrecord)
    // + tuning (the generic `controls json` panel) + a saved-file browser. Saved JPEG/WAV files
    // are pulled with an INDEPENDENT windowed binary `fileread` loop below — its own capture slot,
    // so it never contends with the Files/Maps loops (all three live on separate screens). Pulling
    // a file needs the Secure Channel + an admin login, exactly like the Files page.

    private var mediaReadPath = ""
    private var mediaReadOffset = 0L
    private var mediaReadPurpose = MEDIA_READ_NONE
    private var mediaReadName = ""
    private val mediaReadBuf = java.io.ByteArrayOutputStream()
    private val mediaReadCandidates = ArrayDeque<String>() // fallback paths (SD vs internal)

    private fun mediaReady() = connectionState.value is ConnectionState.Ready

    /** Send a text-reply device verb, then re-read state shortly so the UI reflects the result. */
    private fun mediaVerb(command: String, after: () -> Unit) {
        if (!mediaReady()) return
        ble.sendCommand(command)
        viewModelScope.launch { kotlinx.coroutines.delay(500); after() }
    }

    // Camera ---------------------------------------------------------------------------
    fun refreshCameraStatus() { if (mediaReady()) ble.sendCaptured("cameraread", TAG_CAM_STATUS) }
    fun openCamera() = mediaVerb("opencamera") { refreshCameraStatus() }
    fun closeCamera() = mediaVerb("closecamera") { refreshCameraStatus() }

    /** List saved photos on both stores (each reply updates only its own location's items). */
    fun loadGallery() {
        if (!mediaReady()) return
        ble.sendCaptured("images json", TAG_IMAGES_LFS)
        ble.sendCaptured("images json sd", TAG_IMAGES_SD)
    }

    /**
     * Capture + save a still, then refresh the gallery so the new photo appears (tap it to view).
     * `camerasave` replies plain text on the console (not JSON), so it can't go through the
     * JSON-only capture channel — send it as a normal command and reload once it's had time to land.
     */
    fun capturePhoto() {
        if (!mediaReady() || _mediaBusy.value) return
        _mediaError.value = null
        _mediaBusy.value = true
        ble.sendCommand("camerasave")
        viewModelScope.launch {
            kotlinx.coroutines.delay(1500) // capture + JPEG flash-write takes a beat
            _mediaBusy.value = false
            loadGallery()
        }
    }

    private fun onImagesCapture(text: String, location: String) {
        val listing = ImageListing.parse(text) ?: return
        val folder = if (location == "SD") CAMERA_FOLDER_SD else CAMERA_FOLDER_LFS
        val items = listing.images.map { (name, size) -> ImageItem(name, size, location, "$folder/$name") }
        // Replace just this location's items; keep the other store's.
        val others = (_gallery.value ?: emptyList()).filter { it.location != location }
        _gallery.value = (others + items).sortedByDescending { it.filename }
    }

    fun viewImage(item: ImageItem) = startMediaRead(item.filename, listOf(item.path), MEDIA_READ_IMAGE)
    fun deleteImage(item: ImageItem) = mediaVerb("imagedelete ${q(item.path)}") { loadGallery() }
    fun clearViewedImage() { _viewedImage.value = null }

    // Microphone -----------------------------------------------------------------------
    fun refreshMicStatus() { if (mediaReady()) ble.sendCaptured("micread json", TAG_MIC_STATUS) }
    fun pollMicLevel() { if (mediaReady()) ble.sendCaptured("miclevel json", TAG_MIC_LEVEL) }
    fun openMic() = mediaVerb("openmic") { refreshMicStatus() }
    fun closeMic() = mediaVerb("closemic") { refreshMicStatus() }
    fun loadRecordings() { if (mediaReady()) ble.sendCaptured("miclist json", TAG_MIC_LIST) }
    fun startRecording() = mediaVerb("micrecord start") { refreshMicStatus() }
    fun stopRecording() = mediaVerb("micrecord stop") { refreshMicStatus(); loadRecordings() }
    fun playRecording(item: RecordingItem) = startMediaRead(item.filename, item.candidates, MEDIA_READ_AUDIO)
    fun deleteRecording(item: RecordingItem) = mediaVerb("micdelete ${q(item.filename)}") { loadRecordings() }
    fun clearAudioClip() { _audioClip.value = null }
    fun clearMediaError() { _mediaError.value = null }

    // Windowed binary read (image/audio) ------------------------------------------------
    private fun startMediaRead(name: String, candidates: List<String>, purpose: Int) {
        if (!mediaReady() || _mediaBusy.value) return
        mediaReadCandidates.clear()
        mediaReadCandidates.addAll(candidates)
        val first = mediaReadCandidates.removeFirstOrNull() ?: return
        mediaReadName = name
        mediaReadPurpose = purpose
        mediaReadPath = first
        mediaReadOffset = 0L
        mediaReadBuf.reset()
        _mediaError.value = null
        _mediaBusy.value = true
        requestMediaWindow()
    }

    private fun requestMediaWindow() {
        ble.sendCaptured("fileread ${q(mediaReadPath)} $mediaReadOffset $MEDIA_READ_WINDOW bin", TAG_MEDIA_READ)
    }

    private fun onMediaReadCapture(text: String, timedOut: Boolean) {
        if (mediaReadPurpose == MEDIA_READ_NONE) return
        if (timedOut) { mediaReadTryNextOrFail("Transfer timed out — the file browser needs a Secure Channel + admin login."); return }
        val r = FileReadChunk.parse(text) ?: run { mediaReadTryNextOrFail("Unexpected reply."); return }
        if (!r.success) {
            // A first-window failure means the file may be in the other candidate folder.
            if (mediaReadOffset == 0L) mediaReadTryNextOrFail("Read failed: ${r.error}")
            else mediaReadFail("Read failed: ${r.error}")
            return
        }
        // Correlation guard: drop a late/cross-claimed window and re-request the one we need.
        if (r.offset != mediaReadOffset || (r.path.isNotEmpty() && r.path != mediaReadPath)) {
            requestMediaWindow(); return
        }
        val bytes = if (r.enc == "b64")
            runCatching { android.util.Base64.decode(r.data, android.util.Base64.DEFAULT) }.getOrDefault(ByteArray(0))
        else r.data.toByteArray(Charsets.UTF_8)
        mediaReadBuf.write(bytes)
        mediaReadOffset += r.len
        if (r.eof || r.len == 0) {
            val blob = MediaBlob(mediaReadName, mediaReadPath, mediaReadBuf.toByteArray())
            val purpose = mediaReadPurpose
            mediaReadPurpose = MEDIA_READ_NONE
            _mediaBusy.value = false
            if (purpose == MEDIA_READ_IMAGE) _viewedImage.value = blob else _audioClip.value = blob
        } else {
            requestMediaWindow()
        }
    }

    /** Retry the next candidate path from offset 0, or give up with [msg]. */
    private fun mediaReadTryNextOrFail(msg: String) {
        val next = mediaReadCandidates.removeFirstOrNull()
        if (next != null) {
            mediaReadPath = next
            mediaReadOffset = 0L
            mediaReadBuf.reset()
            requestMediaWindow()
        } else {
            mediaReadFail(msg)
        }
    }

    private fun mediaReadFail(msg: String) {
        mediaReadPurpose = MEDIA_READ_NONE
        _mediaBusy.value = false
        _mediaError.value = msg
    }

    // --- Maps page ----------------------------------------------------------------------
    //
    // Only `map`/`maplist` (and `gpsread`) reply JSON; every other map verb replies plain text
    // with stable prefixes, so verbs send on the console and scrape their verdict line off
    // [BleManager.incoming]. Rendering needs the raw .hwmap bytes, fetched with the same windowed
    // `fileread` scheme as the Files page (admin + secure channel) and cached on the phone keyed
    // by device path + size.

    /** Load/refresh the page: map list, device-loaded map info, waypoints. */
    fun loadMaps() {
        if (connectionState.value !is ConnectionState.Ready) return
        // Don't refresh while a map/track/sidecar transfer is running. This is called on every
        // screen resume and Activity recreation (e.g. folding the device); re-fetching metadata
        // would flip the busy spinner and contend for the capture slot with the in-flight
        // fileread — the "full page refresh" that interrupted downloads. The transfer + rendered
        // map both live in this ViewModel, so they survive the resume untouched.
        if (mapReadPurpose != MAP_READ_NONE) return
        _mapsBusy.value = true
        mapGpsMisses = 0
        mapSidecarUnavailable = false // a manual refresh re-probes fileread availability once
        ble.sendCaptured("maplist json", TAG_MAP_LIST)
        ble.sendCaptured("map json", TAG_MAP_INFO) // sent last → clears busy
        refreshMapWaypoints()
    }

    fun clearMapsNotice() { _mapsNotice.value = null }

    /** Surface a page-level notice from the Maps UI (e.g. selection-cap warnings). */
    fun reportMapsNotice(message: String) { _mapsNotice.value = message }

    private fun onMapInfoCapture(text: String, timedOut: Boolean) {
        _mapsBusy.value = false
        if (timedOut || text.isBlank()) {
            if (_mapInfo.value == null) {
                _mapsError.value = "No reply to `map json` — this firmware may not include Maps."
            }
            return
        }
        val info = com.hardwareone.console.ble.MapInfo.parse(text) ?: return
        _mapsError.value = null
        _mapInfo.value = info
        if (info.valid) maybeLoadCachedMap(info)
    }

    /** The device path of the loaded map: match `maplist` by filename, else the canonical layout. */
    fun resolveMapPath(info: com.hardwareone.console.ble.MapInfo): String =
        _mapList.value.firstOrNull { it.endsWith("/${info.filename}") }
            ?: "/maps/${info.base}/${info.filename}"

    private fun mapCacheFile(path: String, size: Long): java.io.File {
        val safe = path.trim('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return java.io.File(getApplication<Application>().cacheDir, "maps/$safe-$size.hwmap")
    }

    /** Parse a previously-downloaded copy so the map renders instantly on re-open/reconnect. */
    private fun maybeLoadCachedMap(info: com.hardwareone.console.ble.MapInfo) {
        val path = resolveMapPath(info)
        if (_mapFile.value != null && _mapFilePath.value == path) return
        val f = mapCacheFile(path, info.fileSize)
        if (!f.exists()) return
        viewModelScope.launch(Dispatchers.Default) {
            val parsed = runCatching { com.hardwareone.console.ble.HwMapFile.parse(f.readBytes()) }.getOrNull() ?: return@launch
            // The mapRead* machine is main-confined — hop back before touching it.
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                _mapFile.value = parsed
                _mapFilePath.value = path
                refreshWaypointSidecar()
            }
        }
    }

    /** Fetch the device-loaded map's bytes for rendering (windowed fileread; admin only). */
    fun downloadCurrentMap() {
        val info = _mapInfo.value ?: return
        if (!info.valid || connectionState.value !is ConnectionState.Ready) return
        if (mapReadPurpose != MAP_READ_NONE) { _mapsNotice.value = "Another map transfer is in progress."; return }
        // `map json` only reports the map's BASENAME, not its real path, so we can't be sure of
        // the on-disk layout. Try the cheap reconstructions first (maplist entry that ends with
        // the basename, canonical /maps/<base>/<file>, loose root); if all 404, fall back to
        // discovering the real path by browsing /maps with the same `files json` the Files page
        // uses (§discoverMapDownload) — reading a path the device actually reported, never a guess.
        mapDiscoverTried = false
        mapReadCandidates.clear()
        mapReadCandidates.addAll(listOf(resolveMapPath(info), "/maps/${info.filename}").distinct())
        startMapRead(mapReadCandidates.removeFirst(), MAP_READ_MAP, info.fileSize)
    }

    /**
     * Fallback path discovery: list /maps and each subdirectory via `files json` (recycling the
     * Files browser's listing), collect the real `.hwmap` paths, and read the one whose basename
     * matches the loaded map (or the only one present). This is why the Files page works and the
     * old reconstruct-the-path approach didn't: the device tells us the real names.
     */
    private fun discoverMapDownload() {
        mapReadPurpose = MAP_READ_DISCOVER // blocks re-entry + the GPS poll while we browse
        mapDiscoverDirs.clear()
        mapDiscoverFound.clear()
        mapDiscoverCurrentDir = "/maps"
        ble.sendCaptured("files json ${q("/maps")}", TAG_MAP_DISCOVER)
    }

    private fun onMapDiscoverCapture(text: String, timedOut: Boolean) {
        if (mapReadPurpose != MAP_READ_DISCOVER) return // cancelled / disconnected
        if (!timedOut) {
            com.hardwareone.console.ble.FileListing.parse(text)?.takeIf { it.success }?.entries?.forEach { e ->
                val full = joinPath(mapDiscoverCurrentDir, e.name)
                when {
                    e.isDir && mapDiscoverCurrentDir == "/maps" -> mapDiscoverDirs.addLast(full) // one level deep
                    !e.isDir && e.name.lowercase().endsWith(".hwmap") -> mapDiscoverFound.add(full)
                }
            }
        } else if (mapDiscoverCurrentDir == "/maps") {
            // Couldn't even list /maps — this session lacks the Secure Channel + admin for fileread.
            mapReadPurpose = MAP_READ_NONE
            _mapDownload.value = _mapDownload.value?.copy(
                finished = true,
                error = "Map files need the Secure Channel and an admin login.",
            )
            return
        }
        val nextDir = mapDiscoverDirs.removeFirstOrNull()
        if (nextDir != null) {
            mapDiscoverCurrentDir = nextDir
            ble.sendCaptured("files json ${q(nextDir)}", TAG_MAP_DISCOVER)
            return
        }
        // Directory walk done — pick the best real path and read it.
        val info = _mapInfo.value
        val picked = mapDiscoverFound.firstOrNull { it.substringAfterLast('/').equals(info?.filename, ignoreCase = true) }
            ?: mapDiscoverFound.firstOrNull()
        mapReadPurpose = MAP_READ_NONE
        if (picked == null) {
            _mapDownload.value = _mapDownload.value?.copy(
                finished = true,
                error = "Couldn't find the loaded map's .hwmap file under /maps.",
            )
        } else {
            startMapRead(picked, MAP_READ_MAP, info?.fileSize ?: 0)
        }
    }

    fun cancelMapDownload() {
        if (mapReadPurpose != MAP_READ_NONE) mapReadPurpose = MAP_READ_NONE
        _mapDownload.value = null
    }

    fun dismissMapDownload() { _mapDownload.value = null }

    /** Queue the rendered map's cached bytes for the phone's "Save as" picker. */
    fun saveMapToPhone() {
        val info = _mapInfo.value?.takeIf { it.valid } ?: return
        val f = mapCacheFile(resolveMapPath(info), info.fileSize)
        if (!f.exists()) { _mapsNotice.value = "Download the map first."; return }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { f.readBytes() }.getOrNull()?.let { _downloadReady.value = info.filename to it }
        }
    }

    // One map-read window request. `bin` asks the device to ship the chunk as a raw binary
    // Secure-Channel frame (no base64) followed by a JSON header (enc:"raw"). A device that
    // doesn't support it just replies base64 as before, so it's safe to always request.
    // Clear any held body first: on a retry/re-request an orphan from the timed-out or
    // cross-claimed prior reply must not be paired with this window's header. The real body
    // arrives only after this command round-trips, so it always lands after this clear.
    private fun requestMapWindow(offset: Long) {
        ble.clearBinaryBody()
        ble.sendCaptured("fileread ${q(mapReadPath)} $offset $MAP_READ_WINDOW bin", TAG_MAP_READ)
    }

    private fun startMapRead(path: String, purpose: Int, expectedSize: Long = 0) {
        // Never clobber a DIFFERENT in-flight transfer (same-purpose restarts are the candidate
        // fallback and loadMapTrack's pre-claimed slot).
        if (mapReadPurpose != MAP_READ_NONE && mapReadPurpose != purpose) return
        mapReadPath = path
        mapReadOffset = 0L
        mapReadRetried = false
        mapReadPurpose = purpose
        mapReadExpected = expectedSize
        mapReadBuf.reset()
        if (purpose == MAP_READ_MAP) {
            _mapDownload.value = com.hardwareone.console.ble.FileTransfer(
                path.substringAfterLast('/'), 0, expectedSize, upload = false,
            )
        }
        requestMapWindow(0)
    }

    private fun onMapReadCapture(text: String, timedOut: Boolean) {
        val purpose = mapReadPurpose
        if (purpose == MAP_READ_NONE) return // cancelled
        if (timedOut) {
            if (purpose == MAP_READ_SIDECAR) {
                // A timeout (vs. a "not found" reply) means fileread isn't answering this session
                // at all — needs Secure Channel + admin. Stop the background probing entirely.
                mapSidecarUnavailable = true
                mapSidecarCandidates.clear()
                mapReadPurpose = MAP_READ_NONE
                return
            }
            if (!mapReadRetried) { // one retry per window — BLE hiccups are common mid-transfer
                mapReadRetried = true
                requestMapWindow(mapReadOffset)
            } else {
                failMapRead("No reply — map files need the Secure Channel and an admin login.")
            }
            return
        }
        val r = com.hardwareone.console.ble.FileReadChunk.parse(text)
            ?: run { failMapRead("Unexpected file-read reply."); return }
        if (!r.success) { failMapRead(r.error ?: "Read failed."); return }
        // Correlation guard: a late reply from a timed-out window (or one cross-claimed from the
        // Files page's identical loop) must not be appended. Re-request the current window — the
        // stale reply consumed our armed capture, so just dropping it would stall the transfer.
        if (r.offset != mapReadOffset || (r.path.isNotEmpty() && r.path != mapReadPath)) {
            requestMapWindow(mapReadOffset)
            return
        }
        mapReadRetried = false
        val bytes: ByteArray = when (r.enc) {
            "raw" -> {
                // The chunk's bytes arrived as a separate raw binary frame just before this JSON header.
                if (r.len == 0) ByteArray(0)
                else {
                    val body = ble.consumeBinaryBody()
                    if (body == null || body.size != r.len) {
                        // Body frame dropped or size-mismatched — re-request this window rather than
                        // append a short/garbled chunk (mirrors the correlation-guard retry above).
                        requestMapWindow(mapReadOffset)
                        return
                    }
                    body
                }
            }
            "b64" -> runCatching { android.util.Base64.decode(r.data, android.util.Base64.DEFAULT) }.getOrDefault(ByteArray(0))
            else -> r.data.toByteArray(Charsets.UTF_8)
        }
        mapReadBuf.write(bytes)
        mapReadOffset += r.len
        if (purpose == MAP_READ_MAP) {
            _mapDownload.value = _mapDownload.value?.copy(done = mapReadOffset, total = r.size)
        }
        if (r.eof || r.len == 0) {
            mapReadPurpose = MAP_READ_NONE
            finishMapRead(purpose, mapReadPath, mapReadBuf.toByteArray())
        } else {
            requestMapWindow(mapReadOffset)
        }
    }

    private fun failMapRead(msg: String) {
        val purpose = mapReadPurpose
        mapReadPurpose = MAP_READ_NONE
        when (purpose) {
            MAP_READ_MAP -> {
                // Nothing read yet and another candidate path remains → try it (loose /maps-root
                // maps live at a different path than the canonical layout).
                val next = if (mapReadBuf.size() == 0) mapReadCandidates.removeFirstOrNull() else null
                when {
                    next != null -> startMapRead(next, MAP_READ_MAP, mapReadExpected)
                    // All reconstructed guesses 404'd — discover the real path by browsing /maps
                    // (once). Only when nothing was read yet; a mid-transfer failure is a real error.
                    mapReadBuf.size() == 0 && !mapDiscoverTried -> { mapDiscoverTried = true; discoverMapDownload() }
                    else -> _mapDownload.value = _mapDownload.value?.copy(finished = true, error = msg)
                }
            }
            MAP_READ_SIDECAR -> tryNextSidecar() // older maps have no sidecar — quietly fall back
            MAP_READ_TRACK -> _mapsNotice.value = "Track read failed: $msg"
        }
    }

    private fun finishMapRead(purpose: Int, path: String, raw: ByteArray) {
        when (purpose) {
            MAP_READ_MAP -> viewModelScope.launch(Dispatchers.Default) {
                val info = _mapInfo.value
                val parsed = com.hardwareone.console.ble.HwMapFile.parse(raw)
                if (parsed == null) {
                    _mapDownload.value = _mapDownload.value?.copy(finished = true, error = "Not a valid HWMAP v6 map file.")
                    return@launch
                }
                _mapFile.value = parsed
                _mapFilePath.value = path
                _mapDownload.value = null
                if (info != null) {
                    runCatching {
                        val f = mapCacheFile(path, info.fileSize)
                        f.parentFile?.mkdirs()
                        f.writeBytes(raw)
                    }
                }
                // The mapRead* machine is main-confined — hop back before starting the sidecar read.
                kotlinx.coroutines.withContext(Dispatchers.Main) { refreshWaypointSidecar() }
            }
            MAP_READ_SIDECAR -> {
                val sc = com.hardwareone.console.ble.WaypointSidecar.parse(String(raw, Charsets.UTF_8))
                if (sc == null) tryNextSidecar() else _mapWaypoints.value = sc.enrich(_mapWaypoints.value)
            }
            MAP_READ_TRACK -> {
                val t = com.hardwareone.console.ble.GpsTrack.parse(path.substringAfterLast('/'), String(raw, Charsets.UTF_8))
                if (t.points.isEmpty()) {
                    _mapsNotice.value = "No GPS data found in ${path.substringAfterLast('/')}."
                } else {
                    _mapTrack.value = t
                    _mapLiveTracking.value = false
                    val b = _mapInfo.value?.takeIf { it.valid }
                    if (b != null) {
                        val cov = (t.coverage(b.minLat, b.minLon, b.maxLat, b.maxLon) * 100).toInt()
                        _mapsNotice.value = when {
                            cov > 90 -> "Track loaded (${t.points.size} points, $cov% on map)."
                            cov >= 50 -> "Track loaded — only $cov% of it is on this map."
                            else -> "Track loaded, but it's outside this map's bounds ($cov% visible)."
                        }
                    }
                }
            }
        }
    }

    // --- Waypoints (text-scrape list as index truth; sidecar JSON enriches notes/files) ---

    fun refreshMapWaypoints() {
        if (connectionState.value !is ConnectionState.Ready) return
        if (waypointScrapeJob?.isActive == true) return // one `waypoint list` scrape at a time
        waypointScrapeJob = viewModelScope.launch {
            val wps = scrapeWaypointList() ?: return@launch
            _mapWaypoints.value = wps
            _mapTarget.value = wps.firstOrNull { it.isTarget }?.index ?: -1
            refreshWaypointSidecar()
        }
    }

    /** Collect the `waypoint list` text reply: header "Waypoints (n/16):" then one row per slot.
     *  On a plaintext link the whole multi-line reply arrives as ONE emission (only the secure
     *  path line-splits), so every emission is split on '\n' before matching. */
    private suspend fun scrapeWaypointList(): List<com.hardwareone.console.ble.Waypoint>? =
        kotlinx.coroutines.coroutineScope {
            val rows = mutableListOf<com.hardwareone.console.ble.Waypoint>()
            var expected = -1
            val collector = launch {
                ble.incoming.collect { raw ->
                    for (l in raw.split('\n')) {
                        val line = l.trimEnd()
                        if (expected < 0) {
                            com.hardwareone.console.ble.WaypointText.parseHeader(line)?.let { expected = it }
                        } else {
                            com.hardwareone.console.ble.WaypointText.parseRow(line)?.let { rows.add(it) }
                        }
                    }
                }
            }
            ble.sendCommand("waypoint list")
            kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                while (expected < 0 || rows.size < expected) kotlinx.coroutines.delay(50)
            }
            collector.cancel()
            if (expected < 0) null else rows.toList()
        }

    private fun refreshWaypointSidecar() {
        val info = _mapInfo.value?.takeIf { it.valid } ?: return
        if (mapSidecarUnavailable) return // fileread not available to this session — see field doc
        if (mapReadPurpose != MAP_READ_NONE) return // don't preempt a running transfer
        val dir = resolveMapPath(info).substringBeforeLast('/')
        mapSidecarCandidates.clear()
        // Same probe order as the firmware's WaypointManager::loadWaypoints, incl. the legacy
        // bare-name fallback; loose /maps-root maps keep their sidecar at the root.
        mapSidecarCandidates.add("$dir/waypoints_${info.base}.hwmap.json")
        mapSidecarCandidates.add("$dir/waypoints_${info.base}.json")
        mapSidecarCandidates.add("$dir/waypoints.json")
        if (dir != "/maps") {
            mapSidecarCandidates.add("/maps/waypoints_${info.base}.hwmap.json")
            mapSidecarCandidates.add("/maps/waypoints_${info.base}.json")
        }
        tryNextSidecar()
    }

    private fun tryNextSidecar() {
        val next = mapSidecarCandidates.removeFirstOrNull() ?: return
        startMapRead(next, MAP_READ_SIDECAR)
    }

    /**
     * Send a text-reply map verb and surface its verdict line ("Error: …" always terminal).
     * Serialized under [mapScrapeMutex] — the console stream has no command↔reply correlation,
     * so two live scrapes would both claim the first matching line. A reply that outlives its
     * own scrape's timeout can still be claimed by the next serialized scrape; full correlation
     * would need a firmware-side reply tag the CLI contract doesn't provide.
     */
    private suspend fun sendAndScrape(command: String, prefixes: List<String>, timeoutMs: Long): String? =
        mapScrapeMutex.withLock {
            kotlinx.coroutines.coroutineScope {
                var result: String? = null
                val collector = launch {
                    ble.incoming.collect { raw ->
                        // Plaintext links deliver multi-line replies as one emission — split first.
                        for (l in raw.split('\n')) {
                            // The device wraps successful CLI replies with an "OK: " prefix over BLE,
                            // so strip it before matching (and for display): otherwise a scrape prefix
                            // like "Loaded:" never matches "OK: Loaded: …" and every load/unload/
                            // organize/waypoint reply falls back to a bogus "No reply.".
                            val line = l.trim().removePrefix("OK: ")
                            if (result == null && (line.startsWith("Error:") || prefixes.any { line.startsWith(it) })) {
                                result = line
                            }
                        }
                    }
                }
                ble.sendCommand(command)
                kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                    while (result == null) kotlinx.coroutines.delay(50)
                }
                collector.cancel()
                result
            }
        }

    private fun mapVerb(command: String, okPrefix: String, timeoutMs: Long = 3_000, reload: Boolean = true) {
        if (connectionState.value !is ConnectionState.Ready) return
        viewModelScope.launch {
            _mapsNotice.value = sendAndScrape(command, listOf(okPrefix), timeoutMs) ?: "No reply."
            if (reload) refreshMapWaypoints()
        }
    }

    private fun fmt6(v: Double): String = String.format(java.util.Locale.US, "%.6f", v)

    /** Firmware-style sanitize: printable ASCII, no double quote (tokenizer can't carry one). */
    private fun sanitizeWpText(s: String): String = s.filter { it.code in 32..126 && it != '"' }.trim()

    fun addWaypoint(lat: Double, lon: Double, name: String, notes: String = "") {
        if (connectionState.value !is ConnectionState.Ready) return
        val nm = sanitizeWpText(name).take(11).ifEmpty { "WP" }
        viewModelScope.launch {
            val line = sendAndScrape("waypoint add ${fmt6(lat)} ${fmt6(lon)} ${q(nm)}", listOf("Added waypoint"), 3_000)
            _mapsNotice.value = line ?: "No reply."
            val idx = line?.let { Regex("""Added waypoint (\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val nt = sanitizeWpText(notes).take(255)
            if (idx != null && nt.isNotEmpty()) {
                sendAndScrape("waypoint notes $idx $nt", listOf("Set notes"), 2_500)
            }
            refreshMapWaypoints()
        }
    }

    fun deleteWaypoint(index: Int) = mapVerb("waypoint del $index", "Deleted waypoint")
    fun gotoWaypoint(index: Int) = mapVerb("waypoint goto $index", "Navigation target")
    fun clearWaypointTarget() = mapVerb("waypoint clear", "Navigation target")
    fun clearAllWaypoints() = mapVerb("waypoint clearall", "All waypoints")

    fun renameWaypoint(index: Int, name: String) {
        val nm = sanitizeWpText(name).take(11)
        if (nm.isEmpty()) return
        mapVerb("waypoint rename $index $nm", "Renamed waypoint")
    }

    fun setWaypointNotes(index: Int, notes: String) {
        val nt = sanitizeWpText(notes).take(255)
        if (nt.isEmpty()) {
            // `waypoint notes <idx>` with no text is rejected by the firmware (usage error), so
            // notes can't be cleared over the CLI — surface that instead of the raw usage error.
            _mapsNotice.value = "This firmware can't clear notes over the CLI — enter some text."
            return
        }
        mapVerb("waypoint notes $index $nt", "Set notes")
    }

    /** Waypoint export in the web page's JSON shape (client-side file, saved via the picker). */
    fun exportWaypoints() {
        val wps = _mapWaypoints.value
        if (wps.isEmpty()) { _mapsNotice.value = "No waypoints to export."; return }
        val arr = org.json.JSONArray()
        for (w in wps) {
            arr.put(
                org.json.JSONObject()
                    .put("name", w.name).put("lat", w.lat).put("lon", w.lon).put("notes", w.notes),
            )
        }
        val o = org.json.JSONObject()
            .put("waypoints", arr)
            .put("exportedAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
            .put("version", 1)
        val base = _mapInfo.value?.takeIf { it.valid }?.base ?: "map"
        _downloadReady.value = "waypoints_$base.json" to o.toString(2).toByteArray(Charsets.UTF_8)
    }

    /** Re-add waypoints from an exported JSON (web export or firmware sidecar shape). */
    fun importWaypoints(text: String) {
        val sc = com.hardwareone.console.ble.WaypointSidecar.parse(text)
        if (sc == null || sc.waypoints.isEmpty()) { _mapsNotice.value = "Not a waypoints JSON file."; return }
        if (connectionState.value !is ConnectionState.Ready) return
        viewModelScope.launch {
            var added = 0
            for (w in sc.waypoints) {
                val nm = sanitizeWpText(w.name).take(11).ifEmpty { "WP" }
                val line = sendAndScrape("waypoint add ${fmt6(w.lat)} ${fmt6(w.lon)} ${q(nm)}", listOf("Added waypoint"), 3_000)
                if (line != null && line.startsWith("Added waypoint")) {
                    added++
                    val idx = Regex("""Added waypoint (\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    val nt = sanitizeWpText(w.notes).take(255)
                    if (idx != null && nt.isNotEmpty()) sendAndScrape("waypoint notes $idx $nt", listOf("Set notes"), 2_500)
                } else if (line != null && line.contains("No free waypoint slots")) {
                    break
                }
            }
            _mapsNotice.value = "Imported $added waypoint(s)."
            refreshMapWaypoints()
        }
    }

    // --- Device map management ---

    fun deviceLoadMap(path: String) {
        if (connectionState.value !is ConnectionState.Ready) return
        viewModelScope.launch {
            // mapload pre-warms the device's PSRAM tile cache — give it a generous window.
            _mapsNotice.value = sendAndScrape("mapload ${q(path)}", listOf("Loaded:"), 10_000) ?: "No reply."
            loadMaps()
        }
    }

    fun deviceUnloadMap() {
        if (connectionState.value !is ConnectionState.Ready) return
        viewModelScope.launch {
            _mapsNotice.value = sendAndScrape("mapunload", listOf("Map unloaded"), 4_000) ?: "No reply."
            loadMaps()
        }
    }

    /** Move loose .hwmap files at /maps into per-map folders (`maporganize`). */
    fun organizeMaps() {
        if (connectionState.value !is ConnectionState.Ready) return
        viewModelScope.launch {
            _mapsNotice.value = sendAndScrape("maporganize", listOf("Map organize:"), 5_000) ?: "No reply."
            loadMaps()
        }
    }

    /** Upload a .hwmap from the phone (limited only by free storage; slow for big files over BLE),
     *  then organize it into /maps/<base>/. */
    fun uploadMapFile(name: String, bytes: ByteArray) {
        if (!name.endsWith(".hwmap")) { _mapsNotice.value = "Pick a .hwmap file."; return }
        if (rejectQuotedName(name)) return
        // uploadFile() silently no-ops when busy/disconnected — pre-check so the refusal is
        // visible, and clear any stale finished transfer so the watcher below can only ever
        // match THIS upload (a stale same-name success would fake-complete instantly).
        if (connectionState.value !is ConnectionState.Ready) { _mapsNotice.value = "Not connected."; return }
        if (_filesBusy.value) { _mapsNotice.value = "Another file transfer is in progress — try again."; return }
        _fileTransfer.value = null
        uploadFile("/maps/$name", bytes)
        val started = _fileTransfer.value
        if (started == null || started.name != name || started.finished) return // refused or failed instantly
        viewModelScope.launch {
            val done = kotlinx.coroutines.withTimeoutOrNull(600_000L) {
                _fileTransfer.first { it != null && it.name == name && it.finished }
            }
            if (done != null && done.error == null) {
                sendAndScrape("maporganize", listOf("Map organize:"), 5_000)
                loadMaps()
                dismissTransfer()
                _mapsNotice.value = "Uploaded $name and organized it into /maps/."
            }
        }
    }

    // --- GPS (map page poll + live-track accumulation) ---

    /** Poll `gpsread json`. Backs off permanently (until the next [loadMaps]) after 3 straight
     *  misses so a GPS-less device isn't spammed with unknown commands. */
    fun pollMapsGps() {
        if (!_mapGpsUpdates.value) return // GPS updates toggled off (satellite HUD button)
        if (connectionState.value !is ConnectionState.Ready) return
        if (mapGpsMisses >= 3) return
        // Don't queue polls behind an in-flight windowed transfer: a congested window that
        // times out would hand its late reply to the queued gpsread capture (and vice versa).
        if (mapReadPurpose != MAP_READ_NONE) return
        ble.sendCaptured("gpsread json", TAG_MAP_GPS)
    }

    private fun onMapGpsCapture(text: String, timedOut: Boolean) {
        if (timedOut || text.isBlank()) { mapGpsMisses++; return }
        val fix = com.hardwareone.console.ble.GpsFix.parse(text) ?: run { mapGpsMisses++; return }
        mapGpsMisses = 0
        _mapGps.value = fix
        if (_mapLiveTracking.value && fix.valid && fix.fix) appendLiveTrackPoint(fix)
    }

    /** Toggle the GPS sensor itself (the OLED map's GPS menu equivalent). */
    fun setMapsGpsPower(on: Boolean) {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCommand(if (on) "opengps" else "closegps")
        mapGpsMisses = 0
    }

    private fun appendLiveTrackPoint(fix: com.hardwareone.console.ble.GpsFix) {
        val last = liveTrackPoints.lastOrNull()
        // Same dedupe the firmware's live tracker uses: skip points < 2 m apart; cap 10 000.
        if (last != null && com.hardwareone.console.ble.MapGeo.distanceM(last.lat, last.lon, fix.lat, fix.lon) < 2.0) return
        if (liveTrackPoints.size >= 10_000) return
        liveTrackPoints.add(com.hardwareone.console.ble.TrackPoint(System.currentTimeMillis() / 1000.0, fix.lat, fix.lon))
        _mapTrack.value = com.hardwareone.console.ble.GpsTrack("live", liveTrackPoints.toList())
    }

    // --- GPS tracks ---

    /** List candidate track files (admin): the same dirs the device's interfaces scan. */
    fun loadMapTrackFiles() {
        if (connectionState.value !is ConnectionState.Ready) return
        if (_mapTrackFilesLoading.value) return
        _mapTrackFilesLoading.value = true
        mapTrackDirDenied = false
        mapTrackDirs.clear()
        mapTrackDirs.addAll(listOf("/logging_captures", "/logging_captures/tracks", "/logging_captures/sensors"))
        mapTrackFilesTmp.clear()
        nextMapTrackDir()
    }

    private fun nextMapTrackDir() {
        val dir = mapTrackDirs.removeFirstOrNull()
        if (dir == null) {
            _mapTrackFiles.value = mapTrackFilesTmp.toList()
            _mapTrackFilesLoading.value = false
            if (mapTrackFilesTmp.isEmpty()) {
                // `files json` is admin-only and its denial is plain text the capture never
                // claims — a timeout on every dir means "needs admin", not "no logs".
                _mapsNotice.value = if (mapTrackDirDenied) {
                    "Couldn't list device logs — Secure Channel + admin login required."
                } else {
                    "No log files found in /logging_captures."
                }
            }
            return
        }
        mapTrackCurrentDir = dir
        ble.sendCaptured("files json ${q(dir)}", TAG_MAP_TRACKDIR)
    }

    private fun onMapTrackDirCapture(text: String, timedOut: Boolean) {
        if (timedOut) {
            mapTrackDirDenied = true
        } else {
            com.hardwareone.console.ble.FileListing.parse(text)?.takeIf { it.success }?.entries?.forEach { e ->
                val n = e.name.lowercase()
                if (!e.isDir && (n.endsWith(".csv") || n.endsWith(".log") || n.endsWith(".txt"))) {
                    mapTrackFilesTmp.add(joinPath(mapTrackCurrentDir, e.name))
                }
            }
        }
        nextMapTrackDir()
    }

    /** Load a track: point the DEVICE at it (validation parity) and fetch it for local rendering. */
    fun loadMapTrack(path: String) {
        if (connectionState.value !is ConnectionState.Ready) return
        if (mapReadPurpose != MAP_READ_NONE) { _mapsNotice.value = "Another map transfer is in progress."; return }
        // Claim the transfer slot NOW: the scrape below suspends for seconds, during which a
        // second tap (or the background sidecar fetch) would otherwise pass the guard and
        // interleave two windowed reads into one buffer. startMapRead re-sets the same purpose.
        mapReadPurpose = MAP_READ_TRACK
        viewModelScope.launch {
            // "Loaded " (with space) — the gpstrack reply is "Loaded N GPS points"; a bare
            // "Loaded" would also claim mapload's "Loaded: <file>" from a concurrent verb.
            sendAndScrape("gpstrack load ${q(path)}", listOf("Loaded "), 5_000)?.let { _mapsNotice.value = it }
            startMapRead(path, MAP_READ_TRACK)
        }
    }

    fun deleteMapTrackFile(path: String) {
        if (connectionState.value !is ConnectionState.Ready) return
        ble.sendCommand("filedelete ${q(path)} confirm")
        viewModelScope.launch { kotlinx.coroutines.delay(600); loadMapTrackFiles() }
    }

    fun clearMapTrack() {
        _mapTrack.value = null
        _mapLiveTracking.value = false
        liveTrackPoints.clear()
        if (connectionState.value is ConnectionState.Ready) ble.sendCommand("gpstrack clear")
    }

    /**
     * Live tracking, replicated from the OLED flow: open GPS, arm the sensor logger at 1 Hz to
     * /logging_captures/tracks/live.csv, and accumulate a local track from the GPS poll. (The
     * device's in-RAM live buffer isn't reachable over the CLI, so the app builds its own.)
     */
    fun startLiveTrack() {
        if (connectionState.value !is ConnectionState.Ready) return
        liveTrackPoints.clear()
        _mapTrack.value = null
        _mapLiveTracking.value = true
        mapGpsMisses = 0
        ble.sendCommand("opengps")
        ble.sendCommand("sensorlog format track")
        ble.sendCommand("sensorlog sensors gps")
        ble.sendCommand("sensorlog start /logging_captures/tracks/live.csv 1000")
        _mapsNotice.value = "Live tracking on — device logs to /logging_captures/tracks/live.csv."
    }

    fun stopLiveTrack() {
        _mapLiveTracking.value = false
        if (connectionState.value is ConnectionState.Ready) ble.sendCommand("sensorlog stop")
        _mapsNotice.value = "Live tracking stopped."
    }

    /** Export the currently shown track as CSV to the phone. */
    fun exportMapTrack() {
        val t = _mapTrack.value ?: run { _mapsNotice.value = "No track to export."; return }
        val sb = StringBuilder("# GPS Track\n# time,lat,lon\n")
        for (p in t.points) {
            sb.append(if (p.timeS.isNaN()) "0" else p.timeS.toLong().toString())
                .append(',').append(fmt6(p.lat)).append(',').append(fmt6(p.lon)).append('\n')
        }
        _downloadReady.value = "track-${t.name.ifEmpty { "export" }}.csv" to sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Stitch several device log files into one track (`gpstrackmerge`, admin; max 9 inputs). */
    fun mergeMapTracks(output: String, inputs: List<String>) {
        if (connectionState.value !is ConnectionState.Ready || inputs.isEmpty()) return
        if (rejectQuotedName(output)) return
        if (inputs.size > 9) {
            _mapsNotice.value = "The device stitches at most 9 files — deselect ${inputs.size - 9}."
            return
        }
        val cmd = "gpstrackmerge ${q(output)} " + inputs.joinToString(" ") { q(it) }
        // The whole command must fit one secure frame; long track filenames blow the budget well
        // before 9 inputs. Oversized sends are rejected app-side with a console-only error the
        // scrape can't see, so check up front.
        if (cmd.toByteArray(Charsets.UTF_8).size > ble.secureCommandCapacity()) {
            _mapsNotice.value = "Too many long file names for one BLE command — select fewer files."
            return
        }
        viewModelScope.launch {
            _mapsNotice.value = sendAndScrape(cmd, listOf("Stitched"), 10_000) ?: "No reply."
            loadMapTrackFiles()
        }
    }

    // --- Persisted device map settings (admin: mapzoom / maplayers / mapcachekb) ---

    fun setDeviceMapZoom(zoom: Float) =
        mapVerb("mapzoom ${String.format(java.util.Locale.US, "%.2f", zoom)}", "mapZoom set", reload = false)

    fun setDeviceMapLayers(mask: Int) =
        mapVerb("maplayers $mask", "mapVisibleLayers set", reload = false)

    fun setDeviceMapCacheKb(kb: Int) =
        mapVerb("mapcachekb $kb", "mapCacheSizeKB set", reload = false)

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
        const val TAG_SENSOR_READ = "sread:"
        const val TAG_CONTROL_MODULES = "controlmods"
        const val TAG_CONTROLS_PREFIX = "controls:"
        const val TAG_BATTERY = "battery"
        const val TAG_MEMREPORT = "memreport"
        const val TAG_FSUSAGE = "fsusage"
        const val TAG_PERFTOP = "perftop"
        const val TAG_TASKSTATS = "taskstats"
        const val TAG_LLM_STATUS = "llmstatus"
        const val TAG_LLM_MODELS = "llmmodels"
        const val TAG_LLM_GEN = "llmgen"
        const val TAG_LLM_RESULT = "llmresult"
        const val TAG_LLM_CMD = "llmcmd" // one-shot load/unload/stop/clear; reply captured & ignored
        const val TAG_FILES = "files"
        const val TAG_FILE_STATS = "filestats"
        const val TAG_FILE_READ = "fileread"
        const val TAG_FILE_WRITE = "filewrite"
        const val TAG_OTA_PREFIX = "ota:"
        const val TAG_OTA_STATUS = "ota:status"
        const val TAG_OTA_CREDENTIAL = "ota:credential"
        const val TAG_OTA_STAGED = "ota:staged"
        const val TAG_OTA_ARMED = "ota:armed"
        const val TAG_OTA_MONITOR = "ota:monitor"
        const val TAG_OTA_ACK = "ota:ack"
        const val OTA_STATUS_TIMEOUT_MS = 15_000L
        const val OTA_LONG_STATUS_TIMEOUT_MS = 60_000L
        const val OTA_POLL_INTERVAL_MS = 3_000L
        const val OTA_RECONNECT_INTERVAL_MS = 5_000L
        const val OTA_RECONNECT_ATTEMPTS = 36
        val OTA_STARTABLE_PHASES = setOf("idle", "succeeded", "failed", "canceled")
        const val TAG_EN_MODE = "enmode"
        const val TAG_EN_ENC = "enenc"
        const val TAG_EN_MESHROLE = "enmeshrole"
        const val TAG_EN_DEVINFO = "endevinfo"
        const val TAG_EN_LIST = "enlist"
        const val TAG_EN_MESH = "enmesh"
        const val TAG_EN_DEVICES = "endevices"
        const val TAG_EN_BOND = "enbond"
        const val TAG_AUTOMATIONS = "autos"
        const val TAG_AUTOMATION_SYS = "autosys"
        const val TAG_EN_MSGS = "enmsgs"
        const val TAG_EN_REMOTE_ACK = "enremoteack"
        const val TAG_EN_FILES_ACK = "enfilesack"
        const val TAG_EN_FETCH_ACK = "enfetchack"
        const val TAG_MAP_INFO = "mapinfo"
        const val TAG_MAP_LIST = "maplistj"
        const val TAG_MAP_GPS = "mapgps"
        const val TAG_MAP_READ = "mapread"
        const val TAG_MAP_DISCOVER = "mapdiscover"
        const val TAG_MAP_TRACKDIR = "maptrackdir"

        // Camera + microphone media pages.
        const val TAG_CAM_STATUS = "camstatus"
        const val TAG_IMAGES_LFS = "imageslfs"
        const val TAG_IMAGES_SD = "imagessd"
        const val TAG_MIC_STATUS = "micstat"
        const val TAG_MIC_LEVEL = "miclevel"
        const val TAG_MIC_LIST = "miclist"
        const val TAG_MEDIA_READ = "mediaread"
        const val MEDIA_READ_WINDOW = 2048 // same 2 KB window the map/file loops use (fits the ~4 KB reply cap)
        const val MEDIA_READ_NONE = 0
        const val MEDIA_READ_IMAGE = 1
        const val MEDIA_READ_AUDIO = 2
        const val CAMERA_FOLDER_LFS = "/photos"    // default cameraCaptureFolder (LittleFS)
        const val CAMERA_FOLDER_SD = "/sd/photos"  // default cameraCaptureFolder on SD

        // Windowed fileread for map/sidecar/track fetches. Must match the Files page's proven
        // 2048: the fileread REPLY is JSON with the window base64-encoded (4:3 expansion) plus a
        // ~150-byte envelope, and the firmware caps a single command result at ~4 KB. A 4096-byte
        // window produced a ~5.6 KB reply that the device truncated to 4095 bytes — the JSON
        // arrived unterminated, failed to parse, and the download could never complete. 2048 →
        // ~2.9 KB reply, safely under the cap on every firmware build.
        const val MAP_READ_WINDOW = 2048
        const val MAP_READ_NONE = 0
        const val MAP_READ_MAP = 1
        const val MAP_READ_SIDECAR = 2
        const val MAP_READ_TRACK = 3
        const val MAP_READ_DISCOVER = 4 // browsing /maps to find the real map path
    }
}
