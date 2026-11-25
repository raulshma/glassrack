package com.example.glassrack.feature.processes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.glassrack.core.designsystem.ActionSafetyDialog
import com.example.glassrack.core.designsystem.LocalGlassrackDesign
import com.example.glassrack.core.designsystem.RackBladeCard
import com.example.glassrack.core.designsystem.StatusBeacon
import com.example.glassrack.core.model.ProcessInfo
import com.example.glassrack.core.model.ProcessSignal
import com.example.glassrack.core.model.Server
import com.example.glassrack.core.model.ServerHealth
import com.example.glassrack.core.model.SystemdActiveState
import org.koin.compose.viewmodel.koinViewModel

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
            .filter { q.isEmpty() || it.command.lowercase().contains(q) || it.user.lowercase().contains(q) || it.pid.toString().contains(q) }
            .sortedWith(
                when (state.sort) {
                    ProcessSort.CPU -> compareByDescending<ProcessInfo> { it.cpuPct }
                    ProcessSort.MEMORY -> compareByDescending<ProcessInfo> { it.memPct }
                    ProcessSort.PID -> compareBy<ProcessInfo> { it.pid }
                    ProcessSort.COMMAND -> compareBy<ProcessInfo> { it.command.lowercase() }
                },
            )
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text("Search PID, user, or command...") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProcessSort.entries.forEach { s ->
                FilterChip(
                    selected = state.sort == s,
                    onClick = { viewModel.setSort(s) },
                    label = { Text("Sort: ${s.name}") },
                )
            }
            AssistChip(
                onClick = { viewModel.loadProcesses(server) },
                label = { Text("Refresh") },
                leadingIcon = { Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp)) },
            )
        }
        state.message?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearMessage) { Text("OK") }
                }
            }
        }
        if (state.isLoading) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(design.cardSpacing),
        ) {
            items(filtered, key = { it.pid }) { proc ->
                RackBladeCard(
                    accentColor = if (proc.cpuPct > 50f) design.statusWarn else design.statusActive,
                    onClick = { selectedProc = proc },
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("PID ${proc.pid} · ${proc.user}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text("CPU ${proc.cpuPct}% · MEM ${proc.memPct}%", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(proc.command, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("State: ${proc.state} · Elapsed: ${proc.elapsed}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    selectedProc?.let { proc ->
        ModalBottomSheet(onDismissRequest = { selectedProc = null }) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Process PID ${proc.pid}", style = MaterialTheme.typography.titleLarge)
                Text(proc.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Send Signal", style = MaterialTheme.typography.titleMedium)
                ProcessSignal.entries.forEach { sig ->
                    OutlinedButton(
                        enabled = !prefs.safeReadOnlyMode,
                        onClick = {
                            selectedProc = null
                            viewModel.requestSignal(server, proc, sig)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${sig.signalName} (${sig.code})")
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
    var showFailedOnly by remember { mutableStateOf(false) }

    LaunchedEffect(server.id) { viewModel.loadServicesAndCron(server) }

    val units = remember(state.units, showFailedOnly) {
        if (showFailedOnly) state.units.filter { it.activeState == SystemdActiveState.FAILED } else state.units
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = !showFailedOnly, onClick = { showFailedOnly = false }, label = { Text("All Units (${state.units.size})") })
            FilterChip(
                selected = showFailedOnly,
                onClick = { showFailedOnly = true },
                label = { Text("Failed (${state.units.count { it.activeState == SystemdActiveState.FAILED }})") },
            )
            AssistChip(
                onClick = { viewModel.loadServicesAndCron(server) },
                label = { Text("Refresh") },
                leadingIcon = { Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp)) },
            )
        }
        if (state.isLoading) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(design.cardSpacing),
        ) {
            items(units, key = { it.name }) { unit ->
                val health = when (unit.activeState) {
                    SystemdActiveState.ACTIVE -> ServerHealth.ONLINE
                    SystemdActiveState.FAILED -> ServerHealth.OFFLINE
                    SystemdActiveState.RELOADING, SystemdActiveState.ACTIVATING -> ServerHealth.CONNECTING
                    else -> ServerHealth.WARNING
                }
                RackBladeCard(
                    accentColor = when (health) {
                        ServerHealth.ONLINE -> design.statusOk
                        ServerHealth.OFFLINE -> design.statusFault
                        else -> design.statusWarn
                    },
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusBeacon(health = health, size = 12.dp)
                        Column(Modifier.weight(1f)) {
                            Text(unit.name, style = MaterialTheme.typography.titleMedium)
                            Text(unit.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { viewModel.openJournal(server, unit) }) {
                            Icon(Icons.Rounded.Description, contentDescription = "Journal")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(enabled = !prefs.safeReadOnlyMode, onClick = { viewModel.performServiceVerb(server, unit, "start") }) {
                            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Text("Start")
                        }
                        TextButton(enabled = !prefs.safeReadOnlyMode, onClick = { viewModel.performServiceVerb(server, unit, "restart") }) {
                            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                            Text("Restart")
                        }
                        TextButton(enabled = !prefs.safeReadOnlyMode, onClick = { viewModel.performServiceVerb(server, unit, "stop") }) {
                            Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(16.dp))
                            Text("Stop")
                        }
                    }
                }
            }
            if (state.crons.isNotEmpty()) {
                item(key = "cron-header") {
                    Text("SCHEDULED CRON JOBS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.crons) { cron ->
                    RackBladeCard {
                        Text(cron.schedule, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        Text(cron.command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    state.journalUnit?.let { unit ->
        ModalBottomSheet(onDismissRequest = viewModel::closeJournal) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("journalctl · ${unit.name}", style = MaterialTheme.typography.titleLarge)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    Text(
                        text = state.journalLogs,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}
