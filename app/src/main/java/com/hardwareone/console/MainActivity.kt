package com.hardwareone.console

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.content.ContextCompat
import com.hardwareone.console.ble.BleManager
import com.hardwareone.console.ui.ConsoleScreen
import com.hardwareone.console.ui.ConsoleViewModel
import com.hardwareone.console.ui.rememberFoldPosture
import com.hardwareone.console.ui.theme.HardwareOneTheme

/**
 * Single Activity. It owns the two pieces of system interaction that must happen on the
 * Activity (runtime permissions and the "turn Bluetooth on" prompt) and hands everything
 * else to [ConsoleViewModel] / [ConsoleScreen].
 */
class MainActivity : ComponentActivity() {

    private val vm: ConsoleViewModel by viewModels()

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
            HardwareOneTheme {
                val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass
                val foldPosture = rememberFoldPosture(this)
                ConsoleScreen(
                    vm = vm,
                    onScanClicked = ::onScanClicked,
                    widthSizeClass = widthSizeClass,
                    foldPosture = foldPosture,
                )
            }
        }
    }

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
