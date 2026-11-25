package com.example.glassrack.feature.containers

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonGroup
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
import com.example.glassrack.core.model.ComposeStack
import com.example.glassrack.core.model.ContainerAction
import com.example.glassrack.core.model.ContainerInfo
import com.example.glassrack.core.model.ContainerState
import com.example.glassrack.core.model.Formatters
import com.example.glassrack.core.model.Server
import com.example.glassrack.core.model.ServerHealth
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContainersPane(
    server: Server,
    onOpenExecShell: (serverId: String, initialCmd: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContainersViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val prefs by viewModel.preferences.collectAsState()
    val design = LocalGlassrackDesign.current

    LaunchedEffect(server.id) {
        viewModel.loadContainers(server)
    }

    val query = state.searchQuery.trim().lowercase()
    fun matchesFilter(c: ContainerInfo): Boolean {
        val textMatch = query.isEmpty() ||
            c.name.lowercase().contains(query) ||
            c.image.lowercase().contains(query) ||
            (c.composeProject?.lowercase()?.contains(query) == true)
        val stateMatch = when (state.filter) {
            ContainerFilter.ALL, ContainerFilter.STACKS_ONLY -> true
            ContainerFilter.RUNNING -> c.state == ContainerState.RUNNING
            ContainerFilter.STOPPED -> c.state != ContainerState.RUNNING
        }
        return textMatch && stateMatch
    }

    val filteredStacks = state.stacks.mapNotNull { stack ->
        val items = stack.containers.filter(::matchesFilter)
        if (items.isEmpty()) null else stack to items
    }
    val filteredStandalone = if (state.filter == ContainerFilter.STACKS_ONLY) {
        emptyList()
    } else {
        state.standalone.filter(::matchesFilter)
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Filter containers or Compose stacks...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContainerFilter.entries.forEach { f ->
                FilterChip(
                    selected = state.filter == f,
                    onClick = { viewModel.setFilter(f) },
                    label = {
                        Text(
                            when (f) {
                                ContainerFilter.ALL -> "All"
                                ContainerFilter.RUNNING -> "Running"
                                ContainerFilter.STOPPED -> "Stopped"
                                ContainerFilter.STACKS_ONLY -> "Compose Stacks"
                            },
                        )
                    },
                )
            }
            Spacer(Modifier.weight(1f, fill = false))
            AssistChip(
                onClick = { viewModel.loadContainers(server) },
                label = { Text("Refresh") },
                leadingIcon = { Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp)) },
            )
            AssistChip(
                onClick = { viewModel.pruneUnused(server) },
                label = { Text("Prune") },
                leadingIcon = { Icon(Icons.Rounded.CleaningServices, null, modifier = Modifier.size(16.dp)) },
            )
        }

        AnimatedVisibility(visible = state.feedbackMessage != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = state.feedbackMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearFeedback) { Text("Dismiss") }
                }
            }
        }

        if (state.isLoading) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(design.cardSpacing),
        ) {
            filteredStacks.forEach { (stack, containers) ->
                item(key = "stack-${stack.projectName}") {
                    ComposeStackHeader(
                        stack = stack,
                        safeMode = prefs.safeReadOnlyMode,
                        onStackVerb = { verb -> viewModel.performComposeStackVerb(server, stack, verb) },
                    )
                }
                items(containers, key = { "c-${it.id}" }) { container ->
                    ContainerBladeItem(
                        container = container,
                        isTransitioning = container.id in state.activeTransitionIds,
                        safeMode = prefs.safeReadOnlyMode,
                        storageUnit = prefs.storageUnit,
                        onAction = { action -> viewModel.requestContainerAction(server, container, action) },
                        onLogs = { viewModel.openLogs(server, container) },
                        onInspect = { viewModel.openInspect(container) },
                        onExecShell = {
                            onOpenExecShell(server.id, "docker exec -it ${container.name} sh\n")
                        },
                    )
                }
            }

            if (filteredStandalone.isNotEmpty()) {
                item(key = "standalone-header") {
                    Text(
                        text = "STANDALONE CONTAINERS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                items(filteredStandalone, key = { "s-${it.id}" }) { container ->
                    ContainerBladeItem(
                        container = container,
                        isTransitioning = container.id in state.activeTransitionIds,
                        safeMode = prefs.safeReadOnlyMode,
                        storageUnit = prefs.storageUnit,
                        onAction = { action -> viewModel.requestContainerAction(server, container, action) },
                        onLogs = { viewModel.openLogs(server, container) },
                        onInspect = { viewModel.openInspect(container) },
                        onExecShell = {
                            onOpenExecShell(server.id, "docker exec -it ${container.name} sh\n")
                        },
                    )
                }
            }
        }
    }

    state.pendingConfirmAction?.let { (container, action) ->
        ActionSafetyDialog(
            title = "${action.label} ${container.name}?",
            description = "This will execute '${action.label}' on container '${container.name}' (${container.image}) on ${server.name}.",
            confirmationLevel = prefs.destructiveConfirmation,
            onConfirm = { viewModel.confirmPendingAction(server) },
            onDismiss = viewModel::dismissPendingAction,
        )
    }

    state.logsContainer?.let { target ->
        var logSearch by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = viewModel::closeLogs) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Logs · ${target.name}", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { viewModel.openLogs(server, target) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh logs")
                    }
                }
                OutlinedTextField(
                    value = logSearch,
                    onValueChange = { logSearch = it },
                    placeholder = { Text("Filter log lines...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val shownLines = remember(state.logsText, logSearch) {
                    if (logSearch.isBlank()) state.logsText
                    else state.logsText.lines().filter { it.contains(logSearch, ignoreCase = true) }.joinToString("\n")
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                ) {
                    Text(
                        text = shownLines,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    state.inspectContainer?.let { target ->
        ModalBottomSheet(onDismissRequest = viewModel::closeInspect) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Inspect · ${target.name}", style = MaterialTheme.typography.titleLarge)
                Text("ID: ${target.id}", style = MaterialTheme.typography.labelMedium)
                Text("Image: ${target.image}", style = MaterialTheme.typography.bodyMedium)
                Text("Status: ${target.statusText}", style = MaterialTheme.typography.bodyMedium)
                Text("Ports: ${target.portsSummary.ifBlank { "none" }}", style = MaterialTheme.typography.bodyMedium)
                target.composeProject?.let { Text("Compose Project: $it", style = MaterialTheme.typography.bodyMedium) }
                target.composeWorkingDir?.let { Text("Working Dir: $it", style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ComposeStackHeader(
    stack: ComposeStack,
    safeMode: Boolean,
    onStackVerb: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "STACK · ${stack.projectName}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${stack.runningCount}/${stack.totalCount} running${stack.workingDir?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !safeMode, onClick = { onStackVerb("up -d") }) { Text("Up") }
                TextButton(enabled = !safeMode, onClick = { onStackVerb("restart") }) { Text("Restart") }
                TextButton(enabled = !safeMode, onClick = { onStackVerb("pull && docker compose up -d") }) { Text("Pull") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContainerBladeItem(
    container: ContainerInfo,
    isTransitioning: Boolean,
    safeMode: Boolean,
    storageUnit: com.example.glassrack.core.model.StorageUnit,
    onAction: (ContainerAction) -> Unit,
    onLogs: () -> Unit,
    onInspect: () -> Unit,
    onExecShell: () -> Unit,
) {
    val design = LocalGlassrackDesign.current
    val health = when {
        isTransitioning -> ServerHealth.CONNECTING
        container.state == ContainerState.RUNNING -> ServerHealth.ONLINE
        container.state == ContainerState.PAUSED -> ServerHealth.WARNING
        else -> ServerHealth.OFFLINE
    }
    val accent = when (health) {
        ServerHealth.ONLINE -> design.statusOk
        ServerHealth.WARNING -> design.statusWarn
        ServerHealth.CONNECTING -> design.statusActive
        else -> design.statusFault
    }

    RackBladeCard(accentColor = accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusBeacon(health = health, size = 13.dp)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = container.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (container.updateAvailable) {
                        Surface(
                            color = design.statusActive.copy(alpha = 0.2f),
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                text = "Update Avail",
                                style = MaterialTheme.typography.labelSmall,
                                color = design.statusActive,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = container.image,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onInspect) {
                Icon(Icons.Rounded.Info, contentDescription = "Inspect")
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${container.statusText} · CPU ${Formatters.formatPercent(container.cpuPct)} · RAM ${Formatters.formatBytes(container.memUsedBytes, storageUnit)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (container.portsSummary.isNotBlank()) {
            Text(
                text = "Ports: ${container.portsSummary}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (isTransitioning) {
            Spacer(Modifier.height(8.dp))
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(10.dp))
        ButtonGroup(
            overflowIndicator = { },
            modifier = Modifier.fillMaxWidth(),
        ) {
            clickableItem(
                onClick = {
                    if (container.state == ContainerState.RUNNING) {
                        onAction(ContainerAction.RESTART)
                    } else {
                        onAction(ContainerAction.START)
                    }
                },
                enabled = !safeMode && !isTransitioning,
                label = if (container.state == ContainerState.RUNNING) "Restart" else "Start",
                icon = {
                    Icon(
                        if (container.state == ContainerState.RUNNING) Icons.Rounded.Refresh else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            if (container.state == ContainerState.RUNNING) {
                clickableItem(
                    onClick = { onAction(ContainerAction.STOP) },
                    enabled = !safeMode && !isTransitioning,
                    label = "Stop",
                    icon = { Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(16.dp)) },
                )
            }
            clickableItem(
                onClick = onLogs,
                label = "Logs",
                icon = { Icon(Icons.Rounded.Description, null, modifier = Modifier.size(16.dp)) },
            )
            if (container.state == ContainerState.RUNNING) {
                clickableItem(
                    onClick = onExecShell,
                    label = "Shell",
                    icon = { Icon(Icons.Rounded.Terminal, null, modifier = Modifier.size(16.dp)) },
                )
            }
        }
        if (container.updateAvailable && !safeMode) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { onAction(ContainerAction.PULL_RECREATE) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.CloudDownload, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Pull & Recreate Container")
            }
        }
    }
}
