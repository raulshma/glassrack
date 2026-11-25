package com.example.glassrack.feature.processes

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SettingsApplications
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.glassrack.core.designsystem.ActionSafetyDialog
import com.example.glassrack.core.designsystem.ExpressiveBadgeShape
import com.example.glassrack.core.designsystem.ExpressiveCard
import com.example.glassrack.core.designsystem.ExpressiveEmptyState
import com.example.glassrack.core.designsystem.ExpressiveGroupedItem
import com.example.glassrack.core.designsystem.ExpressiveIconTile
import com.example.glassrack.core.designsystem.ExpressiveSearchField
import com.example.glassrack.core.designsystem.ExpressiveSectionHeader
import com.example.glassrack.core.designsystem.ExpressiveSegmentedPillGroup
import com.example.glassrack.core.designsystem.ExpressiveShapeBadge
import com.example.glassrack.core.designsystem.LocalGlassrackDesign
import com.example.glassrack.core.designsystem.expressiveHorizontalGroupShape
import com.example.glassrack.core.designsystem.monoNumericStyle
import com.example.glassrack.core.model.CronEntry
import com.example.glassrack.core.model.ProcessInfo
import com.example.glassrack.core.model.ProcessSignal
import com.example.glassrack.core.model.Server
import com.example.glassrack.core.model.SystemdActiveState
import com.example.glassrack.core.model.SystemdUnit
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

