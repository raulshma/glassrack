package com.raulshma.grack.feature.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.ComposeStack
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.NodeUpdateReport
import com.raulshma.grack.core.model.OsScanStatus
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.ServerHealth
import com.raulshma.grack.core.model.TriggerSource
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.model.updateApplyRequiresConfirmation
import com.raulshma.grack.core.ssh.UpdateParsers
import com.raulshma.grack.core.ssh.UpdateProbes
import com.raulshma.grack.core.model.distroFamilyForDistroId
import com.raulshma.grack.core.security.SecureVault
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.core.ssh.binaryName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** One node's line in the banner a finished scan wave composes; a problem line needs the user's attention. */
internal data class ScanNote(val text: String, val isProblem: Boolean)

/** A scan's one-line verdict for [server]. Unreliable results (a stale index, an unanswered registry) count as problems. */
internal fun scanNote(server: Server, report: NodeUpdateReport): ScanNote {
    val name = server.name
    val os = report.os
    fun line(text: String, problem: Boolean = false) = ScanNote("$name: $text", isProblem = problem)
    return when (os?.status) {
        OsScanStatus.FAILED -> line("OS scan failed (${os.detail})", problem = true)
        // The detail already names the reason and says the count is a lower bound.
        OsScanStatus.STALE -> line(os.detail, problem = true)
        OsScanStatus.UNSUPPORTED -> line("OS updates unsupported (${os.detail})" + foundText(report).let { if (it.isEmpty()) "" else " · $it" })
        null -> line("no OS result", problem = true)
        OsScanStatus.OK -> {
            val found = foundText(report)
            if (found.isNotEmpty()) {
                line(found + unfinishedContainerText(report)?.let { " — $it" }.orEmpty())
            } else {
                // "Up to date" only when every container was actually determined.
                unfinishedContainerText(report)?.let { line("no updates found — $it", problem = true) } ?: line("up to date")
            }
        }
    }
}

/** "3 OS · 2 container updates": what a scan found; "" when nothing. */
private fun foundText(report: NodeUpdateReport): String {
    val containers = report.containerTally.withUpdates
    return listOfNotNull(
        report.osUpdateCount.takeIf { it > 0 }?.let { "$it OS" },
        containers.takeIf { it > 0 }?.let { "$it container update${if (it == 1) "" else "s"}" },
    ).joinToString(" · ")
}

/** Why a report's container half is not fully known, or null when every container was determined. */
internal fun unfinishedContainerText(report: NodeUpdateReport): String? {
    report.containerScanDetail.takeIf { it.isNotBlank() }?.let { return it }
    val undetermined = report.containerTally.undetermined
    return if (undetermined > 0) "$undetermined container${if (undetermined == 1) "" else "s"} undetermined" else null
}

