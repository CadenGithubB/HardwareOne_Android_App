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
        // Load any saved secure-channel passphrase and arm the PSK (off the main thread).
        viewModelScope.launch(Dispatchers.Default) {
            channelSecret.get()?.let { ble.setPsk(SecureChannel.derivePsk(it)) }
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

    fun setChannelPassphrase(passphrase: String) {
        val pass = passphrase.trim()
        if (pass.length < 8) {
            reportError("Secure-channel passphrase must be at least 8 characters.")
            return
        }
        channelSecret.put(pass)
        _secureChannelConfigured.value = true
        viewModelScope.launch(Dispatchers.Default) { ble.setPsk(SecureChannel.derivePsk(pass)) }
        reportInfo("Secure-channel passphrase saved. It applies on the next connect.")
    }

    fun clearChannelPassphrase() {
        channelSecret.clear()
        ble.setPsk(null)
        _secureChannelConfigured.value = false
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
    }
}
