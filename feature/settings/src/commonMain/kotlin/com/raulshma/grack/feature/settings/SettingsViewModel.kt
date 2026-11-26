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
import com.raulshma.grack.core.model.TerminalThemePreset
import com.raulshma.grack.core.model.ThemeVariant
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.model.currentTimeEpochMs
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
)

class SettingsViewModel(
    private val repository: GlassrackRepository,
    private val controller: HomelabController,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.preferences,
        repository.sshKeys,
        repository.snippets,
        repository.alertRules,
        repository.servers,
        _message,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        SettingsUiState(
            preferences = arr[0] as UserPreferences,
            sshKeys = arr[1] as List<SshKeyRecord>,
            snippets = arr[2] as List<Snippet>,
            alertRules = arr[3] as List<AlertRule>,
            servers = arr[4] as List<Server>,
            statusMessage = arr[5] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun updatePreferences(transform: (UserPreferences) -> UserPreferences) {
        viewModelScope.launch { repository.updatePreferences(transform) }
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
            val targets = uiState.value.servers.filter { srv ->
                snippet.targetTags.isEmpty() || srv.tags.any { it in snippet.targetTags }
            }
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
}
