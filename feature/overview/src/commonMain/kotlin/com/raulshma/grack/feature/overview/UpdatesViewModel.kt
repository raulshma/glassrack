package com.raulshma.grack.feature.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.ComposeStack
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.NodeUpdateReport
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.ServerHealth
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.model.updateApplyRequiresConfirmation
import com.raulshma.grack.core.security.SecureVault
import com.raulshma.grack.core.ssh.HomelabController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Ephemeral, session-local state of the Updates card (persisted reports live on the server rows). */
private data class UpdatesEphemeral(
    val showSheet: Boolean = false,
    val statusBanner: String? = null,
    val scanning: Boolean = false,
    val busyServerIds: Set<String> = emptySet(),
    val rebootRecommended: Map<String, Boolean> = emptyMap(),
    val pendingOsApply: Server? = null,
    val pendingStackUpdate: Pair<Server, ComposeStack>? = null,
    val pendingPull: Pair<Server, ContainerInfo>? = null,
)

data class UpdatesUiState(
    val servers: List<Server> = emptyList(),
    val serverHealth: Map<String, ServerHealth> = emptyMap(),
    /** Per-server report: the latest scan when one ran this session, else the persisted one. */
    val reports: Map<String, NodeUpdateReport> = emptyMap(),
    val preferences: UserPreferences = UserPreferences(),
    val isScanning: Boolean = false,
    val busyServerIds: Set<String> = emptySet(),
    val rebootRecommended: Map<String, Boolean> = emptyMap(),
    val showSheet: Boolean = false,
    val statusBanner: String? = null,
    val pendingOsApply: Server? = null,
    val pendingStackUpdate: Pair<Server, ComposeStack>? = null,
    val pendingPull: Pair<Server, ContainerInfo>? = null,
) {
    val totalOsUpdates: Int get() = reports.values.sumOf { it.osUpdateCount }
    val totalContainerUpdates: Int get() = reports.values.sumOf { it.containerUpdateCount }
    val hasAnyUpdates: Boolean get() = totalOsUpdates > 0 || totalContainerUpdates > 0
}

/**
 * Drives the Overview tab's Updates card (Phase 3B): scan now, per-node counts from the
 * persisted report, the list sheet, and the gated apply actions (OS updates privileged via
 * [HomelabController.applyOsUpdates], stack update and standalone pull under the same
 * per-server apply mutex).
 */
