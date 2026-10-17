package com.hardwareone.console

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hardwareone.console.ble.BleConstants
import com.hardwareone.console.ble.BleManager
import com.hardwareone.console.ble.ConnectionState
import com.hardwareone.console.bridge.BridgeStore
import com.hardwareone.console.bridge.HomeBridge
import com.hardwareone.console.pipe.PhonePipe
import com.hardwareone.console.security.SavedLog
import com.hardwareone.console.ui.AppPage
import com.hardwareone.console.ui.CameraScreen
import com.hardwareone.console.ui.ConsoleScreen
import com.hardwareone.console.ui.ConsoleViewModel
import com.hardwareone.console.ui.DeviceSettingsScreen
import com.hardwareone.console.ui.DevicesScreen
import com.hardwareone.console.ui.EspNowConfigScreen
import com.hardwareone.console.ui.EspNowDeviceScreen
import com.hardwareone.console.ui.AutomationsScreen
import com.hardwareone.console.ui.EspNowBondScreen
import com.hardwareone.console.ui.EspNowScreen
import com.hardwareone.console.ui.FilesScreen
import com.hardwareone.console.ui.HealthScreen
import com.hardwareone.console.ui.HeaderNav
import com.hardwareone.console.ui.HomeBridgeScreen
import com.hardwareone.console.ui.PhonePipeScreen
import com.hardwareone.console.ui.LlmChatScreen
import com.hardwareone.console.ui.LoginDialog
import com.hardwareone.console.ui.MapsScreen
import com.hardwareone.console.ui.MicScreen
import com.hardwareone.console.ui.NotificationBanner
import com.hardwareone.console.ui.NotificationsScreen
import com.hardwareone.console.ui.OtaScreen
import com.hardwareone.console.ui.LogViewerScreen
import com.hardwareone.console.ui.SavedLogsScreen
import com.hardwareone.console.ui.SensorsScreen
import com.hardwareone.console.ui.SettingsScreen
import com.hardwareone.console.ui.PerfPage
import com.hardwareone.console.ui.PerfScreen
import com.hardwareone.console.ui.StatusScreen
import com.hardwareone.console.ui.ThemePreference
import com.hardwareone.console.ui.ThemeStore
import com.hardwareone.console.ui.rememberFoldPosture
import com.hardwareone.console.ui.theme.HardwareOneTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher

/**
 * Single Activity (a [FragmentActivity] so it can host [BiometricPrompt]). It owns the
 * system interactions — runtime permissions, the "turn Bluetooth on" prompt, biometric/PIN
 * prompts, and the file picker for exporting logs — plus simple screen navigation, and
 * hands everything else to [ConsoleViewModel] and the screen composables.
 */
class MainActivity : FragmentActivity() {

    private val vm: ConsoleViewModel by viewModels()

    /** One auto-login attempt per connection. */
    private var autoLoginHandled = false

    /** Top-level page (flipped by the header toggle); secondary screens push above it. */
    private var topPage by mutableStateOf(AppPage.DEVICES)

    /** Push stack of secondary screens above the current top page (empty = on a top page). */
    private sealed interface Screen {
        data object Settings : Screen
        data object DeviceSettings : Screen
        data object Camera : Screen
        data object Mic : Screen
        data object Status : Screen
        data object Perf : Screen
        data object Sensors : Screen
        data object Health : Screen
        data object LlmChat : Screen
        data object Files : Screen
        data object Ota : Screen
        data object EspNow : Screen
        data object Automations : Screen
        data object Maps : Screen
        data object EspNowConfig : Screen
        data object EspNowBond : Screen
        data class EspNowDevice(val mac: String, val name: String) : Screen
        data object SavedLogs : Screen
        data object Notifications : Screen
        data object HomeBridge : Screen
        data object BlePipe : Screen
        data class Viewer(val fileName: String, val title: String, val text: String) : Screen
    }

    private val nav = mutableStateListOf<Screen>()
    private fun navTo(screen: Screen) { nav.add(screen) }
    private fun navBack() { if (nav.isNotEmpty()) nav.removeAt(nav.lastIndex) }