private data class ConnectedPillAction(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProcessesPane(
    server: Server,
    modifier: Modifier = Modifier,
    viewModel: ProcessesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val prefs by viewModel.preferences.collectAsState()
    val design = LocalGlassrackDesign.current
    var selectedProc by remember { mutableStateOf<ProcessInfo?>(null) }

    LaunchedEffect(server.id) { viewModel.loadProcesses(server) }

    val filtered = remember(state.processes, state.query, state.sort) {
        val q = state.query.trim().lowercase()
        state.processes
            .filter {
                q.isEmpty() ||
                    it.command.lowercase().contains(q) ||
                    it.user.lowercase().contains(q) ||
                    it.pid.toString().contains(q)
            }
            .sortedWith(
                when (state.sort) {
                    ProcessSort.CPU -> compareByDescending<ProcessInfo> { it.cpuPct }
                    ProcessSort.MEMORY -> compareByDescending<ProcessInfo> { it.memPct }
                    ProcessSort.PID -> compareBy<ProcessInfo> { it.pid }
                    ProcessSort.COMMAND -> compareBy<ProcessInfo> { it.command.lowercase() }
                },
            )
    }

    val sortOptions = remember { listOf(ProcessSort.CPU, ProcessSort.MEMORY, ProcessSort.PID) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExpressiveSearchField(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                placeholder = "Search PID, user, or command...",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExpressiveSegmentedPillGroup(
                    options = sortOptions,
                    selected = if (state.sort in sortOptions) state.sort else ProcessSort.CPU,
                    onSelected = viewModel::setSort,
                    label = { sort ->
                        when (sort) {
                            ProcessSort.CPU -> "CPU"
                            ProcessSort.MEMORY -> "MEM"
                            ProcessSort.PID -> "PID"
                            ProcessSort.COMMAND -> "CMD"
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = { viewModel.loadProcesses(server) },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh processes",
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Refresh", maxLines = 1)
                }
            }
        }

        ProcessFeedbackBanner(
            message = state.message,
            onDismiss = viewModel::clearMessage,
        )

        if (state.isLoading) {
            LinearWavyProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        if (filtered.isEmpty() && !state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                val hasQuery = state.query.isNotBlank()
                ExpressiveEmptyState(
                    icon = Icons.Rounded.Memory,
                    title = if (hasQuery) "No Matching Processes" else "No Processes Loaded",
                    description = if (hasQuery) {
                        "No running processes match '${state.query.trim()}'."
                    } else {
                        "Active process telemetry from ${server.name} will appear here."
                    },
                    actionLabel = if (hasQuery) "Clear Search" else "Refresh",
                    onAction = {
                        if (hasQuery) viewModel.setQuery("") else viewModel.loadProcesses(server)
                    },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                item(key = "processes-header") {
                    ExpressiveSectionHeader(
                        title = "Processes",
                        count = filtered.size,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                itemsIndexed(filtered, key = { _, proc -> proc.pid }) { index, proc ->
                    ProcessGroupedRow(
                        index = index,
                        count = filtered.size,
                        proc = proc,
                        safeReadOnlyMode = prefs.safeReadOnlyMode,
                        onInspect = { selectedProc = proc },
                        onSignal = { sig -> viewModel.requestSignal(server, proc, sig) },
                    )
                }
            }
        }
    }

    selectedProc?.let { proc ->
        ModalBottomSheet(onDismissRequest = { selectedProc = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Process PID ${proc.pid}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "${proc.user} · state ${proc.state} · ${proc.elapsed}",
                            style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = proc.command,
                            style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                        )
                    }
                }
                ExpressiveSectionHeader(title = "Send Signal")
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    ProcessSignal.entries.forEachIndexed { index, sig ->
                        ExpressiveGroupedItem(
                            index = index,
                            count = ProcessSignal.entries.size,
                            containerColor = if (sig.isDestructive && !prefs.safeReadOnlyMode) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            onClick = if (!prefs.safeReadOnlyMode) {
                                {
                                    selectedProc = null
                                    viewModel.requestSignal(server, proc, sig)
                                }
                            } else null,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = sig.signalName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "SIG ${sig.code}",
                                    style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    state.pendingSignal?.let { (proc, signal) ->
        ActionSafetyDialog(
            title = "Send ${signal.signalName} to PID ${proc.pid}?",
            description = "Command: ${proc.command}\nThis action may terminate or suspend the running process.",
            confirmationLevel = prefs.destructiveConfirmation,
            onConfirm = { viewModel.confirmSignal(server) },
            onDismiss = viewModel::dismissSignal,
        )
    }
}

@Composable
private fun ProcessGroupedRow(
    index: Int,
    count: Int,
    proc: ProcessInfo,
    safeReadOnlyMode: Boolean,
    onInspect: () -> Unit,
    onSignal: (ProcessSignal) -> Unit,
) {
    val design = LocalGlassrackDesign.current
    val cpuWarn = proc.cpuPct >= 50f
    val memWarn = proc.memPct >= 50f

    ExpressiveGroupedItem(
        index = index,
        count = count,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onInspect,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = proc.command,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "PID ${proc.pid} · ${proc.user} · ${proc.state} · ${proc.elapsed}",
                    style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TonalMetricPill(
                    text = "CPU ${formatPctOneDecimal(proc.cpuPct)}",
                    containerColor = if (cpuWarn) design.statusWarnContainer else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (cpuWarn) design.onStatusWarnContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                )
                TonalMetricPill(
                    text = "MEM ${formatPctOneDecimal(proc.memPct)}",
                    containerColor = if (memWarn) design.statusWarnContainer else MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = if (memWarn) design.onStatusWarnContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            ConnectedActionPillRow(
                actions = listOf(
                    ConnectedPillAction(
                        label = "TERM",
                        enabled = !safeReadOnlyMode,
                        isDestructive = false,
                        onClick = { onSignal(ProcessSignal.SIGTERM) },
                    ),
                    ConnectedPillAction(
                        label = "KILL",
                        enabled = !safeReadOnlyMode,
                        isDestructive = true,
                        onClick = { onSignal(ProcessSignal.SIGKILL) },
                    ),
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ServicesAndCronPane(
    server: Server,
    modifier: Modifier = Modifier,
    viewModel: ProcessesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val prefs by viewModel.preferences.collectAsState()
    val design = LocalGlassrackDesign.current
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(server.id) { viewModel.loadServicesAndCron(server) }

    val filteredUnits = remember(state.units, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            state.units
        } else {
            state.units.filter {
                it.name.lowercase().contains(q) ||
                    it.description.lowercase().contains(q) ||
                    it.activeState.name.lowercase().contains(q)
            }
        }
    }

    val filteredCrons = remember(state.crons, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            state.crons
        } else {
            state.crons.filter {
                it.command.lowercase().contains(q) ||
                    it.schedule.lowercase().contains(q) ||
                    it.source.lowercase().contains(q)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search systemd units or cron jobs...",
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = { viewModel.loadServicesAndCron(server) },
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh services and cron",
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Refresh", maxLines = 1)
            }
        }

        ProcessFeedbackBanner(
            message = state.message,
            onDismiss = viewModel::clearMessage,
        )

        if (state.isLoading) {
            LinearWavyProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        if (filteredUnits.isEmpty() && filteredCrons.isEmpty() && !state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                val hasQuery = searchQuery.isNotBlank()
                ExpressiveEmptyState(
                    icon = Icons.Rounded.SettingsApplications,
                    title = if (hasQuery) "No Matching Services or Crons" else "No Units or Cron Jobs",
                    description = if (hasQuery) {
                        "No systemd units or cron entries match '${searchQuery.trim()}'."
                    } else {
                        "Systemd units and scheduled cron jobs discovered on ${server.name} will appear here."
                    },
                    actionLabel = if (hasQuery) "Clear Search" else "Refresh",
                    onAction = {
                        if (hasQuery) searchQuery = "" else viewModel.loadServicesAndCron(server)
                    },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                item(key = "units-header") {
                    ExpressiveSectionHeader(
                        title = "Systemd Units",
                        count = filteredUnits.size,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                if (filteredUnits.isEmpty()) {
                    item(key = "units-empty") {
                        ExpressiveCard(
                            tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Text(
                                text = "No matching systemd units.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    itemsIndexed(filteredUnits, key = { _, unit -> "unit-${unit.name}" }) { index, unit ->
                        SystemdUnitGroupedRow(
                            index = index,
                            count = filteredUnits.size,
                            unit = unit,
                            safeReadOnlyMode = prefs.safeReadOnlyMode,
                            onVerb = { verb -> viewModel.performServiceVerb(server, unit, verb) },
                            onLogs = { viewModel.openJournal(server, unit) },
                        )
                    }
                }

                item(key = "cron-header") {
                    ExpressiveSectionHeader(
                        title = "Scheduled Cron Jobs",
                        count = state.crons.size,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    )
                }

                if (filteredCrons.isEmpty()) {
                    item(key = "crons-empty") {
                        ExpressiveCard(
                            tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Text(
                                text = if (state.crons.isEmpty()) {
                                    "No scheduled cron jobs found on this host."
                                } else {
                                    "No cron jobs match '${searchQuery.trim()}'."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    itemsIndexed(filteredCrons, key = { idx, cron -> "cron-$idx-${cron.schedule}-${cron.command}" }) { index, cron ->
                        CronGroupedRow(
                            index = index,
                            count = filteredCrons.size,
                            cron = cron,
                        )
                    }
                }
            }
        }
    }

    state.journalUnit?.let { unit ->
        ModalBottomSheet(onDismissRequest = viewModel::closeJournal) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "journalctl · ${unit.name}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (unit.description.isNotBlank()) {
                            Text(
                                text = unit.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    FilledTonalIconButton(
                        onClick = { viewModel.openJournal(server, unit) },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Reload journalctl")
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                ) {
                    Text(
                        text = state.journalLogs,
                        style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                        modifier = Modifier
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SystemdUnitGroupedRow(
    index: Int,
    count: Int,
    unit: SystemdUnit,
    safeReadOnlyMode: Boolean,
    onVerb: (String) -> Unit,
    onLogs: () -> Unit,
) {
    val design = LocalGlassrackDesign.current
    val (badgeShape, badgeBg, badgeFg, badgeIcon) = when (unit.activeState) {
        SystemdActiveState.ACTIVE -> Quad(
            ExpressiveBadgeShape.Cookie9Sided,
            design.statusOkContainer,
            design.onStatusOkContainer,
            Icons.Rounded.PlayArrow,
        )
        SystemdActiveState.FAILED -> Quad(
            ExpressiveBadgeShape.SoftBurst,
            design.statusFaultContainer,
            design.onStatusFaultContainer,
            Icons.Rounded.Error,
        )
        SystemdActiveState.RELOADING,
        SystemdActiveState.ACTIVATING,
        SystemdActiveState.DEACTIVATING -> Quad(
            ExpressiveBadgeShape.Sunny,
            design.statusActiveContainer,
            design.onStatusActiveContainer,
            Icons.Rounded.Sync,
        )
        SystemdActiveState.INACTIVE,
        SystemdActiveState.UNKNOWN -> Quad(
            ExpressiveBadgeShape.Diamond,
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Rounded.Stop,
        )
    }

    val isActive = unit.activeState == SystemdActiveState.ACTIVE
    val actions = listOf(
        if (isActive) {
            ConnectedPillAction(
                label = "Stop",
                icon = Icons.Rounded.Stop,
                enabled = !safeReadOnlyMode,
                isDestructive = true,
                onClick = { onVerb("stop") },
            )
        } else {
            ConnectedPillAction(
                label = "Start",
                icon = Icons.Rounded.PlayArrow,
                enabled = !safeReadOnlyMode,
                isDestructive = false,
                onClick = { onVerb("start") },
            )
        },
        ConnectedPillAction(
            label = "Restart",
            icon = Icons.Rounded.Refresh,
            enabled = !safeReadOnlyMode,
            isDestructive = false,
            onClick = { onVerb("restart") },
        ),
        ConnectedPillAction(
            label = "Logs",
            icon = Icons.Rounded.Description,
            enabled = true,
            isDestructive = false,
            onClick = onLogs,
        ),
    )

    ExpressiveGroupedItem(
        index = index,
        count = count,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ExpressiveShapeBadge(
                shape = badgeShape,
                containerColor = badgeBg,
                contentColor = badgeFg,
                size = 40.dp,
                icon = badgeIcon,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = unit.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TonalMetricPill(
                        text = unit.activeState.name.lowercase(),
                        containerColor = badgeBg,
                        contentColor = badgeFg,
                    )
                }
                if (unit.description.isNotBlank()) {
                    Text(
                        text = unit.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        ConnectedActionPillRow(
            actions = actions,
            modifier = Modifier.fillMaxWidth(),
            fillWidth = true,
        )
    }
}

@Composable
private fun CronGroupedRow(
    index: Int,
    count: Int,
    cron: CronEntry,
) {
    val design = LocalGlassrackDesign.current
    ExpressiveGroupedItem(
        index = index,
        count = count,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ExpressiveIconTile(
                icon = Icons.Rounded.Schedule,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                size = 38.dp,
                iconSize = 19.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TonalMetricPill(
                        text = cron.schedule,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (cron.source.isNotBlank()) {
                        Text(
                            text = cron.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = cron.command,
                    style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ProcessFeedbackBanner(
    message: String?,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(visible = message != null) {
        ExpressiveCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            tonalContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun TonalMetricPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    val design = LocalGlassrackDesign.current
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ConnectedActionPillRow(
    actions: List<ConnectedPillAction>,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
) {
    Row(
        modifier = if (fillWidth) modifier else modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        actions.forEachIndexed { index, action ->
            val shape = expressiveHorizontalGroupShape(index, actions.size)
            val containerColor = when {
                !action.enabled -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                action.isDestructive -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            }
            val contentColor = when {
                !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                action.isDestructive -> MaterialTheme.colorScheme.onErrorContainer
                else -> MaterialTheme.colorScheme.onSurface
            }
            Surface(
                onClick = action.onClick,
                enabled = action.enabled,
                shape = shape,
                color = containerColor,
                contentColor = contentColor,
                modifier = if (fillWidth) Modifier.weight(1f) else Modifier,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (action.icon != null) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

private fun formatPctOneDecimal(pct: Float): String {
    val rounded = (pct.coerceAtLeast(0f) * 10f).roundToInt() / 10f
    return if (rounded % 1f == 0f) "${rounded.toInt()}%" else "$rounded%"
}
