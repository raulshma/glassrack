package com.raulshma.grack.feature.containers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WrapText
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
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.raulshma.grack.core.model.ContainerImageInfo
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.ContainerState
import com.raulshma.grack.core.model.Formatters
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.StorageUnit
import com.raulshma.grack.core.ssh.binaryName
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.launch
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
                        // Decorative: the button's "Refresh" text is already read by TalkBack.
                        contentDescription = null,
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
                                    onOpenExecShell(server.id, execShellCommand(container))
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
                                    onOpenExecShell(server.id, execShellCommand(container))
                                },
                            )
                        }
                    }

                    if (state.images.isNotEmpty()) {
                        val sortedImages = state.images
                            .sortedWith(compareByDescending<ContainerImageInfo> { !it.dangling }.thenBy { it.repoTag })
                        item(key = "images-header") {
                            ExpressiveSectionHeader(
                                title = "Images",
                                count = sortedImages.size,
                                actionLabel = if (!prefs.safeReadOnlyMode && sortedImages.any { it.dangling }) "Prune Dangling" else null,
                                onAction = if (!prefs.safeReadOnlyMode && sortedImages.any { it.dangling }) {
                                    { viewModel.requestDanglingImagePrune(server) }
                                } else null,
                            )
                        }
                        item(key = "images-card") {
                            ExpressiveGroupedCard {
                                sortedImages.forEachIndexed { index, image ->
                                    ImageGroupedItem(
                                        index = index,
                                        count = sortedImages.size,
                                        image = image,
                                        safeMode = prefs.safeReadOnlyMode,
                                        storageUnit = prefs.storageUnit,
                                        onRemove = { viewModel.requestImageRemove(server, image) },
                                    )
                                }
                            }
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

    state.pendingConfirmStackVerb?.let { (stack, verb) ->
        ActionSafetyDialog(
            title = "Compose $verb on ${stack.projectName}?",
            description = "Runs '$verb' for the '${stack.projectName}' stack on ${server.name}. " +
                "'down' stops and removes the stack's containers.",
            confirmationLevel = prefs.destructiveConfirmation,
            onConfirm = { viewModel.confirmStackVerb(server) },
            onDismiss = viewModel::dismissStackVerb,
        )
    }

    if (state.pendingConfirmPrune) {
        ActionSafetyDialog(
            title = "Prune unused resources?",
            description = "Removes stopped containers, dangling images and unused networks on ${server.name}. " +
                "Volumes are kept, but pruned data cannot be recovered.",
            confirmationLevel = prefs.destructiveConfirmation,
            onConfirm = { viewModel.confirmPrune(server) },
            onDismiss = viewModel::dismissPrune,
        )
    }

    state.pendingConfirmImageRemove?.let { image ->
        ActionSafetyDialog(
            title = "Remove image ${image.repoTag}?",
            description = "Deletes '${image.repoTag}' (${image.id.take(12)}) from ${server.name}. " +
                "Containers still using this image must be removed first; deleted image layers cannot be recovered.",
            confirmationLevel = prefs.destructiveConfirmation,
            onConfirm = { viewModel.confirmImageRemove(server) },
            onDismiss = viewModel::dismissImageRemove,
        )
    }

    if (state.pendingConfirmDanglingPrune) {
        ActionSafetyDialog(
            title = "Prune dangling images?",
            description = "Removes all untagged (<none>) images on ${server.name}. " +
                "Dangling layers left behind by rebuilds cannot be recovered.",
            confirmationLevel = prefs.destructiveConfirmation,
            onConfirm = { viewModel.confirmDanglingImagePrune(server) },
            onDismiss = viewModel::dismissDanglingImagePrune,
        )
    }

    state.logsContainer?.let { target ->
        var logSearch by remember { mutableStateOf("") }
        val scrollState = rememberScrollState()
        val horizontalScrollState = rememberScrollState()
        val scope = rememberCoroutineScope()
        var autoScroll by remember { mutableStateOf(true) }
        val isFollowing = state.isFollowingLogs

        val logSaverLauncher = rememberFileSaverLauncher(
            dialogSettings = FileKitDialogSettings.createDefault(),
        ) { file ->
            if (file != null) {
                scope.launch {
                    file.writeString(state.logsText)
                    viewModel.setFeedback("Downloaded logs for ${target.name}")
                }
            }
        }

        // Pause auto-scroll the moment the user drags the log view manually; the stream
        // keeps running and the Follow pill resumes following the tail.
        LaunchedEffect(scrollState) {
            scrollState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) autoScroll = false
            }
        }

        ModalBottomSheet(onDismissRequest = viewModel::closeLogs) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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

                    Surface(
                        onClick = {
                            when {
                                // Manual scroll paused the tail chase; Follow resumes it.
                                isFollowing && !autoScroll -> autoScroll = true
                                isFollowing -> viewModel.toggleFollowLogs(server, target)
                                else -> {
                                    autoScroll = true
                                    viewModel.toggleFollowLogs(server, target)
                                }
                            }
                        },
                        shape = RoundedCornerShape(999.dp),
                        color = when {
                            isFollowing && !autoScroll -> MaterialTheme.colorScheme.tertiaryContainer
                            isFollowing -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = when {
                            isFollowing && !autoScroll -> MaterialTheme.colorScheme.onTertiaryContainer
                            isFollowing -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RssFeed,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = when {
                                    isFollowing && !autoScroll -> "Follow (paused)"
                                    isFollowing -> "Following"
                                    else -> "Follow"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }

                    FilledTonalIconButton(
                        onClick = {
                            logSaverLauncher.launch(
                                suggestedName = "grack-logs-${target.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}",
                                extension = "txt",
                            )
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = "Download logs")
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExpressiveSearchField(
                        query = logSearch,
                        onQueryChange = { logSearch = it },
                        placeholder = "Filter log lines...",
                        modifier = Modifier.weight(1f),
                    )
                    LogTogglePill(
                        label = "Regex",
                        icon = Icons.Rounded.Code,
                        checked = state.logsRegexEnabled,
                        onToggle = { viewModel.setLogsRegexEnabled(!state.logsRegexEnabled) },
                    )
                    LogTogglePill(
                        label = "Wrap",
                        icon = Icons.Rounded.WrapText,
                        checked = state.logsWrapLines,
                        onToggle = { viewModel.setLogsWrapLines(!state.logsWrapLines) },
                    )
                }

                val shownLines = remember(state.logsText, logSearch, state.logsRegexEnabled) {
                    filterLogLines(state.logsText, logSearch, state.logsRegexEnabled)
                }

                // Chase the tail while following and auto-scroll is active.
                LaunchedEffect(shownLines, isFollowing, autoScroll) {
                    if (isFollowing && autoScroll) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                ) {
                    val textModifier = if (state.logsWrapLines) {
                        Modifier
                    } else {
                        Modifier.horizontalScroll(horizontalScrollState)
                    }
                    Text(
                        text = shownLines.ifBlank { "(no log output)" },
                        style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                        softWrap = state.logsWrapLines,
                        modifier = textModifier
                            .padding(14.dp)
                            .verticalScroll(scrollState),
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
        "Update" to "update",
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

/** Exec command follows the container's own runtime instead of a hardcoded docker. */
private fun execShellCommand(container: ContainerInfo): String =
    "${container.runtime.binaryName} exec -it ${container.name} sh\n"

/** Filters log lines for the logs sheet: plain substring, or a (case-insensitive) regex when enabled. */
internal fun filterLogLines(logsText: String, query: String, regexEnabled: Boolean): String {
    if (query.isBlank()) return logsText
    val regex = if (regexEnabled) {
        runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
    } else {
        null
    }
    return logsText.lines().filter { line ->
        when (regex) {
            null -> line.contains(query, ignoreCase = true)
            else -> regex.containsMatchIn(line)
        }
    }.joinToString("\n")
}

/**
 * Small expressive toggle pill used beside the logs filter for the Regex and Wrap switches.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LogTogglePill(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = { onToggle() },
        shapes = ToggleButtonShapes(
            shape = RoundedCornerShape(999.dp),
            pressedShape = RoundedCornerShape(14.dp),
            checkedShape = RoundedCornerShape(999.dp),
        ),
        colors = ToggleButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** One image row in the Images section; dangling images carry the destructive Remove pill. */
@Composable
private fun ImageGroupedItem(
    index: Int,
    count: Int,
    image: ContainerImageInfo,
    safeMode: Boolean,
    storageUnit: StorageUnit,
    onRemove: () -> Unit,
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
            ExpressiveShapeBadge(
                shape = ExpressiveBadgeShape.Diamond,
                containerColor = if (image.dangling) {
                    design.statusWarnContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (image.dangling) {
                    design.onStatusWarnContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                size = 40.dp,
                icon = Icons.Rounded.Layers,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = image.repoTag,
                    style = MaterialTheme.typography.titleSmall.merge(design.monoNumericStyle),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    add(Formatters.formatBytes(image.sizeBytes, storageUnit))
                    if (image.createdSince.isNotBlank()) add(image.createdSince)
                    if (image.digest.isNotBlank() && image.digest != "<none>") add(image.digest.take(19))
                }
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (image.dangling && !safeMode) {
                Surface(
                    onClick = onRemove,
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Remove",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

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
