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

    val uiState: StateFlow<OverviewUiState> = combine(
        repository.dashboards,
        repository.dashboardTiles,
        controller.runtimeStates,
        repository.activeJobs,
        controller.activeAlerts,
        repository.preferences,
        _selectedDashboardId,
        _isEditMode,
        _showTilePicker,
        _showNewDashboardDialog,
        _showActiveJobsSheet,
        _pinnedContainers,
        _statusBanner,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val dashboards = arr[0] as List<DashboardProfile>
        @Suppress("UNCHECKED_CAST")
        val allTiles = arr[1] as List<DashboardTile>
        @Suppress("UNCHECKED_CAST")
        val servers = arr[2] as Map<String, ServerRuntimeState>
        @Suppress("UNCHECKED_CAST")
        val jobs = arr[3] as List<ActiveJob>
        @Suppress("UNCHECKED_CAST")
        val alerts = arr[4] as List<AppAlert>
        val prefs = arr[5] as UserPreferences
        val requestedDash = arr[6] as String
        val editMode = arr[7] as Boolean
        val showPicker = arr[8] as Boolean
        val showNewDash = arr[9] as Boolean
        val showJobs = arr[10] as Boolean
        @Suppress("UNCHECKED_CAST")
        val pinned = arr[11] as Map<String, ContainerInfo>
        val banner = arr[12] as String?

        val effectiveDashId = if (dashboards.any { it.id == requestedDash }) {
            requestedDash
        } else {
            dashboards.firstOrNull()?.id ?: "dash-main"
        }
        val filteredTiles = allTiles
            .filter { it.dashboardId == effectiveDashId }
            .sortedBy { it.positionIndex }

        OverviewUiState(
            dashboards = dashboards,
            selectedDashboardId = effectiveDashId,
            tiles = filteredTiles,
            servers = servers,
            activeJobs = jobs,
            alerts = alerts,
            preferences = prefs,
            isEditMode = editMode,
            showTilePicker = showPicker,
            showNewDashboardDialog = showNewDash,
            showActiveJobsSheet = showJobs,
            pinnedContainerStates = pinned,
            statusBanner = banner,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverviewUiState())

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
        if (uiState.value.preferences.safeReadOnlyMode) {
            _statusBanner.value = "Blocked: Safe Read-Only Mode is active"
            return
        }
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