class UpdatesViewModel(
    private val repository: GlassrackRepository,
    private val controller: HomelabController,
    private val vault: SecureVault,
) : ViewModel() {

    /** Reports scanned during this session; they override the persisted ones. */
    private val scannedReports = MutableStateFlow<Map<String, NodeUpdateReport>>(emptyMap())

    private val _ephemeral = MutableStateFlow(UpdatesEphemeral())

    private val serversState = repository.servers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<UpdatesUiState> = combine(
        serversState,
        repository.preferences,
        controller.runtimeStates,
        scannedReports,
        _ephemeral,
    ) { servers, prefs, runtimeStates, scanned, eph ->
        UpdatesUiState(
            servers = servers.sortedBy { it.sortOrder },
            serverHealth = runtimeStates.mapValues { it.value.health },
            reports = servers.associate { server ->
                server.id to (scanned[server.id] ?: NodeUpdateReport.fromJson(server.updateReportJson) ?: NEVER_SCANNED)
            },
            preferences = prefs,
            isScanning = eph.scanning,
            busyServerIds = eph.busyServerIds,
            rebootRecommended = eph.rebootRecommended,
            showSheet = eph.showSheet,
            statusBanner = eph.statusBanner,
            pendingOsApply = eph.pendingOsApply,
            pendingStackUpdate = eph.pendingStackUpdate,
            pendingPull = eph.pendingPull,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UpdatesUiState())

    fun scanNow() {
        _ephemeral.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val servers = serversState.value
            servers.forEach { server ->
                launch { scanServer(server) }
            }
        }
    }

    /** Scans one node and persists the merged report; failures end in the status banner. */
    private suspend fun scanServer(server: Server) {
        _ephemeral.update { it.copy(busyServerIds = it.busyServerIds + server.id) }
        try {
            val report = controller.scanNodeUpdates(server)
            scannedReports.update { it + (server.id to report) }
        } catch (t: Throwable) {
            Logger.w(t) { "Update scan failed for ${server.name}" }
            _ephemeral.update { it.copy(statusBanner = "Scan failed on ${server.name}: ${t.message}") }
        } finally {
            _ephemeral.update { state ->
                val busy = state.busyServerIds - server.id
                // The cluster-wide scanning flag clears with the last node's scan.
                state.copy(
                    busyServerIds = busy,
                    scanning = if (busy.isEmpty()) false else state.scanning,
                )
            }
        }
    }

    fun setShowSheet(show: Boolean) {
        _ephemeral.update { it.copy(showSheet = show) }
    }

    fun clearStatusBanner() {
        _ephemeral.update { it.copy(statusBanner = null) }
    }

    // ------------------------------------------------------------------
    // Gated applies
    // ------------------------------------------------------------------

    fun requestOsApply(server: Server) {
        val prefs = uiState.value.preferences
        if (prefs.safeReadOnlyMode) {
            _ephemeral.update { it.copy(statusBanner = "Blocked: Safe Read-Only Mode is active") }
            return
        }
        if (updateApplyRequiresConfirmation(prefs)) {
            _ephemeral.update { it.copy(pendingOsApply = server) }
            return
        }
        executeOsApply(server)
    }

    fun confirmOsApply() {
        val pending = _ephemeral.value.pendingOsApply ?: return
        _ephemeral.update { it.copy(pendingOsApply = null) }
        executeOsApply(pending)
    }

    fun dismissOsApply() {
        _ephemeral.update { it.copy(pendingOsApply = null) }
    }

    private fun executeOsApply(server: Server) {
        viewModelScope.launch {
            _ephemeral.update { it.copy(busyServerIds = it.busyServerIds + server.id) }
            try {
                val result = controller.applyOsUpdates(server, vault)
                if (result.exec.exitCode == 0) {
                    // Refresh the report so counts drop and the card reflects reality.
                    scanServer(server)
                }
                _ephemeral.update { state ->
                    state.copy(
                        rebootRecommended = state.rebootRecommended + (server.id to result.rebootRecommended),
                        statusBanner = buildString {
                            append("OS updates on ${server.name}: ")
                            append(if (result.exec.exitCode == 0) "completed" else "exit ${result.exec.exitCode}")
                            if (result.rebootRecommended) append(" · reboot recommended")
                        },
                    )
                }
            } catch (t: Throwable) {
                Logger.w(t) { "OS apply failed for ${server.name}" }
                _ephemeral.update { it.copy(statusBanner = "OS apply failed on ${server.name}: ${t.message}") }
            } finally {
                _ephemeral.update { state ->
                    state.copy(busyServerIds = state.busyServerIds - server.id)
                }
            }
        }
    }

    fun requestStackUpdate(server: Server, stack: ComposeStack) {
        val prefs = uiState.value.preferences
        if (prefs.safeReadOnlyMode) {
            _ephemeral.update { it.copy(statusBanner = "Blocked: Safe Read-Only Mode is active") }
            return
        }
        // `update` recreates services (pull && up -d); it is gated like the destructive gate.
        if (updateApplyRequiresConfirmation(prefs)) {
            _ephemeral.update { it.copy(pendingStackUpdate = server to stack) }
            return
        }
        executeStackUpdate(server, stack)
    }

    fun confirmStackUpdate() {
        val pending = _ephemeral.value.pendingStackUpdate ?: return
        _ephemeral.update { it.copy(pendingStackUpdate = null) }
        executeStackUpdate(pending.first, pending.second)
    }

    fun dismissStackUpdate() {
        _ephemeral.update { it.copy(pendingStackUpdate = null) }
    }

    private fun executeStackUpdate(server: Server, stack: ComposeStack) {
        viewModelScope.launch {
            _ephemeral.update { it.copy(busyServerIds = it.busyServerIds + server.id) }
            try {
                val res = controller.applyStackUpdate(
                    server = server,
                    projectName = stack.projectName,
                    workingDir = stack.workingDir,
                    configFiles = stack.configFiles,
                )
                _ephemeral.update { state ->
                    state.copy(statusBanner = "Stack ${stack.projectName} update: exit ${res.exitCode}")
                }
                scanServer(server)
            } catch (t: Throwable) {
                Logger.w(t) { "Stack update failed for ${server.name}" }
                _ephemeral.update { it.copy(statusBanner = "Stack update failed: ${t.message}") }
            } finally {
                _ephemeral.update { state ->
                    state.copy(busyServerIds = state.busyServerIds - server.id)
                }
            }
        }
    }

    fun requestPull(server: Server, container: ContainerInfo) {
        val prefs = uiState.value.preferences
        if (prefs.safeReadOnlyMode) {
            _ephemeral.update { it.copy(statusBanner = "Blocked: Safe Read-Only Mode is active") }
            return
        }
        if (updateApplyRequiresConfirmation(prefs)) {
            _ephemeral.update { it.copy(pendingPull = server to container) }
            return
        }
        executePull(server, container)
    }

    fun confirmPull() {
        val pending = _ephemeral.value.pendingPull ?: return
        _ephemeral.update { it.copy(pendingPull = null) }
        executePull(pending.first, pending.second)
    }

    fun dismissPull() {
        _ephemeral.update { it.copy(pendingPull = null) }
    }

    private fun executePull(server: Server, container: ContainerInfo) {
        viewModelScope.launch {
            _ephemeral.update { it.copy(busyServerIds = it.busyServerIds + server.id) }
            try {
                val res = controller.applyContainerPull(server, container)
                _ephemeral.update { state ->
                    state.copy(statusBanner = "Pull ${container.name}: exit ${res.exitCode}")
                }
                scanServer(server)
            } catch (t: Throwable) {
                Logger.w(t) { "Container pull failed for ${server.name}" }
                _ephemeral.update { it.copy(statusBanner = "Pull failed: ${t.message}") }
            } finally {
                _ephemeral.update { state ->
                    state.copy(busyServerIds = state.busyServerIds - server.id)
                }
            }
        }
    }

    companion object {
        /** Placeholder for nodes never scanned, so the card can render "not scanned yet". */
        private val NEVER_SCANNED = NodeUpdateReport(scannedAtEpochMs = 0L)
    }
}