    private var pendingExportText: String = ""

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                ensureBluetoothThenScan()
            } else {
                vm.reportError("Bluetooth permission denied — scanning needs it. Grant it in Settings or tap SCAN to retry.")
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            vm.startScan()
        }

    /** The bridge's ongoing notification needs POST_NOTIFICATIONS on Android 13+; the service
     *  runs either way, so the result only decides whether the status card is visible. */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { HomeBridge.start(this) }

    private fun startHomeBridge() {
        val needsPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPrompt) notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else HomeBridge.start(this)
    }

    /**
     * The one battery step the pipe recommends (FW-1): "Unrestricted" lets Android restart it in the
     * background after process death. Falls back to the battery settings list if the direct prompt
     * is unavailable.
     */
    private fun requestPipeBatteryExemption() {
        val direct = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }
            .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure { PhonePipe.update { it.copy(error = "Open Settings → Apps → HardwareOne → Battery and choose Unrestricted.") } }
    }

    /** What to do once the pipe's permissions are settled (start). */
    private var pendingPipeAction: (() -> Unit)? = null

    /** Nearby devices (connect + advertise) are required; notifications only make the status visible. */
    private val pipePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            val action = pendingPipeAction
            pendingPipeAction = null
            // Re-check rather than trust the result map: an interrupted request returns an empty map.
            if (missingPipePermissions(includeNotifications = false).isEmpty()) {
                action?.invoke()
            } else {
                PhonePipe.update { it.copy(error = "The pipe needs the Nearby devices permission to advertise to HardwareOne.") }
            }
        }

    private fun missingPipePermissions(includeNotifications: Boolean): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(android.Manifest.permission.BLUETOOTH_CONNECT)
            add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (includeNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(android.Manifest.permission.POST_NOTIFICATIONS)
    }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun withPipePermissions(action: () -> Unit) {
        if (pendingPipeAction != null) return // a request is already showing; ignore repeat taps
        val wanted = missingPipePermissions(includeNotifications = true)
        if (wanted.isEmpty()) action() else {
            pendingPipeAction = action
            pipePermissionLauncher.launch(wanted.toTypedArray())
        }
    }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(pendingExportText.toByteArray(Charsets.UTF_8))
                    }
                }
                    .onSuccess { vm.reportInfo("Log exported.") }
                    .onFailure { vm.reportError("Export failed: ${it.message}") }
            }
            pendingExportText = ""
        }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always block screenshots, screen recording, screen-share, and the recents preview.
        // Not user-configurable by design.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        handleWidgetConnect(intent) // tap-to-reconnect launched us from the widget
        handleBridgeIntent(intent) // the bridge notification opens its page
        handlePipeIntent(intent) // so does the pipe's; onResume resumes an enabled pipe (FW-1)
        setContent {
            var themePref by remember { mutableStateOf(ThemeStore.load(this)) }
            val darkTheme = when (themePref) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            HardwareOneTheme(darkTheme = darkTheme) {
                // Keep the screen awake while a BLE file transfer is in flight. These are slow
                // (base64, one small chunk per round-trip), and a screen timeout that dozes the app
                // can stall or drop the transfer. Covers uploads + file downloads (filesBusy) and an
                // in-progress map download. Cleared the instant it finishes (or the composition
                // leaves), so the normal screen timeout resumes right after.
                val filesBusy by vm.filesBusy.collectAsState()
                val mapDownload by vm.mapDownload.collectAsState()
                val otaState by vm.ota.collectAsState()
                val keepScreenAwake = filesBusy || (mapDownload?.finished == false) || otaState.keepScreenAwake
                val localView = androidx.compose.ui.platform.LocalView.current
                androidx.compose.runtime.DisposableEffect(keepScreenAwake) {
                    localView.keepScreenOn = keepScreenAwake
                    onDispose { localView.keepScreenOn = false }
                }

                BackHandler(enabled = nav.isNotEmpty() || topPage == AppPage.CONSOLE) {
                    if (nav.isNotEmpty()) navBack() else topPage = AppPage.DEVICES
                }

                val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass
                val foldPosture = rememberFoldPosture(this)

                // The switcher/nav hub lives top-left on every page. Build the right [HeaderNav]
                // for whichever page is showing — the device-tool dropdown is gated on login
                // (the tools are all account-gated anyway).
                val loggedIn by vm.authenticated.collectAsState()
                val unreadAlerts by vm.unreadNotifications.collectAsState()
                // Device tools this firmware build doesn't contain — the nav greys them out with
                // the reason rather than hiding them.
                val deviceCaps by vm.capabilities.collectAsState()
                val unsupportedCmds by vm.unsupportedCommands.collectAsState()
                val unavailableTools = remember(deviceCaps, unsupportedCmds) {
                    vm.unavailableTools(deviceCaps, unsupportedCmds)
                }
                val goDevices = { nav.clear(); topPage = AppPage.DEVICES }
                fun openTool(screen: Screen) { nav.clear(); navTo(screen) }
                // `current` is the tool screen showing (null on the Console/Devices top pages).
                // Each dropdown entry for the page you're already on is omitted — same rule the
                // Devices list page already uses for its own "Devices" entry.
                fun headerNav(
                    active: AppPage,
                    onOpenDevices: (() -> Unit)?,
                    current: Screen? = null,
                    onSaveLog: (() -> Unit)? = null,
                    onClearLog: (() -> Unit)? = null,
                ) = HeaderNav(
                    active = active,
                    onSelect = { p -> nav.clear(); topPage = p },
                    onOpenSettings = { navTo(Screen.Settings) },
                    loggedIn = loggedIn,
                    devicesLabel = when (current) {
                        Screen.Status -> "Status"
                        Screen.Perf -> "Perf"
                        Screen.Sensors -> "Sensors"
                        Screen.Health -> "R1 Health"
                        Screen.LlmChat -> "LLM Chat"
                        Screen.Files -> "Files"
                        Screen.Ota -> "OTA"
                        Screen.EspNow -> "ESP-NOW"
                        Screen.Automations -> "Automations"
                        Screen.Maps -> "Maps"
                        Screen.DeviceSettings -> "Settings"
                        Screen.Camera -> "Camera"
                        Screen.Mic -> "Mic"
                        else -> "Devices"
                    },
                    onOpenDevices = onOpenDevices,
                    onOpenStatus = ({ openTool(Screen.Status) }).takeIf { current != Screen.Status },
                    onOpenPerf = ({ openTool(Screen.Perf) }).takeIf { current != Screen.Perf },
                    onOpenSensors = ({ openTool(Screen.Sensors) }).takeIf { current != Screen.Sensors },
                    onOpenHealth = ({ vm.refreshHealthStatus(); vm.loadHealthLog(); openTool(Screen.Health) }).takeIf { current != Screen.Health },
                    onOpenLlm = ({ openTool(Screen.LlmChat) }).takeIf { current != Screen.LlmChat },
                    onOpenFiles = ({ vm.loadFiles("/"); openTool(Screen.Files) }).takeIf { current != Screen.Files },
                    onOpenOta = ({ vm.refreshOtaStatus(); openTool(Screen.Ota) }).takeIf { current != Screen.Ota },
                    onOpenEspNow = ({ openTool(Screen.EspNow) }).takeIf { current != Screen.EspNow },
                    onOpenAutomations = ({ vm.loadAutomations(); openTool(Screen.Automations) }).takeIf { current != Screen.Automations },
                    onOpenDeviceSettings = ({ vm.loadControlModules(); openTool(Screen.DeviceSettings) }).takeIf { current != Screen.DeviceSettings },
                    onOpenCamera = ({ openTool(Screen.Camera) }).takeIf { current != Screen.Camera },
                    onOpenMic = ({ openTool(Screen.Mic) }).takeIf { current != Screen.Mic },
                    // No eager load here (unlike Files/Automations): the Screen.Maps branch's
                    // RESUMED loop loads on entry, and a double loadMaps() would run two
                    // concurrent `waypoint list` scrapes against the shared console stream.
                    onOpenMaps = ({ openTool(Screen.Maps) }).takeIf { current != Screen.Maps },
                    onSyncClock = vm::syncClock,
                    onSaveLog = onSaveLog,
                    onClearLog = onClearLog,
                    unreadNotifications = unreadAlerts,
                    unavailableTools = unavailableTools,
                    // Pushed, not cleared-and-pushed: Back should return you to whatever page
                    // the alert interrupted.
                    onOpenNotifications = ({ vm.markNotificationsRead(); navTo(Screen.Notifications) })
                        .takeIf { nav.lastOrNull() != Screen.Notifications },
                )

                Box(Modifier.fillMaxSize()) {
                when (val screen = nav.lastOrNull()) {
                    null -> when (topPage) {
                        AppPage.CONSOLE -> ConsoleScreen(
                            vm = vm,
                            widthSizeClass = widthSizeClass,
                            foldPosture = foldPosture,
                            nav = headerNav(
                                active = AppPage.CONSOLE,
                                onOpenDevices = goDevices,
                                onSaveLog = if (vm.canSaveLogs) ({ vm.saveCurrentLog() }) else null,
                                onClearLog = vm::clearLog,
                            ),
                            onLoginButton = ::onLoginButtonClicked,
                        )
                        AppPage.DEVICES -> {
                            val battery by vm.battery.collectAsState()
                            // Poll battery while connected so the connection card shows it.
                            LaunchedEffect(Unit) {
                                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                    while (true) {
                                        vm.refreshBattery()
                                        kotlinx.coroutines.delay(6_000)
                                    }
                                }
                            }
                            val bridgeState by HomeBridge.state.collectAsState()
                            DevicesScreen(
                                vm = vm,
                                battery = battery,
                                onScanClicked = ::onScanClicked,
                                onLogin = ::onLoginButtonClicked,
                                nav = headerNav(active = AppPage.DEVICES, onOpenDevices = null),
                                bridgeSummary = if (bridgeState.running) HomeBridge.summary(bridgeState) else null,
                                onOpenBridge = { navTo(Screen.HomeBridge) },
                            )
                        }
                    }

                    Screen.Settings -> {
                        val autoLogin by vm.autoLogin.collectAsState()
                        val hasSaved by vm.hasSavedCredentials.collectAsState()
                        val savedUser by vm.savedUsername.collectAsState()
                        val autoSaveLogs by vm.autoSaveLogs.collectAsState()
                        val secureConfigured by vm.secureChannelConfigured.collectAsState()
                        val secureLocked by vm.secureChannelLocked.collectAsState()
                        val bridgeState by HomeBridge.state.collectAsState()
                        val pipeState by PhonePipe.state.collectAsState()
                        LaunchedEffect(Unit) { PhonePipe.load(this@MainActivity) }
                        SettingsScreen(
                            bridgeSummary = HomeBridge.summary(bridgeState),
                            onOpenHomeBridge = { navTo(Screen.HomeBridge) },
                            pipeSummary = PhonePipe.summary(pipeState),
                            onOpenPipe = { navTo(Screen.BlePipe) },
                            themePref = themePref,
                            // this@MainActivity, not `this`: the surrounding Box makes the
                            // implicit receiver a BoxScope.
                            onThemeChange = { themePref = it; ThemeStore.save(this@MainActivity, it) },
                            securityAvailable = vm.canUseCredentialStore,
                            hasSavedCredentials = hasSaved,
                            savedUsername = savedUser,
                            autoLogin = autoLogin,
                            onAutoLoginChange = vm::setAutoLogin,
                            onForget = vm::forgetCredentials,
                            logsAvailable = vm.canSaveLogs,
                            autoSaveLogs = autoSaveLogs,
                            onAutoSaveLogsChange = vm::setAutoSaveLogs,
                            onOpenSavedLogs = { vm.refreshSavedLogs(); navTo(Screen.SavedLogs) },
                            secureChannelConfigured = secureConfigured,
                            secureChannelLocked = secureLocked,
                            onSetChannelPassphrase = vm::setChannelPassphrase,
                            onClearChannelPassphrase = vm::clearChannelPassphrase,
                            onBack = { navBack() },
                        )
                    }

                    Screen.DeviceSettings -> {
                        val controlModules by vm.controlModules.collectAsState()
                        val controls by vm.controls.collectAsState()
                        val controlsLoading by vm.controlsLoading.collectAsState()
                        // One-shot fetch of the module list on entry / app-resume; each module's
                        // controls load lazily when its card is expanded (same as the Sensors page).
                        LaunchedEffect(Unit) {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.loadControlModules() }
                        }
                        DeviceSettingsScreen(
                            modules = controlModules,
                            controls = controls,
                            controlsLoading = controlsLoading,
                            onLoadControls = vm::loadControls,
                            onSetControl = vm::setControl,
                            onRefresh = vm::loadControlModules,
                            nav = headerNav(active = AppPage.DEVICES, onOpenDevices = goDevices, current = Screen.DeviceSettings),
                        )
                    }

                    Screen.Camera -> {
                        val camStatus by vm.cameraStatus.collectAsState()
                        val gallery by vm.gallery.collectAsState()
                        val viewedImage by vm.viewedImage.collectAsState()
                        val controls by vm.controls.collectAsState()
                        val controlsLoading by vm.controlsLoading.collectAsState()
                        val mediaBusy by vm.mediaBusy.collectAsState()
                        val mediaError by vm.mediaError.collectAsState()
                        LaunchedEffect(Unit) {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                vm.loadGallery()
                                // Pause status polling while a JPEG pull owns the capture slot.
                                while (true) {
                                    if (!vm.mediaBusy.value) vm.refreshCameraStatus()
                                    kotlinx.coroutines.delay(3_000)
                                }
                            }
                        }
                        CameraScreen(
                            status = camStatus,
                            gallery = gallery,
                            viewedImage = viewedImage,
                            controls = controls["camera"],
                            controlsLoading = "camera" in controlsLoading,
                            mediaBusy = mediaBusy,
                            error = mediaError,
                            onToggleCamera = { on -> if (on) vm.openCamera() else vm.closeCamera() },
                            onCapture = vm::capturePhoto,
                            onLoadControls = { vm.loadControls("camera") },
                            onSetControl = { key, token -> vm.setControl("camera", key, token) },
                            onViewImage = vm::viewImage,
                            onDeleteImage = vm::deleteImage,
                            onCloseImage = vm::clearViewedImage,
                            onRefresh = { vm.refreshCameraStatus(); vm.loadGallery() },
                            nav = headerNav(active = AppPage.DEVICES, onOpenDevices = goDevices, current = Screen.Camera),
                        )
                    }

                    Screen.Mic -> {
                        val micStatus by vm.micStatus.collectAsState()
                        val micLevel by vm.micLevel.collectAsState()
                        val recordings by vm.recordings.collectAsState()
                        val audioClip by vm.audioClip.collectAsState()
                        val controls by vm.controls.collectAsState()
                        val controlsLoading by vm.controlsLoading.collectAsState()
                        val mediaBusy by vm.mediaBusy.collectAsState()
                        val mediaError by vm.mediaError.collectAsState()
                        LaunchedEffect(Unit) {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                vm.loadRecordings()
                                // `micread json` carries the live level, so one poll drives both the
                                // status card and the VU meter. Pause while a WAV pull owns the
                                // capture slot (same rule the LLM screen uses during generation).
                                while (true) {
                                    if (!vm.mediaBusy.value) vm.refreshMicStatus()
                                    kotlinx.coroutines.delay(600)
                                }
                            }
                        }
                        MicScreen(
                            status = micStatus,
                            level = micLevel,
                            recordings = recordings,
                            audioClip = audioClip,
                            controls = controls["microphone"],
                            controlsLoading = "microphone" in controlsLoading,
                            mediaBusy = mediaBusy,
                            error = mediaError,
                            onToggleMic = { on -> if (on) vm.openMic() else vm.closeMic() },
                            onStartRec = vm::startRecording,
                            onStopRec = vm::stopRecording,
                            onLoadControls = { vm.loadControls("microphone") },
                            onSetControl = { key, token -> vm.setControl("microphone", key, token) },
                            onPlay = vm::playRecording,
                            onDelete = vm::deleteRecording,
                            onClearClip = vm::clearAudioClip,
                            onRefresh = { vm.refreshMicStatus(); vm.loadRecordings() },
                            nav = headerNav(active = AppPage.DEVICES, onOpenDevices = goDevices, current = Screen.Mic),
                        )
                    }

                    Screen.Status -> {
                        val status by vm.deviceStatus.collectAsState()
                        val statusError by vm.statusError.collectAsState()
                        val i2cDevices by vm.i2cDevices.collectAsState()
                        val i2cLoading by vm.i2cLoading.collectAsState()
                        val battery by vm.battery.collectAsState()
                        val deviceInfo by vm.deviceInfo.collectAsState()
                        val bleTraffic by vm.bleTraffic.collectAsState()
                        // Poll while the page is open AND the app is in the foreground
                        // (request/response — no push/subscribe). repeatOnLifecycle cancels the
                        // loop on pause/screen-lock/background and restarts it on resume, so a
                        // locked phone doesn't keep polling the device.
                        LaunchedEffect(Unit) {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                while (true) {
                                    // Fetch the battery + device list FIRST so they're already
                                    // in state when the status reply (which gates the page
                                    // render) arrives — the page paints all at once, no pop-in.
                                    // (devices json also fixes the I²C "found" count.)
                                    vm.refreshBattery()
                                    vm.loadI2cDevices(silent = true)
                                    vm.refreshStatus()
                                    kotlinx.coroutines.delay(3_000)
                                }
                            }
                        }
                        StatusScreen(
                            status = status,
                            error = statusError,
                            i2cDevices = i2cDevices,
                            i2cLoading = i2cLoading,
                            onLoadI2cDevices = vm::loadI2cDevices,
                            battery = battery,
                            deviceInfo = deviceInfo,
                            traffic = bleTraffic,
                            onRefresh = vm::refreshStatus,
                            nav = headerNav(active = AppPage.DEVICES, onOpenDevices = goDevices, current = Screen.Status),
                        )
                    }

                    Screen.Perf -> {
                        val perfTop by vm.perfTop.collectAsState()
                        val taskStats by vm.taskStats.collectAsState()
                        val taskStatsError by vm.taskStatsError.collectAsState()
                        val memReport by vm.memReport.collectAsState()
                        val fsUsage by vm.fsUsage.collectAsState()
                        var perfPage by remember { mutableStateOf(PerfPage.CPU) }
                        // Poll only the visible page's command (keyed on perfPage so switching tabs
                        // restarts the loop). The CPU page's `perftop json` samples ~0.75 s
                        // on-device, so it polls slower than the cheap stack/memory pages.
                        LaunchedEffect(perfPage) {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                while (true) {
                                    when (perfPage) {
                                        PerfPage.CPU -> vm.refreshPerfCpu()
                                        PerfPage.STACK -> vm.refreshPerfStack()
                                        PerfPage.MEMORY -> vm.refreshMemory()
                                    }
                                    kotlinx.coroutines.delay(if (perfPage == PerfPage.CPU) 3_000 else 2_000)
                                }
                            }
                        }
                        PerfScreen(
                            perfTop = perfTop,
                            taskStats = taskStats,
                            taskStatsError = taskStatsError,
                            memReport = memReport,
                            fsUsage = fsUsage,
                            page = perfPage,
                            onSelectPage = { perfPage = it },
                            onRefresh = {
                                when (perfPage) {
                                    PerfPage.CPU -> vm.refreshPerfCpu()
                                    PerfPage.STACK -> vm.refreshPerfStack()
                                    PerfPage.MEMORY -> vm.refreshMemory()
                                }
                            },
                            nav = headerNav(active = AppPage.DEVICES, onOpenDevices = goDevices, current = Screen.Perf),
                        )
                    }

                    Screen.Sensors -> {
                        val sensors by vm.sensors.collectAsState()
                        val sensorsLoading by vm.sensorsLoading.collectAsState()
                        val sensorsError by vm.sensorsError.collectAsState()
                        val controlModules by vm.controlModules.collectAsState()
                        val controls by vm.controls.collectAsState()
                        val controlsLoading by vm.controlsLoading.collectAsState()
                        LaunchedEffect(Unit) {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                vm.loadControlModules() // which sensors have adjustable settings
                                while (true) {
                                    vm.refreshSensors()
                                    // Faster cadence so graphical redraws (gamepad) feel live.
                                    kotlinx.coroutines.delay(1_000)
                                }
                            }
                        }
                        SensorsScreen(
                            sensors = sensors,
                            loading = sensorsLoading,
                            error = sensorsError,
                            controlModules = controlModules,
                            controls = controls,
                            controlsLoading = controlsLoading,
                            onToggle = vm::toggleSensor,
                            onLoadControls = vm::loadControls,
                            onSetControl = vm::setControl,
                            onAction = vm::sensorAction,
                            onRefresh = vm::refreshSensors,
                            nav = headerNav(active = AppPage.DEVICES, onOpenDevices = goDevices, current = Screen.Sensors),
                        )
                    }

                    Screen.LlmChat -> {
                        val llmStatus by vm.llmStatus.collectAsState()
                        val llmModels by vm.llmModels.collectAsState()
                        val llmLoadingModel by vm.llmLoadingModel.collectAsState()
                        val llmUnloading by vm.llmUnloading.collectAsState()
                        val llmMessages by vm.llmMessages.collectAsState()
                        val llmGenerating by vm.llmGenerating.collectAsState()
                        LaunchedEffect(Unit) {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                vm.refreshLlmModels()
                                while (true) {
                                    // Don't poll status while a generation is streaming — the
                                    // result loop owns the capture channel then. Interleaving the
                                    // two pollers risks desyncing command/reply pairing.
                                    if (!vm.llmGenerating.value) vm.refreshLlmStatus()
                                    kotlinx.coroutines.delay(2_000)
                                }
                            }
                        }
                        LlmChatScreen(
                            status = llmStatus,
                            models = llmModels,
                            loadingModel = llmLoadingModel,
                            unloading = llmUnloading,
                            messages = llmMessages,
                            generating = llmGenerating,
                            onLoadModel = vm::loadLlmModel,
                            onUnload = vm::unloadLlmModel,
                            onSend = vm::sendLlmPrompt,
                            onDo = vm::sendLlmDo,
                            onRunCommand = vm::runDoCommand,
                            onStop = vm::stopLlmGeneration,
                            onRetry = vm::retryLlm,
                            onClear = vm::clearLlmChat,
                            nav = headerNav(active = AppPage.DEVICES, onOpenDevices = goDevices, current = Screen.LlmChat),
                        )
                    }

                    Screen.Files -> FilesScreen(
                        vm = vm,
                        nav = headerNav(active = AppPage.DEVICES, onOpenDevices = goDevices, current = Screen.Files),
                    )

                    Screen.Ota -> {
                        LaunchedEffect(Unit) { vm.refreshOtaStatus() }
                        OtaScreen(
                            vm = vm,
                            nav = headerNav(active = AppPage.DEVICES, onOpenDevices = goDevices, current = Screen.Ota),
                            onLogin = ::onLoginButtonClicked,
                        )
                    }

                    Screen.EspNow -> {
                        val enMode by vm.espNowMode.collectAsState()
                        val enEnc by vm.espNowEnc.collectAsState()
                        val enMeshRole by vm.espNowMeshRole.collectAsState()
                        val enDevInfo by vm.espNowDeviceInfo.collectAsState()
                        val enPaired by vm.espNowPaired.collectAsState()
                        val enMesh by vm.espNowMesh.collectAsState()
                        val enLoading by vm.espNowLoading.collectAsState()
                        // Load the full snapshot on open; then poll just the live bits (peers/list).
                        LaunchedEffect(Unit) {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                vm.loadEspNow()
                                while (true) {
                                    kotlinx.coroutines.delay(5_000)
                                    vm.refreshEspNowPeers()
                                }
                            }
                        }
                        EspNowScreen(
                            nav = headerNav(active = AppPage.DEVICES, onOpenDevices = goDevices, current = Screen.EspNow),
                            mode = enMode,
                            enc = enEnc,
                            meshRole = enMeshRole,
                            deviceInfo = enDevInfo,
                            paired = enPaired,
                            mesh = enMesh,
                            loading = enLoading,
                            onRefresh = vm::loadEspNow,
                            onOpenDevice = { mac, name -> navTo(Screen.EspNowDevice(mac, name)) },
                            onOpenConfig = { navTo(Screen.EspNowConfig) },
                            onOpenBond = { vm.loadEspNowBond(); navTo(Screen.EspNowBond) },
                        )
                    }

                    Screen.EspNowBond -> {
                        val bond by vm.espNowBond.collectAsState()
                        LaunchedEffect(Unit) {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                vm.loadEspNowBond()
                                while (true) { kotlinx.coroutines.delay(4_000); vm.loadEspNowBond() }
                            }
                        }
                        EspNowBondScreen(
                            bond = bond,
                            onSwapRoles = { vm.swapBondRoles() },
                            onConnect = { bond?.peer?.let { p -> vm.bondConnect(p) } },
                            onDisconnect = { vm.bondDisconnect() },
                            onResync = { vm.bondResync() },
                            onBack = { navBack() },
                        )
                    }

                    Screen.Automations -> {
                        LaunchedEffect(Unit) { vm.loadAutomations() }
                        AutomationsScreen(
                            vm = vm,
                            nav = headerNav(active = AppPage.DEVICES, onOpenDevices = goDevices, current = Screen.Automations),
                        )
                    }

                    Screen.Maps -> {
                        // GPS position poll. Cadence is the user's "GPS updates" interval (2 s floor
                        // while live tracking so the local track keeps up). Skips while the satellite
                        // toggle is off; backs off automatically on GPS-less devices.
                        LaunchedEffect(Unit) {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                vm.loadMaps()
                                while (true) {
                                    if (vm.mapGpsUpdates.value) {
                                        vm.pollMapsGps()
                                        val base = vm.mapGpsIntervalMs.value.toLong()
                                        kotlinx.coroutines.delay(if (vm.mapLiveTracking.value) minOf(base, 2_000L) else base)
                                    } else {
                                        kotlinx.coroutines.delay(800) // idle — re-check the toggle soon
                                    }
                                }
                            }
                        }
                        MapsScreen(
                            vm = vm,
                            nav = headerNav(active = AppPage.DEVICES, onOpenDevices = goDevices, current = Screen.Maps),
                        )
                    }

                    Screen.EspNowConfig -> {
                        val cInfo by vm.espNowDeviceInfo.collectAsState()
                        val cMode by vm.espNowMode.collectAsState()
                        val cRole by vm.espNowMeshRole.collectAsState()
                        val cEnc by vm.espNowEnc.collectAsState()
                        // Re-read periodically so the Enabled/mode/role reflect openespnow etc. once
                        // they settle (openespnow can take a moment to initialize).
                        LaunchedEffect(Unit) {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                vm.loadEspNow()
                                while (true) { kotlinx.coroutines.delay(3_000); vm.loadEspNow() }
                            }
                        }
                        EspNowConfigScreen(
                            info = cInfo,
                            mode = cMode,
                            meshRole = cRole,
                            running = cEnc?.running == true,
                            onSetName = vm::setEspNowName,
                            onSetFriendlyName = vm::setEspNowFriendlyName,
                            onSetRoom = vm::setEspNowRoom,
                            onSetZone = vm::setEspNowZone,
                            onSetTags = vm::setEspNowTags,
                            onSetStationary = vm::setEspNowStationary,
                            onSetMode = vm::setEspNowMode,
                            onSetMeshRole = vm::setEspNowMeshRole,
                            onSetEnabled = vm::setEspNowEnabled,
                            onBack = { navBack() },
                        )
                    }

                    is Screen.EspNowDevice -> {
                        val feed by vm.espNowFeed.collectAsState()
                        val enc by vm.espNowEnc.collectAsState()
                        val rBusy by vm.espNowRemoteBusy.collectAsState()
                        val rError by vm.espNowRemoteError.collectAsState()
                        val rResult by vm.espNowRemoteResult.collectAsState()
                        val fPath by vm.espNowFilesPath.collectAsState()
                        val fListing by vm.espNowFilesListing.collectAsState()
                        val fBusy by vm.espNowFilesBusy.collectAsState()
                        val fError by vm.espNowFilesError.collectAsState()
                        val fetchBusy by vm.espNowFetchBusy.collectAsState()
                        val fetchStatus by vm.espNowFetchStatus.collectAsState()
                        val enDevices by vm.espNowDevices.collectAsState()
                        val meta = enDevices?.devices?.firstOrNull { it.mac.equals(screen.mac, ignoreCase = true) }
                        LaunchedEffect(screen.mac) {
                            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                // ONLY the feed polls while this screen is open. Do NOT fan out other
                                // captured commands here (notably the metadata sync): the capture layer
                                // is a single slot with no command↔reply correlation, so a second
                                // concurrent stream lets a bursty reply flush early and get cross-claimed
                                // by the wrong tag — stealing the feed's page/terminator and stalling
                                // chat (sent/received messages silently never appear). Metadata is pulled
                                // on demand when the Info tab is opened instead.
                                vm.openEspNowFeed(screen.mac)
                                try {
                                    kotlinx.coroutines.awaitCancellation()
                                } finally {
                                    vm.pauseEspNowFeed()
                                }
                            }
                        }
                        EspNowDeviceScreen(
                            deviceName = screen.name,
                            mac = screen.mac,
                            encrypted = enc?.encrypted == true,
                            feed = feed,
                            onSend = { vm.sendEspNowMessage(screen.mac, it) },
                            remoteBusy = rBusy,
                            remoteError = rError,
                            remoteResult = rResult,
                            onRunCommand = { user, pass, cmd -> vm.runEspNowRemote(screen.mac, user, pass, cmd) },
                            filesPath = fPath,
                            filesListing = fListing,
                            filesBusy = fBusy,
                            filesError = fError,
                            fetchBusy = fetchBusy,
                            fetchStatus = fetchStatus,
                            onBrowseFiles = { user, pass, path -> vm.browseEspNowFiles(screen.mac, user, pass, path) },
                            onOpenDir = { name -> vm.openEspNowDir(screen.mac, name) },
                            onFilesUp = { vm.espNowFilesUp(screen.mac) },
                            onFetchFile = { name -> vm.fetchEspNowFile(screen.mac, name) },
                            metadata = meta,
                            onRefreshMeta = { vm.syncEspNowPeerMeta(screen.mac) },
                            onSaveMeta = { u, p, field, v -> vm.editEspNowPeerMeta(screen.mac, u, p, field, v) },
                            onUnpair = { vm.unpairEspNow(screen.mac); vm.clearEspNowFeed(); navBack(); vm.refreshEspNowPeers() },
                            onForget = { vm.forgetEspNow(screen.mac); vm.clearEspNowFeed(); navBack(); vm.refreshEspNowPeers() },
                            onBack = { vm.clearEspNowFeed(); navBack() },
                        )
                    }

                    Screen.SavedLogs -> {
                        val savedLogs by vm.savedLogs.collectAsState()
                        SavedLogsScreen(
                            logs = savedLogs,
                            storageLocation = vm.logStorageLocation,
                            onOpen = ::openSavedLog,
                            onBack = { navBack() },
                        )
                    }

                    Screen.Health -> {
                        val healthStatus by vm.healthStatus.collectAsState()
                        val healthLog by vm.healthLog.collectAsState()
                        val healthBusy by vm.healthBusy.collectAsState()
                        val healthError by vm.healthError.collectAsState()
                        HealthScreen(
                            status = healthStatus,
                            log = healthLog,
                            busy = healthBusy,
                            error = healthError,
                            onRefresh = { vm.refreshHealthStatus(); vm.loadHealthLog() },
                            onToggleLogging = vm::setHealthLogging,
                            nav = headerNav(active = AppPage.DEVICES, onOpenDevices = goDevices, current = Screen.Health),
                        )
                    }

                    Screen.HomeBridge -> {
                        val bridgeState by HomeBridge.state.collectAsState()
                        val connection by vm.connectionState.collectAsState()
                        val info by vm.deviceInfo.collectAsState()
                        val store = remember { BridgeStore(this@MainActivity) }
                        // What one Bluetooth command may carry on the live link (see BleManager.sendCommand).
                        val linkLimit = if (connection is ConnectionState.Ready && info != null) {
                            if (info!!.secure) (info!!.mtu - 28).coerceAtLeast(20) else BleConstants.MAX_COMMAND_BYTES
                        } else 0
                        HomeBridgeScreen(
                            state = bridgeState,
                            store = store,
                            bleLoggedIn = loggedIn && connection is ConnectionState.Ready,
                            bleCommandLimit = linkLimit,
                            onStart = ::startHomeBridge,
                            onStop = { HomeBridge.stop(this@MainActivity) },
                            onProbe = { HomeBridge.probe(this@MainActivity) },
                            onSendDeviceCommand = vm::send,
                            onBack = { navBack() },
                        )
                    }

                    Screen.BlePipe -> {
                        val pipeState by PhonePipe.state.collectAsState()
                        LaunchedEffect(Unit) { PhonePipe.load(this@MainActivity) }
                        PhonePipeScreen(
                            state = pipeState,
                            onStart = { withPipePermissions { PhonePipe.start(this@MainActivity) } },
                            onStop = { PhonePipe.stop(this@MainActivity) },
                            onSaveSecret = { secret -> PhonePipe.saveSecret(this@MainActivity, secret) },
                            onSaveTarget = { url -> PhonePipe.saveTarget(this@MainActivity, url) },
                            onForget = { PhonePipe.forget(this@MainActivity) },
                            onAllowBackground = { requestPipeBatteryExemption() },
                            onExportLog = { exportPipeLog() },
                            onClearLog = { Thread { com.hardwareone.console.pipe.PipeLog(this@MainActivity).clear() }.start() },
                            onBack = { navBack() },
                        )
                    }

                    Screen.Notifications -> {
                        val alerts by vm.notifications.collectAsState()
                        NotificationsScreen(
                            cards = alerts,
                            onMuteKind = vm::muteNotificationKind,
                            onBack = { navBack() },
                        )
                    }

                    is Screen.Viewer -> LogViewerScreen(
                        title = screen.title,
                        text = screen.text,
                        onExport = { exportLog(screen.text) },
                        onDelete = { vm.deleteSavedLog(screen.fileName); navBack() },
                        onBack = { navBack() },
                    )
                }

                // Device notification cards, overlaid on every page — the device already judged
                // these interrupt-worthy for this user, so they must not be page-scoped. The
                // auto-dismiss timer lives in the ViewModel so it survives navigation.
                val activeAlert by vm.activeNotification.collectAsState()
                if (nav.lastOrNull() != Screen.Notifications) {
                    NotificationBanner(
                        card = activeAlert,
                        onDismiss = vm::dismissActiveNotification,
                        onOpenAll = {
                            vm.dismissActiveNotification()
                            vm.markNotificationsRead()
                            navTo(Screen.Notifications)
                        },
                        modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding(),
                    )
                }
                }

                // Login dialog as a global overlay so it works from any page (Console *and*
                // Devices). Biometric login is Activity-level and already page-agnostic.
                val showLogin by vm.loginDialogVisible.collectAsState()
                if (showLogin) {
                    val savedUsername by vm.savedUsername.collectAsState()
                    LoginDialog(
                        initialUsername = savedUsername ?: "",
                        canRemember = vm.canUseCredentialStore,
                        onDismiss = { vm.hideLoginDialog() },
                        onSubmit = { user, pass, remember ->
                            vm.login(user, pass)
                            if (remember) authAndSaveCredentials(user, pass)
                            vm.hideLoginDialog()
                        },
                    )
                }
            }
        }

        // Auto-login: when the link is ready and the user opted in, prompt + decrypt + log in.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.connectionState.collect { state ->
                    when (state) {
                        is ConnectionState.Ready -> maybeAutoLogin()
                        is ConnectionState.Disconnected, is ConnectionState.Failed ->
                            autoLoginHandled = false
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the battery prompt: show the new state even while the pipe is stopped. Also
        // resumes an enabled pipe that Android stopped while this activity stayed in recents (FW-1).
        PhonePipe.load(this)
    }

    override fun onStop() {
        super.onStop()
        vm.autoSaveLogIfEnabled()
    }

    // --- Credential store: biometric/PIN-gated save & auto-login -----------------------

    private fun authAndSaveCredentials(username: String, password: String) {
        val cipher = vm.encryptCipherOrNull()
        if (cipher == null) {
            vm.reportError("Secure storage unavailable on this device.")
            return
        }
        showCryptoPrompt(
            title = "Save credentials",
            subtitle = "Authenticate to encrypt your login",
            cipher = cipher,
            authenticators = vm.allowedAuthenticators(),
        ) { authed -> vm.commitCredentials(authed, username, password) }
    }

    private fun maybeAutoLogin() {
        if (autoLoginHandled) return
        if (!vm.autoLogin.value || !vm.hasSavedCredentials.value || vm.authenticated.value) return
        autoLoginHandled = true
        startBiometricLogin()
    }

    private fun onLoginButtonClicked() {
        if (vm.canUseCredentialStore && vm.hasSavedCredentials.value) startBiometricLogin()
        else vm.showLoginDialog()
    }

    private fun startBiometricLogin() {
        val cipher = vm.decryptCipherOrNull()
        val username = vm.savedUsername.value
        if (cipher == null || username == null) {
            vm.reportError("Saved login unavailable — please log in again.")
            vm.showLoginDialog()
            return
        }
        showCryptoPrompt(
            title = "Log in to HardwareOne",
            subtitle = "Authenticate to use your saved login",
            cipher = cipher,
            authenticators = vm.allowedAuthenticators(),
            onCancel = { vm.showLoginDialog() },
        ) { authed ->
            val password = vm.readStoredPassword(authed)
            if (password != null) vm.login(username, password)
            else vm.reportError("Could not read saved credentials.")
        }
    }

    // --- Saved logs: biometric-gated open + plaintext export ----------------------------

    private fun openSavedLog(log: SavedLog) {
        val cipher = vm.logDecryptCipherOrNull()
        if (cipher == null) {
            vm.reportError("No saved log to open.")
            return
        }
        showCryptoPrompt(
            title = "Open saved log",
            subtitle = "Authenticate to decrypt this log",
            cipher = cipher,
            authenticators = vm.logAllowedAuthenticators(),
        ) { authed ->
            val text = vm.finishLogDecrypt(log.fileName, authed)
            if (text != null) navTo(Screen.Viewer(log.fileName, savedLogTitle(log), text))
            else vm.reportError("Could not decrypt log.")
        }
    }

    /** The pipe's qualification log plus a counters line, through the same document picker as logs. */
    private fun exportPipeLog() {
        val s = PhonePipe.state.value
        Thread {
            val text = com.hardwareone.console.pipe.PipeLog(this).export(
                com.hardwareone.console.pipe.PipeLog.countersLine(s.stats, s.droppedCallbacks, android.os.SystemClock.elapsedRealtime()),
            )
            runOnUiThread {
                pendingExportText = text
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                createDocumentLauncher.launch("pipe-qualification-$stamp.jsonl")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun exportLog(text: String) {
        pendingExportText = text
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        createDocumentLauncher.launch("hardwareone-log-$stamp.txt")
    }

    private fun savedLogTitle(log: SavedLog): String {
        val base = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(Date(log.lastModified))
        return if (log.isAuto) "$base (auto)" else base
    }

    // --- Shared biometric prompt -------------------------------------------------------

    private fun showCryptoPrompt(
        title: String,
        subtitle: String,
        cipher: Cipher,
        authenticators: Int,
        onCancel: () -> Unit = {},
        onSuccess: (Cipher) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authed = result.cryptoObject?.cipher
                if (authed != null) onSuccess(authed)
                else vm.reportError("Authentication did not return a usable key.")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_CANCELED
                ) {
                    vm.reportError("Authentication error: $errString")
                }
                onCancel()
            }
        }
        val prompt = BiometricPrompt(this, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .apply {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) setNegativeButtonText("Cancel")
            }
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    // --- Widget tap-to-reconnect -------------------------------------------------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetConnect(intent)
        handleBridgeIntent(intent)
        handlePipeIntent(intent)
    }

    /** The pipe notification lands on the pipe page. */
    private fun handlePipeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PIPE, false) != true) return
        intent.removeExtra(EXTRA_OPEN_PIPE)
        nav.clear()
        navTo(Screen.BlePipe)
    }

    /** The bridge notification (and its Stop action's sibling tap target) lands on the bridge page. */
    private fun handleBridgeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_BRIDGE, false) != true) return
        intent.removeExtra(EXTRA_OPEN_BRIDGE)
        nav.clear()
        navTo(Screen.HomeBridge)
    }

    /** If the launch carried a device address (only set when the widget showed "disconnected"),
     *  reconnect to it. From the lock screen this runs after the unlock the system already required
     *  to open the activity, so no sensitive action happens on a locked device. */
    private fun handleWidgetConnect(intent: Intent?) {
        val address = intent?.getStringExtra(EXTRA_CONNECT_ADDRESS) ?: return
        intent.removeExtra(EXTRA_CONNECT_ADDRESS) // consume so it doesn't re-fire
        val missing = BleManager.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.connectAddress(address) else permissionLauncher.launch(missing.toTypedArray())
    }

    companion object {
        /** Intent extra: a device MAC the app should reconnect to (set by the widget when offline). */
        const val EXTRA_CONNECT_ADDRESS = "hw1_connect_address"
        /** Intent extra: open the Home AI bridge page (set by the bridge's notification). */
        const val EXTRA_OPEN_BRIDGE = "hw1_open_bridge"
        /** Intent extra: open the BLE relay pipe page (set by the pipe's notification). */
        const val EXTRA_OPEN_PIPE = "hw1_open_pipe"
    }

    // --- Scanning entry point ----------------------------------------------------------

    private fun onScanClicked() {
        val missing = BleManager.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else ensureBluetoothThenScan()
    }

    private fun ensureBluetoothThenScan() {
        val adapter = (ContextCompat.getSystemService(this, BluetoothManager::class.java))?.adapter
        if (adapter == null) {
            vm.startScan()
            return
        }
        if (!adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            vm.startScan()
        }
    }
}