/** The banner for a finished scan wave: one line per node, problems first so they are never pushed off-screen. */
internal fun composeScanBanner(notes: Collection<ScanNote>): String? =
    notes.takeIf { it.isNotEmpty() }?.let { all ->
        (all.filter { it.isProblem } + all.filterNot { it.isProblem }).joinToString("\n") { it.text }
    }

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

    /** Some node's counts are not trustworthy: index not refreshed, scan failed, or the registry left containers undetermined. */
    val hasUnreliableCounts: Boolean
        get() = reports.values.any {
            val status = it.os?.status
            status == OsScanStatus.STALE || status == OsScanStatus.FAILED || unfinishedContainerText(it) != null
        }
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

    /** The current scan wave's per-node verdicts, composed into the banner when the last node finishes. */
    private val scanNotes = MutableStateFlow<Map<String, ScanNote>>(emptyMap())

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
        scanNotes.value = emptyMap()
        _ephemeral.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val servers = serversState.value
            if (servers.isEmpty()) {
                // No node to scan is a finished scan with nothing to report, not one that never ends.
                _ephemeral.update { it.copy(scanning = false, statusBanner = "No nodes to scan yet") }
                return@launch
            }
            servers.forEach { server ->
                launch { scanServer(server, composeWaveBanner = true) }
            }
        }
    }

    /**
     * Scans one node and returns its one-line verdict. The OS half is published the moment it
     * lands — before the slower container registry probes — and a wave's banner ([composeWaveBanner])
     * appears when the last node of it finishes. A scan that follows an apply leaves the banner
     * to the apply.
     */
    private suspend fun scanServer(server: Server, composeWaveBanner: Boolean): ScanNote {
        _ephemeral.update { it.copy(busyServerIds = it.busyServerIds + server.id) }
        var note: ScanNote? = null
        try {
            // Detached: a scan that outlives this screen (the user navigated away) keeps going
            // and still lands in the persisted report and the ledger; awaiting only re-throws
            // this scope's own cancellation.
            val report = controller.launchDetached {
                controller.scanNodeUpdates(server, vault, TriggerSource.UI) { partial ->
                    scannedReports.update { it + (server.id to partial) }
                }
            }.await()
            scannedReports.update { it + (server.id to report) }
            note = scanNote(server, report)
            return checkNotNull(note)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Logger.w(t) { "Update scan failed for ${server.name}" }
            return ScanNote("${server.name}: scan failed — ${t.message}", isProblem = true).also { note = it }
        } finally {
            note?.let { verdict -> scanNotes.update { it + (server.id to verdict) } }
            _ephemeral.update { state ->
                val busy = state.busyServerIds - server.id
                state.copy(
                    busyServerIds = busy,
                    // The cluster-wide scanning flag clears with the last node's scan, which is
                    // also when a wave's per-node verdicts become the banner.
                    scanning = busy.isNotEmpty(),
                    statusBanner = if (composeWaveBanner && busy.isEmpty()) {
                        waveBanner() ?: state.statusBanner
                    } else {
                        state.statusBanner
                    },
                )
            }
        }
    }

    /** The wave's lines in node order, problems first, so the banner reads the same whatever node finished first. */
    private fun waveBanner(): String? {
        val notes = scanNotes.value
        val listed = serversState.value.map { it.id }.toSet()
        return composeScanBanner(serversState.value.mapNotNull { notes[it.id] } + notes.filterKeys { it !in listed }.values)
    }

    /** [headline] followed by the rescan's verdict, when that verdict needs the user's attention. */
    private fun withScanWarning(headline: String, note: ScanNote): String =
        if (note.isProblem) "$headline\n${note.text}" else headline

    fun setShowSheet(show: Boolean) {
        _ephemeral.update { it.copy(showSheet = show) }
    }

    fun clearStatusBanner() {
        _ephemeral.update { it.copy(statusBanner = null) }
    }

    // ------------------------------------------------------------------
    // Gated applies
    // ------------------------------------------------------------------
    //
    // Every refusal reaches the ledger as BLOCKED: Safe Read-Only Mode, and a confirmation the
    // user dismisses, are decisions not to run an action the user asked for — never silence.

    /**
     * Records a refused action. The command is the text that would have run, so the entry
     * reads like the execution it prevented.
     */
    private fun recordBlocked(
        server: Server,
        category: AuditCategory,
        actionTitle: String,
        command: String,
        reason: String,
    ) {
        viewModelScope.launch {
            // The refusal is already a fact; a screen closed right after must not take its entry along.
            withContext(NonCancellable) {
                controller.recordBlockedAction(server, category, actionTitle, command, reason)
            }
        }
    }

    private fun osApplyCommand(server: Server): String =
        UpdateProbes.osUpdateApplyCommand(distroFamilyForDistroId(server.capabilities.distroId))

    private fun blockedBySafeReadOnly() {
        _ephemeral.update { it.copy(statusBanner = "Blocked: Safe Read-Only Mode is active") }
    }

    fun requestOsApply(server: Server) {
        if (uiState.value.preferences.safeReadOnlyMode) {
            blockedBySafeReadOnly()
            recordBlocked(server, AuditCategory.SYSTEM, "Apply OS updates", osApplyCommand(server), "Blocked: Safe Read-Only Mode is active")
            return
        }
        if (updateApplyRequiresConfirmation(uiState.value.preferences)) {
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
        val pending = _ephemeral.value.pendingOsApply ?: return
        _ephemeral.update { it.copy(pendingOsApply = null) }
        recordBlocked(pending, AuditCategory.SYSTEM, "Apply OS updates", osApplyCommand(pending), "Dismissed the confirmation")
    }

    private fun executeOsApply(server: Server) {
        viewModelScope.launch {
            _ephemeral.update { it.copy(busyServerIds = it.busyServerIds + server.id) }
            try {
                // Detached: an upgrade interrupted at the half-way mark is worse than one that
                // finishes unseen — the apply keeps running in the controller's scope even if
                // the user leaves this screen.
                val result = controller.launchDetached { controller.applyOsUpdates(server, vault) }.await()
                val succeeded = result.exec.exitCode == 0
                val banner = if (succeeded) {
                    buildString {
                        append("OS updates on ${server.name}: completed")
                        if (result.rebootRecommended) append(" · reboot recommended")
                    }
                } else {
                    // What the node printed, in plain words — never a bare "exit 1".
                    "OS updates on ${server.name} failed: " +
                        UpdateParsers.explainPrivilegedFailure(result.exec, server.username)
                }
                // Refresh the report so counts drop and the card reflects reality; the banner
                // keeps the apply's verdict and only gains the rescan's problems.
                val note = if (succeeded) scanServer(server, composeWaveBanner = false) else null
                _ephemeral.update { state ->
                    state.copy(
                        rebootRecommended = state.rebootRecommended + (server.id to result.rebootRecommended),
                        statusBanner = note?.let { withScanWarning(banner, it) } ?: banner,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
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

    private fun stackUpdateCommand(server: Server, stack: ComposeStack): String =
        "${controller.containerRuntimeFor(server).binaryName} compose pull ${stack.projectName} && " +
            "${controller.containerRuntimeFor(server).binaryName} compose up -d ${stack.projectName}"

    fun requestStackUpdate(server: Server, stack: ComposeStack) {
        if (uiState.value.preferences.safeReadOnlyMode) {
            blockedBySafeReadOnly()
            recordBlocked(server, AuditCategory.CONTAINER, "Compose update: ${stack.projectName}", stackUpdateCommand(server, stack), "Blocked: Safe Read-Only Mode is active")
            return
        }
        // `update` recreates services (pull && up -d); it is gated like the destructive gate.
        if (updateApplyRequiresConfirmation(uiState.value.preferences)) {
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
        val pending = _ephemeral.value.pendingStackUpdate ?: return
        _ephemeral.update { it.copy(pendingStackUpdate = null) }
        recordBlocked(
            pending.first,
            AuditCategory.CONTAINER,
            "Compose update: ${pending.second.projectName}",
            stackUpdateCommand(pending.first, pending.second),
            "Dismissed the confirmation",
        )
    }

    private fun executeStackUpdate(server: Server, stack: ComposeStack) {
        viewModelScope.launch {
            _ephemeral.update { it.copy(busyServerIds = it.busyServerIds + server.id) }
            try {
                // Detached (see [executeOsApply]): `pull && up -d` half-done is worse than done unseen.
                val res = controller.launchDetached {
                    controller.applyStackUpdate(
                        server = server,
                        projectName = stack.projectName,
                        workingDir = stack.workingDir,
                        configFiles = stack.configFiles,
                    )
                }.await()
                val banner = "Stack ${stack.projectName} update: exit ${res.exitCode}"
                val note = scanServer(server, composeWaveBanner = false)
                _ephemeral.update { state ->
                    state.copy(statusBanner = withScanWarning(banner, note))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
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

    private fun pullCommand(server: Server, container: ContainerInfo): String =
        "${controller.containerRuntimeFor(server).binaryName} pull '${container.image}'"

    fun requestPull(server: Server, container: ContainerInfo) {
        if (uiState.value.preferences.safeReadOnlyMode) {
            blockedBySafeReadOnly()
            recordBlocked(server, AuditCategory.CONTAINER, "Container pull: ${container.name}", pullCommand(server, container), "Blocked: Safe Read-Only Mode is active")
            return
        }
        if (updateApplyRequiresConfirmation(uiState.value.preferences)) {
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
        val pending = _ephemeral.value.pendingPull ?: return
        _ephemeral.update { it.copy(pendingPull = null) }
        recordBlocked(
            pending.first,
            AuditCategory.CONTAINER,
            "Container pull: ${pending.second.name}",
            pullCommand(pending.first, pending.second),
            "Dismissed the confirmation",
        )
    }

    private fun executePull(server: Server, container: ContainerInfo) {
        viewModelScope.launch {
            _ephemeral.update { it.copy(busyServerIds = it.busyServerIds + server.id) }
            try {
                // Detached (see [executeOsApply]).
                val res = controller.launchDetached { controller.applyContainerPull(server, container) }.await()
                val banner = "Pull ${container.name}: exit ${res.exitCode}"
                val note = scanServer(server, composeWaveBanner = false)
                _ephemeral.update { state ->
                    state.copy(statusBanner = withScanWarning(banner, note))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
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
