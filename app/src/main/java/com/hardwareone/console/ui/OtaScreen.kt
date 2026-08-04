package com.hardwareone.console.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ble.ConnectionState
import com.hardwareone.console.ble.OtaBundle
import com.hardwareone.console.ble.OtaStep
import com.hardwareone.console.ui.theme.LocalHwColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Closed-loop OTA over HardwareOne's encrypted Bluetooth command channel. */
@Composable
fun OtaScreen(
    vm: ConsoleViewModel,
    nav: HeaderNav,
    onLogin: () -> Unit,
) {
    val hw = LocalHwColors.current
    val state by vm.ota.collectAsState()
    val connection by vm.connectionState.collectAsState()
    val authenticated by vm.authenticated.collectAsState()
    val info by vm.deviceInfo.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recoveryPassword by remember { mutableStateOf("") }
    var confirmInstall by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val name = otaDisplayName(context, uri)
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    readOtaBundle(it, OtaBundle.MAX_BUNDLE_BYTES)
                } ?: throw IllegalArgumentException("The selected file could not be opened.")
            }.onSuccess { bytes ->
                withContext(Dispatchers.Main) { vm.importOtaBundle(name, bytes) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    vm.reportOtaImportError(error.message ?: "The selected file could not be read.")
                }
            }
        }
    }

    val connected = connection is ConnectionState.Ready
    val secured = info?.secure == true
    val busy = state.step in setOf(
        OtaStep.CHECKING_PACKAGE,
        OtaStep.SETTING_RECOVERY_PASSWORD,
        OtaStep.VERIFYING_STAGED,
        OtaStep.ARMING_RECOVERY,
    )

    Box(Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 12.dp),
        ) {
            AppHeader(
                nav = nav,
                busy = busy,
                onRefresh = vm::refreshOtaStatus,
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionCard("Offline Bluetooth OTA") {
                    Text(
                        "The app never fetches firmware or opens web content. Download a .hw1ota file separately, then choose it here. Transfer and control stay on encrypted Bluetooth; recovery mode remains a fallback only.",
                        color = hw.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                SectionCard("Connection") {
                    InfoRow("Bluetooth", if (connected) "Connected" else "Not connected")
                    InfoRow("Encrypted channel", if (secured) "Established" else "Required")
                    InfoRow("Account", if (authenticated) "Logged in" else "Superadmin login required")
                    if (!authenticated && connected) PrimaryButton(onClick = onLogin, text = "LOG IN")
                }

                state.device?.let { device ->
                    SectionCard("Device OTA") {
                        InfoRow("Available", if (device.available) "Yes" else "No")
                        InfoRow("Board", device.board.ifBlank { "—" })
                        InfoRow("Layout", device.layout.ifBlank { "—" })
                        InfoRow("Running", device.runningPartition.ifBlank { "—" })
                        InfoRow("Phase", device.phase.ifBlank { "—" })
                        InfoRow("Staged pair", if (device.candidateStaged) "Present" else "None")
                        InfoRow("Recovery fallback", if (device.recoveryCredentialConfigured) "Configured" else "Not configured")
                        if (device.resultPending) {
                            InfoRow("Pending result", "${device.resultCode} #${device.resultSequence}")
                            if (device.resultDetail.isNotBlank()) InfoRow("Detail", device.resultDetail)
                            PrimaryButton(
                                onClick = vm::acknowledgeOtaResult,
                                enabled = authenticated && secured,
                                text = "ACKNOWLEDGE RESULT",
                            )
                        }
                    }

                    if (!device.recoveryCredentialConfigured) {
                        SectionCard("Recovery fallback password") {
                            Text(
                                "Used only if recovery mode must expose its local WPA2/HTTP fallback. The app sends it once over encrypted Bluetooth and does not save it.",
                                color = hw.muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = recoveryPassword,
                                onValueChange = { if (it.length <= 63) recoveryPassword = it },
                                label = { Text("12–63 printable characters") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            PrimaryButton(
                                onClick = {
                                    val password = recoveryPassword
                                    recoveryPassword = ""
                                    vm.setOtaRecoveryPassword(password)
                                },
                                enabled = recoveryPassword.length in 12..63 && authenticated && secured,
                                text = "SET ON DEVICE",
                            )
                        }
                    }
                }

                SectionCard("Firmware package") {
                    val bundle = state.bundle
                    InfoRow("File", state.packageName ?: "None selected")
                    if (bundle != null) {
                        InfoRow("Version", bundle.version)
                        InfoRow("Target", "${bundle.board} / ${bundle.layout}")
                        InfoRow("Image", otaBytes(bundle.imageSize.toLong()))
                        InfoRow("SHA-256", bundle.imageSha256.take(16) + "…")
                        InfoRow("Local key", bundle.publicKeyFingerprint.take(16) + "…")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            onClick = { picker.launch("*/*") },
                            enabled = !state.transferActive && state.step !in setOf(
                                OtaStep.VERIFYING_STAGED,
                                OtaStep.ARMING_RECOVERY,
                                OtaStep.WAITING_FOR_DEVICE,
                                OtaStep.PROBATION,
                            ),
                            text = if (bundle == null) "CHOOSE PACKAGE" else "REPLACE PACKAGE",
                        )
                        if (bundle != null && !state.transferActive) {
                            OtaOutlinedButton(onClick = vm::clearOtaPackage, text = "CLEAR")
                        }
                    }
                }

                if (state.progressTotal > 0) {
                    SectionCard("Progress") {
                        OtaProgress(state.progressDone, state.progressTotal)
                        InfoRow("Transferred", "${otaBytes(state.progressDone)} / ${otaBytes(state.progressTotal)}")
                        InfoRow("Step", otaStepLabel(state.step))
                    }
                }

                SectionCard("Status") {
                    Text(state.message, color = hw.onGradient, style = MaterialTheme.typography.bodyMedium)
                    state.error?.let {
                        Text(it, color = hw.danger, style = MaterialTheme.typography.bodyMedium)
                    }
                    when {
                        state.transferActive -> OtaOutlinedButton(onClick = vm::cancelOtaTransfer, text = "STOP UPLOAD")
                        state.step == OtaStep.WAITING_FOR_LOGIN -> PrimaryButton(onClick = onLogin, text = "LOG IN TO CONTINUE")
                        state.bundle != null && state.device != null -> PrimaryButton(
                            onClick = { confirmInstall = true },
                            enabled = connected && secured && authenticated &&
                                state.device?.recoveryCredentialConfigured == true &&
                                state.device?.resultPending == false &&
                                state.step in setOf(OtaStep.READY, OtaStep.IDLE, OtaStep.FAILED),
                            text = "INSTALL OVER BLUETOOTH",
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (confirmInstall) {
        AlertDialog(
            onDismissRequest = { confirmInstall = false },
            title = { Text("Install firmware update?") },
            text = {
                Text(
                    "The signed package will be staged over Bluetooth, independently verified by HardwareOne, then the device will reboot through its recovery updater. Keep the phone nearby until probation succeeds.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmInstall = false; vm.startOtaInstall() }) { Text("INSTALL") }
            },
            dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun OtaOutlinedButton(onClick: () -> Unit, text: String) {
    val hw = LocalHwColors.current
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = hw.onGradient),
    ) { Text(text) }
}

@Composable
private fun OtaProgress(done: Long, total: Long) {
    val hw = LocalHwColors.current
    val fraction = if (total <= 0) 0f else (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(hw.cardBorder),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(hw.accent),
        )
    }
}

private fun otaStepLabel(step: OtaStep): String = when (step) {
    OtaStep.IDLE -> "Idle"
    OtaStep.CHECKING_PACKAGE -> "Checking package"
    OtaStep.READY -> "Ready"
    OtaStep.SETTING_RECOVERY_PASSWORD -> "Setting recovery password"
    OtaStep.UPLOADING_FIRMWARE -> "Uploading firmware"
    OtaStep.UPLOADING_MANIFEST -> "Uploading manifest"
    OtaStep.VERIFYING_STAGED -> "Verifying staged package"
    OtaStep.ARMING_RECOVERY -> "Arming recovery updater"
    OtaStep.WAITING_FOR_DEVICE -> "Waiting for device"
    OtaStep.WAITING_FOR_LOGIN -> "Waiting for login"
    OtaStep.PROBATION -> "Health probation"
    OtaStep.SUCCEEDED -> "Succeeded"
    OtaStep.FAILED -> "Needs attention"
}

private fun otaBytes(value: Long): String = when {
    value >= 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024.0))
    value >= 1024 -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}

private fun otaDisplayName(context: Context, uri: Uri): String {
    var result = "update.hw1ota"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) result = cursor.getString(index) ?: result
        }
    }
    return result
}

private fun readOtaBundle(input: InputStream, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "OTA bundle exceeds ${otaBytes(maxBytes.toLong())}." }
        output.write(buffer, 0, count)
    }
    require(total > 0) { "OTA bundle is empty." }
    return output.toByteArray()
}
