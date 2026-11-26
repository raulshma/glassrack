package com.raulshma.grack.feature.containers

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
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
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
import com.raulshma.grack.core.designsystem.ActionSafetyDialog
import com.raulshma.grack.core.designsystem.ExpressiveBadgeShape
import com.raulshma.grack.core.designsystem.ExpressiveCard
import com.raulshma.grack.core.designsystem.ExpressiveEmptyState
import com.raulshma.grack.core.designsystem.ExpressiveGroupedCard
import com.raulshma.grack.core.designsystem.ExpressiveGroupedItem
import com.raulshma.grack.core.designsystem.ExpressiveIconTile
import com.raulshma.grack.core.designsystem.ExpressiveSearchField
import com.raulshma.grack.core.designsystem.ExpressiveSectionHeader
import com.raulshma.grack.core.designsystem.ExpressiveSegmentedPillGroup
import com.raulshma.grack.core.designsystem.ExpressiveShapeBadge
import com.raulshma.grack.core.designsystem.LocalGlassrackDesign
import com.raulshma.grack.core.designsystem.expressiveHorizontalGroupShape
import com.raulshma.grack.core.designsystem.monoNumericStyle
import com.raulshma.grack.core.model.ComposeStack
import com.raulshma.grack.core.model.ContainerAction
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.ContainerState
import com.raulshma.grack.core.model.Formatters
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.StorageUnit
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
    val totalContainers = state.stacks.sumOf { it.totalCount } + state.standalone.size

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExpressiveSearchField(
                query = state.searchQuery,
                onQueryChange = viewModel::setSearch,
                placeholder = "Filter containers or Compose stacks...",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExpressiveSegmentedPillGroup(
                    options = listOf(
                        ContainerFilter.ALL,
                        ContainerFilter.RUNNING,
                        ContainerFilter.STOPPED,
                    ),
                    selected = if (state.filter == ContainerFilter.STACKS_ONLY) ContainerFilter.ALL else state.filter,
                    onSelected = viewModel::setFilter,
                    label = { filter ->
                        when (filter) {
                            ContainerFilter.ALL -> "All"
                            ContainerFilter.RUNNING -> "Running"
                            ContainerFilter.STOPPED -> "Stopped"
                            ContainerFilter.STACKS_ONLY -> "Stacks"
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = { viewModel.loadContainers(server) },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh containers",
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Refresh", maxLines = 1)
                }
            }
        }

        AnimatedVisibility(visible = state.feedbackMessage != null) {
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
                        text = state.feedbackMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearFeedback) {
                        Text("Dismiss")
                    }
                }
            }
        }

        if (state.isLoading) {
            LinearWavyProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        when {
            totalContainers == 0 && !state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    ExpressiveEmptyState(
                        icon = Icons.Rounded.Inventory2,
                        title = "No Containers Found",
                        description = "Docker or Podman containers detected on this node will appear here.",
                        actionLabel = "Refresh",
                        onAction = { viewModel.loadContainers(server) },
                    )
                }
            }
            filteredStacks.isEmpty() && filteredStandalone.isEmpty() && !state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    ExpressiveCard(tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Text(
                            text = "No Matching Containers",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "No containers or stacks match the current filter criteria.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = {
                                viewModel.setSearch("")
                                viewModel.setFilter(ContainerFilter.ALL)
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (filteredStacks.isNotEmpty()) {
                        item(key = "stacks-section-header") {
                            ExpressiveSectionHeader(
                                title = "Compose Stacks",
                                count = filteredStacks.size,
                                actionLabel = if (!prefs.safeReadOnlyMode && filteredStandalone.isEmpty()) "Prune" else null,
                                onAction = if (!prefs.safeReadOnlyMode && filteredStandalone.isEmpty()) {
                                    { viewModel.pruneUnused(server) }
                                } else null,
                            )
                        }
                        filteredStacks.forEach { (stack, containers) ->
                            item(key = "stack-${stack.projectName}") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ComposeStackHeader(
                                        stack = stack,
                                        safeMode = prefs.safeReadOnlyMode,
                                        onStackVerb = { verb -> viewModel.performComposeStackVerb(server, stack, verb) },
                                    )
                                    ExpressiveGroupedCard {
                                        containers.forEachIndexed { index, container ->
                                            ContainerGroupedItem(
                                                index = index,
                                                count = containers.size,
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
                        }
                    }

                    if (filteredStandalone.isNotEmpty()) {
                        item(key = "standalone-header") {
                            ExpressiveSectionHeader(
                                title = "Containers",
                                count = filteredStandalone.size,
                                actionLabel = if (!prefs.safeReadOnlyMode) "Prune" else null,
                                onAction = if (!prefs.safeReadOnlyMode) {
                                    { viewModel.pruneUnused(server) }
                                } else null,
                            )
                        }
                        itemsIndexed(filteredStandalone, key = { _, it -> "s-${it.id}" }) { index, container ->
                            ContainerGroupedItem(
                                index = index,
                                count = filteredStandalone.size,
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

                    if (!prefs.safeReadOnlyMode && totalContainers > 0) {
                        item(key = "prune-footer") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                FilledTonalButton(
                                    onClick = { viewModel.pruneUnused(server) },
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Prune Unused Resources")
                                }
                            }
                        }
                    }
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
                            text = "Logs · ${target.name}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = target.image,
                            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { viewModel.openLogs(server, target) },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh logs")
                    }
                }

                ExpressiveSearchField(
                    query = logSearch,
                    onQueryChange = { logSearch = it },
                    placeholder = "Filter log lines...",
                )

                val shownLines = remember(state.logsText, logSearch) {
                    if (logSearch.isBlank()) state.logsText
                    else state.logsText.lines().filter { it.contains(logSearch, ignoreCase = true) }.joinToString("\n")
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                ) {
                    Text(
                        text = shownLines,
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

    state.inspectContainer?.let { target ->
        ModalBottomSheet(onDismissRequest = viewModel::closeInspect) {
            val rows = buildList {
                add("Container ID" to target.id)
                add("Image" to target.image)
                add("Status" to target.statusText)
                add("Ports" to target.portsSummary.ifBlank { "none" })
                target.composeProject?.let { add("Compose Project" to it) }
                target.composeWorkingDir?.let { add("Working Dir" to it) }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Inspect · ${target.name}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                ExpressiveGroupedCard {
                    rows.forEachIndexed { index, (k, v) ->
                        ExpressiveGroupedItem(
                            index = index,
                            count = rows.size,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Text(
                                text = k,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = v,
                                style = MaterialTheme.typography.bodyMedium.merge(design.monoNumericStyle),
                            )
                        }
                    }
                }
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
    val design = LocalGlassrackDesign.current
    val stackVerbs = listOf(
        "Up" to "up -d",
        "Pull" to "pull",
        "Down" to "down",
    )
    ExpressiveCard(
        tonalContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ExpressiveIconTile(
                icon = Icons.Rounded.Layers,
                containerColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                size = 38.dp,
                iconSize = 19.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stack.projectName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${stack.runningCount}/${stack.totalCount} running${stack.workingDir?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            stackVerbs.forEachIndexed { index, (label, verb) ->
                val isDown = label == "Down"
                Surface(
                    onClick = { onStackVerb(verb) },
                    enabled = !safeMode,
                    shape = expressiveHorizontalGroupShape(index, stackVerbs.size),
                    color = when {
                        safeMode -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.06f)
                        isDown -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.14f)
                    },
                    contentColor = when {
                        safeMode -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.4f)
                        isDown -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

private data class ContainerPillAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContainerGroupedItem(
    index: Int,
    count: Int,
    container: ContainerInfo,
    isTransitioning: Boolean,
    safeMode: Boolean,
    storageUnit: StorageUnit,
    onAction: (ContainerAction) -> Unit,
    onLogs: () -> Unit,
    onInspect: () -> Unit,
    onExecShell: () -> Unit,
) {
    val design = LocalGlassrackDesign.current
    val isRunning = container.state == ContainerState.RUNNING
    val isPaused = container.state == ContainerState.PAUSED

    val badgeContainerColor = when {
        isTransitioning -> design.statusActiveContainer
        isRunning -> design.statusOkContainer
        isPaused -> design.statusWarnContainer
        else -> design.statusFaultContainer
    }
    val badgeContentColor = when {
        isTransitioning -> design.onStatusActiveContainer
        isRunning -> design.onStatusOkContainer
        isPaused -> design.onStatusWarnContainer
        else -> design.onStatusFaultContainer
    }

    val actions = buildList {
        if (isRunning) {
            add(
                ContainerPillAction(
                    label = "Stop",
                    icon = Icons.Rounded.Stop,
                    enabled = !safeMode && !isTransitioning,
                    isDestructive = true,
                    onClick = { onAction(ContainerAction.STOP) },
                ),
            )
        } else {
            add(
                ContainerPillAction(
                    label = "Start",
                    icon = Icons.Rounded.PlayArrow,
                    enabled = !safeMode && !isTransitioning,
                    onClick = { onAction(ContainerAction.START) },
                ),
            )
        }
        add(
            ContainerPillAction(
                label = "Restart",
                icon = Icons.Rounded.Refresh,
                enabled = !safeMode && !isTransitioning,
                onClick = { onAction(ContainerAction.RESTART) },
            ),
        )
        add(
            ContainerPillAction(
                label = "Logs",
                icon = Icons.Rounded.Description,
                onClick = onLogs,
            ),
        )
        if (isRunning) {
            add(
                ContainerPillAction(
                    label = "Shell",
                    icon = Icons.Rounded.Terminal,
                    onClick = onExecShell,
                ),
            )
        }
    }

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
            when {
                isTransitioning -> ExpressiveShapeBadge(
                    shape = ExpressiveBadgeShape.Sunny,
                    containerColor = badgeContainerColor,
                    contentColor = badgeContentColor,
                    size = 40.dp,
                    icon = Icons.Rounded.Sync,
                )
                isRunning -> ExpressiveShapeBadge(
                    shape = ExpressiveBadgeShape.Cookie9Sided,
                    containerColor = badgeContainerColor,
                    contentColor = badgeContentColor,
                    size = 40.dp,
                    icon = Icons.Rounded.PlayArrow,
                )
                isPaused -> Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialShapes.Triangle.toShape(),
                    color = badgeContainerColor,
                    contentColor = badgeContentColor,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Pause,
                            contentDescription = "Paused",
                            modifier = Modifier.size(18.dp),
                            tint = badgeContentColor,
                        )
                    }
                }
                else -> ExpressiveShapeBadge(
                    shape = ExpressiveBadgeShape.Diamond,
                    containerColor = badgeContainerColor,
                    contentColor = badgeContentColor,
                    size = 40.dp,
                    icon = Icons.Rounded.Stop,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = container.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (container.updateAvailable) {
                        Surface(
                            color = design.statusActiveContainer,
                            contentColor = design.onStatusActiveContainer,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                                text = "Update",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = container.image,
                    style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (container.portsSummary.isNotBlank()) {
                    Text(
                        text = container.portsSummary,
                        style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(onClick = onInspect) {
                Icon(Icons.Rounded.Info, contentDescription = "Inspect")
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContainerMetricPill(
                text = container.statusText.ifBlank { container.state.name },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ContainerMetricPill(
                text = "CPU ${Formatters.formatPercent(container.cpuPct)}",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            ContainerMetricPill(
                text = "MEM ${Formatters.formatBytes(container.memUsedBytes, storageUnit)}",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }

        if (isTransitioning) {
            Spacer(Modifier.height(8.dp))
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            actions.forEachIndexed { actIndex, action ->
                val shape = expressiveHorizontalGroupShape(actIndex, actions.size)
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
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
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

        if (container.updateAvailable && !safeMode) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { onAction(ContainerAction.PULL_RECREATE) },
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.CloudDownload, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Pull & Recreate Container")
            }
        }
    }
}

@Composable
private fun ContainerMetricPill(
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
