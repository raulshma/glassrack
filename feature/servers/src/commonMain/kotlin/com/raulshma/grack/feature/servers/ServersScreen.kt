package com.raulshma.grack.feature.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raulshma.grack.core.designsystem.ActionSafetyDialog
import com.raulshma.grack.core.designsystem.ExpressiveBadgeShape
import com.raulshma.grack.core.designsystem.ExpressiveCard
import com.raulshma.grack.core.designsystem.ExpressiveEmptyState
import com.raulshma.grack.core.designsystem.ExpressiveGroupedCard
import com.raulshma.grack.core.designsystem.ExpressiveGroupedItem
import com.raulshma.grack.core.designsystem.ExpressiveHeroCard
import com.raulshma.grack.core.designsystem.ExpressiveIconTile
import com.raulshma.grack.core.designsystem.ExpressiveSearchField
import com.raulshma.grack.core.designsystem.ExpressiveSectionHeader
import com.raulshma.grack.core.designsystem.ExpressiveSegmentedPillGroup
import com.raulshma.grack.core.designsystem.ExpressiveShapeBadge
import com.raulshma.grack.core.designsystem.HoldToConfirmPill
import com.raulshma.grack.core.designsystem.LocalGlassrackDesign
import com.raulshma.grack.core.designsystem.StatusBeacon
import com.raulshma.grack.core.designsystem.VicoSparkline
import com.raulshma.grack.core.designsystem.VicoTelemetryChart
import com.raulshma.grack.core.designsystem.WavyMetricBar
import com.raulshma.grack.core.designsystem.WindowWidths
import com.raulshma.grack.core.designsystem.expressiveHorizontalGroupShape
import com.raulshma.grack.core.designsystem.monoNumericStyle
import com.raulshma.grack.core.model.AuthType
import com.raulshma.grack.core.model.Formatters
import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.PortForwardTunnel
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.StorageUnit
import com.raulshma.grack.core.model.ServerHealth
import com.raulshma.grack.core.model.ServerRuntimeState
import com.raulshma.grack.core.model.SshKeyRecord
import com.raulshma.grack.core.model.SudoAuth
import com.raulshma.grack.core.model.UpdatePolicy
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.model.requiresConfirmation
import com.raulshma.grack.feature.containers.ContainersPane
import com.raulshma.grack.feature.processes.ProcessesPane
import com.raulshma.grack.feature.processes.ServicesAndCronPane
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import org.koin.compose.viewmodel.koinViewModel
import kotlin.random.Random

