package com.hardwareone.console.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareone.console.ble.BleManager
import com.hardwareone.console.ble.BleMessage
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
    val deviceInfo: StateFlow<com.hardwareone.console.ble.DeviceInfo?> = ble.deviceInfo

    // --- Device status page (`status json`, captured off-console) ---
    private val _deviceStatus = MutableStateFlow<com.hardwareone.console.ble.DeviceStatus?>(null)
    val deviceStatus: StateFlow<com.hardwareone.console.ble.DeviceStatus?> = _deviceStatus.asStateFlow()

    private val _statusError = MutableStateFlow<String?>(null)
    val statusError: StateFlow<String?> = _statusError.asStateFlow()

    private val _statusLoading = MutableStateFlow(false)
    val statusLoading: StateFlow<Boolean> = _statusLoading.asStateFlow()

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
        // Route captured (off-console) command replies to their pages.
        viewModelScope.launch {
            ble.captures.collect { capture ->
                val tag = capture.tag
                when {
                    tag == TAG_STATUS -> onStatusCapture(capture.text, capture.timedOut)
                    tag == TAG_DEVICES -> onDevicesCapture(capture.text, capture.timedOut)
                    tag == TAG_SENSORS -> onSensorsCapture(capture.text, capture.timedOut)
                    tag == TAG_CONTROL_MODULES -> onControlModulesCapture(capture.text)
                    tag.startsWith(TAG_CONTROLS_PREFIX) ->
                        onControlsCapture(tag.removePrefix(TAG_CONTROLS_PREFIX), capture.text)
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
     * The open/close token differs from the `sensors json` id for the gamepad (unified under the
     * "input" driver).
     */
    private fun enableCommandFor(id: String, enable: Boolean): String {
        val verb = if (enable) "open" else "close"
        return when (id) {
            "gamepad" -> "${verb}input" // openinput / closeinput
            "microphone" -> "${verb}mic" // openmic / closemic (if ever surfaced in sensors json)
            // openimu, opentof, opengps, openfmradio, openapds, openpresence, openrtc, openthermal
            else -> "$verb$id"
        }
    }

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

    private fun onControlsCapture(moduleId: String, text: String) {
        _controlsLoading.value = _controlsLoading.value - moduleId
        com.hardwareone.console.ble.ControlsModule.parse(text)?.let {
            _controls.value = _controls.value + (moduleId to it)
        }
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
    }
}
