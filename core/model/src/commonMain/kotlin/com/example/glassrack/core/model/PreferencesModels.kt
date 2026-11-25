package com.example.glassrack.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeVariant {
    PRISMATIC_RACK,
    AMOLED_OBSIDIAN,
    DYNAMIC_SYSTEM,
}

@Serializable
enum class DensityMode {
    EXPRESSIVE,
    BALANCED,
    COMPACT,
}

@Serializable
enum class ConfirmationLevel {
    NONE,
    DIALOG,
    HOLD_TO_CONFIRM,
    BIOMETRIC,
}

@Serializable
enum class TemperatureUnit {
    CELSIUS,
    FAHRENHEIT,
}

@Serializable
enum class StorageUnit {
    BINARY_GIB,
    SI_GB,
}

@Serializable
enum class TerminalThemePreset(
    val displayName: String,
    val backgroundArgb: Long,
    val foregroundArgb: Long,
    val accentArgb: Long,
) {
    PRISMATIC("Prismatic Slate", 0xFF0A0E17, 0xFFE6EDF7, 0xFF00D1FF),
    CATPPUCCIN_MOCHA("Catppuccin Mocha", 0xFF1E1E2E, 0xFFCDD6F4, 0xFF89B4FA),
    DRACULA("Dracula", 0xFF282A36, 0xFFF8F8F2, 0xFFBD93F9),
    GRUVBOX("Gruvbox Dark", 0xFF282828, 0xFFEBDBB2, 0xFFFABD2F),
    NORD("Nord", 0xFF2E3440, 0xFFECEFF4, 0xFF88C0D0),
    SOLARIZED_DARK("Solarized Dark", 0xFF002B36, 0xFF839496, 0xFF268BD2),
}

@Serializable
enum class TerminalCursorShape {
    BLOCK,
    BAR,
    UNDERLINE,
}

@Immutable
@Serializable
data class TerminalAppearance(
    val themePreset: TerminalThemePreset = TerminalThemePreset.PRISMATIC,
    val fontSizeSp: Float = 13f,
    val cursorShape: TerminalCursorShape = TerminalCursorShape.BLOCK,
    val scrollbackLines: Int = 2000,
    val customExtraKeys: List<String> = listOf("ESC", "TAB", "CTRL", "ALT", "UP", "DOWN", "LEFT", "RIGHT", "|", "~", "/", "-", ":", "CTRL_C"),
)

@Immutable
@Serializable
data class UserPreferences(
    val themeVariant: ThemeVariant = ThemeVariant.DYNAMIC_SYSTEM,
    val seedColorArgb: Long = 0xFF006B7D,
    val densityMode: DensityMode = DensityMode.EXPRESSIVE,
    val useExpressiveMotion: Boolean = true,
    val darkModeOverride: Boolean? = null, // null = follow system light/dark
    val safeReadOnlyMode: Boolean = false,
    val biometricAppLock: Boolean = false,
    val destructiveConfirmation: ConfirmationLevel = ConfirmationLevel.HOLD_TO_CONFIRM,
    val globalPollIntervalSec: Int = 5,
    val pausePollingOnMetered: Boolean = true,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val storageUnit: StorageUnit = StorageUnit.BINARY_GIB,
    val auditRetentionDays: Int = 90,
    val recordTerminalCommands: Boolean = true,
    val terminalAppearance: TerminalAppearance = TerminalAppearance(),
)

@Serializable
data class GlassrackBackupPayload(
    val version: Int = 1,
    val exportedAtEpochMs: Long,
    val servers: List<Server>,
    val dashboards: List<DashboardProfile>,
    val tiles: List<DashboardTile>,
    val snippets: List<Snippet>,
    val alertRules: List<AlertRule>,
    val preferences: UserPreferences,
    val sshKeyMetadata: List<SshKeyRecord>,
)

@Serializable
data class EncryptedBackupBundle(
    val formatVersion: Int = 1,
    val saltBase64: String,
    val ivBase64: String,
    val ciphertextBase64: String,
)
