package com.example.glassrack.feature.servers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.glassrack.core.designsystem.ActionSafetyDialog
import com.example.glassrack.core.designsystem.LocalGlassrackDesign
import com.example.glassrack.core.designsystem.RackBladeCard
import com.example.glassrack.core.designsystem.StatusBeacon
import com.example.glassrack.core.designsystem.VicoSparkline
import com.example.glassrack.core.designsystem.VicoTelemetryChart
import com.example.glassrack.core.designsystem.WavyMetricBar
import com.example.glassrack.core.model.AuthType
import com.example.glassrack.core.model.Formatters
import com.example.glassrack.core.model.HostKeyChallenge
import com.example.glassrack.core.model.Server
import com.example.glassrack.core.model.ServerHealth
import com.example.glassrack.core.model.ServerRuntimeState
import com.example.glassrack.core.model.SshKeyRecord
import com.example.glassrack.core.model.UserPreferences
import com.example.glassrack.feature.containers.ContainersPane
import com.example.glassrack.feature.processes.ProcessesPane
import com.example.glassrack.feature.processes.ServicesAndCronPane
import org.koin.compose.viewmodel.koinViewModel
import kotlin.random.Random

@Composable
fun ServersScreen(
    selectedServerId: String?,
    onSelectServer: (String?) -> Unit,
    onOpenTerminal: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServersViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showAddEditWizard by remember { mutableStateOf<Server?>(null) }
    var isNewWizard by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 780.dp
        if (isWide) {
            Row(Modifier.fillMaxSize()) {
                ServersInventoryPane(
                    state = state,
                    selectedId = selectedServerId,
                    onSearch = viewModel::setSearch,
                    onTag = viewModel::setSelectedTag,
                    onSelect = onSelectServer,
                    onOpenTerminal = { onOpenTerminal(it, null) },
                    onWake = viewModel::wakeServer,
                    onAddClick = {
                        isNewWizard = true
                        showAddEditWizard = Server(
                            id = "srv-${Random.nextInt(10000, 99999)}",
                            name = "",
                            primaryHost = "",
                        )
                    },
                    modifier = Modifier.width(360.dp).fillMaxHeight(),
                )
                HorizontalDivider(modifier = Modifier.width(1.dp).fillMaxHeight())
                val activeRuntime = state.servers.firstOrNull { it.server.id == selectedServerId }
                    ?: state.servers.firstOrNull()
                if (activeRuntime != null) {
                    ServerDetailPane(
                        runtime = activeRuntime,
                        allState = state,
                        showBack = false,
                        onBack = {},
                        onEdit = {
                            isNewWizard = false
                            showAddEditWizard = activeRuntime.server
                        },
                        onDelete = {
                            viewModel.deleteServer(activeRuntime.server.id)
                            onSelectServer(null)
                        },
                        onOpenTerminal = onOpenTerminal,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        } else {
            val activeRuntime = selectedServerId?.let { id -> state.servers.firstOrNull { it.server.id == id } }
            if (activeRuntime == null) {
                ServersInventoryPane(
                    state = state,
                    selectedId = null,
                    onSearch = viewModel::setSearch,
                    onTag = viewModel::setSelectedTag,
                    onSelect = onSelectServer,
                    onOpenTerminal = { onOpenTerminal(it, null) },
                    onWake = viewModel::wakeServer,
                    onAddClick = {
                        isNewWizard = true
                        showAddEditWizard = Server(
                            id = "srv-${Random.nextInt(10000, 99999)}",
                            name = "",
                            primaryHost = "",
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ServerDetailPane(
                    runtime = activeRuntime,
                    allState = state,
                    showBack = true,
                    onBack = { onSelectServer(null) },
                    onEdit = {
                        isNewWizard = false
                        showAddEditWizard = activeRuntime.server
                    },
                    onDelete = {
                        viewModel.deleteServer(activeRuntime.server.id)
                        onSelectServer(null)
                    },
                    onOpenTerminal = onOpenTerminal,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    showAddEditWizard?.let { draft ->
        AddEditServerSheet(
            initial = draft,
            isNew = isNewWizard,
            allServers = state.servers.map { it.server },
            sshKeys = state.sshKeys,
            testStatus = state.testStatusMessage,
            isTesting = state.isTestingConnection,
            onTest = { srv, pw -> viewModel.testConnection(srv, pw, trustFirst = false) },
            onSave = { srv, pw ->
                viewModel.saveServer(srv, pw)
                showAddEditWizard = null
            },
            onDismiss = { showAddEditWizard = null },
        )
    }

    state.hostKeyChallenge?.let { challenge ->
        HostKeyChallengeDialog(
            challenge = challenge,
            onTrust = { viewModel.acceptChallenge(challenge) },
            onDismiss = viewModel::dismissChallenge,
        )
    }
}

@Composable
private fun ServersInventoryPane(
    state: ServersUiState,
    selectedId: String?,
    onSearch: (String) -> Unit,
    onTag: (String) -> Unit,
    onSelect: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onWake: (Server) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val design = LocalGlassrackDesign.current
    val allTags = remember(state.servers) {
        listOf("All") + state.servers.flatMap { listOf(it.server.groupTag) + it.server.tags }.distinct().sorted()
    }
    val filtered = remember(state.servers, state.searchQuery, state.selectedTag) {
        val q = state.searchQuery.trim().lowercase()
        state.servers.filter { rt ->
            val tagOk = state.selectedTag == "All" ||
                rt.server.groupTag == state.selectedTag ||
                state.selectedTag in rt.server.tags
            val qOk = q.isEmpty() ||
                rt.server.name.lowercase().contains(q) ||
                rt.server.primaryHost.lowercase().contains(q) ||
                rt.server.notes.lowercase().contains(q)
            tagOk && qOk
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Rack Nodes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${state.servers.size} configured servers", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onAddClick) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Node")
            }
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearch,
            placeholder = { Text("Search host, IP, or tag...") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            allTags.forEach { tag ->
                FilterChip(
                    selected = state.selectedTag == tag,
                    onClick = { onTag(tag) },
                    label = { Text(tag) },
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(design.cardSpacing),
        ) {
            items(filtered, key = { it.server.id }) { rt ->
                val metric = rt.latestMetric
                val accent = Color(rt.server.accentColorHex)
                RackBladeCard(
                    accentColor = accent,
                    highlightedBorder = if (rt.server.id == selectedId) MaterialTheme.colorScheme.primary else null,
                    onClick = { onSelect(rt.server.id) },
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                            StatusBeacon(health = rt.health, size = 14.dp)
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(rt.server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (rt.server.isSimulated) {
                                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                                            Text("DEMO", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                                        }
                                    }
                                }
                                Text(
                                    "${rt.server.username}@${rt.server.primaryHost}:${rt.server.port} · ${rt.server.groupTag}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row {
                            if (rt.health == ServerHealth.OFFLINE && rt.server.macAddress.isNotBlank()) {
                                IconButton(onClick = { onWake(rt.server) }) {
                                    Icon(Icons.Rounded.Bolt, contentDescription = "Wake on LAN", tint = design.statusWarn)
                                }
                            }
                            IconButton(onClick = { onOpenTerminal(rt.server.id) }) {
                                Icon(Icons.Rounded.Terminal, contentDescription = "SSH")
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    if (metric != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("CPU ${Formatters.formatPercent(metric.cpuPct)}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                            Text("RAM ${Formatters.formatPercent(metric.memPct)}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                            Text("TEMP ${Formatters.formatTemperature(metric.tempC, state.preferences.temperatureUnit)}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                            Text("CTR ${metric.runningContainers}/${metric.totalContainers}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                        }
                        if (rt.history.size >= 2) {
                            Spacer(Modifier.height(6.dp))
                            VicoSparkline(
                                values = rt.history.map { it.cpuPct },
                                lineColor = accent,
                                modifier = Modifier.fillMaxWidth().height(42.dp),
                            )
                        }
                    } else if (rt.lastError != null) {
                        Text(
                            text = "Offline: ${rt.lastError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = design.statusFault,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerDetailPane(
    runtime: ServerRuntimeState,
    allState: ServersUiState,
    showBack: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenTerminal: (String, String?) -> Unit,
    viewModel: ServersViewModel,
    modifier: Modifier = Modifier,
) {
    val server = runtime.server
    var selectedTab by remember(server.id) { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Containers", "Services", "Processes", "Net & Files")
    var confirmPowerAction by remember { mutableStateOf<Boolean?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (showBack) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
                StatusBeacon(health = runtime.health, size = 14.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(server.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${server.capabilities.distro.ifBlank { server.primaryHost }} · ${runtime.latestMetric?.latencyMs ?: 0}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row {
                IconButton(onClick = { onOpenTerminal(server.id, null) }) {
                    Icon(Icons.Rounded.Terminal, contentDescription = "Open SSH")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit Server")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete Server")
                }
            }
        }

        PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp) {
            tabs.forEachIndexed { idx, title ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = { Text(title) },
                )
            }
        }

        when (selectedTab) {
            0 -> ServerOverviewTab(
                runtime = runtime,
                prefs = allState.preferences,
                onWake = { viewModel.wakeServer(server) },
                onReboot = { confirmPowerAction = true },
                onShutdown = { confirmPowerAction = false },
            )
            1 -> ContainersPane(
                server = server,
                onOpenExecShell = { id, cmd -> onOpenTerminal(id, cmd) },
            )
            2 -> ServicesAndCronPane(server = server)
            3 -> ProcessesPane(server = server)
            4 -> NetworkAndFilesTab(
                server = server,
                tunnels = allState.tunnels.filter { it.serverId == server.id },
                activeTunnelIds = allState.activeTunnelIds,
                prefs = allState.preferences,
                viewModel = viewModel,
            )
        }
    }

    confirmPowerAction?.let { isReboot ->
        ActionSafetyDialog(
            title = if (isReboot) "Reboot ${server.name}?" else "Shutdown ${server.name}?",
            description = "This sends an immediate power signal to ${server.primaryHost}.",
            confirmationLevel = allState.preferences.destructiveConfirmation,
            onConfirm = {
                confirmPowerAction = null
                viewModel.rebootOrShutdown(server, isReboot)
            },
            onDismiss = { confirmPowerAction = null },
        )
    }
}

@Composable
private fun ServerOverviewTab(
    runtime: ServerRuntimeState,
    prefs: UserPreferences,
    onWake: () -> Unit,
    onReboot: () -> Unit,
    onShutdown: () -> Unit,
) {
    val design = LocalGlassrackDesign.current
    val metric = runtime.latestMetric
    val server = runtime.server
    val accent = Color(server.accentColorHex)

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(design.cardSpacing),
    ) {
        item {
            RackBladeCard(accentColor = accent) {
                Text("Compute & Memory", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                WavyMetricBar("CPU Total", Formatters.formatPercent(metric?.cpuPct ?: 0f), (metric?.cpuPct ?: 0f) / 100f, accentColor = accent)
                Spacer(Modifier.height(8.dp))
                WavyMetricBar(
                    "Memory",
                    "${Formatters.formatBytes(metric?.memUsedBytes ?: 0L, prefs.storageUnit)} / ${Formatters.formatBytes(metric?.memTotalBytes ?: 0L, prefs.storageUnit)}",
                    (metric?.memPct ?: 0f) / 100f,
                    accentColor = design.statusActive,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Load Avg: ${metric?.load1m ?: 0f} / ${metric?.load5m ?: 0f} / ${metric?.load15m ?: 0f} · Uptime: ${Formatters.formatUptime(metric?.uptimeSec ?: 0L)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (runtime.history.size >= 2) {
                    Spacer(Modifier.height(12.dp))
                    VicoTelemetryChart(
                        seriesList = listOf(
                            runtime.history.map { it.cpuPct } to accent,
                            runtime.history.map { it.memPct } to design.statusActive,
                        ),
                        modifier = Modifier.fillMaxWidth().height(170.dp),
                    )
                }
            }
        }

        metric?.rpiStats?.let { rpi ->
            item {
                RackBladeCard(accentColor = design.statusOk) {
                    Text("Raspberry Pi Hardware", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    if (rpi.boardModel.isNotBlank()) {
                        Text(rpi.boardModel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("SoC: ${Formatters.formatTemperature(rpi.socTempC?.toFloat(), prefs.temperatureUnit)}", fontFamily = FontFamily.Monospace)
                        Text("ARM: ${rpi.cpuFreqMhz ?: 0} MHz", fontFamily = FontFamily.Monospace)
                        rpi.fanRpm?.let { Text("Fan: $it RPM", fontFamily = FontFamily.Monospace) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Throttled Flags: ${rpi.throttledRaw ?: "0x0"} · Under-Voltage: ${rpi.underVoltageDetected} · Throttled: ${rpi.currentlyThrottled}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (rpi.underVoltageDetected || rpi.currentlyThrottled) design.statusFault else design.statusOk,
                    )
                }
            }
        }

        if (metric != null && metric.disks.isNotEmpty()) {
            item {
                RackBladeCard(accentColor = design.statusActive) {
                    Text("Storage & Mounts", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    metric.disks.forEach { disk ->
                        WavyMetricBar(
                            label = "${disk.mountPoint} (${disk.filesystem})",
                            valueText = "${Formatters.formatBytes(disk.usedBytes, prefs.storageUnit)} / ${Formatters.formatBytes(disk.totalBytes, prefs.storageUnit)} (${disk.usedPercent}%)",
                            fraction = disk.usedPercent / 100f,
                            accentColor = if (disk.usedPercent > 88) design.statusWarn else design.statusActive,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        if (metric != null && metric.gpus.isNotEmpty()) {
            item {
                RackBladeCard(accentColor = design.statusOk) {
                    Text("GPU Telemetry", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    metric.gpus.forEach { gpu ->
                        Text(
                            "${gpu.name} · ${gpu.utilizationPct}% util · VRAM ${gpu.vramUsedMb}/${gpu.vramTotalMb} MB · ${gpu.tempC}°C${gpu.powerWatts?.let { " · ${it}W" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        item {
            RackBladeCard(accentColor = design.statusWarn) {
                Text("Power & Wake-on-LAN", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onWake,
                        enabled = server.macAddress.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Wake")
                    }
                    OutlinedButton(
                        onClick = onReboot,
                        enabled = !prefs.safeReadOnlyMode,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.RestartAlt, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reboot")
                    }
                    OutlinedButton(
                        onClick = onShutdown,
                        enabled = !prefs.safeReadOnlyMode,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.PowerSettingsNew, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Shut")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkAndFilesTab(
    server: Server,
    tunnels: List<com.example.glassrack.core.model.PortForwardTunnel>,
    activeTunnelIds: Set<String>,
    prefs: UserPreferences,
    viewModel: ServersViewModel,
) {
    val netFiles by viewModel.netFilesState.collectAsState()
    val design = LocalGlassrackDesign.current
    var showNewTunnel by remember { mutableStateOf(false) }

    LaunchedEffect(server.id) {
        viewModel.loadNetworkAndFiles(server, "/")
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(design.cardSpacing),
    ) {
        netFiles.statusMessage?.let { msg ->
            item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::clearMessage) { Text("OK") }
                    }
                }
            }
        }

        item {
            RackBladeCard(accentColor = design.statusActive) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("SSH Port Forward Tunnels", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showNewTunnel = true }) { Text("+ Tunnel") }
                }
                if (tunnels.isEmpty()) {
                    Text("Forward remote admin UIs (e.g. 8096, 8123, 8006) securely over SSH.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    tunnels.forEach { tun ->
                        val active = tun.id in activeTunnelIds
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${tun.label}: localhost:${tun.localPort} -> ${tun.remoteHost}:${tun.remotePort}", style = MaterialTheme.typography.labelMedium)
                            Switch(checked = active, onCheckedChange = { viewModel.toggleTunnel(tun, server) })
                        }
                    }
                }
            }
        }

        item {
            RackBladeCard(accentColor = design.statusOk) {
                Text("Network Interfaces & Open Ports", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                netFiles.interfaces.forEach { iface ->
                    Text(
                        "${iface.name}: ${iface.ipv4.ifBlank { "—" }} · RX ${Formatters.formatBytes(iface.rxBytes, prefs.storageUnit)} · TX ${Formatters.formatBytes(iface.txBytes, prefs.storageUnit)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (netFiles.ports.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    netFiles.ports.take(10).forEach { p ->
                        Text("${p.protocol.uppercase()} :${p.port} (${p.processName})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            RackBladeCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("SFTP Config Browser", style = MaterialTheme.typography.titleMedium)
                        Text(netFiles.currentPath, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                    if (netFiles.currentPath != "/") {
                        TextButton(onClick = {
                            val parent = netFiles.currentPath.substringBeforeLast('/').ifEmpty { "/" }
                            viewModel.loadNetworkAndFiles(server, parent)
                        }) { Text("Up") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                netFiles.files.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            Icon(if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile, null, modifier = Modifier.size(18.dp))
                            Column {
                                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${entry.permissions} · ${entry.sizeBytes} B", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(onClick = {
                            if (entry.isDirectory) {
                                viewModel.loadNetworkAndFiles(server, entry.fullPath)
                            } else {
                                viewModel.openRemoteFile(server, entry.fullPath)
                            }
                        }) {
                            Text(if (entry.isDirectory) "Open" else "Edit")
                        }
                    }
                }
            }
        }
    }

    netFiles.openedFilePath?.let { path ->
        ModalBottomSheet(onDismissRequest = viewModel::closeRemoteFile) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Editing $path", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                Text("Saving automatically creates a .bak backup.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = netFiles.openedFileDraft,
                    onValueChange = viewModel::updateFileDraft,
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = viewModel::closeRemoteFile) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !prefs.safeReadOnlyMode, onClick = { viewModel.saveRemoteFile(server) }) {
                        Text("Save + Backup")
                    }
                }
            }
        }
    }

    if (showNewTunnel) {
        var label by remember { mutableStateOf("Web UI") }
        var localP by remember { mutableStateOf("8096") }
        var remoteP by remember { mutableStateOf("8096") }
        AlertDialog(
            onDismissRequest = { showNewTunnel = false },
            title = { Text("Add SSH Tunnel") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") })
                    OutlinedTextField(value = localP, onValueChange = { localP = it }, label = { Text("Local Port") })
                    OutlinedTextField(value = remoteP, onValueChange = { remoteP = it }, label = { Text("Remote Port") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createTunnel(server.id, label, localP.toIntOrNull() ?: 8096, "127.0.0.1", remoteP.toIntOrNull() ?: 8096)
                    showNewTunnel = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showNewTunnel = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddEditServerSheet(
    initial: Server,
    isNew: Boolean,
    allServers: List<Server>,
    sshKeys: List<SshKeyRecord>,
    testStatus: String?,
    isTesting: Boolean,
    onTest: (Server, String?) -> Unit,
    onSave: (Server, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var groupTag by remember { mutableStateOf(initial.groupTag) }
    var primaryHost by remember { mutableStateOf(initial.primaryHost) }
    var fallbackHost by remember { mutableStateOf(initial.fallbackHost) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    var username by remember { mutableStateOf(initial.username) }
    var authType by remember { mutableStateOf(initial.authType) }
    var selectedKeyId by remember { mutableStateOf(initial.sshKeyId ?: sshKeys.firstOrNull()?.id) }
    var password by remember { mutableStateOf("") }
    var macAddress by remember { mutableStateOf(initial.macAddress) }
    var jumpHostId by remember { mutableStateOf(initial.jumpHostServerId) }
    var pollInterval by remember { mutableStateOf(initial.pollIntervalSec.toString()) }
    var notes by remember { mutableStateOf(initial.notes) }
    var isSimulated by remember { mutableStateOf(if (isNew) false else initial.isSimulated) }

    fun buildServer(): Server = initial.copy(
        name = name.trim().ifBlank { primaryHost.trim() },
        groupTag = groupTag.trim().ifBlank { "Homelab" },
        primaryHost = primaryHost.trim(),
        fallbackHost = fallbackHost.trim(),
        port = portText.toIntOrNull() ?: 22,
        username = username.trim().ifBlank { "root" },
        authType = authType,
        sshKeyId = if (authType == AuthType.KEY) selectedKeyId else null,
        macAddress = macAddress.trim(),
        jumpHostServerId = jumpHostId,
        pollIntervalSec = (pollInterval.toIntOrNull() ?: 5).coerceIn(2, 300),
        notes = notes.trim(),
        isSimulated = isSimulated,
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (isNew) "Onboard SSH Node" else "Edit ${initial.name}", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Node Name (e.g. rpi5-core.lan)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = primaryHost, onValueChange = { primaryHost = it }, label = { Text("Primary LAN Host / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = fallbackHost, onValueChange = { fallbackHost = it }, label = { Text("Fallback Tailscale / DDNS Host (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("SSH User") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = portText, onValueChange = { portText = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.width(100.dp))
            }
            OutlinedTextField(value = groupTag, onValueChange = { groupTag = it }, label = { Text("Rack Group / Tag") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Authentication", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthType.entries.forEach { t ->
                    FilterChip(selected = authType == t, onClick = { authType = t }, label = { Text(t.name) })
                }
            }
            if (authType == AuthType.KEY) {
                if (sshKeys.isEmpty()) {
                    Text("No SSH keys in vault yet — create or import one in Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sshKeys.forEach { k ->
                            FilterChip(
                                selected = selectedKeyId == k.id,
                                onClick = { selectedKeyId = k.id },
                                leadingIcon = { Icon(Icons.Rounded.Key, null, modifier = Modifier.size(16.dp)) },
                                label = { Text(k.alias) },
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (stored in Tink Keystore Vault)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(value = macAddress, onValueChange = { macAddress = it }, label = { Text("MAC Address for Wake-on-LAN (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pollInterval, onValueChange = { pollInterval = it }, label = { Text("Telemetry Poll Interval (sec)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())

            val otherNodes = allServers.filter { it.id != initial.id }
            if (otherNodes.isNotEmpty()) {
                Text("SSH Jump Host / Bastion (optional)", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = jumpHostId == null, onClick = { jumpHostId = null }, label = { Text("Direct") })
                    otherNodes.forEach { j ->
                        FilterChip(selected = jumpHostId == j.id, onClick = { jumpHostId = j.id }, label = { Text(j.name) })
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Simulated Demo Mode Node", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = isSimulated, onCheckedChange = { isSimulated = it })
            }

            if (isTesting) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            testStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onTest(buildServer(), password.takeIf { it.isNotEmpty() }) },
                    enabled = primaryHost.isNotBlank() && !isTesting,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Lan, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Test SSH")
                }
                Button(
                    onClick = { onSave(buildServer(), password.takeIf { it.isNotEmpty() }) },
                    enabled = primaryHost.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save Node")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HostKeyChallengeDialog(
    challenge: HostKeyChallenge,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    val design = LocalGlassrackDesign.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Rounded.Security,
                contentDescription = null,
                tint = if (challenge.isMismatch) design.statusFault else design.statusWarn,
            )
        },
        title = {
            Text(if (challenge.isMismatch) "HOST KEY CHANGED (MITM WARNING)" else "Verify SSH Host Fingerprint")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Host: ${challenge.host}:${challenge.port} (${challenge.keyType})")
                Text("Received: ${challenge.fingerprintSha256}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                challenge.expectedFingerprint?.let {
                    Text("Expected: $it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = design.statusFault)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onTrust,
                colors = if (challenge.isMismatch) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) {
                Text(if (challenge.isMismatch) "Override & Trust" else "Trust Fingerprint")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Block") }
        },
    )
}
