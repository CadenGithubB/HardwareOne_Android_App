package com.hardwareone.console.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hardwareone.console.BuildConfig
import com.hardwareone.console.R
import com.hardwareone.console.ui.theme.LocalHwColors

private val CardShape = RoundedCornerShape(14.dp)

@Composable
fun SettingsScreen(
    themePref: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    securityAvailable: Boolean,
    hasSavedCredentials: Boolean,
    savedUsername: String?,
    autoLogin: Boolean,
    onAutoLoginChange: (Boolean) -> Unit,
    onForget: () -> Unit,
    logsAvailable: Boolean,
    autoSaveLogs: Boolean,
    onAutoSaveLogsChange: (Boolean) -> Unit,
    onOpenSavedLogs: () -> Unit,
    secureChannelConfigured: Boolean,
    secureChannelLocked: Boolean,
    onSetChannelPassphrase: (String) -> Unit,
    onClearChannelPassphrase: () -> Unit,
    onBack: () -> Unit,
) {
    val hw = LocalHwColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(hw.gradient)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            ) {
                // Top bar: back + title
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = hw.onGradient,
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "Settings",
                        color = hw.onGradient,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Scrollable cards (the top bar above stays fixed).
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Appearance card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CardShape)
                            .background(hw.cardBg)
                            .border(1.dp, hw.cardBorder, CardShape)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "Appearance",
                            color = hw.muted,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        ThemeOptionRow("System default", themePref == ThemePreference.SYSTEM) {
                            onThemeChange(ThemePreference.SYSTEM)
                        }
                        ThemeOptionRow("Light", themePref == ThemePreference.LIGHT) {
                            onThemeChange(ThemePreference.LIGHT)
                        }
                        ThemeOptionRow("Dark", themePref == ThemePreference.DARK) {
                            onThemeChange(ThemePreference.DARK)
                        }
                    }

                    SecurityCard(
                        securityAvailable = securityAvailable,
                        hasSavedCredentials = hasSavedCredentials,
                        savedUsername = savedUsername,
                        autoLogin = autoLogin,
                        onAutoLoginChange = onAutoLoginChange,
                        onForget = onForget,
                    )

                    LogsCard(
                        logsAvailable = logsAvailable,
                        autoSaveLogs = autoSaveLogs,
                        onAutoSaveLogsChange = onAutoSaveLogsChange,
                        onOpenSavedLogs = onOpenSavedLogs,
                    )

                    SecureChannelCard(
                        configured = secureChannelConfigured,
                        locked = secureChannelLocked,
                        onSet = onSetChannelPassphrase,
                        onClear = onClearChannelPassphrase,
                    )

                    Text(
                        text = "HardwareOne Console v${BuildConfig.VERSION_NAME}",
                        color = hw.muted,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SecurityCard(
    securityAvailable: Boolean,
    hasSavedCredentials: Boolean,
    savedUsername: String?,
    autoLogin: Boolean,
    onAutoLoginChange: (Boolean) -> Unit,
    onForget: () -> Unit,
) {
    val hw = LocalHwColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Security",
            color = hw.muted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 6.dp),
        )

        if (!securityAvailable) {
            Text(
                text = "Set up a screen lock or biometric to enable secure credential storage.",
                color = hw.onGradient,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (!hasSavedCredentials) {
            Text(
                text = "No saved credentials. Tick \"Remember\" when you log in to store them securely.",
                color = hw.onGradient,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-login on connect",
                        color = hw.onGradient,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Saved for ${savedUsername ?: "user"}",
                        color = hw.muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(
                    checked = autoLogin,
                    onCheckedChange = onAutoLoginChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = hw.onGradient,
                        checkedTrackColor = hw.accent,
                        uncheckedThumbColor = hw.muted,
                        uncheckedTrackColor = hw.cardBorder,
                    ),
                )
            }
            TextButton(
                onClick = onForget,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text("Forget saved credentials", color = hw.danger)
            }
        }

        Text(
            text = "Stored in the device keystore (StrongBox when available), unlocked by a " +
                "biometric or PIN prompt. The BLE link is currently unencrypted, so credentials " +
                "are still sent in cleartext over the air.",
            color = hw.muted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SecureChannelCard(
    configured: Boolean,
    locked: Boolean,
    onSet: (String) -> Unit,
    onClear: () -> Unit,
) {
    val hw = LocalHwColors.current
    var pass by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, if (locked) hw.danger else hw.cardBorder, CardShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = when {
                locked -> "Secure channel · LOCKED"
                configured -> "Secure channel · ON"
                else -> "Secure channel"
            },
            color = if (locked) hw.danger else hw.muted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        if (locked) {
            Text(
                text = "🔒 The saved passphrase couldn't be unlocked (this can happen after " +
                    "reinstalling the app), so the channel is off. Re-enter it below to restore " +
                    "encryption.",
                color = hw.danger,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Text(
            text = "Encrypts commands and replies app-side (X25519 + ChaCha20-Poly1305). The " +
                "passphrase must match the device's \"blesecret\". Applies on the next connect.",
            color = hw.onGradient,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            singleLine = true,
            shape = CardShape,
            placeholder = { Text("passphrase (min 8 chars)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = hw.onGradient,
                unfocusedTextColor = hw.onGradient,
                focusedContainerColor = hw.cardBg,
                unfocusedContainerColor = hw.cardBg,
                cursorColor = hw.accent,
                focusedBorderColor = hw.accent,
                unfocusedBorderColor = hw.cardBorder,
                focusedPlaceholderColor = hw.muted,
                unfocusedPlaceholderColor = hw.muted,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onSet(pass); pass = "" },
                enabled = pass.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = hw.onGradient,
                    contentColor = hw.accent,
                    disabledContainerColor = hw.cardBg,
                    disabledContentColor = hw.muted,
                ),
            ) { Text(if (locked) "RE-ENTER" else if (configured) "UPDATE" else "SAVE") }
            if (configured || locked) {
                TextButton(onClick = onClear) { Text("Disable", color = hw.danger) }
            }
        }
    }
}

@Composable
private fun LogsCard(
    logsAvailable: Boolean,
    autoSaveLogs: Boolean,
    onAutoSaveLogsChange: (Boolean) -> Unit,
    onOpenSavedLogs: () -> Unit,
) {
    val hw = LocalHwColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Logs",
            color = hw.muted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        if (!logsAvailable) {
            Text(
                text = "Set up a screen lock or biometric to enable encrypted log storage.",
                color = hw.onGradient,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-save log on exit",
                        color = hw.onGradient,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Encrypted; open it later from Saved logs",
                        color = hw.muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(
                    checked = autoSaveLogs,
                    onCheckedChange = onAutoSaveLogsChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = hw.onGradient,
                        checkedTrackColor = hw.accent,
                        uncheckedThumbColor = hw.muted,
                        uncheckedTrackColor = hw.cardBorder,
                    ),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSavedLogs)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Saved logs",
                    color = hw.onGradient,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "›", color = hw.muted, style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(
            text = "Saved in the app's private storage, encrypted with a Keystore key; " +
                "opening one requires a biometric/PIN prompt.",
            color = hw.muted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ThemeOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val hw = LocalHwColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = hw.onGradient,
                unselectedColor = hw.muted,
            ),
        )
        Text(
            text = label,
            color = hw.onGradient,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
