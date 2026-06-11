package com.hardwareone.console

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hardwareone.console.ble.BleManager
import com.hardwareone.console.ble.ConnectionState
import com.hardwareone.console.ui.ConsoleScreen
import com.hardwareone.console.ui.ConsoleViewModel
import com.hardwareone.console.ui.SettingsScreen
import com.hardwareone.console.ui.ThemePreference
import com.hardwareone.console.ui.ThemeStore
import com.hardwareone.console.ui.rememberFoldPosture
import com.hardwareone.console.ui.theme.HardwareOneTheme
import kotlinx.coroutines.launch
import javax.crypto.Cipher

/**
 * Single Activity (a [FragmentActivity] so it can host [BiometricPrompt]). It owns the
 * system interactions that must happen on the Activity — runtime permissions, the
 * "turn Bluetooth on" prompt, and the biometric/PIN prompt for the credential store —
 * and hands everything else to [ConsoleViewModel] / [ConsoleScreen].
 */
class MainActivity : FragmentActivity() {

    private val vm: ConsoleViewModel by viewModels()

    /** One auto-login attempt per connection. */
    private var autoLoginHandled = false

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
            // Whether the user enabled BT or not, just try to scan; the manager reports
            // a clear error if BT is still off.
            vm.startScan()
        }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent bars with light (white) icons — readable over the coloured/dark
        // gradient in both light and dark themes.
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
                var showSettings by rememberSaveable { mutableStateOf(false) }
                val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass
                val foldPosture = rememberFoldPosture(this)
                if (showSettings) {
                    BackHandler { showSettings = false }
                    val autoLogin by vm.autoLogin.collectAsState()
                    val hasSaved by vm.hasSavedCredentials.collectAsState()
                    val savedUser by vm.savedUsername.collectAsState()
                    SettingsScreen(
                        themePref = themePref,
                        onThemeChange = { themePref = it; ThemeStore.save(this, it) },
                        securityAvailable = vm.canUseCredentialStore,
                        hasSavedCredentials = hasSaved,
                        savedUsername = savedUser,
                        autoLogin = autoLogin,
                        onAutoLoginChange = vm::setAutoLogin,
                        onForget = vm::forgetCredentials,
                        onBack = { showSettings = false },
                    )
                } else {
                    ConsoleScreen(
                        vm = vm,
                        onScanClicked = ::onScanClicked,
                        widthSizeClass = widthSizeClass,
                        foldPosture = foldPosture,
                        onOpenSettings = { showSettings = true },
                        onLogin = { user, pass, remember ->
                            vm.login(user, pass)
                            if (remember) authAndSaveCredentials(user, pass)
                        },
                        onLoginButton = ::onLoginButtonClicked,
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

    // --- Credential store: biometric/PIN-gated save & auto-login -----------------------

    private fun authAndSaveCredentials(username: String, password: String) {
        val cipher = vm.encryptCipherOrNull()
        if (cipher == null) {
            vm.reportError("Secure storage unavailable on this device.")
            return
        }
        showCryptoPrompt("Save credentials", "Authenticate to encrypt your login", cipher) { authed ->
            vm.commitCredentials(authed, username, password)
        }
    }

    /** Automatic, once-per-connection biometric login when the user opted in. */
    private fun maybeAutoLogin() {
        if (autoLoginHandled) return
        if (!vm.autoLogin.value || !vm.hasSavedCredentials.value || vm.authenticated.value) return
        autoLoginHandled = true
        startBiometricLogin()
    }

    /** LOGIN button: re-offer the biometric prompt if creds are saved, else the form. */
    private fun onLoginButtonClicked() {
        if (vm.canUseCredentialStore && vm.hasSavedCredentials.value) {
            startBiometricLogin()
        } else {
            vm.showLoginDialog()
        }
    }

    /** Prompt biometric/PIN, decrypt the saved password, and log in. Cancel → manual form. */
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
            // Backed out of the prompt? Fall back to the manual login form.
            onCancel = { vm.showLoginDialog() },
        ) { authed ->
            val password = vm.readStoredPassword(authed)
            if (password != null) vm.login(username, password)
            else vm.reportError("Could not read saved credentials.")
        }
    }

    private fun showCryptoPrompt(
        title: String,
        subtitle: String,
        cipher: Cipher,
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
                // Surface real errors; either way fall back to whatever onCancel wants.
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
            .setAllowedAuthenticators(vm.allowedAuthenticators())
            .apply {
                // A negative button is required only when device credential is NOT an option.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) setNegativeButtonText("Cancel")
            }
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    // --- Scanning entry point ----------------------------------------------------------

    /** Permission-aware scan entry point passed down to the UI. */
    private fun onScanClicked() {
        val missing = BleManager.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            ensureBluetoothThenScan()
        }
    }

    private fun ensureBluetoothThenScan() {
        val adapter = (ContextCompat.getSystemService(this, BluetoothManager::class.java))?.adapter
        if (adapter == null) {
            vm.startScan() // manager reports "Bluetooth not available"
            return
        }
        if (!adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            vm.startScan()
        }
    }
}
