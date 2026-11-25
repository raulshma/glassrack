package com.example.glassrack.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.glassrack.core.designsystem.LocalGlassrackDesign
import com.example.glassrack.core.designsystem.RackBladeCard
import com.example.glassrack.core.model.AlertMetric
import com.example.glassrack.core.model.ConfirmationLevel
import com.example.glassrack.core.model.DensityMode
import com.example.glassrack.core.model.SshKeyAlgorithm
import com.example.glassrack.core.model.SshKeyRecord
import com.example.glassrack.core.model.StorageUnit
import com.example.glassrack.core.model.TemperatureUnit
import com.example.glassrack.core.model.TerminalThemePreset
import com.example.glassrack.core.model.ThemeVariant
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val prefs = state.preferences
    val design = LocalGlassrackDesign.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var showGenerateKeyDialog by remember { mutableStateOf(false) }
    var showNewSnippetDialog by remember { mutableStateOf(false) }
    var showNewAlertDialog by remember { mutableStateOf(false) }
    var deployTargetKey by remember { mutableStateOf<SshKeyRecord?>(null) }
    var backupPassphraseDialog by remember { mutableStateOf<Boolean?>(null) } // true=export, false=import
    var pendingPassphrase by remember { mutableStateOf("") }

    val importKeyLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(),
        dialogSettings = FileKitDialogSettings.createDefault(),
    ) { file ->
        if (file != null) {
            scope.launch {
                val pem = file.readString()
                viewModel.importSshKey("imported-key", pem, null)
            }
        }
    }

    val exportBackupLauncher = rememberFileSaverLauncher(
        dialogSettings = FileKitDialogSettings.createDefault(),
    ) { file ->
        if (file != null) {
            scope.launch {
                val bundle = viewModel.exportEncryptedBackup(pendingPassphrase)
                file.writeString(bundle)
                viewModel.setMessage("Encrypted backup exported")
            }
        }
    }

    val importBackupLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(),
        dialogSettings = FileKitDialogSettings.createDefault(),
    ) { file ->
        if (file != null) {
            scope.launch {
                val bundleJson = file.readString()
                viewModel.importEncryptedBackup(bundleJson, pendingPassphrase)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(design.cardSpacing),
    ) {
        item {
            Text("Settings & Vault", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        state.statusMessage?.let { msg ->
            item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.setMessage(null) }) { Text("OK") }
                    }
                }
            }
        }

        // 1. Appearance & M3 Expressive
        item {
            RackBladeCard(accentColor = MaterialTheme.colorScheme.primary) {
                Text("Appearance & M3 Expressive", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Theme Style", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeVariant.entries.forEach { v ->
                        FilterChip(
                            selected = prefs.themeVariant == v,
                            onClick = { viewModel.setThemeVariant(v) },
                            label = { Text(v.name.replace("_", " ")) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Seed Accent", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    listOf(0xFF00B4D8, 0xFF10B981, 0xFF6366F1, 0xFFF59E0B, 0xFFEC4899).forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .border(
                                    width = if (prefs.seedColorArgb == hex) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape,
                                )
                                .clickable { viewModel.setSeedColor(hex) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Card Density", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DensityMode.entries.forEach { d ->
                        FilterChip(selected = prefs.densityMode == d, onClick = { viewModel.setDensity(d) }, label = { Text(d.name) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Expressive Spring Motion", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = prefs.useExpressiveMotion, onCheckedChange = { v -> viewModel.updatePreferences { it.copy(useExpressiveMotion = v) } })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Dark Mode", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = prefs.darkModeOverride != false, onCheckedChange = { v -> viewModel.updatePreferences { it.copy(darkModeOverride = v) } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TemperatureUnit.entries.forEach { u ->
                        FilterChip(selected = prefs.temperatureUnit == u, onClick = { viewModel.setTempUnit(u) }, label = { Text(if (u == TemperatureUnit.CELSIUS) "°C" else "°F") })
                    }
                    StorageUnit.entries.forEach { u ->
                        FilterChip(selected = prefs.storageUnit == u, onClick = { viewModel.setStorageUnit(u) }, label = { Text(if (u == StorageUnit.BINARY_GIB) "GiB" else "GB") })
                    }
                }
            }
        }

        // 2. Safety & Security
        item {
            RackBladeCard(accentColor = design.statusWarn) {
                Text("Safety & Security", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Safe Read-Only Mode", style = MaterialTheme.typography.bodyMedium)
                        Text("Blocks all mutating container, process, and power actions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = prefs.safeReadOnlyMode, onCheckedChange = { v -> viewModel.updatePreferences { it.copy(safeReadOnlyMode = v) } })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Biometric App Lock", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = prefs.biometricAppLock, onCheckedChange = { v -> viewModel.updatePreferences { it.copy(biometricAppLock = v) } })
                }
                Text("Destructive Action Gate", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConfirmationLevel.entries.forEach { lvl ->
                        FilterChip(selected = prefs.destructiveConfirmation == lvl, onClick = { viewModel.setConfirmationLevel(lvl) }, label = { Text(lvl.name) })
                    }
                }
            }
        }

        // 3. SSH Key Vault
        item {
            RackBladeCard(accentColor = design.statusOk) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("SSH Key Vault (Tink Keystore)", style = MaterialTheme.typography.titleMedium)
                    Row {
                        IconButton(onClick = { importKeyLauncher.launch() }) {
                            Icon(Icons.Rounded.UploadFile, contentDescription = "Import Key")
                        }
                        TextButton(onClick = { showGenerateKeyDialog = true }) { Text("+ Generate") }
                    }
                }
                if (state.sshKeys.isEmpty()) {
                    Text("Generate an Ed25519 key on-device or import an OpenSSH key.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.sshKeys.forEach { key ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Key, null, modifier = Modifier.size(18.dp))
                                Column {
                                    Text("${key.alias} (${key.algorithm.name})", style = MaterialTheme.typography.bodyMedium)
                                    Text(key.fingerprintSha256, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row {
                                IconButton(onClick = {
                                    clipboard.setText(AnnotatedString(key.publicKeyOpenSsh))
                                    viewModel.setMessage("Copied public key to clipboard")
                                }) { Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp)) }
                                TextButton(onClick = { deployTargetKey = key }) { Text("Deploy") }
                                IconButton(onClick = { viewModel.deleteSshKey(key.id) }) { Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                }
            }
        }

        // 4. Snippets & Broadcast
        item {
            RackBladeCard(accentColor = design.statusActive) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Snippets & Multi-Server Broadcast", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showNewSnippetDialog = true }) { Text("+ Snippet") }
                }
                state.snippets.forEach { snip ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(snip.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(snip.commandTemplate, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Row {
                            IconButton(enabled = !prefs.safeReadOnlyMode, onClick = { viewModel.broadcastSnippet(snip) }) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Broadcast")
                            }
                            IconButton(onClick = { viewModel.deleteSnippet(snip.id) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }

        // 5. Alerts, Polling & Terminal Profile
        item {
            RackBladeCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Threshold Alerts", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showNewAlertDialog = true }) { Text("+ Rule") }
                }
                state.alertRules.forEach { rule ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${rule.name} (${rule.metric.name} ≥ ${rule.threshold.toInt()})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Switch(checked = rule.enabled, onCheckedChange = { viewModel.toggleAlertRule(rule) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Terminal Theme & Font (${prefs.terminalAppearance.fontSizeSp.toInt()}sp)", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TerminalThemePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = prefs.terminalAppearance.themePreset == preset,
                            onClick = { viewModel.setTerminalPreset(preset) },
                            label = { Text(preset.displayName) },
                        )
                    }
                }
                Slider(
                    value = prefs.terminalAppearance.fontSizeSp,
                    onValueChange = { sz ->
                        viewModel.updatePreferences { it.copy(terminalAppearance = it.terminalAppearance.copy(fontSizeSp = sz)) }
                    },
                    valueRange = 10f..20f,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Pause Polling on Metered Network", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = prefs.pausePollingOnMetered, onCheckedChange = { v -> viewModel.updatePreferences { it.copy(pausePollingOnMetered = v) } })
                }
            }
        }

        // 6. Encrypted Config Backup & Restore
        item {
            RackBladeCard {
                Text("Encrypted Backup & Restore", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { backupPassphraseDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Backup, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export")
                    }
                    OutlinedButton(
                        onClick = { backupPassphraseDialog = false },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Restore, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Restore")
                    }
                }
            }
        }
    }

    if (showGenerateKeyDialog) {
        var alias by remember { mutableStateOf("glassrack-ed25519") }
        var alg by remember { mutableStateOf(SshKeyAlgorithm.ED25519) }
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGenerateKeyDialog = false },
            title = { Text("Generate SSH Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = alias, onValueChange = { alias = it }, label = { Text("Key Alias") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SshKeyAlgorithm.entries.forEach { a ->
                            FilterChip(selected = alg == a, onClick = { alg = a }, label = { Text(a.name) })
                        }
                    }
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Passphrase (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.generateSshKey(alias, alg, pass.takeIf { it.isNotEmpty() })
                    showGenerateKeyDialog = false
                }) { Text("Generate") }
            },
            dismissButton = { TextButton(onClick = { showGenerateKeyDialog = false }) { Text("Cancel") } },
        )
    }

    deployTargetKey?.let { key ->
        AlertDialog(
            onDismissRequest = { deployTargetKey = null },
            title = { Text("Deploy '${key.alias}' Public Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Pick target server to append ~/.ssh/authorized_keys:")
                    state.servers.forEach { srv ->
                        OutlinedButton(
                            onClick = {
                                viewModel.deployPublicKey(key, srv)
                                deployTargetKey = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${srv.name} (${srv.username}@${srv.primaryHost})")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { deployTargetKey = null }) { Text("Close") } },
        )
    }

    if (showNewSnippetDialog) {
        var name by remember { mutableStateOf("") }
        var cmd by remember { mutableStateOf("") }
        var tags by remember { mutableStateOf("docker,raspberrypi") }
        AlertDialog(
            onDismissRequest = { showNewSnippetDialog = false },
            title = { Text("New Snippet / Runbook") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                    OutlinedTextField(value = cmd, onValueChange = { cmd = it }, label = { Text("Command (supports {{var}})") })
                    OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Target Tags (comma separated)") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveSnippet(name, cmd, tags, requiresConfirm = false)
                    showNewSnippetDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showNewSnippetDialog = false }) { Text("Cancel") } },
        )
    }

    if (showNewAlertDialog) {
        var name by remember { mutableStateOf("") }
        var metric by remember { mutableStateOf(AlertMetric.TEMP_C) }
        var threshold by remember { mutableStateOf("80") }
        AlertDialog(
            onDismissRequest = { showNewAlertDialog = false },
            title = { Text("New Threshold Alert") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Rule Name") })
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AlertMetric.entries.forEach { m ->
                            FilterChip(selected = metric == m, onClick = { metric = m }, label = { Text(m.name) })
                        }
                    }
                    OutlinedTextField(value = threshold, onValueChange = { threshold = it }, label = { Text("Threshold") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addAlertRule(name, metric, threshold.toFloatOrNull() ?: 80f)
                    showNewAlertDialog = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showNewAlertDialog = false }) { Text("Cancel") } },
        )
    }

    backupPassphraseDialog?.let { isExport ->
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { backupPassphraseDialog = null },
            title = { Text(if (isExport) "Encrypt Backup Bundle" else "Decrypt Backup Bundle") },
            text = {
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = pass.length >= 4,
                    onClick = {
                        pendingPassphrase = pass
                        backupPassphraseDialog = null
                        if (isExport) {
                            exportBackupLauncher.launch(suggestedName = "glassrack-backup", extension = "json")
                        } else {
                            importBackupLauncher.launch()
                        }
                    },
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { backupPassphraseDialog = null }) { Text("Cancel") } },
        )
    }
}
