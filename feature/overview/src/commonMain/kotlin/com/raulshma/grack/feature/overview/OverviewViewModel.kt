package com.raulshma.grack.feature.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.ActiveJob
import com.raulshma.grack.core.model.AppAlert
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.ContainerAction
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.DashboardProfile
import com.raulshma.grack.core.model.DashboardTile
import com.raulshma.grack.core.model.MetricKind
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.ServerRuntimeState
import com.raulshma.grack.core.model.TileType
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.model.requiresConfirmation
import com.raulshma.grack.core.ssh.HomelabController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

data class PendingTileContainerAction(
    val server: Server,
    val containerName: String,
    val action: ContainerAction,
)

data class OverviewUiState(
    val dashboards: List<DashboardProfile> = emptyList(),
    val selectedDashboardId: String = "dash-main",
    val tiles: List<DashboardTile> = emptyList(),
    val servers: Map<String, ServerRuntimeState> = emptyMap(),
    val activeJobs: List<ActiveJob> = emptyList(),
    val alerts: List<AppAlert> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
    val isEditMode: Boolean = false,
    val showTilePicker: Boolean = false,
    val showNewDashboardDialog: Boolean = false,
    val showActiveJobsSheet: Boolean = false,
    val pinnedContainerStates: Map<String, ContainerInfo> = emptyMap(),
    val statusBanner: String? = null,
    val pendingTileContainerAction: PendingTileContainerAction? = null,
)

/**
 * Stage-1 snapshot (Phase 8 combine split): dashboard content — the dashboard list, the
 * effectively selected dashboard and its sorted tiles. Re-runs only when dashboard data
 * or the selection changes.
 */
data class OverviewDashboardContent(
    val dashboards: List<DashboardProfile> = emptyList(),
    val selectedDashboardId: String = "dash-main",
    val tiles: List<DashboardTile> = emptyList(),
)

/**
 * Stage-2 snapshot: the live environment — runtime states, jobs, alerts and preferences.
 * Re-runs only when one of those changes, not on every editor toggle.
 */
data class OverviewEnvironment(
    val servers: Map<String, ServerRuntimeState> = emptyMap(),
    val activeJobs: List<ActiveJob> = emptyList(),
    val alerts: List<AppAlert> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
)

/** Stage-3 snapshot: the four view-local boolean flags (edit mode and sheet visibility). */
data class OverviewEditorFlags(
    val isEditMode: Boolean = false,
    val showTilePicker: Boolean = false,
    val showNewDashboardDialog: Boolean = false,
    val showActiveJobsSheet: Boolean = false,
)

/** Stage-4 snapshot: transient interaction state (pinned containers, banner, pending tile action). */
data class OverviewEditorTransient(
    val pinnedContainerStates: Map<String, ContainerInfo> = emptyMap(),
    val statusBanner: String? = null,
    val pendingTileContainerAction: PendingTileContainerAction? = null,
)

/**
 * Stage-1 reduction: resolves the effective dashboard id (requested if it exists, else the
 * first dashboard, else "dash-main") and its position-sorted tiles.
 */
fun resolveDashboardContent(
    dashboards: List<DashboardProfile>,
    allTiles: List<DashboardTile>,
    requestedDashboardId: String,
): OverviewDashboardContent {
    val effectiveDashId = if (dashboards.any { it.id == requestedDashboardId }) {
        requestedDashboardId
    } else {
        dashboards.firstOrNull()?.id ?: "dash-main"
    }
    return OverviewDashboardContent(
        dashboards = dashboards,
        selectedDashboardId = effectiveDashId,
        tiles = allTiles
            .filter { it.dashboardId == effectiveDashId }
            .sortedBy { it.positionIndex },
    )
}

/**
 * Final assembly: joins the four staged snapshots into [OverviewUiState] — a pure
 * field-for-field mapping, equivalent to the single 14-flow combine it replaced.
 */