@Composable
fun ServersScreen(
    selectedServerId: String?,
    onSelectServer: (String?) -> Unit,
    onOpenTerminal: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    initialOpenAddSheet: Boolean = false,
    onConsumedOpenAddSheet: () -> Unit = {},
    /** Destructive widget tap staged for in-app confirmation (Phase 8); null when none. */
    pendingWidgetContainerAction: WidgetPendingContainerAction? = null,
    onConsumePendingWidgetAction: () -> Unit = {},
    viewModel: ServersViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showAddEditWizard by remember { mutableStateOf<Server?>(null) }
    var isNewWizard by remember { mutableStateOf(false) }

    LaunchedEffect(initialOpenAddSheet) {
        if (initialOpenAddSheet) {
            isNewWizard = true
            showAddEditWizard = Server(
                id = "srv-${Random.nextInt(10000, 99999)}",
                name = "",
                primaryHost = "",
                // New nodes default to the global poll interval preference.
                pollIntervalSec = state.preferences.globalPollIntervalSec,
            )
            onConsumedOpenAddSheet()
        }
    }

    // A staged widget action whose node no longer exists can never surface; drop it quietly.
    LaunchedEffect(state.servers) {
        viewModel.clearStalePendingWidgetAction(state.servers.map { it.server.id }.toSet())
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= WindowWidths.twoPaneSplit
        if (isWide) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.width(360.dp).fillMaxHeight(),
                ) {
                    ServersInventoryPane(
                        state = state,
                        selectedId = selectedServerId,
                        onSearch = viewModel::setSearch,
                        onTag = viewModel::setSelectedTag,
                        onSelect = onSelectServer,
                        onAddClick = {
                            isNewWizard = true
                            showAddEditWizard = Server(
                                id = "srv-${Random.nextInt(10000, 99999)}",
                                name = "",
                                primaryHost = "",
                                pollIntervalSec = state.preferences.globalPollIntervalSec,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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
                        pendingWidgetAction = pendingWidgetContainerAction,
                        onConsumePendingWidgetAction = onConsumePendingWidgetAction,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                } else {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ExpressiveEmptyState(
                            icon = Icons.Rounded.Dns,
                            title = "Select or Add a Node",
                            description = "Choose a server from the inventory or onboard a new SSH host to inspect live telemetry.",
                            actionLabel = "Onboard SSH Node",
                            onAction = {
                                isNewWizard = true
                                showAddEditWizard = Server(
                                    id = "srv-${Random.nextInt(10000, 99999)}",
                                    name = "",
                                    primaryHost = "",
                                    pollIntervalSec = state.preferences.globalPollIntervalSec,
                                )
                            },
                        )
                    }
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
                    onAddClick = {
                        isNewWizard = true
                        showAddEditWizard = Server(
                            id = "srv-${Random.nextInt(10000, 99999)}",
                            name = "",
                            primaryHost = "",
                            pollIntervalSec = state.preferences.globalPollIntervalSec,
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
                    pendingWidgetAction = pendingWidgetContainerAction,
                    onConsumePendingWidgetAction = onConsumePendingWidgetAction,
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
            sudoSecretServerIds = state.sudoSecretServerIds,
            testStatus = state.testStatusMessage,
            isTesting = state.isTestingConnection,
            onTest = { srv, pw -> viewModel.testConnection(srv, pw, trustFirst = false) },
            onSave = { srv, secrets ->
                viewModel.saveServer(srv, secrets)
                showAddEditWizard = null
            },
            onDismiss = { showAddEditWizard = null },
        )
    }

    state.hostKeyChallenge?.let { challenge ->
        HostKeyChallengeDialog(
            challenge = challenge,
            onTrust = { viewModel.acceptChallenge(challenge) },
            onDismiss = { viewModel.dismissChallenge(challenge) },
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
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val design = LocalGlassrackDesign.current
    val allTags = remember(state.servers) {
        listOf("All") + state.servers.flatMap { listOf(it.server.groupTag) + it.server.tags }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
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
                rt.server.groupTag.lowercase().contains(q) ||
                rt.server.tags.any { it.lowercase().contains(q) } ||
                rt.server.notes.lowercase().contains(q)
            tagOk && qOk
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "Nodes",
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = "${state.servers.size} nodes",
                        style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            FilledTonalButton(
                onClick = onAddClick,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Node")
            }
        }

        ExpressiveSearchField(
            query = state.searchQuery,
            onQueryChange = onSearch,
            placeholder = "Search nodes, tags, or hosts...",
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (allTags.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                allTags.forEach { tag ->
                    val selected = state.selectedTag == tag
                    Surface(
                        onClick = { onTag(tag) },
                        shape = RoundedCornerShape(999.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.height(10.dp))
        }

        when {
            state.servers.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    ExpressiveEmptyState(
                        icon = Icons.Rounded.Dns,
                        title = "No Nodes Configured",
                        description = "Add a Linux server, Raspberry Pi, or Docker host over SSH to start monitoring.",
                        actionLabel = "Onboard SSH Node",
                        onAction = onAddClick,
                    )
                }
            }
            filtered.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    ExpressiveCard(
                        tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Text(
                            text = "No Matching Nodes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "No configured nodes match your current search or tag filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = {
                                onSearch("")
                                onTag("All")
                            },
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text("Clear Filters")
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    itemsIndexed(filtered, key = { _, rt -> rt.server.id }) { index, rt ->
                        val metric = rt.latestMetric
                        val isSelected = rt.server.id == selectedId
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }
                        val contentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        val mutedColor = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }

                        ExpressiveGroupedItem(
                            index = index,
                            count = filtered.size,
                            containerColor = containerColor,
                            onClick = { onSelect(rt.server.id) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                StatusBeacon(health = rt.health, size = 12.dp)
                                ExpressiveIconTile(
                                    icon = if (rt.server.capabilities.isRaspberryPi) {
                                        Icons.Rounded.DeveloperBoard
                                    } else {
                                        Icons.Rounded.Dns
                                    },
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    },
                                    contentColor = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    size = 38.dp,
                                    iconSize = 19.dp,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = rt.server.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = contentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${rt.server.username}@${rt.server.primaryHost}:${rt.server.port}",
                                        style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                        color = mutedColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (metric != null) {
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        MetricPillBadge(
                                            text = "${metric.latencyMs}ms",
                                            containerColor = if (isSelected) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                            },
                                            contentColor = contentColor,
                                        )
                                        MetricPillBadge(
                                            text = "CPU ${Formatters.formatPercent(metric.cpuPct)}",
                                            containerColor = if (isSelected) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                            } else {
                                                MaterialTheme.colorScheme.secondaryContainer
                                            },
                                            contentColor = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            },
                                        )
                                    }
                                }
                            }

                            if (metric != null && rt.history.size >= 2) {
                                Spacer(Modifier.height(8.dp))
                                VicoSparkline(
                                    values = rt.history.map { it.cpuPct },
                                    lineColor = Color(rt.server.accentColorHex),
                                    modifier = Modifier.fillMaxWidth().height(34.dp),
                                )
                            } else if (rt.lastError != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Offline: ${rt.lastError}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) contentColor else design.statusFault,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
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
    pendingWidgetAction: WidgetPendingContainerAction?,
    onConsumePendingWidgetAction: () -> Unit,
    viewModel: ServersViewModel,
    modifier: Modifier = Modifier,
) {
    val design = LocalGlassrackDesign.current
    val server = runtime.server
    var selectedTab by remember(server.id) { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Containers", "Processes", "Services", "Net & Files")
    var confirmPowerAction by remember { mutableStateOf<Boolean?>(null) }
    var confirmDeleteNode by remember { mutableStateOf(false) }

    // Phase 5 (request #4): collapsing detail header. One state + one connection cover all
    // five detail tabs; showing a different node resets the header to expanded.
    val settleScope = rememberCoroutineScope()
    val headerState = remember(server.id, settleScope) { CollapsingDetailHeaderState(settleScope) }
    val headerConnection = remember(headerState) { headerState.nestedScrollConnection() }

    val heroContainerColor = when (runtime.health) {
        ServerHealth.OFFLINE -> MaterialTheme.colorScheme.errorContainer
        ServerHealth.WARNING -> design.statusWarnContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val heroContentColor = when (runtime.health) {
        ServerHealth.OFFLINE -> MaterialTheme.colorScheme.onErrorContainer
        ServerHealth.WARNING -> design.onStatusWarnContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val heroBadgeIcon = when (runtime.health) {
        ServerHealth.ONLINE -> Icons.Rounded.CheckCircle
        ServerHealth.WARNING -> Icons.Rounded.Warning
        ServerHealth.OFFLINE -> Icons.Rounded.CloudOff
        ServerHealth.CONNECTING -> Icons.Rounded.Sync
        ServerHealth.UNKNOWN -> Icons.Rounded.Dns
    }
    val subtitleText = remember(server) {
        listOfNotNull(
            server.primaryHost.takeIf { it.isNotBlank() },
            server.capabilities.distro.takeIf { it.isNotBlank() },
            server.capabilities.arch.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }
    val heroActions = buildList {
        add(HeroActionItem("SSH", Icons.Rounded.Terminal) { onOpenTerminal(server.id, null) })
        add(HeroActionItem("Poll", Icons.Rounded.Refresh) { viewModel.pollServer(server) })
        if (server.macAddress.isNotBlank()) {
            add(HeroActionItem("Wake", Icons.Rounded.Bolt) { viewModel.wakeServer(server) })
        }
        add(HeroActionItem("Edit", Icons.Rounded.Edit, onClick = onEdit))
        add(
            HeroActionItem(
                label = "Delete",
                icon = Icons.Rounded.Delete,
                isDestructive = true,
                onClick = {
                    if (requiresConfirmation(actionIsDestructive = true, prefs = allState.preferences)) {
                        confirmDeleteNode = true
                    } else {
                        onDelete()
                    }
                },
            ),
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Blocked host-key challenge: the dialog is dismissed, but the node keeps failing its
        // polls until the key is reviewed — surface a persistent banner instead of silence.
        runtime.pendingHostKeyChallenge?.let { challenge ->
            if (challenge == allState.dismissedHostKeyChallenge) {
                ExpressiveCard(
                    tonalContainerColor = design.statusWarnContainer,
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Security,
                            contentDescription = null,
                            tint = design.onStatusWarnContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Host key needs review — connections to ${server.name} stay blocked",
                            style = MaterialTheme.typography.bodySmall,
                            color = design.onStatusWarnContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.reviewHostKey(challenge) }) {
                            Text("Review host key")
                        }
                    }
                }
            }
        }
        if (showBack) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalIconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Back to Nodes",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Phase 5: the hero collapses before the active pane's LazyColumn scrolls and expands
        // again from leftover scroll at the top (NestedScrollConnection below); a spring
        // settles mid-way stops, and a tab switch or compact-row tap expands it. Heights are
        // measured, so this works unchanged in the phone and the >=780dp split layout.
        CollapsingDetailHeader(
            state = headerState,
            heroActions = heroActions,
            runtime = runtime,
            containerColor = heroContainerColor,
            contentColor = heroContentColor,
            badgeIcon = heroBadgeIcon,
            hero = { actions ->
                ExpressiveHeroCard(
                    headline = server.name,
                    subtitle = subtitleText.ifBlank { "${server.username}@${server.primaryHost}:${server.port}" },
                    eyebrow = buildString {
                        append(server.groupTag)
                        append(" · ")
                        append(runtime.health.name)
                        runtime.latestMetric?.let { append(" · ${it.latencyMs}ms") }
                    },
                    containerColor = heroContainerColor,
                    contentColor = heroContentColor,
                    badgeIcon = heroBadgeIcon,
                    badgeShape = ExpressiveBadgeShape.Cookie9Sided,
                    actions = actions,
                )
            },
        )

        // Hero feedback (Phase 1C item 4): Wake/Poll/Reboot results land in the shared
        // status message; surface them here so they are not only visible in the edit sheet.
        AnimatedVisibility(visible = allState.testStatusMessage != null) {
            allState.testStatusMessage?.let { msg ->
                val isFailed = msg.startsWith("Failed", ignoreCase = true) ||
                    msg.startsWith("WoL error", ignoreCase = true) ||
                    msg.startsWith("Poll failed", ignoreCase = true) ||
                    msg.startsWith("Blocked", ignoreCase = true)
                Surface(
                    color = if (isFailed) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = if (isFailed) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearMessage) {
                            Text("OK")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { idx, title ->
                val isSelected = selectedTab == idx
                Surface(
                    // Phase 5: switching tabs re-expands the collapsing header.
                    onClick = {
                        selectedTab = idx
                        headerState.expandAnimated()
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
        }

        // One nested scroll connection for all five tabs: whichever LazyColumn is active
        // dispatches through this container and drives the collapsing header above. No
        // callbacks into the panes (the v4 approach, dropped in v5).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(headerConnection),
        ) {
            when (selectedTab) {
                0 -> ServerOverviewTab(
                    runtime = runtime,
                    prefs = allState.preferences,
                    onWake = { viewModel.wakeServer(server) },
                    onReboot = {
                        if (requiresConfirmation(actionIsDestructive = true, prefs = allState.preferences)) {
                            confirmPowerAction = true
                        } else {
                            viewModel.rebootOrShutdown(server, reboot = true)
                        }
                    },
                    onShutdown = {
                        if (requiresConfirmation(actionIsDestructive = true, prefs = allState.preferences)) {
                            confirmPowerAction = false
                        } else {
                            viewModel.rebootOrShutdown(server, reboot = false)
                        }
                    },
                    onOpenTerminal = { cmd -> onOpenTerminal(server.id, cmd) },
                )
                1 -> ContainersPane(
                    server = server,
                    onOpenExecShell = { id, cmd -> onOpenTerminal(id, cmd) },
                )
                2 -> ProcessesPane(server = server)
                3 -> ServicesAndCronPane(server = server)
                4 -> NetworkAndFilesTab(
                    server = server,
                    tunnels = allState.tunnels.filter { it.serverId == server.id },
                    activeTunnelIds = allState.activeTunnelIds,
                    prefs = allState.preferences,
                    viewModel = viewModel,
                )
            }
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

    // Phase 8: a destructive widget tap deep-linked here with the action staged; the
    // in-app dialog completes the UX the widget cannot provide. Execution (or the BLOCKED
    // audit on dismiss) happens from this side; dismissing also clears the deep-link marker.
    if (pendingWidgetAction != null && pendingWidgetAction.serverId == server.id) {
        ActionSafetyDialog(
            title = "${pendingWidgetAction.action.label} ${pendingWidgetAction.containerName}?",
            description = "Requested from a home-screen widget. This will " +
                "${pendingWidgetAction.action.label.lowercase()} container " +
                "'${pendingWidgetAction.containerName}' on ${server.name}.",
            confirmationLevel = allState.preferences.destructiveConfirmation,
            onConfirm = {
                viewModel.confirmPendingWidgetAction(server)
                onConsumePendingWidgetAction()
            },
            onDismiss = {
                viewModel.dismissPendingWidgetAction(server)
                onConsumePendingWidgetAction()
            },
        )
    }

    if (confirmDeleteNode) {
        ActionSafetyDialog(
            title = "Delete ${server.name}?",
            description = "This removes ${server.primaryHost} and its stored configuration from GRack.",
            confirmationLevel = allState.preferences.destructiveConfirmation,
            onConfirm = {
                confirmDeleteNode = false
                onDelete()
            },
            onDismiss = { confirmDeleteNode = false },
        )
    }
}

/**
 * Compact (collapsed) detail header, Phase 5: node name, Cookie9Sided health badge, host +
 * latency, the SSH action, and an overflow menu holding the remaining hero actions. The
 * pinned tab pills below never collapse away. Tapping the row (outside the SSH pill and the
 * overflow) re-expands the hero; interactive elements stay disabled while the row is still
 * fading in so neither header half steals taps mid-transition.
 */
@Composable
internal fun CompactDetailHeaderRow(
    runtime: ServerRuntimeState,
    containerColor: Color,
    contentColor: Color,
    badgeIcon: ImageVector,
    sshAction: HeroActionItem?,
    overflowActions: List<HeroActionItem>,
    isInteractive: Boolean,
    onExpand: () -> Unit,
    onMeasuredHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val design = LocalGlassrackDesign.current
    val server = runtime.server
    val latency = runtime.latestMetric?.latencyMs
    var overflowExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onMeasuredHeight(it.height) }
            .clickable(enabled = isInteractive, onClick = onExpand)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpressiveShapeBadge(
            shape = ExpressiveBadgeShape.Cookie9Sided,
            containerColor = containerColor,
            contentColor = contentColor,
            size = 30.dp,
            icon = badgeIcon,
            contentDescription = runtime.health.name.lowercase().replaceFirstChar { it.uppercase() },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(server.username)
                    append("@")
                    append(server.primaryHost)
                    latency?.let { append(" · ${it}ms") }
                },
                style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (sshAction != null) {
            Surface(
                onClick = sshAction.onClick,
                enabled = isInteractive,
                shape = RoundedCornerShape(999.dp),
                color = contentColor.copy(alpha = 0.14f),
                contentColor = contentColor,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = sshAction.icon,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = sshAction.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
        Box {
            IconButton(
                onClick = { overflowExpanded = true },
                enabled = isInteractive,
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "More actions for ${server.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = overflowExpanded,
                onDismissRequest = { overflowExpanded = false },
            ) {
                overflowActions.forEach { action ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = action.label,
                                color = if (action.isDestructive) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    Color.Unspecified
                                },
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                tint = if (action.isDestructive) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        onClick = {
                            overflowExpanded = false
                            action.onClick()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerOverviewTab(
    runtime: ServerRuntimeState,
    prefs: UserPreferences,
    onWake: () -> Unit,
    onReboot: () -> Unit,
    onShutdown: () -> Unit,
    onOpenTerminal: (String?) -> Unit,
) {
    val design = LocalGlassrackDesign.current
    val metric = runtime.latestMetric
    val server = runtime.server
    val accent = Color(server.accentColorHex)
    val primaryDisk = remember(metric?.disks) {
        metric?.disks?.firstOrNull { it.mountPoint == "/" } ?: metric?.disks?.maxByOrNull { it.usedPercent }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Paired 2-column tonal stat cards
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OverviewStatTile(
                        label = "CPU",
                        value = Formatters.formatPercent(metric?.cpuPct ?: 0f),
                        subtitle = "Load ${metric?.load1m ?: 0f}",
                        icon = Icons.Rounded.Speed,
                        badgeShape = ExpressiveBadgeShape.Cookie9Sided,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    OverviewStatTile(
                        label = "Memory",
                        value = Formatters.formatPercent(metric?.memPct ?: 0f),
                        subtitle = "${Formatters.formatBytes(metric?.memUsedBytes ?: 0L, prefs.storageUnit)} / ${Formatters.formatBytes(metric?.memTotalBytes ?: 0L, prefs.storageUnit)}",
                        icon = Icons.Rounded.Memory,
                        badgeShape = ExpressiveBadgeShape.Cookie7Sided,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OverviewStatTile(
                        label = "Disk",
                        value = primaryDisk?.let { "${it.usedPercent}%" } ?: "—",
                        subtitle = primaryDisk?.let {
                            "${it.mountPoint} · ${Formatters.formatBytes(it.usedBytes, prefs.storageUnit)}"
                        } ?: "No mounts",
                        icon = Icons.Rounded.Storage,
                        badgeShape = ExpressiveBadgeShape.SoftBurst,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    OverviewStatTile(
                        label = "Temp",
                        value = Formatters.formatTemperature(metric?.tempC, prefs.temperatureUnit),
                        subtitle = "Up ${Formatters.formatUptime(metric?.uptimeSec ?: 0L)}",
                        icon = Icons.Rounded.Thermostat,
                        badgeShape = ExpressiveBadgeShape.Sunny,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Live Telemetry
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpressiveSectionHeader(title = "Live Telemetry")
                ExpressiveCard(tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    WavyMetricBar(
                        label = "CPU Total",
                        valueText = Formatters.formatPercent(metric?.cpuPct ?: 0f),
                        fraction = (metric?.cpuPct ?: 0f) / 100f,
                        accentColor = accent,
                    )
                    Spacer(Modifier.height(10.dp))
                    WavyMetricBar(
                        label = "Memory",
                        valueText = "${Formatters.formatBytes(metric?.memUsedBytes ?: 0L, prefs.storageUnit)} / ${Formatters.formatBytes(metric?.memTotalBytes ?: 0L, prefs.storageUnit)}",
                        fraction = (metric?.memPct ?: 0f) / 100f,
                        accentColor = design.statusActive,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Load ${metric?.load1m ?: 0f} / ${metric?.load5m ?: 0f} / ${metric?.load15m ?: 0f} · Uptime ${Formatters.formatUptime(metric?.uptimeSec ?: 0L)}",
                        style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
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
        }

        // Disk Mounts
        if (metric != null && metric.disks.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpressiveSectionHeader(title = "Disk Mounts", count = metric.disks.size)
                    ExpressiveGroupedCard {
                        metric.disks.forEachIndexed { index, disk ->
                            ExpressiveGroupedItem(
                                index = index,
                                count = metric.disks.size,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                WavyMetricBar(
                                    label = "${disk.mountPoint} (${disk.filesystem})",
                                    valueText = "${Formatters.formatBytes(disk.usedBytes, prefs.storageUnit)} / ${Formatters.formatBytes(disk.totalBytes, prefs.storageUnit)} (${disk.usedPercent}%)",
                                    fraction = disk.usedPercent / 100f,
                                    accentColor = if (disk.usedPercent > 88) design.statusWarn else design.statusActive,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Raspberry Pi Hardware
        metric?.rpiStats?.let { rpi ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpressiveSectionHeader(title = "Raspberry Pi Hardware")
                    val rpiRows = buildList<Pair<String, String>> {
                        if (rpi.boardModel.isNotBlank()) add("Board Model" to rpi.boardModel)
                        add("SoC Temperature" to Formatters.formatTemperature(rpi.socTempC?.toFloat(), prefs.temperatureUnit))
                        add("ARM Clock" to "${rpi.cpuFreqMhz ?: 0} MHz")
                        rpi.fanRpm?.let { add("Fan Speed" to "$it RPM") }
                        add(
                            "Power & Thermal Flags" to
                                "${rpi.throttledRaw ?: "0x0"} · UV: ${rpi.underVoltageDetected} · Throttled: ${rpi.currentlyThrottled}",
                        )
                    }
                    ExpressiveGroupedCard {
                        rpiRows.forEachIndexed { index, (k, v) ->
                            val isAlertRow = k == "Power & Thermal Flags" && (rpi.underVoltageDetected || rpi.currentlyThrottled)
                            ExpressiveGroupedItem(
                                index = index,
                                count = rpiRows.size,
                                containerColor = if (isAlertRow) design.statusFaultContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = k,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isAlertRow) design.onStatusFaultContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = v,
                                        style = MaterialTheme.typography.labelLarge.merge(design.monoNumericStyle),
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isAlertRow) design.onStatusFaultContainer else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Custom Probes
        if (metric != null && metric.customResults.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpressiveSectionHeader(title = "Custom Probes", count = metric.customResults.size)
                    ExpressiveGroupedCard {
                        metric.customResults.forEachIndexed { index, probe ->
                            val ok = probe.exitCode == 0
                            ExpressiveGroupedItem(
                                index = index,
                                count = metric.customResults.size,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = probe.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    MetricPillBadge(
                                        text = "exit ${probe.exitCode}",
                                        containerColor = if (ok) design.statusOkContainer else design.statusFaultContainer,
                                        contentColor = if (ok) design.onStatusOkContainer else design.onStatusFaultContainer,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = probe.output.ifBlank { "(no output)" },
                                    style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ZFS Pools
        if (metric != null && metric.zfsPools.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpressiveSectionHeader(title = "ZFS Pools", count = metric.zfsPools.size)
                    ExpressiveGroupedCard {
                        metric.zfsPools.forEachIndexed { index, pool ->
                            val healthy = pool.state.equals("ONLINE", ignoreCase = true)
                            val frac = if (pool.sizeBytes > 0L) {
                                (pool.allocatedBytes.toFloat() / pool.sizeBytes.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            ExpressiveGroupedItem(
                                index = index,
                                count = metric.zfsPools.size,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = pool.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    MetricPillBadge(
                                        text = pool.state,
                                        containerColor = if (healthy) design.statusOkContainer else design.statusWarnContainer,
                                        contentColor = if (healthy) design.onStatusOkContainer else design.onStatusWarnContainer,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                WavyMetricBar(
                                    label = "Allocated",
                                    valueText = "${Formatters.formatBytes(pool.allocatedBytes, prefs.storageUnit)} / ${Formatters.formatBytes(pool.sizeBytes, prefs.storageUnit)}",
                                    fraction = frac,
                                    accentColor = if (healthy) design.statusOk else design.statusWarn,
                                )
                                if (pool.scrubStatus.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = pool.scrubStatus,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // S.M.A.R.T. Health
        if (server.capabilities.hasSmartctl) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpressiveSectionHeader(title = "S.M.A.R.T. Health")
                    ExpressiveGroupedCard {
                        ExpressiveGroupedItem(
                            index = 0,
                            count = 1,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "smartctl Available",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "Run an interactive SMART drive check in SSH.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { onOpenTerminal("sudo smartctl --scan\n") },
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text("Inspect")
                                }
                            }
                        }
                    }
                }
            }
        }

        // GPUs
        if (metric != null && metric.gpus.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpressiveSectionHeader(title = "GPUs", count = metric.gpus.size)
                    ExpressiveGroupedCard {
                        metric.gpus.forEachIndexed { index, gpu ->
                            ExpressiveGroupedItem(
                                index = index,
                                count = metric.gpus.size,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = gpu.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        MetricPillBadge(
                                            text = Formatters.formatTemperature(gpu.tempC.toFloat(), prefs.temperatureUnit),
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = MaterialTheme.colorScheme.onSurface,
                                        )
                                        gpu.powerWatts?.let { watts ->
                                            MetricPillBadge(
                                                text = "${watts}W",
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                WavyMetricBar(
                                    label = "Utilization · VRAM ${gpu.vramUsedMb}/${gpu.vramTotalMb} MB",
                                    valueText = "${gpu.utilizationPct}%",
                                    fraction = gpu.utilizationPct / 100f,
                                    accentColor = design.statusOk,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Power & Wake-on-LAN
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpressiveSectionHeader(title = "Power Controls")
                ExpressiveCard(tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val powerButtons = listOf(
                            Triple("Wake", Icons.Rounded.Bolt, server.macAddress.isNotBlank()) to onWake,
                            Triple("Reboot", Icons.Rounded.RestartAlt, !prefs.safeReadOnlyMode) to onReboot,
                            Triple("Shutdown", Icons.Rounded.PowerSettingsNew, !prefs.safeReadOnlyMode) to onShutdown,
                        )
                        powerButtons.forEachIndexed { idx, (meta, handler) ->
                            val (label, icon, enabled) = meta
                            val isShutdown = label == "Shutdown"
                            Surface(
                                onClick = handler,
                                enabled = enabled,
                                shape = expressiveHorizontalGroupShape(idx, powerButtons.size),
                                color = when {
                                    !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                                    isShutdown -> MaterialTheme.colorScheme.errorContainer
                                    else -> MaterialTheme.colorScheme.secondaryContainer
                                },
                                contentColor = when {
                                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    isShutdown -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewStatTile(
    label: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    badgeShape: ExpressiveBadgeShape,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val design = LocalGlassrackDesign.current
    ExpressiveCard(
        modifier = modifier,
        tonalContainerColor = containerColor,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor.copy(alpha = 0.84f),
            )
            ExpressiveShapeBadge(
                shape = badgeShape,
                containerColor = contentColor.copy(alpha = 0.14f),
                contentColor = contentColor,
                size = 36.dp,
                icon = icon,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium.merge(design.monoNumericStyle),
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
            color = contentColor.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MetricPillBadge(
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NetworkAndFilesTab(
    server: Server,
    tunnels: List<PortForwardTunnel>,
    activeTunnelIds: Set<String>,
    prefs: UserPreferences,
    viewModel: ServersViewModel,
) {
    val netFiles by viewModel.netFilesState.collectAsState()
    val design = LocalGlassrackDesign.current
    // Add-Tunnel dialog state: null = closed; a draft (possibly prefilled by "Forward this",
    // Phase 8) opens the dialog with its values.
    var tunnelDraft by remember { mutableStateOf<TunnelDraft?>(null) }
    var pendingDeleteTunnel by remember { mutableStateOf<PortForwardTunnel?>(null) }

    // Phase 6 binary transfers: the entry queued for the FileKit saver, plus the launchers.
    var pendingDownloadEntry by remember { mutableStateOf<RemoteFileEntry?>(null) }

    LaunchedEffect(server.id) {
        viewModel.loadNetworkAndFiles(server, "/")
    }

    val saverLauncher = rememberFileSaverLauncher(
        dialogSettings = FileKitDialogSettings.createDefault(),
    ) { destination ->
        val entry = pendingDownloadEntry
        pendingDownloadEntry = null
        if (destination != null && entry != null) {
            viewModel.prepareFileDownload(server, entry, destination)
        }
    }
    val pickerLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(),
        dialogSettings = FileKitDialogSettings.createDefault(),
    ) { source ->
        if (source != null) {
            viewModel.prepareFileUpload(server, source)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        netFiles.statusMessage?.let { msg ->
            item {
                ExpressiveCard(
                    tonalContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearMessage) {
                            Text("OK")
                        }
                    }
                }
            }
        }

        // Active streamed binary transfers (Phase 6): progress + per-row cancel.
        if (netFiles.activeTransfers.isNotEmpty()) {
            item(key = "active-file-transfers") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpressiveSectionHeader(
                        title = "File Transfers",
                        count = netFiles.activeTransfers.size,
                    )
                    ExpressiveGroupedCard {
                        netFiles.activeTransfers.forEachIndexed { index, transfer ->
                            ActiveTransferRow(
                                transfer = transfer,
                                storageUnit = prefs.storageUnit,
                                count = netFiles.activeTransfers.size,
                                index = index,
                                onCancel = { viewModel.cancelFileTransfer(transfer.id) },
                            )
                        }
                    }
                }
            }
        }

        // Port-Forward Tunnels
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpressiveSectionHeader(
                    title = "Port-Forward Tunnels",
                    count = tunnels.size,
                    actionLabel = "+ Tunnel",
                    onAction = { tunnelDraft = TunnelDraft() },
                )
                if (tunnels.isEmpty()) {
                    ExpressiveCard(tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Text(
                            text = "Forward remote admin UIs (e.g. 8096, 8123, 8006) securely over SSH.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(
                            onClick = { tunnelDraft = TunnelDraft() },
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Create Tunnel")
                        }
                    }
                } else {
                    ExpressiveGroupedCard {
                        tunnels.forEachIndexed { index, tun ->
                            val active = tun.id in activeTunnelIds
                            ExpressiveGroupedItem(
                                index = index,
                                count = tunnels.size,
                                containerColor = if (active) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        ExpressiveIconTile(
                                            icon = Icons.Rounded.Router,
                                            containerColor = if (active) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                            } else {
                                                MaterialTheme.colorScheme.secondaryContainer
                                            },
                                            size = 36.dp,
                                            iconSize = 18.dp,
                                        )
                                        Column {
                                            Text(
                                                text = tun.label,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = "localhost:${tun.localPort} -> ${tun.remoteHost}:${tun.remotePort}",
                                                style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        IconButton(
                                            onClick = {
                                                if (requiresConfirmation(actionIsDestructive = true, prefs = prefs)) {
                                                    pendingDeleteTunnel = tun
                                                } else {
                                                    viewModel.deleteTunnel(tun, server)
                                                }
                                            },
                                        ) {
                                            Icon(
                                                Icons.Rounded.Delete,
                                                contentDescription = "Delete tunnel ${tun.label}",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        Switch(
                                            checked = active,
                                            onCheckedChange = { viewModel.toggleTunnel(tun, server) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Network Interfaces
        if (netFiles.interfaces.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpressiveSectionHeader(title = "Network Interfaces", count = netFiles.interfaces.size)
                    ExpressiveGroupedCard {
                        netFiles.interfaces.forEachIndexed { index, iface ->
                            ExpressiveGroupedItem(
                                index = index,
                                count = netFiles.interfaces.size,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ExpressiveIconTile(
                                        icon = Icons.Rounded.Lan,
                                        containerColor = if (iface.linkUp) design.statusOkContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = if (iface.linkUp) design.onStatusOkContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        size = 36.dp,
                                        iconSize = 18.dp,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = iface.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            MetricPillBadge(
                                                text = if (iface.linkUp) "UP" else "DOWN",
                                                containerColor = if (iface.linkUp) design.statusOkContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                                contentColor = if (iface.linkUp) design.onStatusOkContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            text = iface.ipv4.ifBlank { "No IPv4" },
                                            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "RX ${Formatters.formatBytes(iface.rxBytes, prefs.storageUnit)}",
                                            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                        )
                                        Text(
                                            text = "TX ${Formatters.formatBytes(iface.txBytes, prefs.storageUnit)}",
                                            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Listening Ports
        if (netFiles.ports.isNotEmpty()) {
            val shownPorts = netFiles.ports.take(12)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpressiveSectionHeader(title = "Listening Ports", count = netFiles.ports.size)
                    ExpressiveGroupedCard {
                        shownPorts.forEachIndexed { index, p ->
                            ExpressiveGroupedItem(
                                index = index,
                                count = shownPorts.size,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        MetricPillBadge(
                                            text = p.protocol.uppercase(),
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                        Text(
                                            text = "${p.localAddress}:${p.port}",
                                            style = MaterialTheme.typography.labelLarge.merge(design.monoNumericStyle),
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                    Text(
                                        text = p.processName.ifBlank { "—" },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // "Forward this" (Phase 8): opens the tunnel dialog
                                    // prefilled from this port's host/port/process.
                                    IconButton(
                                        onClick = { tunnelDraft = tunnelDraftForPort(p) },
                                    ) {
                                        Icon(
                                            Icons.Rounded.Router,
                                            contentDescription = "Forward this port",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Remote File Explorer
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpressiveSectionHeader(
                    title = "Remote File Explorer",
                    count = netFiles.files.size,
                    actionLabel = if (netFiles.currentPath != "/") "Parent Dir" else null,
                    onAction = {
                        val parent = netFiles.currentPath.substringBeforeLast('/').ifEmpty { "/" }
                        viewModel.loadNetworkAndFiles(server, parent)
                    },
                )
                ExpressiveCard(
                    tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = netFiles.currentPath,
                        style = MaterialTheme.typography.labelLarge.merge(design.monoNumericStyle),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                FilledTonalButton(
                    onClick = pickerLauncher::launch,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Upload File Here")
                }

                if (netFiles.files.isNotEmpty()) {
                    ExpressiveGroupedCard {
                        netFiles.files.forEachIndexed { index, entry ->
                            ExpressiveGroupedItem(
                                index = index,
                                count = netFiles.files.size,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                onClick = {
                                    if (entry.isDirectory) {
                                        viewModel.loadNetworkAndFiles(server, entry.fullPath)
                                    } else {
                                        viewModel.openRemoteFile(server, entry.fullPath)
                                    }
                                },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    ExpressiveIconTile(
                                        icon = if (entry.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                        containerColor = if (entry.isDirectory) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        contentColor = if (entry.isDirectory) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        size = 38.dp,
                                        iconSize = 19.dp,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${entry.permissions} · ${Formatters.formatBytes(entry.sizeBytes, prefs.storageUnit)}",
                                            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (entry.isDirectory) {
                                        Icon(
                                            imageVector = Icons.Rounded.ChevronRight,
                                            contentDescription = "Open directory",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            TransferPill(
                                                label = "Download",
                                                icon = Icons.Rounded.FileDownload,
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                onClick = {
                                                    pendingDownloadEntry = entry
                                                    saverLauncher.launch(
                                                        suggestedName = entry.name,
                                                        extension = entry.name.substringAfterLast('.', "").ifEmpty { null },
                                                    )
                                                },
                                            )
                                            TransferPill(
                                                label = "Edit",
                                                icon = null,
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                onClick = { viewModel.openRemoteFile(server, entry.fullPath) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    netFiles.openedFilePath?.let { path ->
        ModalBottomSheet(onDismissRequest = viewModel::closeRemoteFile) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.titleMedium.merge(design.monoNumericStyle),
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = "Saving automatically creates a .bak backup",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
                OutlinedTextField(
                    value = netFiles.openedFileDraft,
                    onValueChange = viewModel::updateFileDraft,
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    textStyle = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                    shape = RoundedCornerShape(18.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = viewModel::closeRemoteFile) {
                        Text("Cancel")
                    }
                    Button(
                        enabled = !prefs.safeReadOnlyMode,
                        onClick = { viewModel.saveRemoteFile(server) },
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text("Save + Backup")
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    tunnelDraft?.let { draft ->
        // Prefills (label, ports, host) re-seed whenever a new draft is staged, so
        // "Forward this" on a different port row always starts from that row's values.
        var label by remember(draft) { mutableStateOf(draft.label) }
        var localP by remember(draft) { mutableStateOf(draft.localPort) }
        var remoteH by remember(draft) { mutableStateOf(draft.remoteHost.ifBlank { "127.0.0.1" }) }
        var remoteP by remember(draft) { mutableStateOf(draft.remotePort) }
        val localPortInt = localP.trim().toIntOrNull()
        val remotePortInt = remoteP.trim().toIntOrNull()
        val canSubmit = localPortInt != null && localPortInt in 1..65535 &&
            remotePortInt != null && remotePortInt in 1..65535

        AlertDialog(
            onDismissRequest = { tunnelDraft = null },
            shape = RoundedCornerShape(28.dp),
            title = {
                Text("Add SSH Tunnel", style = MaterialTheme.typography.headlineSmall)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Label") },
                        placeholder = { Text("e.g. Web UI") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = localP,
                        onValueChange = { localP = it },
                        label = { Text("Local Port") },
                        placeholder = { Text("8096") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = remoteH,
                        onValueChange = { remoteH = it },
                        label = { Text("Remote Host") },
                        placeholder = { Text("127.0.0.1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = remoteP,
                        onValueChange = { remoteP = it },
                        label = { Text("Remote Port") },
                        placeholder = { Text("8096") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = canSubmit,
                    shape = RoundedCornerShape(999.dp),
                    onClick = {
                        viewModel.createTunnel(
                            serverId = server.id,
                            label = label.trim(),
                            localPort = localPortInt!!,
                            remoteHost = remoteH.trim().ifBlank { "127.0.0.1" },
                            remotePort = remotePortInt!!,
                        )
                        tunnelDraft = null
                    },
                ) {
                    Text("Add Tunnel")
                }
            },
            dismissButton = {
                TextButton(onClick = { tunnelDraft = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    pendingDeleteTunnel?.let { tun ->
        ActionSafetyDialog(
            title = "Delete tunnel ${tun.label}?",
            description = "Removes the forward localhost:${tun.localPort} -> ${tun.remoteHost}:${tun.remotePort} " +
                "from ${server.name}. An active tunnel is closed first.",
            confirmationLevel = prefs.destructiveConfirmation,
            onConfirm = {
                pendingDeleteTunnel = null
                viewModel.deleteTunnel(tun, server)
            },
            onDismiss = { pendingDeleteTunnel = null },
        )
    }

    // Size gate (Phase 6): anything above 100 MB needs an explicit confirmation.
    netFiles.pendingTransferConfirmation?.let { pending ->
        val direction = pending.direction
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingTransfer,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    text = if (direction == FileTransferDirection.DOWNLOAD) "Download Large File?" else "Upload Large File?",
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${Formatters.formatBytes(pending.totalBytes, prefs.storageUnit)} — ${pending.localName}",
                        style = MaterialTheme.typography.titleMedium.merge(design.monoNumericStyle),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (direction == FileTransferDirection.DOWNLOAD) {
                            "Streams ${pending.remotePath} from ${server.name} to the picked location on this device."
                        } else {
                            "Streams ${pending.localName} to ${pending.remotePath} on ${server.name}. " +
                                "Overwriting keeps the previous content at a .bak backup."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(999.dp),
                    onClick = viewModel::confirmPendingTransfer,
                ) {
                    Text(if (direction == FileTransferDirection.DOWNLOAD) "Download Anyway" else "Upload Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingTransfer) {
                    Text("Cancel")
                }
            },
        )
    }
}

/** One running transfer: direction icon, name, path, progress bar and a Cancel pill. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActiveTransferRow(
    transfer: ActiveFileTransfer,
    storageUnit: StorageUnit,
    index: Int,
    count: Int,
    onCancel: () -> Unit,
) {
    val design = LocalGlassrackDesign.current
    val isDownload = transfer.direction == FileTransferDirection.DOWNLOAD
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
                icon = if (isDownload) Icons.Rounded.FileDownload else Icons.Rounded.FileUpload,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                size = 38.dp,
                iconSize = 19.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transfer.localFileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = transfer.remotePath,
                    style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }

        Spacer(Modifier.height(8.dp))

        val fraction = transfer.fraction
        if (fraction != null) {
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${transfer.percent}% · " +
                    "${Formatters.formatBytes(transfer.bytesTransferred, storageUnit)} / " +
                    Formatters.formatBytes(transfer.totalBytes, storageUnit),
                style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = Formatters.formatBytes(transfer.bytesTransferred, storageUnit),
                style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Small clickable pill used for the per-file Download/Edit affordances. */
@Composable
private fun TransferPill(
    label: String,
    icon: ImageVector?,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddEditServerSheet(
    initial: Server,
    isNew: Boolean,
    allServers: List<Server>,
    sshKeys: List<SshKeyRecord>,
    sudoSecretServerIds: Set<String>,
    testStatus: String?,
    isTesting: Boolean,
    onTest: (Server, String?) -> Unit,
    onSave: (Server, ServerSecretEdits) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var groupTag by remember { mutableStateOf(initial.groupTag) }
    var tagsCsv by remember { mutableStateOf(initial.tags.joinToString(", ")) }
    var primaryHost by remember { mutableStateOf(initial.primaryHost) }
    var fallbackHost by remember { mutableStateOf(initial.fallbackHost) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    var username by remember { mutableStateOf(initial.username) }
    var authType by remember {
        mutableStateOf(if (initial.authType == AuthType.INTERACTIVE) AuthType.PASSWORD else initial.authType)
    }
    var selectedKeyId by remember { mutableStateOf(initial.sshKeyId ?: sshKeys.firstOrNull()?.id) }
    var password by remember { mutableStateOf("") }
    var clearSavedPassword by remember { mutableStateOf(false) }
    var sudoAuth by remember { mutableStateOf(initial.sudoAuth) }
    var sudoPassword by remember { mutableStateOf("") }
    var clearSudoPassword by remember { mutableStateOf(false) }
    // Phase 4 AUTO update consent: flipping to AUTO always requires the hold pill,
    // regardless of the global destructiveConfirmation level — enabling it IS the consent.
    var updatePolicyDraft by remember { mutableStateOf(initial.updatePolicy) }
    var updateOsDraft by remember { mutableStateOf(initial.updateOs) }
    var updateContainersDraft by remember { mutableStateOf(initial.updateContainers) }
    var pendingAutoConsent by remember { mutableStateOf(false) }
    var macAddress by remember { mutableStateOf(initial.macAddress) }
    var jumpHostId by remember { mutableStateOf(initial.jumpHostServerId) }
    var pollInterval by remember { mutableStateOf(initial.pollIntervalSec.toString()) }
    var notes by remember { mutableStateOf(initial.notes) }

    val hasStoredPassword = !isNew && initial.authType == AuthType.PASSWORD && initial.credentialVaultId != null
    // Dedicated sudo secret existence comes from the vault-backed set, since the model
    // intentionally carries no column for it (the secret's existence is the state).
    val hasStoredSudoPassword = !isNew &&
        initial.sudoAuth == SudoAuth.SEPARATE_PASSWORD &&
        initial.id in sudoSecretServerIds

    fun buildServer(): Server = initial.copy(
        name = name.trim().ifBlank { primaryHost.trim() },
        groupTag = groupTag.trim().ifBlank { "Homelab" },
        tags = tagsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        primaryHost = primaryHost.trim(),
        fallbackHost = fallbackHost.trim(),
        port = portText.toIntOrNull() ?: 22,
        username = username.trim().ifBlank { "root" },
        authType = authType,
        sshKeyId = if (authType == AuthType.KEY) selectedKeyId else null,
        macAddress = macAddress.trim(),
        jumpHostServerId = jumpHostId,
        // A cleared field keeps the node's current interval (or the global default for new nodes).
        pollIntervalSec = (pollInterval.toIntOrNull() ?: initial.pollIntervalSec).coerceIn(2, 300),
        notes = notes.trim(),
        sudoAuth = sudoAuth,
        updatePolicy = updatePolicyDraft,
        updateOs = updateOsDraft,
        updateContainers = updateContainersDraft,
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isNew) "Onboard SSH Node" else "Edit ${initial.name}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            ExpressiveCard(tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(
                    text = "Node & Connection",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Node Name") },
                        placeholder = { Text("e.g. rpi5-core.lan") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = primaryHost,
                        onValueChange = { primaryHost = it },
                        label = { Text("Primary LAN Host / IP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = fallbackHost,
                        onValueChange = { fallbackHost = it },
                        label = { Text("Fallback Tailscale / DDNS Host (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("SSH User") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it },
                            label = { Text("Port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(104.dp),
                        )
                    }
                    OutlinedTextField(
                        value = groupTag,
                        onValueChange = { groupTag = it },
                        label = { Text("Rack Group / Tag") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = tagsCsv,
                        onValueChange = { tagsCsv = it },
                        label = { Text("Tags for Snippet Broadcasts (comma-separated)") },
                        placeholder = { Text("e.g. docker, nas, raspberrypi") },
                        supportingText = {
                            Text(
                                text = "Broadcast runbooks target nodes with any of these tags",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            ExpressiveCard(tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(
                    text = "Authentication",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                ExpressiveSegmentedPillGroup(
                    options = listOf(AuthType.KEY, AuthType.PASSWORD),
                    selected = authType,
                    onSelected = { authType = it },
                    label = { if (it == AuthType.KEY) "SSH Key" else "Password" },
                )
                Spacer(Modifier.height(10.dp))
                if (authType == AuthType.KEY) {
                    if (sshKeys.isEmpty()) {
                        Text(
                            text = "No SSH keys in vault yet — create or import one in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            sshKeys.forEach { k ->
                                val selected = selectedKeyId == k.id
                                Surface(
                                    onClick = { selectedKeyId = k.id },
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(Icons.Rounded.Key, null, modifier = Modifier.size(16.dp))
                                        Text(k.alias, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            if (it.isNotEmpty()) clearSavedPassword = false
                        },
                        label = {
                            Text(
                                if (hasStoredPassword) {
                                    "New Password (leave blank to keep stored)"
                                } else {
                                    "Password (stored in Tink Keystore Vault)"
                                },
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (hasStoredPassword) {
                        TextButton(
                            onClick = {
                                clearSavedPassword = !clearSavedPassword
                                if (clearSavedPassword) password = ""
                            },
                        ) {
                            Text(
                                if (clearSavedPassword) {
                                    "Stored password will be cleared on save — undo"
                                } else {
                                    "Clear stored password"
                                },
                            )
                        }
                    }
                }
            }

            ExpressiveCard(tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(
                    text = "Privilege Escalation",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                ExpressiveSegmentedPillGroup(
                    options = listOf(SudoAuth.NONE, SudoAuth.LOGIN_PASSWORD, SudoAuth.SEPARATE_PASSWORD),
                    selected = sudoAuth,
                    onSelected = { selected ->
                        sudoAuth = selected
                        if (selected != SudoAuth.SEPARATE_PASSWORD) {
                            sudoPassword = ""
                            clearSudoPassword = false
                        }
                    },
                    label = {
                        when (it) {
                            SudoAuth.NONE -> "None"
                            SudoAuth.LOGIN_PASSWORD -> "Login Password"
                            SudoAuth.SEPARATE_PASSWORD -> "Separate"
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                when (sudoAuth) {
                    SudoAuth.NONE -> Text(
                        text = "Root actions and update scans rely on a root login or passwordless " +
                            "sudo; without either, privileged actions run unprivileged and scans " +
                            "list a stale package index.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SudoAuth.LOGIN_PASSWORD -> Text(
                        text = "sudo reuses this node's SSH login password (read from the vault " +
                            "when a privileged command runs)." +
                            if (authType != AuthType.PASSWORD) {
                                " Requires password authentication — this node currently logs in with a key."
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SudoAuth.SEPARATE_PASSWORD -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sudoPassword,
                            onValueChange = {
                                sudoPassword = it
                                if (it.isNotEmpty()) clearSudoPassword = false
                            },
                            label = {
                                Text(
                                    if (hasStoredSudoPassword) {
                                        "Sudo Password (leave blank to keep stored)"
                                    } else {
                                        "Sudo Password (stored in Tink Keystore Vault)"
                                    },
                                )
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            // sudo authenticates the invoking user: on stock Debian/Mint/Pi OS
                            // this is the login user's password, and the root account's is a
                            // different (usually locked) credential.
                            text = "The password sudo asks for on the node — normally the SSH login " +
                                "user's own password, not root's.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (hasStoredSudoPassword) {
                            TextButton(
                                onClick = {
                                    clearSudoPassword = !clearSudoPassword
                                    if (clearSudoPassword) sudoPassword = ""
                                },
                            ) {
                                Text(
                                    if (clearSudoPassword) {
                                        "Stored sudo password will be cleared on save — undo"
                                    } else {
                                        "Clear stored sudo password"
                                    },
                                )
                            }
                        }
                    }
                }
            }

            ExpressiveCard(tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(
                    text = "Automatic Updates",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                ExpressiveSegmentedPillGroup(
                    options = listOf(UpdatePolicy.MANUAL, UpdatePolicy.AUTO),
                    selected = updatePolicyDraft,
                    onSelected = { selected ->
                        when (selected) {
                            UpdatePolicy.MANUAL -> {
                                updatePolicyDraft = UpdatePolicy.MANUAL
                                pendingAutoConsent = false
                            }
                            UpdatePolicy.AUTO -> pendingAutoConsent = true
                        }
                    },
                    label = {
                        when (it) {
                            UpdatePolicy.MANUAL -> "Manual"
                            UpdatePolicy.AUTO -> "AUTO"
                        }
                    },
                )
                if (pendingAutoConsent || updatePolicyDraft == UpdatePolicy.AUTO) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "OS updates (apt/dnf/pacman/apk upgrade)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = updateOsDraft,
                            onCheckedChange = { updateOsDraft = it },
                            enabled = updatePolicyDraft == UpdatePolicy.AUTO || pendingAutoConsent,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Container updates (stack update / pull)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = updateContainersDraft,
                            onCheckedChange = { updateContainersDraft = it },
                            enabled = updatePolicyDraft == UpdatePolicy.AUTO || pendingAutoConsent,
                        )
                    }
                }
                if (pendingAutoConsent) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "AUTO applies updates unattended on a daily schedule — no prompt " +
                            "at run time, whatever the global confirmation level is. Holding " +
                            "below is the consent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    HoldToConfirmPill(
                        label = "Enable AUTO updates",
                        onConfirmed = {
                            updatePolicyDraft = UpdatePolicy.AUTO
                            pendingAutoConsent = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (updatePolicyDraft == UpdatePolicy.AUTO) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "AUTO is armed: the scheduler scans this node daily and applies " +
                            "the enabled categories (audited as SCHEDULED, a summary notification " +
                            "is posted). Never reboots.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ExpressiveCard(tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(
                    text = "Hardware & Polling",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = macAddress,
                        onValueChange = { macAddress = it },
                        label = { Text("MAC Address for Wake-on-LAN (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pollInterval,
                        onValueChange = { pollInterval = it },
                        label = { Text("Telemetry Poll Interval (sec)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    val otherNodes = allServers.filter { it.id != initial.id }
                    if (otherNodes.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "SSH Jump Host / Bastion (optional)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val directSelected = jumpHostId == null
                            Surface(
                                onClick = { jumpHostId = null },
                                shape = RoundedCornerShape(999.dp),
                                color = if (directSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                contentColor = if (directSelected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            ) {
                                Text(
                                    text = "Direct",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }
                            otherNodes.forEach { j ->
                                val selected = jumpHostId == j.id
                                Surface(
                                    onClick = { jumpHostId = j.id },
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                ) {
                                    Text(
                                        text = j.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isTesting) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            AnimatedVisibility(visible = testStatus != null) {
                testStatus?.let { msg ->
                    val isFailed = msg.startsWith("Failed", ignoreCase = true)
                    Surface(
                        color = if (isFailed) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = if (isFailed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { onTest(buildServer(), password.takeIf { it.isNotEmpty() }) },
                    enabled = primaryHost.isNotBlank() && !isTesting,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Lan, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Test SSH")
                }
                Button(
                    onClick = {
                        onSave(
                            buildServer(),
                            ServerSecretEdits(
                                loginPassword = password.takeIf { it.isNotEmpty() },
                                clearLoginPassword = clearSavedPassword,
                                sudoPassword = sudoPassword.takeIf { it.isNotEmpty() },
                                clearSudoPassword = clearSudoPassword,
                            ),
                        )
                    },
                    enabled = primaryHost.isNotBlank(),
                    shape = RoundedCornerShape(999.dp),
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
        shape = RoundedCornerShape(28.dp),
        icon = {
            ExpressiveShapeBadge(
                shape = if (challenge.isMismatch) {
                    ExpressiveBadgeShape.SoftBurst
                } else {
                    ExpressiveBadgeShape.Cookie9Sided
                },
                containerColor = if (challenge.isMismatch) {
                    design.statusFaultContainer
                } else {
                    design.statusWarnContainer
                },
                contentColor = if (challenge.isMismatch) {
                    design.onStatusFaultContainer
                } else {
                    design.onStatusWarnContainer
                },
                size = 48.dp,
                icon = Icons.Rounded.Security,
            )
        },
        title = {
            Text(
                text = if (challenge.isMismatch) "HOST KEY CHANGED" else "Verify SSH Host Fingerprint",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${challenge.host}:${challenge.port} (${challenge.keyType})",
                    style = MaterialTheme.typography.titleSmall.merge(design.monoNumericStyle),
                )
                ExpressiveCard(
                    tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    Text(
                        text = "Received Fingerprint",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = challenge.fingerprintSha256,
                        style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                    )
                }
                challenge.expectedFingerprint?.let { expected ->
                    ExpressiveCard(
                        tonalContainerColor = design.statusFaultContainer,
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        Text(
                            text = "Expected Fingerprint",
                            style = MaterialTheme.typography.labelSmall,
                            color = design.onStatusFaultContainer,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = expected,
                            style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                            color = design.onStatusFaultContainer,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onTrust,
                shape = RoundedCornerShape(999.dp),
                colors = if (challenge.isMismatch) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(if (challenge.isMismatch) "Override & Trust" else "Trust Fingerprint")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Block")
            }
        },
    )
}
