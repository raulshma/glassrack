package com.raulshma.grack.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raulshma.grack.core.designsystem.ExpressiveCard
import com.raulshma.grack.core.designsystem.ExpressiveGroupedCard
import com.raulshma.grack.core.designsystem.ExpressiveGroupedItem
import com.raulshma.grack.core.designsystem.ExpressiveIconTile
import com.raulshma.grack.core.designsystem.ExpressiveSectionHeader
import com.raulshma.grack.core.designsystem.ExpressiveSeedSwatches
import com.raulshma.grack.core.designsystem.ExpressiveSegmentedPillGroup
import com.raulshma.grack.core.designsystem.LocalGlassrackDesign
import com.raulshma.grack.core.designsystem.monoNumericStyle
import com.raulshma.grack.core.model.AlertMetric
import com.raulshma.grack.core.model.ConfirmationLevel
import com.raulshma.grack.core.model.DensityMode
import com.raulshma.grack.core.model.SshKeyAlgorithm
import com.raulshma.grack.core.model.SshKeyRecord
import com.raulshma.grack.core.model.StorageUnit
import com.raulshma.grack.core.model.TemperatureUnit
import com.raulshma.grack.core.model.TerminalThemePreset
import com.raulshma.grack.core.model.ThemeVariant
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

private enum class ThemeModeOption {
    SYSTEM,
    LIGHT,
    DARK,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val prefs = state.preferences
    val design = LocalGlassrackDesign.current
    @Suppress("DEPRECATION")
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
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Editorial Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Settings & Vault",
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = "Appearance, safety gates, SSH keys, runbooks, and encrypted backups",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Status Feedback Banner
        item {
            AnimatedVisibility(visible = state.statusMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.statusMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { viewModel.setMessage(null) },
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text("OK")
                        }
                    }
                }
            }
        }

        // 1. Appearance Section
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpressiveSectionHeader("Appearance")

                // Theme Mode
                val selectedThemeMode = when (prefs.darkModeOverride) {
                    null -> ThemeModeOption.SYSTEM
                    false -> ThemeModeOption.LIGHT
                    true -> ThemeModeOption.DARK
                }
                ExpressiveSegmentedPillGroup(
                    options = ThemeModeOption.entries,
                    selected = selectedThemeMode,
                    onSelected = { option ->
                        val next = when (option) {
                            ThemeModeOption.SYSTEM -> null
                            ThemeModeOption.LIGHT -> false
                            ThemeModeOption.DARK -> true
                        }
                        viewModel.updatePreferences { it.copy(darkModeOverride = next) }
                    },
                    label = { option ->
                        when (option) {
                            ThemeModeOption.SYSTEM -> "System"
                            ThemeModeOption.LIGHT -> "Light"
                            ThemeModeOption.DARK -> "Dark"
                        }
                    },
                    icon = { option ->
                        when (option) {
                            ThemeModeOption.SYSTEM -> Icons.Rounded.AutoAwesome
                            ThemeModeOption.LIGHT -> Icons.Rounded.LightMode
                            ThemeModeOption.DARK -> Icons.Rounded.DarkMode
                        }
                    },
                )

                // Seed Accent Swatches
                val activeSwatchName = ExpressiveSeedSwatches
                    .firstOrNull { it.second == prefs.seedColorArgb }
                    ?.first ?: "Custom"
                ExpressiveCard(
                    tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Seed Accent", style = MaterialTheme.typography.titleSmall)
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Text(
                                text = activeSwatchName,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ExpressiveSeedSwatches.forEach { (name, hex) ->
                            val isSelected = prefs.seedColorArgb == hex
                            val swatchColor = Color(hex)
                            val checkTint = if (swatchColor.luminance() > 0.5f) Color.Black else Color.White
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(
                                                width = 2.5.dp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                shape = RoundedCornerShape(16.dp),
                                            )
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(3.dp)
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(swatchColor)
                                    .clickable { viewModel.setSeedColor(hex) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = name,
                                        tint = checkTint,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Theme Style
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Theme Style",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ExpressiveSegmentedPillGroup(
                        options = listOf(
                            ThemeVariant.DYNAMIC_SYSTEM,
                            ThemeVariant.PRISMATIC_RACK,
                            ThemeVariant.AMOLED_OBSIDIAN,
                        ),
                        selected = prefs.themeVariant,
                        onSelected = viewModel::setThemeVariant,
                        label = { variant ->
                            when (variant) {
                                ThemeVariant.DYNAMIC_SYSTEM -> "Dynamic"
                                ThemeVariant.PRISMATIC_RACK -> "Expressive"
                                ThemeVariant.AMOLED_OBSIDIAN -> "Obsidian"
                            }
                        },
                    )
                }

                // Card Density
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Card Density",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ExpressiveSegmentedPillGroup(
                        options = listOf(
                            DensityMode.EXPRESSIVE,
                            DensityMode.BALANCED,
                            DensityMode.COMPACT,
                        ),
                        selected = prefs.densityMode,
                        onSelected = viewModel::setDensity,
                        label = { density ->
                            when (density) {
                                DensityMode.EXPRESSIVE -> "Expressive"
                                DensityMode.BALANCED -> "Balanced"
                                DensityMode.COMPACT -> "Compact"
                            }
                        },
                    )
                }

                // Grouped Tonal Preferences
                ExpressiveGroupedCard(itemSpacing = 2.dp) {
                    ExpressiveGroupedItem(index = 0, count = 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ExpressiveIconTile(
                                    icon = Icons.Rounded.Animation,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Column {
                                    Text("Expressive Spring Motion", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "Physics-driven transitions & morphing pills",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = prefs.useExpressiveMotion,
                                onCheckedChange = { v -> viewModel.updatePreferences { it.copy(useExpressiveMotion = v) } },
                            )
                        }
                    }

                    ExpressiveGroupedItem(index = 1, count = 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ExpressiveIconTile(
                                    icon = Icons.Rounded.Thermostat,
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text("Temperature Unit", style = MaterialTheme.typography.bodyLarge)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TemperatureUnit.entries.forEach { u ->
                                    SettingsUnitPill(
                                        label = if (u == TemperatureUnit.CELSIUS) "°C" else "°F",
                                        selected = prefs.temperatureUnit == u,
                                        onClick = { viewModel.setTempUnit(u) },
                                    )
                                }
                            }
                        }
                    }

                    ExpressiveGroupedItem(index = 2, count = 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ExpressiveIconTile(
                                    icon = Icons.Rounded.Storage,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text("Storage Unit", style = MaterialTheme.typography.bodyLarge)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                StorageUnit.entries.forEach { u ->
                                    SettingsUnitPill(
                                        label = if (u == StorageUnit.BINARY_GIB) "GiB" else "GB",
                                        selected = prefs.storageUnit == u,
                                        onClick = { viewModel.setStorageUnit(u) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Safety & Security Section
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpressiveSectionHeader("Safety & Security")
                ExpressiveGroupedCard(itemSpacing = 2.dp) {
                    ExpressiveGroupedItem(index = 0, count = 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ExpressiveIconTile(
                                    icon = Icons.Rounded.Lock,
                                    containerColor = design.statusWarnContainer,
                                    contentColor = design.onStatusWarnContainer,
                                )
                                Column {
                                    Text("Safe Read-Only Mode", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "Blocks mutating container, process, and power actions",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = prefs.safeReadOnlyMode,
                                onCheckedChange = { v -> viewModel.updatePreferences { it.copy(safeReadOnlyMode = v) } },
                            )
                        }
                    }

                    ExpressiveGroupedItem(index = 1, count = 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ExpressiveIconTile(
                                    icon = Icons.Rounded.Fingerprint,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Column {
                                    Text("Biometric App Lock", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "Require authentication before opening vault",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = prefs.biometricAppLock,
                                onCheckedChange = { v -> viewModel.updatePreferences { it.copy(biometricAppLock = v) } },
                            )
                        }
                    }

                    ExpressiveGroupedItem(index = 2, count = 3) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ExpressiveIconTile(
                                    icon = Icons.Rounded.Shield,
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Column {
                                    Text("Destructive Action Gate", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "Confirmation required for reboot, stop, or prune",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            ExpressiveSegmentedPillGroup(
                                options = ConfirmationLevel.entries,
                                selected = prefs.destructiveConfirmation,
                                onSelected = viewModel::setConfirmationLevel,
                                label = { lvl ->
                                    when (lvl) {
                                        ConfirmationLevel.NONE -> "None"
                                        ConfirmationLevel.DIALOG -> "Dialog"
                                        ConfirmationLevel.HOLD_TO_CONFIRM -> "Hold"
                                        ConfirmationLevel.BIOMETRIC -> "Bio"
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // 3. Telemetry & Polling (plus Terminal Profile)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpressiveSectionHeader("Telemetry & Polling")
                ExpressiveGroupedCard(itemSpacing = 2.dp) {
                    ExpressiveGroupedItem(index = 0, count = 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ExpressiveIconTile(
                                    icon = Icons.Rounded.NetworkCheck,
                                    containerColor = design.statusActiveContainer,
                                    contentColor = design.onStatusActiveContainer,
                                )
                                Column {
                                    Text("Pause Polling on Metered Network", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "Conserves cellular data when off Wi-Fi",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = prefs.pausePollingOnMetered,
                                onCheckedChange = { v -> viewModel.updatePreferences { it.copy(pausePollingOnMetered = v) } },
                            )
                        }
                    }

                    ExpressiveGroupedItem(index = 1, count = 3) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ExpressiveIconTile(
                                        icon = Icons.Rounded.TextFields,
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Text("Terminal Font Size", style = MaterialTheme.typography.bodyLarge)
                                }
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ) {
                                    Text(
                                        text = "${prefs.terminalAppearance.fontSizeSp.toInt()} sp",
                                        style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            Slider(
                                value = prefs.terminalAppearance.fontSizeSp,
                                onValueChange = { sz ->
                                    viewModel.updatePreferences {
                                        it.copy(terminalAppearance = it.terminalAppearance.copy(fontSizeSp = sz))
                                    }
                                },
                                valueRange = 10f..20f,
                            )
                        }
                    }

                    ExpressiveGroupedItem(index = 2, count = 3) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ExpressiveIconTile(
                                    icon = Icons.Rounded.Palette,
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text("Terminal Color Theme", style = MaterialTheme.typography.bodyLarge)
                            }
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                TerminalThemePreset.entries.forEach { preset ->
                                    SettingsUnitPill(
                                        label = preset.displayName,
                                        selected = prefs.terminalAppearance.themePreset == preset,
                                        onClick = { viewModel.setTerminalPreset(preset) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. SSH Key Vault
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpressiveSectionHeader(
                    title = "SSH Key Vault",
                    count = state.sshKeys.size,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { showGenerateKeyDialog = true },
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Generate")
                    }
                    FilledTonalButton(
                        onClick = { importKeyLauncher.launch() },
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
                    }
                }

                if (state.sshKeys.isEmpty()) {
                    ExpressiveCard(tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Text(
                            text = "Generate an Ed25519 key in Tink Keystore or import an existing OpenSSH private key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    ExpressiveGroupedCard(itemSpacing = 2.dp) {
                        state.sshKeys.forEachIndexed { idx, key ->
                            ExpressiveGroupedItem(index = idx, count = state.sshKeys.size) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        ExpressiveIconTile(
                                            icon = Icons.Rounded.Key,
                                            containerColor = design.statusOkContainer,
                                            contentColor = design.onStatusOkContainer,
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    text = key.alias,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(999.dp),
                                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                ) {
                                                    Text(
                                                        text = key.algorithm.name,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                    )
                                                }
                                            }
                                            Text(
                                                text = key.fingerprintSha256,
                                                style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        IconButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(key.publicKeyOpenSsh))
                                                viewModel.setMessage("Copied public key to clipboard")
                                            },
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(17.dp))
                                        }
                                        FilledTonalButton(
                                            onClick = { deployTargetKey = key },
                                            shape = RoundedCornerShape(999.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        ) {
                                            Text("Deploy", style = MaterialTheme.typography.labelMedium)
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteSshKey(key.id) },
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Delete", modifier = Modifier.size(17.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Command Runbooks
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpressiveSectionHeader(
                    title = "Command Runbooks",
                    count = state.snippets.size,
                    actionLabel = "Add",
                    onAction = { showNewSnippetDialog = true },
                )
                if (state.snippets.isEmpty()) {
                    ExpressiveCard(tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Save reusable SSH commands for multi-node broadcast.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            FilledTonalButton(
                                onClick = { showNewSnippetDialog = true },
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Text("Add")
                            }
                        }
                    }
                } else {
                    ExpressiveGroupedCard(itemSpacing = 2.dp) {
                        state.snippets.forEachIndexed { idx, snip ->
                            ExpressiveGroupedItem(index = idx, count = state.snippets.size) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        ExpressiveIconTile(
                                            icon = Icons.Rounded.Terminal,
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = snip.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = snip.commandTemplate,
                                                style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        FilledTonalIconButton(
                                            enabled = !prefs.safeReadOnlyMode,
                                            onClick = { viewModel.broadcastSnippet(snip) },
                                            modifier = Modifier.size(36.dp),
                                        ) {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Broadcast", modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteSnippet(snip.id) },
                                            modifier = Modifier.size(36.dp),
                                        ) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Alert Thresholds
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpressiveSectionHeader(
                    title = "Alert Thresholds",
                    count = state.alertRules.size,
                    actionLabel = "Add",
                    onAction = { showNewAlertDialog = true },
                )
                if (state.alertRules.isEmpty()) {
                    ExpressiveCard(tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Configure CPU, temperature, memory, or offline alert rules.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            FilledTonalButton(
                                onClick = { showNewAlertDialog = true },
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Text("Add")
                            }
                        }
                    }
                } else {
                    ExpressiveGroupedCard(itemSpacing = 2.dp) {
                        state.alertRules.forEachIndexed { idx, rule ->
                            ExpressiveGroupedItem(index = idx, count = state.alertRules.size) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        ExpressiveIconTile(
                                            icon = Icons.Rounded.NotificationsActive,
                                            containerColor = design.statusWarnContainer,
                                            contentColor = design.onStatusWarnContainer,
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = rule.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${rule.metric.name.replace('_', ' ')} ≥ ${rule.threshold.toInt()}",
                                                style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Switch(
                                            checked = rule.enabled,
                                            onCheckedChange = { viewModel.toggleAlertRule(rule) },
                                        )
                                        IconButton(
                                            onClick = { viewModel.deleteAlertRule(rule.id) },
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Delete rule", modifier = Modifier.size(17.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 7. Encrypted Backup
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpressiveSectionHeader("Encrypted Backup")
                ExpressiveGroupedCard(itemSpacing = 2.dp) {
                    ExpressiveGroupedItem(index = 0, count = 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ExpressiveIconTile(
                                    icon = Icons.Rounded.Backup,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Column {
                                    Text("Export Encrypted Bundle", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "Passphrase-protected JSON snapshot",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { backupPassphraseDialog = true },
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Text("Export")
                            }
                        }
                    }
                    ExpressiveGroupedItem(index = 1, count = 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ExpressiveIconTile(
                                    icon = Icons.Rounded.Restore,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Column {
                                    Text("Restore from Bundle", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "Decrypt servers, tiles, rules & preferences",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = { backupPassphraseDialog = false },
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Text("Restore")
                            }
                        }
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
            shape = RoundedCornerShape(28.dp),
            title = { Text("Generate SSH Key", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it },
                        label = { Text("Key Alias") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ExpressiveSegmentedPillGroup(
                        options = SshKeyAlgorithm.entries,
                        selected = alg,
                        onSelected = { alg = it },
                        label = { it.name },
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Passphrase (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.generateSshKey(alias, alg, pass.takeIf { it.isNotEmpty() })
                        showGenerateKeyDialog = false
                    },
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Generate")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showGenerateKeyDialog = false },
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    deployTargetKey?.let { key ->
        AlertDialog(
            onDismissRequest = { deployTargetKey = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Deploy '${key.alias}'", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Pick target server to append ~/.ssh/authorized_keys:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.servers.isEmpty()) {
                        Text(
                            text = "No servers configured yet.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        ExpressiveGroupedCard(itemSpacing = 2.dp) {
                            state.servers.forEachIndexed { idx, srv ->
                                ExpressiveGroupedItem(
                                    index = idx,
                                    count = state.servers.size,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    onClick = {
                                        viewModel.deployPublicKey(key, srv)
                                        deployTargetKey = null
                                    },
                                ) {
                                    Text(srv.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${srv.username}@${srv.primaryHost}",
                                        style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { deployTargetKey = null },
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Close")
                }
            },
        )
    }

    if (showNewSnippetDialog) {
        var name by remember { mutableStateOf("") }
        var cmd by remember { mutableStateOf("") }
        var tags by remember { mutableStateOf("docker,raspberrypi") }
        AlertDialog(
            onDismissRequest = { showNewSnippetDialog = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("New Snippet / Runbook", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = cmd,
                        onValueChange = { cmd = it },
                        label = { Text("Command (supports {{var}})") },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = { Text("Target Tags (comma separated)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveSnippet(name, cmd, tags, requiresConfirm = false)
                        showNewSnippetDialog = false
                    },
                    enabled = name.isNotBlank() && cmd.isNotBlank(),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNewSnippetDialog = false },
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showNewAlertDialog) {
        var name by remember { mutableStateOf("") }
        var metric by remember { mutableStateOf(AlertMetric.TEMP_C) }
        var threshold by remember { mutableStateOf("80") }
        AlertDialog(
            onDismissRequest = { showNewAlertDialog = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("New Threshold Alert", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Rule Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AlertMetric.entries.forEach { m ->
                            SettingsUnitPill(
                                label = m.name.replace("_", " "),
                                selected = metric == m,
                                onClick = { metric = m },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = threshold,
                        onValueChange = { threshold = it },
                        label = { Text("Threshold") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addAlertRule(name, metric, threshold.toFloatOrNull() ?: 80f)
                        showNewAlertDialog = false
                    },
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNewAlertDialog = false },
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    backupPassphraseDialog?.let { isExport ->
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { backupPassphraseDialog = null },
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    text = if (isExport) "Encrypt Backup Bundle" else "Decrypt Backup Bundle",
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Passphrase (min 4 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = pass.length >= 4,
                    onClick = {
                        pendingPassphrase = pass
                        backupPassphraseDialog = null
                        if (isExport) {
                            exportBackupLauncher.launch(suggestedName = "grack-backup", extension = "json")
                        } else {
                            importBackupLauncher.launch()
                        }
                    },
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { backupPassphraseDialog = null },
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SettingsUnitPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