fun assembleOverviewUiState(
    content: OverviewDashboardContent,
    environment: OverviewEnvironment,
    flags: OverviewEditorFlags,
    transient: OverviewEditorTransient,
): OverviewUiState = OverviewUiState(
    dashboards = content.dashboards,
    selectedDashboardId = content.selectedDashboardId,
    tiles = content.tiles,
    servers = environment.servers,
    activeJobs = environment.activeJobs,
    alerts = environment.alerts,
    preferences = environment.preferences,
    isEditMode = flags.isEditMode,
    showTilePicker = flags.showTilePicker,
    showNewDashboardDialog = flags.showNewDashboardDialog,
    showActiveJobsSheet = flags.showActiveJobsSheet,
    pinnedContainerStates = transient.pinnedContainerStates,
    statusBanner = transient.statusBanner,
    pendingTileContainerAction = transient.pendingTileContainerAction,
)

class OverviewViewModel(
    private val repository: GlassrackRepository,
    private val controller: HomelabController,
) : ViewModel() {

    private val _selectedDashboardId = MutableStateFlow("dash-main")
    private val _isEditMode = MutableStateFlow(false)
    private val _showTilePicker = MutableStateFlow(false)
    private val _showNewDashboardDialog = MutableStateFlow(false)
    private val _showActiveJobsSheet = MutableStateFlow(false)
    private val _pinnedContainers = MutableStateFlow<Map<String, ContainerInfo>>(emptyMap())
    private val _statusBanner = MutableStateFlow<String?>(null)
    private val _pendingTileAction = MutableStateFlow<PendingTileContainerAction?>(null)

    /**
     * The 14-flow monolithic combine (which re-ran in full on every emission, mixing
     * repository data, controller state and view-local flags) is split into four staged
     * combines: one emission now only re-runs its own stage plus the cheap final assembly.
     */
    private val dashboardContent: StateFlow<OverviewDashboardContent> =
        combine(
            repository.dashboards,
            repository.dashboardTiles,
            _selectedDashboardId,
        ) { dashboards, allTiles, requestedDash ->
            resolveDashboardContent(dashboards, allTiles, requestedDash)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverviewDashboardContent())

    private val environment: StateFlow<OverviewEnvironment> =
        combine(
            controller.runtimeStates,
            repository.activeJobs,
            controller.activeAlerts,
            repository.preferences,
        ) { servers, jobs, alerts, prefs ->
            OverviewEnvironment(servers = servers, activeJobs = jobs, alerts = alerts, preferences = prefs)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverviewEnvironment())

    private val editorFlags: StateFlow<OverviewEditorFlags> =
        combine(
            _isEditMode,
            _showTilePicker,
            _showNewDashboardDialog,
            _showActiveJobsSheet,
        ) { editMode, showPicker, showNewDash, showJobs ->
            OverviewEditorFlags(
                isEditMode = editMode,
                showTilePicker = showPicker,
                showNewDashboardDialog = showNewDash,
                showActiveJobsSheet = showJobs,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverviewEditorFlags())

    private val editorTransient: StateFlow<OverviewEditorTransient> =
        combine(
            _pinnedContainers,
            _statusBanner,
            _pendingTileAction,
        ) { pinned, banner, pendingTileAction ->
            OverviewEditorTransient(
                pinnedContainerStates = pinned,
                statusBanner = banner,
                pendingTileContainerAction = pendingTileAction,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverviewEditorTransient())

    val uiState: StateFlow<OverviewUiState> =
        combine(dashboardContent, environment, editorFlags, editorTransient, ::assembleOverviewUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverviewUiState())

    init {
        refreshPinnedContainers()
    }

    fun selectDashboard(id: String) {
        _selectedDashboardId.value = id
    }

    fun toggleEditMode() {
        _isEditMode.update { !it }
    }

    fun setShowTilePicker(show: Boolean) {
        _showTilePicker.value = show
    }

    fun setShowNewDashboardDialog(show: Boolean) {
        _showNewDashboardDialog.value = show
    }

    fun setShowActiveJobsSheet(show: Boolean) {
        _showActiveJobsSheet.value = show
    }

    fun toggleSafeMode() {
        viewModelScope.launch {
            repository.updatePreferences { it.copy(safeReadOnlyMode = !it.safeReadOnlyMode) }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            uiState.value.servers.values.forEach { state ->
                launch { controller.pollServerOnce(state.server) }
            }
            refreshPinnedContainers()
        }
    }

    private fun refreshPinnedContainers() {
        viewModelScope.launch {
            val servers = repository.getServersOnce()
            val map = mutableMapOf<String, ContainerInfo>()
            servers.forEach { srv ->
                runCatching {
                    controller.fetchContainers(srv).forEach { c ->
                        map["${srv.id}:${c.name}"] = c
                    }
                }
            }
            _pinnedContainers.value = map
        }
    }

    fun createDashboard(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val id = "dash-${Random.nextInt(10000, 99999)}"
            val nextOrder = uiState.value.dashboards.size
            repository.upsertDashboard(DashboardProfile(id = id, title = title.trim(), sortOrder = nextOrder))
            _selectedDashboardId.value = id
            _showNewDashboardDialog.value = false
        }
    }

    fun addTile(
        tileType: TileType,
        serverId: String?,
        metricKind: MetricKind,
        titleOverride: String,
        containerName: String?,
        customCommand: String?,
        spanX: Int,
    ) {
        viewModelScope.launch {
            val currentTiles = uiState.value.tiles
            val tile = DashboardTile(
                id = "tile-${Random.nextInt(10000, 99999)}",
                dashboardId = uiState.value.selectedDashboardId,
                serverId = serverId,
                tileType = tileType,
                metricKind = metricKind,
                titleOverride = titleOverride.trim(),
                containerName = containerName?.takeIf { it.isNotBlank() },
                customCommand = customCommand?.takeIf { it.isNotBlank() },
                spanX = spanX.coerceIn(1, 2),
                spanY = 1,
                positionIndex = currentTiles.size,
            )
            if (tileType == TileType.CUSTOM_COMMAND && !serverId.isNullOrBlank() && !customCommand.isNullOrBlank()) {
                val srv = repository.getServerById(serverId)
                if (srv != null) {
                    val probeId = "custom_${tile.id}"
                    val updatedProbes = srv.customProbes + com.raulshma.grack.core.model.CustomProbeDefinition(
                        id = probeId,
                        label = titleOverride.ifBlank { "Custom Probe" },
                        command = customCommand,
                    )
                    repository.upsertServer(srv.copy(customProbes = updatedProbes))
                }
            }
            repository.upsertTile(tile)
            _showTilePicker.value = false
        }
    }

    fun moveTile(fromIndex: Int, toIndex: Int) {
        val current = uiState.value.tiles.toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)
        viewModelScope.launch {
            repository.reorderTiles(current)
        }
    }

    fun toggleTileSpan(tile: DashboardTile) {
        val nextSpan = if (tile.spanX == 2) 1 else 2
        viewModelScope.launch {
            repository.upsertTile(tile.copy(spanX = nextSpan))
        }
    }

    fun deleteTile(tileId: String) {
        viewModelScope.launch {
            repository.deleteTile(tileId)
        }
    }

    fun triggerPinnedContainer(server: Server, containerName: String, action: ContainerAction) {
        val prefs = uiState.value.preferences
        if (prefs.safeReadOnlyMode) {
            _statusBanner.value = "Blocked: Safe Read-Only Mode is active"
            return
        }
        if (requiresConfirmation(actionIsDestructive = action.isDestructive, prefs = prefs)) {
            _pendingTileAction.value = PendingTileContainerAction(server, containerName, action)
            return
        }
        executeTileContainerAction(server, containerName, action)
    }

    fun confirmTileContainerAction() {
        val pending = _pendingTileAction.value ?: return
        _pendingTileAction.value = null
        executeTileContainerAction(pending.server, pending.containerName, pending.action)
    }

    fun dismissTileContainerAction() {
        _pendingTileAction.value = null
    }

    private fun executeTileContainerAction(server: Server, containerName: String, action: ContainerAction) {
        viewModelScope.launch {
            val container = _pinnedContainers.value["${server.id}:$containerName"]
                ?: ContainerInfo(id = containerName, shortId = containerName, name = containerName, image = "docker")
            val res = controller.performContainerAction(server, container, action)
            refreshPinnedContainers()
            _statusBanner.value = "${container.name}: ${action.label} (exit ${res.exitCode})"
        }
    }

    fun clearCompletedJobs() = repository.clearCompletedJobs()
    fun clearStatusBanner() { _statusBanner.value = null }
}
