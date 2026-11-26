package com.raulshma.grack.feature.processes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.ConfirmationLevel
import com.raulshma.grack.core.model.CronEntry
import com.raulshma.grack.core.model.ProcessInfo
import com.raulshma.grack.core.model.ProcessSignal
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.SystemdUnit
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.security.BiometricGate
import com.raulshma.grack.core.ssh.HomelabController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProcessSort { CPU, MEMORY, PID, COMMAND }

data class ProcessesUiState(
    val isLoading: Boolean = false,
    val processes: List<ProcessInfo> = emptyList(),
    val units: List<SystemdUnit> = emptyList(),
    val crons: List<CronEntry> = emptyList(),
    val query: String = "",
    val sort: ProcessSort = ProcessSort.CPU,
    val pendingSignal: Pair<ProcessInfo, ProcessSignal>? = null,
    val journalUnit: SystemdUnit? = null,
    val journalLogs: String = "",
    val message: String? = null,
)

class ProcessesViewModel(
    private val controller: HomelabController,
    private val repository: GlassrackRepository,
    private val biometricGate: BiometricGate,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = repository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    private val _uiState = MutableStateFlow(ProcessesUiState())
    val uiState: StateFlow<ProcessesUiState> = _uiState.asStateFlow()

    fun loadProcesses(server: Server) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                val procs = controller.fetchProcesses(server)
                _uiState.update { it.copy(isLoading = false, processes = procs) }
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, message = err.message) }
            }
        }
    }

    fun loadServicesAndCron(server: Server) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                val (units, crons) = controller.fetchSystemdAndCron(server)
                _uiState.update { it.copy(isLoading = false, units = units, crons = crons) }
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, message = err.message) }
            }
        }
    }

    fun setQuery(q: String) = _uiState.update { it.copy(query = q) }
    fun setSort(sort: ProcessSort) = _uiState.update { it.copy(sort = sort) }

    fun requestSignal(server: Server, proc: ProcessInfo, signal: ProcessSignal) {
        val prefs = preferences.value
        if (prefs.safeReadOnlyMode) {
            _uiState.update { it.copy(message = "Blocked: Safe Read-Only Mode is active") }
            return
        }
        val gated = (signal.isDestructive || proc.pid <= 10) &&
            prefs.destructiveConfirmation != ConfirmationLevel.NONE
        if (gated) {
            if (prefs.destructiveConfirmation == ConfirmationLevel.BIOMETRIC && biometricGate.canAuthenticate()) {
                viewModelScope.launch {
                    val ok = biometricGate.authenticate(
                        "Send ${signal.signalName} to PID ${proc.pid}",
                        proc.command,
                    )
                    if (ok) {
                        executeSignal(server, proc, signal)
                    } else {
                        controller.recordBlockedAction(
                            server = server,
                            category = AuditCategory.PROCESS,
                            actionTitle = "Process Signal Blocked",
                            command = "kill -${signal.code} ${proc.pid}",
                            reason = "Biometric confirmation cancelled",
                        )
                        _uiState.update { it.copy(message = "Biometric confirmation cancelled") }
                    }
                }
            } else {
                _uiState.update { it.copy(pendingSignal = proc to signal) }
            }
        } else {
            executeSignal(server, proc, signal)
        }
    }

    fun confirmSignal(server: Server) {
        val pending = _uiState.value.pendingSignal ?: return
        _uiState.update { it.copy(pendingSignal = null) }
        executeSignal(server, pending.first, pending.second)
    }

    fun dismissSignal() = _uiState.update { it.copy(pendingSignal = null) }

    private fun executeSignal(server: Server, proc: ProcessInfo, signal: ProcessSignal) {
        viewModelScope.launch {
            val res = controller.sendProcessSignal(server, proc, signal)
            _uiState.update { it.copy(message = res.stdout.ifBlank { res.stderr }) }
            loadProcesses(server)
        }
    }

    fun performServiceVerb(server: Server, unit: SystemdUnit, verb: String) {
        if (preferences.value.safeReadOnlyMode) {
            _uiState.update { it.copy(message = "Blocked: Safe Read-Only Mode is active") }
            return
        }
        viewModelScope.launch {
            val res = controller.performSystemdAction(server, unit.name, verb)
            _uiState.update { it.copy(message = res.stdout.ifBlank { "Exit ${res.exitCode}" }) }
            loadServicesAndCron(server)
        }
    }

    fun openJournal(server: Server, unit: SystemdUnit) {
        viewModelScope.launch {
            _uiState.update { it.copy(journalUnit = unit, journalLogs = "Loading journalctl for ${unit.name}...") }
            val res = controller.executeAuditedAction(
                server = server,
                category = AuditCategory.SERVICE,
                actionTitle = "Inspect journalctl ${unit.name}",
                command = "journalctl -u '${unit.name}' -n 80 --no-pager",
            )
            _uiState.update { it.copy(journalLogs = res.stdout.ifBlank { res.stderr }) }
        }
    }

    fun closeJournal() = _uiState.update { it.copy(journalUnit = null, journalLogs = "") }
    fun clearMessage() = _uiState.update { it.copy(message = null) }
}
