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
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hardwareone.console.ble.BleManager
import com.hardwareone.console.ble.ConnectionState
import com.hardwareone.console.security.SavedLog
import com.hardwareone.console.ui.AppPage
import com.hardwareone.console.ui.ConsoleScreen
import com.hardwareone.console.ui.ConsoleViewModel
import com.hardwareone.console.ui.DevicesScreen
import com.hardwareone.console.ui.FilesScreen
import com.hardwareone.console.ui.HeaderNav
import com.hardwareone.console.ui.LlmChatScreen
import com.hardwareone.console.ui.LoginDialog
import com.hardwareone.console.ui.LogViewerScreen
import com.hardwareone.console.ui.SavedLogsScreen
import com.hardwareone.console.ui.SensorsScreen
import com.hardwareone.console.ui.SettingsScreen
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
        data object Status : Screen
        data object Sensors : Screen
        data object LlmChat : Screen
        data object Files : Screen
        data object SavedLogs : Screen
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
        setContent {
            var themePref by remember { mutableStateOf(ThemeStore.load(this)) }
            val darkTheme = when (themePref) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            HardwareOneTheme(darkTheme = darkTheme) {
                BackHandler(enabled = nav.isNotEmpty() || topPage == AppPage.CONSOLE) {
                    if (nav.isNotEmpty()) navBack() else topPage = AppPage.DEVICES
                }

                val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass
                val foldPosture = rememberFoldPosture(this)

                // The switcher/nav hub lives top-left on every page. Build the right [HeaderNav]
                // for whichever page is showing — the device-tool dropdown is gated on login
                // (the tools are all account-gated anyway).
                val loggedIn by vm.authenticated.collectAsState()
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
                        Screen.Sensors -> "Sensors"
                        Screen.LlmChat -> "LLM Chat"
                        Screen.Files -> "Files"
                        else -> "Devices"
                    },
                    onOpenDevices = onOpenDevices,
                    onOpenStatus = ({ openTool(Screen.Status) }).takeIf { current != Screen.Status },
                    onOpenSensors = ({ openTool(Screen.Sensors) }).takeIf { current != Screen.Sensors },
                    onOpenLlm = ({ openTool(Screen.LlmChat) }).takeIf { current != Screen.LlmChat },
                    onOpenFiles = ({ vm.loadFiles("/"); openTool(Screen.Files) }).takeIf { current != Screen.Files },
                    onSyncClock = vm::syncClock,
                    onSaveLog = onSaveLog,
                    onClearLog = onClearLog,
                )

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
                            DevicesScreen(
                                vm = vm,
                                battery = battery,
                                onScanClicked = ::onScanClicked,
                                onLogin = ::onLoginButtonClicked,
                                nav = headerNav(active = AppPage.DEVICES, onOpenDevices = null),
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
                        SettingsScreen(
                            themePref = themePref,
                            onThemeChange = { themePref = it; ThemeStore.save(this, it) },
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

                    Screen.SavedLogs -> {
                        val savedLogs by vm.savedLogs.collectAsState()
                        SavedLogsScreen(
                            logs = savedLogs,
                            storageLocation = vm.logStorageLocation,
                            onOpen = ::openSavedLog,
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
