package com.example.glassrack.feature.overview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.glassrack.core.designsystem.ActiveJobsPill
import com.example.glassrack.core.designsystem.LocalGlassrackDesign
import com.example.glassrack.core.designsystem.RackBladeCard
import com.example.glassrack.core.designsystem.SafeModeBadge
import com.example.glassrack.core.designsystem.StatusBeacon
import com.example.glassrack.core.designsystem.VicoSparkline
import com.example.glassrack.core.designsystem.WavyHeroGauge
import com.example.glassrack.core.designsystem.WavyMetricBar
import com.example.glassrack.core.model.ContainerAction
import com.example.glassrack.core.model.ContainerInfo
import com.example.glassrack.core.model.ContainerState
import com.example.glassrack.core.model.DashboardTile
import com.example.glassrack.core.model.Formatters
import com.example.glassrack.core.model.JobPhase
import com.example.glassrack.core.model.MetricKind
import com.example.glassrack.core.model.ServerHealth
import com.example.glassrack.core.model.ServerRuntimeState
import com.example.glassrack.core.model.TileType
import com.example.glassrack.core.model.UserPreferences
import org.koin.compose.viewmodel.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OverviewScreen(
    onOpenServerDetail: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val design = LocalGlassrackDesign.current
    val gridState = rememberLazyGridState()
    val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
        viewModel.moveTile(from.index, to.index)
    }

    val runningJobsCount = state.activeJobs.count { it.phase == JobPhase.RUNNING || it.phase == JobPhase.CONNECTING }

    Column(modifier = modifier.fillMaxSize()) {
        // Top Header + Status Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Glassrack",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                val onlineCount = state.servers.values.count { it.health == ServerHealth.ONLINE || it.health == ServerHealth.WARNING }
                Text(
                    text = "$onlineCount/${state.servers.size} nodes online · Prismatic Rack",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SafeModeBadge(
                    isSafeMode = state.preferences.safeReadOnlyMode,
                    onToggle = viewModel::toggleSafeMode,
                )
                IconButton(onClick = viewModel::toggleEditMode) {
                    Icon(
                        imageVector = if (state.isEditMode) Icons.Rounded.Close else Icons.Rounded.Edit,
                        contentDescription = "Edit dashboard",
                        tint = if (state.isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Active Jobs + Refresh + Alerts Strip
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
                shape = MaterialTheme.shapes.extraLarge,
                color = design.rackSurfaceElevated,
                onClick = viewModel::refreshAll,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(14.dp))
                    Text("Poll Now", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (state.alerts.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = design.statusWarn.copy(alpha = 0.2f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Rounded.Warning, null, modifier = Modifier.size(14.dp), tint = design.statusWarn)
                        Text("${state.alerts.size} Alert(s)", style = MaterialTheme.typography.labelMedium, color = design.statusWarn)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Multi-Dashboard Switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.dashboards.forEach { dash ->
                FilterChip(
                    selected = dash.id == state.selectedDashboardId,
                    onClick = { viewModel.selectDashboard(dash.id) },
                    label = { Text(dash.title) },
                )
            }
            FilterChip(
                selected = false,
                onClick = { viewModel.setShowNewDashboardDialog(true) },
                label = { Text("+ Profile") },
            )
            if (state.isEditMode) {
                FilterChip(
                    selected = true,
                    onClick = { viewModel.setShowTilePicker(true) },
                    label = { Text("+ Add Tile") },
                )
            }
        }

        AnimatedVisibility(visible = state.statusBanner != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(state.statusBanner.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearStatusBanner) { Text("OK") }
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(design.cardSpacing),
            verticalArrangement = Arrangement.spacedBy(design.cardSpacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                items = state.tiles,
                key = { it.id },
                span = { GridItemSpan(it.spanX.coerceIn(1, 2)) },
            ) { tile ->
                ReorderableItem(reorderableState, key = tile.id) { isDragging ->
                    Box {
                        DashboardTileCard(
                            tile = tile,
                            servers = state.servers,
                            pinnedContainers = state.pinnedContainerStates,
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

    if (state.showNewDashboardDialog) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.setShowNewDashboardDialog(false) },
            title = { Text("New Dashboard Profile") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Dashboard Name (e.g. Pi Cluster)") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.createDashboard(title) }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setShowNewDashboardDialog(false) }) { Text("Cancel") }
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
        ModalBottomSheet(onDismissRequest = { viewModel.setShowActiveJobsSheet(false) }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Active & Recent Jobs", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = viewModel::clearCompletedJobs) { Text("Clear Finished") }
                }
                if (state.activeJobs.isEmpty()) {
                    Text("No jobs executed yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.activeJobs, key = { it.id }) { job ->
                            RackBladeCard(
                                accentColor = when (job.phase) {
                                    JobPhase.SUCCEEDED -> design.statusOk
                                    JobPhase.FAILED -> design.statusFault
                                    else -> design.statusActive
                                },
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("${job.title} · ${job.serverName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    if (job.phase == JobPhase.RUNNING) {
                                        LoadingIndicator(modifier = Modifier.size(18.dp))
                                    } else {
                                        Text("Exit ${job.exitCode ?: "—"} · ${Formatters.formatDuration(job.elapsedMs)}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Text("$ ${job.commandPreview}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                if (job.liveOutput.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(job.liveOutput.takeLast(320), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun DashboardTileCard(
    tile: DashboardTile,
    servers: Map<String, ServerRuntimeState>,
    pinnedContainers: Map<String, ContainerInfo>,
    prefs: UserPreferences,
    isEditMode: Boolean,
    onOpenServer: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onContainerAction: (com.example.glassrack.core.model.Server, String, ContainerAction) -> Unit,
    onToggleSpan: () -> Unit,
    onDelete: () -> Unit,
    dragHandleModifier: Modifier,
) {
    val design = LocalGlassrackDesign.current
    val runtime = tile.serverId?.let { servers[it] }
    val metric = runtime?.latestMetric
    val accentColor = runtime?.let { Color(it.server.accentColorHex) } ?: MaterialTheme.colorScheme.primary

    RackBladeCard(
        accentColor = accentColor,
        onClick = if (!isEditMode && runtime != null) {
            { onOpenServer(runtime.server.id) }
        } else null,
    ) {
        if (isEditMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragIndicator,
                    contentDescription = "Reorder",
                    modifier = dragHandleModifier.size(20.dp),
                )
                Row {
                    IconButton(onClick = onToggleSpan, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.AspectRatio, contentDescription = "Resize", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
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
                        size = 92.dp,
                        color = accentColor,
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tile.titleOverride.ifBlank { "Cluster Pulse" }, style = MaterialTheme.typography.titleMedium)
                        Text("$online/${servers.size} Nodes Online", style = MaterialTheme.typography.labelMedium, color = design.statusOk)
                        Text("$runningCtrs/$totalCtrs Containers Up", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "RAM ${Formatters.formatBytes(usedMem, prefs.storageUnit)} / ${Formatters.formatBytes(totalMem, prefs.storageUnit)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusBeacon(health = runtime.health, size = 12.dp)
                            Column {
                                Text(runtime.server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${runtime.server.primaryHost} · ${metric?.latencyMs ?: 0}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onOpenTerminal(runtime.server.id) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Rounded.Terminal, contentDescription = "SSH", modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    WavyMetricBar(
                        label = "CPU",
                        valueText = Formatters.formatPercent(metric?.cpuPct ?: 0f),
                        fraction = (metric?.cpuPct ?: 0f) / 100f,
                        accentColor = accentColor,
                    )
                    Spacer(Modifier.height(6.dp))
                    WavyMetricBar(
                        label = "RAM",
                        valueText = "${Formatters.formatBytes(metric?.memUsedBytes ?: 0L, prefs.storageUnit)} / ${Formatters.formatBytes(metric?.memTotalBytes ?: 0L, prefs.storageUnit)}",
                        fraction = (metric?.memPct ?: 0f) / 100f,
                        accentColor = design.statusActive,
                    )
                    if (runtime.history.size >= 2) {
                        Spacer(Modifier.height(8.dp))
                        VicoSparkline(
                            values = runtime.history.map { it.cpuPct },
                            lineColor = accentColor,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                        )
                    }
                }
            }

            TileType.RPI_HEALTH -> {
                val rpi = metric?.rpiStats
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Thermostat, null, tint = accentColor, modifier = Modifier.size(16.dp))
                    Text(
                        text = tile.titleOverride.ifBlank { runtime?.server?.name ?: "Raspberry Pi" },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = Formatters.formatTemperature(metric?.tempC, prefs.temperatureUnit),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if ((metric?.tempC ?: 0f) > 75f) design.statusWarn else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildString {
                        append(rpi?.cpuFreqMhz?.let { "${it} MHz" } ?: "ARM SoC")
                        rpi?.fanRpm?.let { append(" · $it RPM") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val throttleLabel = when {
                    rpi?.underVoltageDetected == true -> "UNDER-VOLT!"
                    rpi?.currentlyThrottled == true -> "THROTTLED"
                    else -> "POWER OK (0x0)"
                }
                Text(
                    text = throttleLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rpi?.underVoltageDetected == true || rpi?.currentlyThrottled == true) design.statusFault else design.statusOk,
                )
            }

            TileType.SINGLE_METRIC -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Memory, null, tint = accentColor, modifier = Modifier.size(16.dp))
                    Text(
                        text = "${runtime?.server?.name ?: "Node"} · ${tile.metricKind.name.replace("_", " ")}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
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
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusBeacon(health = if (running) ServerHealth.ONLINE else ServerHealth.OFFLINE, size = 12.dp)
                        Column {
                            Text(tile.titleOverride.ifBlank { ctrName }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${runtime?.server?.name ?: ""} · ${ctr?.statusText ?: "Checking..."}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (runtime != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                enabled = !prefs.safeReadOnlyMode,
                                onClick = {
                                    onContainerAction(
                                        runtime.server,
                                        ctrName,
                                        if (running) ContainerAction.STOP else ContainerAction.START,
                                    )
                                },
                            ) {
                                Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                            }
                            IconButton(
                                enabled = !prefs.safeReadOnlyMode,
                                onClick = { onContainerAction(runtime.server, ctrName, ContainerAction.RESTART) },
                            ) {
                                Icon(Icons.Rounded.Refresh, null)
                            }
                        }
                    }
                }
            }

            TileType.CUSTOM_COMMAND -> {
                Text(tile.titleOverride.ifBlank { "Custom Probe" }, style = MaterialTheme.typography.labelLarge, color = accentColor)
                val probe = metric?.customResults?.firstOrNull { it.command == tile.customCommand }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = probe?.output?.ifBlank { "(pending poll)" } ?: "Command: ${tile.customCommand.orEmpty()}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TileBuilderModalSheet(
    servers: List<com.example.glassrack.core.model.Server>,
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add Dashboard Tile", style = MaterialTheme.typography.titleLarge)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TileType.entries.forEach { t ->
                    FilterChip(
                        selected = selectedType == t,
                        onClick = { selectedType = t },
                        label = { Text(t.name.replace("_", " ")) },
                    )
                }
            }
            if (selectedType != TileType.CLUSTER_AGGREGATE && servers.isNotEmpty()) {
                Text("Target Server", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    servers.forEach { srv ->
                        FilterChip(
                            selected = selectedServerId == srv.id,
                            onClick = { selectedServerId = srv.id },
                            label = { Text(srv.name) },
                        )
                    }
                }
            }
            if (selectedType == TileType.SINGLE_METRIC) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetricKind.entries.forEach { m ->
                        FilterChip(selected = selectedMetric == m, onClick = { selectedMetric = m }, label = { Text(m.name) })
                    }
                }
            }
            OutlinedTextField(
                value = titleOverride,
                onValueChange = { titleOverride = it },
                label = { Text("Custom Title (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (selectedType == TileType.PINNED_CONTAINER) {
                OutlinedTextField(
                    value = containerName,
                    onValueChange = { containerName = it },
                    label = { Text("Container Name (e.g. jellyfin)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (selectedType == TileType.CUSTOM_COMMAND) {
                OutlinedTextField(
                    value = customCmd,
                    onValueChange = { customCmd = it },
                    label = { Text("Shell Command (e.g. pihole -c -e)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = spanX == 1, onClick = { spanX = 1 }, label = { Text("Half Width (1×1)") })
                FilterChip(selected = spanX == 2, onClick = { spanX = 2 }, label = { Text("Full Width (2×1)") })
            }
            Button(
                onClick = {
                    onAddTile(selectedType, selectedServerId, selectedMetric, titleOverride, containerName, customCmd, spanX)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Add, null)
                Text("Add Tile to Dashboard")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
