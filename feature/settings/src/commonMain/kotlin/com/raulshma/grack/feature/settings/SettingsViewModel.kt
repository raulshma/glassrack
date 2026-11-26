package com.raulshma.grack.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.AlertMetric
import com.raulshma.grack.core.model.AlertRule
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.ConfirmationLevel
import com.raulshma.grack.core.model.DensityMode
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.Snippet
import com.raulshma.grack.core.model.SshKeyAlgorithm
import com.raulshma.grack.core.model.SshKeyRecord
import com.raulshma.grack.core.model.StorageUnit
import com.raulshma.grack.core.model.TemperatureUnit
import com.raulshma.grack.core.model.TerminalCursorShape
import com.raulshma.grack.core.model.TerminalThemePreset
import com.raulshma.grack.core.model.ThemeVariant
import com.raulshma.grack.core.model.ScheduleValidation
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.model.currentTimeEpochMs
import com.raulshma.grack.core.model.parseScheduleSpec
import com.raulshma.grack.core.model.snippetTargetServers
import com.raulshma.grack.core.model.validateSnippetSchedule
import com.raulshma.grack.core.security.NotificationGate
import com.raulshma.grack.core.security.SshKeyGenerator
import com.raulshma.grack.core.ssh.HomelabController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences(),
    val sshKeys: List<SshKeyRecord> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val alertRules: List<AlertRule> = emptyList(),
    val servers: List<Server> = emptyList(),
    val statusMessage: String? = null,
    val notificationsEnabled: Boolean = true,
)

