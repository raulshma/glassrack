package com.raulshma.grack.feature.overview

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raulshma.grack.core.designsystem.ActiveJobsPill
import com.raulshma.grack.core.designsystem.ExpressiveBadgeShape
import com.raulshma.grack.core.designsystem.ExpressiveCard
import com.raulshma.grack.core.designsystem.ExpressiveEmptyState
import com.raulshma.grack.core.designsystem.ExpressiveGroupedCard
import com.raulshma.grack.core.designsystem.ExpressiveGroupedItem
import com.raulshma.grack.core.designsystem.ExpressiveHeroCard
import com.raulshma.grack.core.designsystem.ExpressiveIconTile
import com.raulshma.grack.core.designsystem.ExpressiveSectionHeader
import com.raulshma.grack.core.designsystem.ExpressiveSegmentedPillGroup
import com.raulshma.grack.core.designsystem.ExpressiveShapeBadge
import com.raulshma.grack.core.designsystem.LocalGlassrackDesign
import com.raulshma.grack.core.designsystem.SafeModeBadge
import com.raulshma.grack.core.designsystem.StatusBeacon
import com.raulshma.grack.core.designsystem.VicoSparkline
import com.raulshma.grack.core.designsystem.WavyHeroGauge
import com.raulshma.grack.core.designsystem.WavyMetricBar
import com.raulshma.grack.core.designsystem.expressiveHorizontalGroupShape
import com.raulshma.grack.core.designsystem.monoNumericStyle
import com.raulshma.grack.core.model.ActiveJob
import com.raulshma.grack.core.model.AppAlert
import com.raulshma.grack.core.model.ContainerAction
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.ContainerState
import com.raulshma.grack.core.model.DashboardTile
import com.raulshma.grack.core.model.Formatters
import com.raulshma.grack.core.model.JobPhase
import com.raulshma.grack.core.model.MetricKind
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.ServerHealth
import com.raulshma.grack.core.model.ServerRuntimeState
import com.raulshma.grack.core.model.TileType
import com.raulshma.grack.core.model.UserPreferences
import org.koin.compose.viewmodel.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OverviewScreen(
    onOpenServerDetail: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onAddNode: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val design = LocalGlassrackDesign.current
    val gridState = rememberLazyGridState()
    val hasHeroCard by rememberUpdatedState(state.servers.isNotEmpty())
    val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
        val offset = if (hasHeroCard) 1 else 0
        val fromIdx = from.index - offset
        val toIdx = to.index - offset
        if (fromIdx >= 0 && toIdx >= 0) {
            viewModel.moveTile(fromIdx, toIdx)
        }
    }

    var showAlertsSheet by remember { mutableStateOf(false) }

    val runningJobsCount = state.activeJobs.count {
        it.phase == JobPhase.RUNNING || it.phase == JobPhase.CONNECTING
    }
    val onlineCount = state.servers.values.count {
        it.health == ServerHealth.ONLINE || it.health == ServerHealth.WARNING
    }
    val offlineCount = state.servers.values.count { it.health == ServerHealth.OFFLINE }
    val attentionCount = state.servers.values.count {
        it.health == ServerHealth.OFFLINE || it.health == ServerHealth.WARNING
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Editorial Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "GRack",
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                NodeStatusHeaderPill(
                    totalServers = state.servers.size,
                    onlineCount = onlineCount,
                    offlineCount = offlineCount,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SafeModeBadge(
                    isSafeMode = state.preferences.safeReadOnlyMode,
                    onToggle = viewModel::toggleSafeMode,
                )
                FilledTonalIconButton(
                    onClick = viewModel::toggleEditMode,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (state.isEditMode) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = if (state.isEditMode) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                ) {
                    Icon(
                        imageVector = if (state.isEditMode) Icons.Rounded.Close else Icons.Rounded.Edit,
                        contentDescription = if (state.isEditMode) "Done editing" else "Edit dashboard",
                    )
                }
            }
        }

        // Status Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActiveJobsPill(
                runningCount = runningJobsCount,
                latestTitle = state.activeJobs.firstOrNull()?.title,
                onClick = { viewModel.setShowActiveJobsSheet(true) },
            )
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                onClick = viewModel::refreshAll,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "Poll Now",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (state.alerts.isNotEmpty()) {
                val hasCritical = state.alerts.any { it.severity == ServerHealth.OFFLINE }
                val alertBg = if (hasCritical) design.statusFaultContainer else design.statusWarnContainer
                val alertFg = if (hasCritical) design.onStatusFaultContainer else design.onStatusWarnContainer
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = alertBg,
                    contentColor = alertFg,
                    onClick = { showAlertsSheet = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = alertFg,
                        )
                        Text(
                            text = "${state.alerts.size} Alert${if (state.alerts.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                            fontWeight = FontWeight.SemiBold,
                            color = alertFg,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Dashboard Profile Switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.dashboards.forEach { dash ->
                val selected = dash.id == state.selectedDashboardId
                DashboardPillChip(
                    label = dash.title,
                    selected = selected,
                    onClick = { viewModel.selectDashboard(dash.id) },
                )
            }
            DashboardPillChip(
                label = "+ Profile",
                selected = false,
                onClick = { viewModel.setShowNewDashboardDialog(true) },
            )
            if (state.isEditMode) {
                Surface(
                    onClick = { viewModel.setShowTilePicker(true) },
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Tile",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = state.statusBanner != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.statusBanner.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = viewModel::clearStatusBanner,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text("OK")
                    }
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(design.cardSpacing),
            verticalArrangement = Arrangement.spacedBy(design.cardSpacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.servers.isEmpty()) {
                item(key = "empty-rack", span = { GridItemSpan(2) }) {
                    ExpressiveEmptyState(
                        icon = Icons.Rounded.Dns,
                        title = "Your Rack is Empty",
                        description = "Connect a Linux host, Raspberry Pi, or Docker server over SSH to inspect live telemetry and workloads.",
                        actionLabel = "Add Your First Node",
                        onAction = onAddNode,
                    )
                }
            } else {
                item(key = "cluster-hero", span = { GridItemSpan(2) }) {
                    ClusterHeroCard(
                        servers = state.servers,
                        onlineCount = onlineCount,
                        offlineCount = offlineCount,
                        attentionCount = attentionCount,
                        alertCount = state.alerts.size,
                        onPollCluster = viewModel::refreshAll,
                        onQuickSsh = state.servers.values.firstOrNull()?.server?.let { srv ->
                            { onOpenTerminal(srv.id) }
                        },
                    )
                }

                if (state.tiles.isEmpty()) {
                    item(key = "empty-dashboard", span = { GridItemSpan(2) }) {
                        ExpressiveEmptyState(
                            icon = Icons.Rounded.Dashboard,
                            title = "Build This Dashboard",
                            description = "Pin live gauges, Raspberry Pi health tiles, or container controls to this view.",
                            actionLabel = "Add Dashboard Tile",
                            onAction = { viewModel.setShowTilePicker(true) },
                        )
                    }
                } else {
                    items(
                        items = state.tiles,
                        key = { it.id },
                        span = { GridItemSpan(it.spanX.coerceIn(1, 2)) },
                    ) { tile ->
                        ReorderableItem(reorderableState, key = tile.id) { _ ->
                            Box {
                                DashboardTileCard(
                                    tile = tile,
                                    servers = state.servers,
                                    pinnedContainers = state.pinnedContainerStates,
                                    alerts = state.alerts,
                                    prefs = state.preferences,
                                    isEditMode = state.isEditMode,
                                    onOpenServer = onOpenServerDetail,
                                    onOpenTerminal = onOpenTerminal,
                                    onContainerAction = viewModel::triggerPinnedContainer,
                                    onToggleSpan = { viewModel.toggleTileSpan(tile) },
                                    onDelete = { viewModel.deleteTile(tile.id) },
                                    dragHandleModifier = if (state.isEditMode) Modifier.draggableHandle() else Modifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showNewDashboardDialog) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.setShowNewDashboardDialog(false) },
            shape = RoundedCornerShape(28.dp),
            title = { Text("New Dashboard Profile", style = MaterialTheme.typography.headlineSmall) },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Dashboard Name (e.g. Pi Cluster)") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.createDashboard(title) },
                    enabled = title.isNotBlank(),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.setShowNewDashboardDialog(false) },
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (state.showTilePicker) {
        TileBuilderModalSheet(
            servers = state.servers.values.map { it.server },
            onAddTile = viewModel::addTile,
            onDismiss = { viewModel.setShowTilePicker(false) },
        )
    }

    if (state.showActiveJobsSheet) {
        ActiveJobsModalSheet(
            jobs = state.activeJobs,
            onClearFinished = viewModel::clearCompletedJobs,
            onDismiss = { viewModel.setShowActiveJobsSheet(false) },
        )
    }

    if (showAlertsSheet) {
        AlertsFeedModalSheet(
            alerts = state.alerts,
            onOpenServer = { serverId ->
                showAlertsSheet = false
                onOpenServerDetail(serverId)
            },
            onDismiss = { showAlertsSheet = false },
        )
    }
}

@Composable
private fun NodeStatusHeaderPill(
    totalServers: Int,
    onlineCount: Int,
    offlineCount: Int,
) {
    val design = LocalGlassrackDesign.current
    val (bg, fg, label) = when {
        totalServers == 0 -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "No nodes",
        )
        offlineCount > 0 -> Triple(
            design.statusFaultContainer,
            design.onStatusFaultContainer,
            "$onlineCount/$totalServers online",
        )
        onlineCount < totalServers -> Triple(
            design.statusWarnContainer,
            design.onStatusWarnContainer,
            "$onlineCount/$totalServers online",
        )
        else -> Triple(
            design.statusOkContainer,
            design.onStatusOkContainer,
            "$onlineCount/$totalServers online",
        )
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = bg,
        contentColor = fg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (totalServers > 0) {
                val health = when {
                    offlineCount > 0 -> ServerHealth.OFFLINE
                    onlineCount < totalServers -> ServerHealth.WARNING
                    else -> ServerHealth.ONLINE
                }
                StatusBeacon(health = health, size = 8.dp)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                fontWeight = FontWeight.SemiBold,
                color = fg,
            )
        }
    }
}

@Composable
private fun DashboardPillChip(
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ClusterHeroCard(
    servers: Map<String, ServerRuntimeState>,
    onlineCount: Int,
    offlineCount: Int,
    attentionCount: Int,
    alertCount: Int,
    onPollCluster: () -> Unit,
    onQuickSsh: (() -> Unit)?,
) {
    val design = LocalGlassrackDesign.current
    val (containerColor, contentColor, badgeIcon) = when {
        offlineCount > 0 -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Rounded.ErrorOutline,
        )
        attentionCount > 0 || alertCount > 0 -> Triple(
            design.statusWarnContainer,
            design.onStatusWarnContainer,
            Icons.Rounded.Warning,
        )
        else -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Rounded.Verified,
        )
    }

    val headline = when {
        attentionCount == 0 && alertCount == 0 -> "Cluster Healthy"
        attentionCount > 0 -> "$attentionCount ${if (attentionCount == 1) "Node Needs" else "Nodes Need"} Attention"
        else -> "$alertCount Active ${if (alertCount == 1) "Alert" else "Alerts"}"
    }
    val summary = "$onlineCount/${servers.size} online · $alertCount ${if (alertCount == 1) "alert" else "alerts"}"

    val actionCount = if (onQuickSsh != null) 2 else 1
    ExpressiveHeroCard(
        eyebrow = "LIVE CLUSTER PULSE",
        headline = headline,
        subtitle = summary,
        containerColor = containerColor,
        contentColor = contentColor,
        badgeIcon = badgeIcon,
        badgeShape = ExpressiveBadgeShape.Cookie9Sided,
        actions = {
            Button(
                onClick = onPollCluster,
                shape = expressiveHorizontalGroupShape(index = 0, count = actionCount),
                colors = ButtonDefaults.buttonColors(
                    containerColor = contentColor,
                    contentColor = containerColor,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Poll Cluster", style = MaterialTheme.typography.labelLarge)
            }
            if (onQuickSsh != null) {
                FilledTonalButton(
                    onClick = onQuickSsh,
                    shape = expressiveHorizontalGroupShape(index = 1, count = actionCount),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = contentColor.copy(alpha = 0.14f),
                        contentColor = contentColor,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Quick SSH", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    )
}

@Composable
private fun DashboardTileCard(
    tile: DashboardTile,
    servers: Map<String, ServerRuntimeState>,
    pinnedContainers: Map<String, ContainerInfo>,
    alerts: List<AppAlert>,
    prefs: UserPreferences,
    isEditMode: Boolean,
    onOpenServer: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onContainerAction: (Server, String, ContainerAction) -> Unit,
    onToggleSpan: () -> Unit,
    onDelete: () -> Unit,
    dragHandleModifier: Modifier,
) {
    val design = LocalGlassrackDesign.current
    val runtime = tile.serverId?.let { servers[it] }
    val metric = runtime?.latestMetric

    val isRpiFault = tile.tileType == TileType.RPI_HEALTH &&
        (metric?.rpiStats?.underVoltageDetected == true || metric?.rpiStats?.currentlyThrottled == true)
    val isRpiWarn = tile.tileType == TileType.RPI_HEALTH && (metric?.tempC ?: 0f) > 75f

    val (tonalContainer, explicitContentColor) = when (tile.tileType) {
        TileType.CLUSTER_AGGREGATE -> {
            if (alerts.isNotEmpty()) {
                design.statusWarnContainer to design.onStatusWarnContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            }
        }
        TileType.RPI_HEALTH -> when {
            isRpiFault -> design.statusFaultContainer to design.onStatusFaultContainer
            isRpiWarn -> design.statusWarnContainer to design.onStatusWarnContainer
            else -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        }
        TileType.PINNED_CONTAINER -> {
            val ctr = tile.serverId?.let { pinnedContainers["$it:${tile.containerName ?: "container"}"] }
            if (ctr?.state == ContainerState.RUNNING) {
                MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
            }
        }
        TileType.COMPOSITE_NODE -> when (runtime?.health) {
            ServerHealth.OFFLINE -> design.statusFaultContainer to design.onStatusFaultContainer
            ServerHealth.WARNING -> design.statusWarnContainer to design.onStatusWarnContainer
            else -> design.rackSurface to MaterialTheme.colorScheme.onSurface
        }
        TileType.SINGLE_METRIC -> {
            if (tile.metricKind == MetricKind.TEMP_C || tile.metricKind == MetricKind.NET_THROUGHPUT) {
                MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                design.rackSurfaceElevated to MaterialTheme.colorScheme.onSurface
            }
        }
        TileType.CUSTOM_COMMAND -> design.rackSurface to MaterialTheme.colorScheme.onSurface
    }

    val accentColor = when {
        tile.tileType == TileType.CLUSTER_AGGREGATE && alerts.isNotEmpty() -> design.statusWarn
        tile.tileType == TileType.RPI_HEALTH && isRpiFault -> design.statusFault
        tile.tileType == TileType.RPI_HEALTH && isRpiWarn -> design.statusWarn
        runtime != null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }

    ExpressiveCard(
        tonalContainerColor = tonalContainer,
        onClick = if (!isEditMode && runtime != null) {
            { onOpenServer(runtime.server.id) }
        } else {
            null
        },
    ) {
        CompositionLocalProvider(LocalContentColor provides explicitContentColor) {
            if (isEditMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = explicitContentColor.copy(alpha = 0.1f),
                        modifier = dragHandleModifier,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DragIndicator,
                                contentDescription = "Reorder",
                                modifier = Modifier.size(16.dp),
                            )
                            Text("Drag", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalIconButton(
                            onClick = onToggleSpan,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(Icons.Rounded.AspectRatio, contentDescription = "Resize", modifier = Modifier.size(16.dp))
                        }
                        FilledTonalIconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(30.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            when (tile.tileType) {
                TileType.CLUSTER_AGGREGATE -> {
                    val allMetrics = servers.values.mapNotNull { it.latestMetric }
                    val avgCpu = if (allMetrics.isEmpty()) 0f else allMetrics.map { it.cpuPct }.average().toFloat()
                    val online = servers.values.count { it.health == ServerHealth.ONLINE || it.health == ServerHealth.WARNING }
                    val runningCtrs = allMetrics.sumOf { it.runningContainers }
                    val totalCtrs = allMetrics.sumOf { it.totalContainers }
                    val usedMem = allMetrics.sumOf { it.memUsedBytes }
                    val totalMem = allMetrics.sumOf { it.memTotalBytes }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        WavyHeroGauge(
                            progress = avgCpu / 100f,
                            centerPrimary = Formatters.formatPercent(avgCpu),
                            centerSecondary = "CLUSTER CPU",
                            size = 94.dp,
                            color = accentColor,
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = tile.titleOverride.ifBlank { "Cluster Pulse" },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                ExpressiveShapeBadge(
                                    shape = ExpressiveBadgeShape.Cookie9Sided,
                                    containerColor = explicitContentColor.copy(alpha = 0.14f),
                                    contentColor = explicitContentColor,
                                    size = 28.dp,
                                    icon = if (alerts.isNotEmpty()) Icons.Rounded.Warning else Icons.Rounded.Verified,
                                )
                            }
                            Text(
                                text = "$online/${servers.size} Nodes Online",
                                style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "$runningCtrs/$totalCtrs Containers Up",
                                style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                            )
                            Text(
                                text = "RAM ${Formatters.formatBytes(usedMem, prefs.storageUnit)} / ${Formatters.formatBytes(totalMem, prefs.storageUnit)}",
                                style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                color = explicitContentColor.copy(alpha = 0.78f),
                            )
                        }
                    }
                }

                TileType.COMPOSITE_NODE -> {
                    if (runtime == null) {
                        Text("Node removed", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                StatusBeacon(health = runtime.health, size = 12.dp)
                                Column {
                                    Text(
                                        text = runtime.server.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${runtime.server.primaryHost} · ${metric?.latencyMs ?: 0}ms",
                                        style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                        color = explicitContentColor.copy(alpha = 0.74f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            FilledTonalIconButton(
                                onClick = { onOpenTerminal(runtime.server.id) },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(Icons.Rounded.Terminal, contentDescription = "SSH", modifier = Modifier.size(17.dp))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        WavyMetricBar(
                            label = "CPU",
                            valueText = Formatters.formatPercent(metric?.cpuPct ?: 0f),
                            fraction = (metric?.cpuPct ?: 0f) / 100f,
                            accentColor = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        WavyMetricBar(
                            label = "RAM",
                            valueText = "${Formatters.formatBytes(metric?.memUsedBytes ?: 0L, prefs.storageUnit)} / ${Formatters.formatBytes(metric?.memTotalBytes ?: 0L, prefs.storageUnit)}",
                            fraction = (metric?.memPct ?: 0f) / 100f,
                            accentColor = design.statusActive,
                        )
                        if (runtime.history.size >= 2) {
                            Spacer(Modifier.height(10.dp))
                            VicoSparkline(
                                values = runtime.history.map { it.cpuPct },
                                lineColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                            )
                        }
                    }
                }

                TileType.RPI_HEALTH -> {
                    val rpi = metric?.rpiStats
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = tile.titleOverride.ifBlank { runtime?.server?.name ?: "Raspberry Pi" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        ExpressiveShapeBadge(
                            shape = ExpressiveBadgeShape.Cookie7Sided,
                            containerColor = explicitContentColor.copy(alpha = 0.14f),
                            contentColor = explicitContentColor,
                            size = 34.dp,
                            icon = Icons.Rounded.Thermostat,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = Formatters.formatTemperature(metric?.tempC, prefs.temperatureUnit),
                        style = MaterialTheme.typography.displaySmall.merge(design.monoNumericStyle),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append(rpi?.cpuFreqMhz?.let { "$it MHz" } ?: "ARM SoC")
                            rpi?.fanRpm?.let { append(" · $it RPM") }
                        },
                        style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                        color = explicitContentColor.copy(alpha = 0.78f),
                    )
                    Spacer(Modifier.height(6.dp))
                    val throttleLabel = when {
                        rpi?.underVoltageDetected == true -> "UNDER-VOLT"
                        rpi?.currentlyThrottled == true -> "THROTTLED"
                        else -> rpi?.throttledRaw?.let { "POWER OK ($it)" } ?: "POWER OK"
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = explicitContentColor.copy(alpha = 0.14f),
                        contentColor = explicitContentColor,
                    ) {
                        Text(
                            text = throttleLabel,
                            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                    }
                }

                TileType.SINGLE_METRIC -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${runtime?.server?.name ?: "Node"} · ${tile.metricKind.name.replace("_", " ")}",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        ExpressiveShapeBadge(
                            shape = ExpressiveBadgeShape.Cookie9Sided,
                            containerColor = explicitContentColor.copy(alpha = 0.12f),
                            contentColor = explicitContentColor,
                            size = 30.dp,
                            icon = Icons.Rounded.Memory,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    val valueStr = when (tile.metricKind) {
                        MetricKind.CPU_PCT -> Formatters.formatPercent(metric?.cpuPct ?: 0f)
                        MetricKind.RAM_PCT -> Formatters.formatPercent(metric?.memPct ?: 0f)
                        MetricKind.DISK_PCT -> "${metric?.disks?.firstOrNull()?.usedPercent ?: 0}%"
                        MetricKind.TEMP_C -> Formatters.formatTemperature(metric?.tempC, prefs.temperatureUnit)
                        MetricKind.UPTIME -> Formatters.formatUptime(metric?.uptimeSec ?: 0L)
                        MetricKind.LOAD_AVG -> "${metric?.load1m ?: 0f}"
                        MetricKind.CONTAINER_COUNT -> "${metric?.runningContainers ?: 0}/${metric?.totalContainers ?: 0}"
                        MetricKind.NET_THROUGHPUT -> Formatters.formatBitrate(metric?.netRxBps ?: 0L)
                    }
                    Text(
                        text = valueStr,
                        style = MaterialTheme.typography.displaySmall.merge(design.monoNumericStyle),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                TileType.PINNED_CONTAINER -> {
                    val ctrName = tile.containerName ?: "container"
                    val ctr = tile.serverId?.let { pinnedContainers["$it:$ctrName"] }
                    val running = ctr?.state == ContainerState.RUNNING
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ExpressiveShapeBadge(
                                shape = ExpressiveBadgeShape.Cookie9Sided,
                                containerColor = explicitContentColor.copy(alpha = 0.14f),
                                contentColor = explicitContentColor,
                                size = 36.dp,
                                icon = Icons.Rounded.ViewInAr,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    StatusBeacon(
                                        health = if (running) ServerHealth.ONLINE else ServerHealth.OFFLINE,
                                        size = 9.dp,
                                    )
                                    Text(
                                        text = tile.titleOverride.ifBlank { ctrName },
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = "${runtime?.server?.name ?: ""} · ${ctr?.statusText ?: "Checking..."}",
                                    style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                    color = explicitContentColor.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (runtime != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilledTonalIconButton(
                                    enabled = !prefs.safeReadOnlyMode,
                                    onClick = {
                                        onContainerAction(
                                            runtime.server,
                                            ctrName,
                                            if (running) ContainerAction.STOP else ContainerAction.START,
                                        )
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                        contentDescription = if (running) "Stop" else "Start",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                FilledTonalIconButton(
                                    enabled = !prefs.safeReadOnlyMode,
                                    onClick = { onContainerAction(runtime.server, ctrName, ContainerAction.RESTART) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = "Restart",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                TileType.CUSTOM_COMMAND -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tile.titleOverride.ifBlank { "Custom Probe" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ExpressiveShapeBadge(
                            shape = ExpressiveBadgeShape.Cookie9Sided,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            size = 28.dp,
                            icon = Icons.Rounded.Terminal,
                        )
                    }
                    val probe = metric?.customResults?.firstOrNull { it.command == tile.customCommand }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = probe?.output?.ifBlank { "(pending poll)" } ?: "$ ${tile.customCommand.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TileBuilderModalSheet(
    servers: List<Server>,
    onAddTile: (TileType, String?, MetricKind, String, String?, String?, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(TileType.COMPOSITE_NODE) }
    var selectedServerId by remember { mutableStateOf(servers.firstOrNull()?.id) }
    var selectedMetric by remember { mutableStateOf(MetricKind.CPU_PCT) }
    var titleOverride by remember { mutableStateOf("") }
    var containerName by remember { mutableStateOf("") }
    var customCmd by remember { mutableStateOf("") }
    var spanX by remember { mutableIntStateOf(2) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Add Dashboard Tile", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Pin live gauges, Raspberry Pi health, or container controls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ExpressiveSectionHeader("Tile Type")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TileType.entries.forEach { t ->
                    DashboardPillChip(
                        label = t.name.replace("_", " "),
                        selected = selectedType == t,
                        onClick = { selectedType = t },
                    )
                }
            }

            if (selectedType != TileType.CLUSTER_AGGREGATE && servers.isNotEmpty()) {
                ExpressiveSectionHeader("Target Server")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    servers.forEach { srv ->
                        DashboardPillChip(
                            label = srv.name,
                            selected = selectedServerId == srv.id,
                            onClick = { selectedServerId = srv.id },
                        )
                    }
                }
            }

            if (selectedType == TileType.SINGLE_METRIC) {
                ExpressiveSectionHeader("Metric")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MetricKind.entries.forEach { m ->
                        DashboardPillChip(
                            label = m.name.replace("_", " "),
                            selected = selectedMetric == m,
                            onClick = { selectedMetric = m },
                        )
                    }
                }
            }

            val inputCount = 1 +
                (if (selectedType == TileType.PINNED_CONTAINER) 1 else 0) +
                (if (selectedType == TileType.CUSTOM_COMMAND) 1 else 0)
            var inputIdx = 0
            ExpressiveGroupedCard(itemSpacing = 2.dp) {
                ExpressiveGroupedItem(index = inputIdx++, count = inputCount) {
                    OutlinedTextField(
                        value = titleOverride,
                        onValueChange = { titleOverride = it },
                        label = { Text("Custom Title (optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (selectedType == TileType.PINNED_CONTAINER) {
                    ExpressiveGroupedItem(index = inputIdx++, count = inputCount) {
                        OutlinedTextField(
                            value = containerName,
                            onValueChange = { containerName = it },
                            label = { Text("Container Name (e.g. jellyfin)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (selectedType == TileType.CUSTOM_COMMAND) {
                    ExpressiveGroupedItem(index = inputIdx, count = inputCount) {
                        OutlinedTextField(
                            value = customCmd,
                            onValueChange = { customCmd = it },
                            label = { Text("Shell Command (e.g. pihole -c -e)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            ExpressiveSectionHeader("Grid Width")
            ExpressiveSegmentedPillGroup(
                options = listOf(1, 2),
                selected = spanX,
                onSelected = { spanX = it },
                label = { if (it == 1) "Half Width (1×1)" else "Full Width (2×1)" },
            )

            Button(
                onClick = {
                    onAddTile(selectedType, selectedServerId, selectedMetric, titleOverride, containerName, customCmd, spanX)
                },
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add Tile to Dashboard", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActiveJobsModalSheet(
    jobs: List<ActiveJob>,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    val design = LocalGlassrackDesign.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveSectionHeader(
                title = "Active & Recent Jobs",
                count = jobs.size,
                actionLabel = if (jobs.isNotEmpty()) "Clear Finished" else null,
                onAction = onClearFinished,
            )
            if (jobs.isEmpty()) {
                ExpressiveCard(
                    tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Text(
                        text = "No jobs executed yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(jobs, key = { _, job -> job.id }) { idx, job ->
                        val badgeColor = when (job.phase) {
                            JobPhase.SUCCEEDED -> design.statusOkContainer to design.onStatusOkContainer
                            JobPhase.FAILED -> design.statusFaultContainer to design.onStatusFaultContainer
                            else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                        }
                        ExpressiveGroupedItem(
                            index = idx,
                            count = jobs.size,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (job.phase == JobPhase.RUNNING || job.phase == JobPhase.CONNECTING) {
                                        LoadingIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    } else {
                                        ExpressiveShapeBadge(
                                            shape = ExpressiveBadgeShape.Cookie9Sided,
                                            containerColor = badgeColor.first,
                                            contentColor = badgeColor.second,
                                            size = 32.dp,
                                            icon = Icons.Rounded.Terminal,
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${job.title} · ${job.serverName}",
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "$ ${job.commandPreview}",
                                            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (job.phase != JobPhase.RUNNING && job.phase != JobPhase.CONNECTING) {
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = badgeColor.first,
                                        contentColor = badgeColor.second,
                                    ) {
                                        Text(
                                            text = "Exit ${job.exitCode ?: "—"} · ${Formatters.formatDuration(job.elapsedMs)}",
                                            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                            if (job.liveOutput.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = job.liveOutput.takeLast(320),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 6,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertsFeedModalSheet(
    alerts: List<AppAlert>,
    onOpenServer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val design = LocalGlassrackDesign.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveSectionHeader(title = "Active Alerts", count = alerts.size)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(alerts, key = { _, alert -> alert.id }) { idx, alert ->
                    val isFault = alert.severity == ServerHealth.OFFLINE
                    val bg = if (isFault) design.statusFaultContainer else design.statusWarnContainer
                    val fg = if (isFault) design.onStatusFaultContainer else design.onStatusWarnContainer
                    ExpressiveGroupedItem(
                        index = idx,
                        count = alerts.size,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        onClick = { onOpenServer(alert.serverId) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ExpressiveShapeBadge(
                                shape = ExpressiveBadgeShape.Cookie9Sided,
                                containerColor = bg,
                                contentColor = fg,
                                size = 40.dp,
                                icon = Icons.Rounded.NotificationsActive,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(alert.serverName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = alert.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