class SettingsViewModel(
    private val repository: GlassrackRepository,
    private val controller: HomelabController,
    private val notificationGate: NotificationGate,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    private val _notificationsEnabled = MutableStateFlow(notificationGate.areNotificationsEnabled())

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.preferences,
        repository.sshKeys,
        repository.snippets,
        repository.alertRules,
        repository.servers,
        _message,
        _notificationsEnabled,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        SettingsUiState(
            preferences = arr[0] as UserPreferences,
            sshKeys = arr[1] as List<SshKeyRecord>,
            snippets = arr[2] as List<Snippet>,
            alertRules = arr[3] as List<AlertRule>,
            servers = arr[4] as List<Server>,
            statusMessage = arr[5] as String?,
            notificationsEnabled = arr[6] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun updatePreferences(transform: (UserPreferences) -> UserPreferences) {
        viewModelScope.launch { repository.updatePreferences(transform) }
    }

    /** Re-reads the OS notification permission, e.g. after returning from system settings. */
    fun refreshNotificationState() {
        _notificationsEnabled.value = notificationGate.areNotificationsEnabled()
    }

    fun openNotificationSettings() {
        notificationGate.openNotificationSettings()
    }

    fun generateSshKey(alias: String, algorithm: SshKeyAlgorithm, passphrase: String?) {
        viewModelScope.launch {
            runCatching {
                val generated = SshKeyGenerator.generateKeyPair(alias.ifBlank { "glassrack-${algorithm.name.lowercase()}" }, algorithm, passphrase)
                repository.saveSshKey(generated.record, generated.privateKeyPem, generated.passphrase)
                _message.value = "Generated ${algorithm.name} key '${generated.record.alias}' (${generated.record.fingerprintSha256})"
            }.onFailure { err ->
                _message.value = "Key generation failed: ${err.message}"
            }
        }
    }

    fun importSshKey(alias: String, privateKeyPem: String, passphrase: String?) {
        viewModelScope.launch {
            runCatching {
                val imported = SshKeyGenerator.parseImportedPrivateKey(alias.ifBlank { "imported-key" }, privateKeyPem, passphrase)
                repository.saveSshKey(imported.record, imported.privateKeyPem, imported.passphrase)
                _message.value = "Imported key '${imported.record.alias}' (${imported.record.fingerprintSha256})"
            }.onFailure { err ->
                _message.value = "Import failed: ${err.message}"
            }
        }
    }

    fun deployPublicKey(key: SshKeyRecord, server: Server) {
        viewModelScope.launch {
            val res = controller.deployPublicKey(server, key.publicKeyOpenSsh)
            _message.value = if (res.exitCode == 0) {
                "Deployed '${key.alias}' to ${server.name}"
            } else {
                "Deploy failed: ${res.stderr.ifBlank { "exit ${res.exitCode}" }}"
            }
        }
    }

    fun deleteSshKey(id: String) {
        viewModelScope.launch { repository.deleteSshKey(id) }
    }

    fun saveSnippet(name: String, template: String, tagsCsv: String, requiresConfirm: Boolean) {
        if (name.isBlank() || template.isBlank()) return
        viewModelScope.launch {
            val tags = tagsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            repository.upsertSnippet(
                Snippet(
                    id = "snip-${Random.nextInt(10000, 99999)}",
                    name = name.trim(),
                    commandTemplate = template.trim(),
                    targetTags = tags,
                    requiresConfirmation = requiresConfirm,
                    sortOrder = uiState.value.snippets.size,
                ),
            )
        }
    }

    fun broadcastSnippet(snippet: Snippet) {
        if (uiState.value.preferences.safeReadOnlyMode) {
            _message.value = "Blocked: Safe Read-Only Mode active"
            return
        }
        viewModelScope.launch {
            val targets = snippetTargetServers(uiState.value.servers, snippet.targetTags)
            targets.forEach { srv ->
                launch {
                    controller.executeAuditedAction(
                        server = srv,
                        category = AuditCategory.SSH,
                        actionTitle = "Broadcast: ${snippet.name}",
                        command = snippet.commandTemplate,
                        streaming = true,
                    )
                }
            }
            _message.value = "Broadcast '${snippet.name}' to ${targets.size} node(s)"
        }
    }

    fun deleteSnippet(id: String) {
        viewModelScope.launch { repository.deleteSnippet(id) }
    }

    /**
     * Saves (or clears) a runbook's schedule (Phase 4). Confirmation-requiring snippets are
     * rejected with the validator's explanation — they cannot run unattended. Saving a
     * schedule also clears the previous run's streak bookkeeping: the failure-streak lives
     * in `lastRunStatus`, and a (re)scheduled runbook starts with a clean slate.
     */
    fun saveSchedule(snippet: Snippet, specRaw: String?, targets: List<String>) {
        val spec = parseScheduleSpec(specRaw)
        val validation = validateSnippetSchedule(snippet, spec)
        if (validation is ScheduleValidation.Invalid) {
            _message.value = validation.reason
            return
        }
        viewModelScope.launch {
            repository.upsertSnippet(
                snippet.copy(
                    scheduleSpec = specRaw,
                    scheduleTargets = targets,
                    lastRunEpochMs = null,
                    lastRunStatus = null,
                ),
            )
            _message.value = when (spec) {
                null -> "Schedule cleared for '${snippet.name}'"
                else -> "Scheduled '${snippet.name}' ($specRaw)"
            }
        }
    }

    fun addAlertRule(name: String, metric: AlertMetric, threshold: Float) {
        viewModelScope.launch {
            repository.upsertAlertRule(
                AlertRule(
                    id = "rule-${Random.nextInt(10000, 99999)}",
                    name = name.ifBlank { "${metric.name} > ${threshold.toInt()}" },
                    metric = metric,
                    threshold = threshold,
                    enabled = true,
                ),
            )
        }
    }

    fun toggleAlertRule(rule: AlertRule) {
        viewModelScope.launch { repository.upsertAlertRule(rule.copy(enabled = !rule.enabled)) }
    }

    fun deleteAlertRule(id: String) {
        viewModelScope.launch { repository.deleteAlertRule(id) }
    }

    suspend fun exportEncryptedBackup(passphrase: String): String {
        return repository.exportEncryptedBackup(passphrase, currentTimeEpochMs())
    }

    fun importEncryptedBackup(bundleJson: String, passphrase: String) {
        viewModelScope.launch {
            runCatching {
                repository.importEncryptedBackup(bundleJson, passphrase)
                _message.value = "Encrypted backup restored successfully"
            }.onFailure { err ->
                _message.value = "Restore failed: ${err.message}"
            }
        }
    }

    fun setMessage(msg: String?) { _message.value = msg }

    fun setThemeVariant(v: ThemeVariant) = updatePreferences { it.copy(themeVariant = v) }
    fun setSeedColor(argb: Long) = updatePreferences { it.copy(seedColorArgb = argb) }
    fun setDensity(d: DensityMode) = updatePreferences { it.copy(densityMode = d) }
    fun setConfirmationLevel(c: ConfirmationLevel) = updatePreferences { it.copy(destructiveConfirmation = c) }
    fun setTempUnit(u: TemperatureUnit) = updatePreferences { it.copy(temperatureUnit = u) }
    fun setStorageUnit(u: StorageUnit) = updatePreferences { it.copy(storageUnit = u) }
    fun setTerminalPreset(p: TerminalThemePreset) = updatePreferences {
        it.copy(terminalAppearance = it.terminalAppearance.copy(themePreset = p))
    }

    /** Default poll interval applied to newly onboarded nodes. */
    fun setGlobalPollInterval(sec: Int) = updatePreferences {
        it.copy(globalPollIntervalSec = sec.coerceIn(MIN_POLL_INTERVAL_SEC, MAX_POLL_INTERVAL_SEC))
    }

    /** Audit entries older than this are pruned automatically (and via the Audit screen). */
    fun setAuditRetentionDays(days: Int) = updatePreferences {
        it.copy(auditRetentionDays = days.coerceAtLeast(1))
    }

    /** termlib 0.3.15 caps its scrollback buffer at 1000 lines; clamp here. */
    fun setTerminalScrollback(lines: Int) = updatePreferences {
        it.copy(terminalAppearance = it.terminalAppearance.copy(scrollbackLines = lines.coerceIn(MIN_SCROLLBACK_LINES, MAX_SCROLLBACK_LINES)))
    }

    fun setTerminalCursor(shape: TerminalCursorShape) = updatePreferences {
        it.copy(terminalAppearance = it.terminalAppearance.copy(cursorShape = shape))
    }

    companion object {
        const val MIN_POLL_INTERVAL_SEC: Int = 2
        const val MAX_POLL_INTERVAL_SEC: Int = 300
        const val MIN_SCROLLBACK_LINES: Int = 200
        const val MAX_SCROLLBACK_LINES: Int = 1000
    }
}
