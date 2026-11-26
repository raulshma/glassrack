package com.raulshma.grack.feature.terminal

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raulshma.grack.core.designsystem.ExpressiveBadgeShape
import com.raulshma.grack.core.designsystem.ExpressiveCard
import com.raulshma.grack.core.designsystem.ExpressiveEmptyState
import com.raulshma.grack.core.designsystem.ExpressiveGroupedItem
import com.raulshma.grack.core.designsystem.ExpressiveIconTile
import com.raulshma.grack.core.designsystem.ExpressiveSectionHeader
import com.raulshma.grack.core.designsystem.LocalGlassrackDesign
import com.raulshma.grack.core.designsystem.monoNumericStyle
import com.raulshma.grack.core.terminal.ExtraKeyCode
import com.raulshma.grack.core.terminal.TerminalConnectionState
import com.raulshma.grack.core.terminal.TerminalSurface
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    pendingOpenServerId: String? = null,
    pendingInitialCommand: String? = null,
    onConsumedPendingLaunch: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val clipboard = LocalClipboardManager.current
    val design = LocalGlassrackDesign.current

    LaunchedEffect(pendingOpenServerId, pendingInitialCommand) {
        if (pendingOpenServerId != null) {
            viewModel.openSessionForServer(pendingOpenServerId, pendingInitialCommand)
            onConsumedPendingLaunch()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // Expressive Editorial Header
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Terminal",
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
                        text = "${state.tabs.size} sessions",
                        style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { viewModel.setShowSnippets(true) },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Snippets", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = { viewModel.setShowServerPicker(true) },
                    enabled = state.servers.isNotEmpty(),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("New Tab", maxLines = 1)
                }
            }
        }

        // Session Tab Strip
        if (state.tabs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.tabs.forEach { tab ->
                    val selected = tab.tabId == state.activeTabId
                    val containerColor = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                    val contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        onClick = { viewModel.selectTab(tab.tabId) },
                        shape = RoundedCornerShape(999.dp),
                        color = containerColor,
                        contentColor = contentColor,
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.labelLarge.merge(design.monoNumericStyle),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(
                                onClick = { viewModel.closeTab(tab.tabId) },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close tab ${tab.title}",
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }

                Surface(
                    onClick = viewModel::toggleBroadcast,
                    shape = RoundedCornerShape(999.dp),
                    color = if (state.broadcastEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (state.broadcastEnabled) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CastConnected,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "Broadcast",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = state.errorMessage != null) {
            ExpressiveCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                tonalContainerColor = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearError) {
                        Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        val activeTab = state.tabs.firstOrNull { it.tabId == state.activeTabId }
        // Phase 7 reconnect banner: automatic retries run first (RECONNECTING); once they
        // are exhausted (DISCONNECTED) the banner offers a manual retry.
        val activeConnection = activeTab?.state
        AnimatedVisibility(
            visible = activeTab != null && activeConnection != TerminalConnectionState.CONNECTED,
        ) {
            val reconnecting = activeConnection == TerminalConnectionState.RECONNECTING
            ExpressiveCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                tonalContainerColor = if (reconnecting) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (reconnecting) {
                            val attempt = activeTab?.reconnectAttempt ?: 0
                            "Connection lost — reconnecting (attempt $attempt of 3)…"
                        } else {
                            "Connection to ${activeTab?.title.orEmpty()} lost"
                        },
                        color = if (reconnecting) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (!reconnecting) {
                        FilledTonalButton(
                            onClick = viewModel::reconnectActive,
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("Reconnect")
                        }
                    }
                }
            }
        }
        when {
            state.servers.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ExpressiveEmptyState(
                        icon = Icons.Rounded.Terminal,
                        title = "No SSH Hosts Configured",
                        description = "Add an SSH node in the Nodes tab before opening an interactive terminal session.",
                        outerShape = ExpressiveBadgeShape.Cookie12Sided,
                    )
                }
            }

            activeTab == null -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ExpressiveEmptyState(
                            icon = Icons.Rounded.Terminal,
                            title = "No Active SSH Sessions",
                            description = "Pick a configured node below or tap New Tab to launch an interactive PTY shell.",
                            actionLabel = "New Tab",
                            onAction = { viewModel.setShowServerPicker(true) },
                            outerShape = ExpressiveBadgeShape.Cookie12Sided,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            state.servers.forEach { srv ->
                                FilledTonalButton(
                                    onClick = { viewModel.openSessionForServer(srv.id) },
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Icon(Icons.Rounded.Dns, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(srv.name, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(state.preferences.terminalAppearance.themePreset.backgroundArgb),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    TerminalSurface(
                        controller = activeTab.controller,
                        appearance = state.preferences.terminalAppearance,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(24.dp)),
                    )
                }

                // Tactile Extra-Keys Dock
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            ExtraKeyButton("ESC") { viewModel.sendExtraKey(ExtraKeyCode.ESC) }
                            ExtraKeyButton("TAB") { viewModel.sendExtraKey(ExtraKeyCode.TAB) }
                            ExtraKeyButton(
                                label = "CTRL",
                                isActive = state.ctrlLatched,
                                onClick = { viewModel.sendExtraKey(ExtraKeyCode.CTRL) },
                            )
                            ExtraKeyButton(
                                label = "ALT",
                                isActive = state.altLatched,
                                onClick = { viewModel.sendExtraKey(ExtraKeyCode.ALT) },
                            )
                            ExtraKeyButton("↑") { viewModel.sendExtraKey(ExtraKeyCode.UP) }
                            ExtraKeyButton("↓") { viewModel.sendExtraKey(ExtraKeyCode.DOWN) }
                            ExtraKeyButton("←") { viewModel.sendExtraKey(ExtraKeyCode.LEFT) }
                            ExtraKeyButton("→") { viewModel.sendExtraKey(ExtraKeyCode.RIGHT) }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ExtraKeyButton("|") { viewModel.sendExtraKey(ExtraKeyCode.PIPE) }
                            ExtraKeyButton("~") { viewModel.sendExtraKey(ExtraKeyCode.TILDE) }
                            ExtraKeyButton("/") { viewModel.sendExtraKey(ExtraKeyCode.SLASH) }
                            ExtraKeyButton("-") { viewModel.sendExtraKey(ExtraKeyCode.DASH) }
                            ExtraKeyButton(":") { viewModel.sendExtraKey(ExtraKeyCode.COLON) }
                            ExtraKeyButton("Ctrl+C") { viewModel.sendExtraKey(ExtraKeyCode.CTRL_C) }
                            ExtraKeyButton("Ctrl+D") { viewModel.sendExtraKey(ExtraKeyCode.CTRL_D) }
                            // Phase 7: paste goes through the emulator's pasteText
                            // (bracketed paste), not a blind byte write.
                            Surface(
                                onClick = {
                                    val text = clipboard.getText()?.text
                                    if (!text.isNullOrEmpty()) viewModel.pasteFromClipboard(text)
                                },
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(14.dp))
                                    Text(
                                        text = "Paste",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            Surface(
                                onClick = { viewModel.setShowHistory(true) },
                                enabled = state.commandHistory.isNotEmpty(),
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(Icons.Rounded.History, null, modifier = Modifier.size(14.dp))
                                    Text(
                                        text = "History",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(82.dp))
    }

    if (state.showServerPicker) {
        ModalBottomSheet(onDismissRequest = { viewModel.setShowServerPicker(false) }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExpressiveSectionHeader(
                    title = "Open SSH Tab",
                    count = state.servers.size,
                )
                if (state.servers.isEmpty()) {
                    ExpressiveEmptyState(
                        icon = Icons.Rounded.Terminal,
                        title = "No SSH Hosts Configured",
                        description = "Add an SSH node in the Nodes tab before opening an interactive terminal session.",
                        outerShape = ExpressiveBadgeShape.Cookie12Sided,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        itemsIndexed(state.servers, key = { _, srv -> srv.id }) { index, srv ->
                            ExpressiveGroupedItem(
                                index = index,
                                count = state.servers.size,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                onClick = {
                                    viewModel.setShowServerPicker(false)
                                    viewModel.openSessionForServer(srv.id)
                                },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ExpressiveIconTile(
                                        icon = Icons.Rounded.Dns,
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = srv.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = "${srv.username}@${srv.primaryHost}:${srv.port}",
                                            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    if (state.showHistoryDrawer) {
        ModalBottomSheet(onDismissRequest = { viewModel.setShowHistory(false) }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExpressiveSectionHeader(
                    title = "Command History",
                    count = state.commandHistory.size,
                )
                if (state.commandHistory.isEmpty()) {
                    ExpressiveEmptyState(
                        icon = Icons.Rounded.History,
                        title = "No Commands Recorded",
                        description = "Commands sent from GRack to this node appear here (governed by the command-recording preference).",
                        outerShape = ExpressiveBadgeShape.Cookie9Sided,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        itemsIndexed(
                            state.commandHistory.asReversed(),
                            key = { index, _ -> "history-$index" },
                        ) { index, command ->
                            ExpressiveGroupedItem(
                                index = index,
                                count = state.commandHistory.size,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                onClick = {
                                    viewModel.setShowHistory(false)
                                    viewModel.sendText("$command\n")
                                },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ExpressiveIconTile(
                                        icon = Icons.Rounded.History,
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Text(
                                        text = command,
                                        style = MaterialTheme.typography.labelLarge.merge(design.monoNumericStyle),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
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

    if (state.showSnippetsDrawer) {
        ModalBottomSheet(onDismissRequest = { viewModel.setShowSnippets(false) }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExpressiveSectionHeader(
                    title = "Runbooks & Snippets",
                    count = state.snippets.size,
                )
                if (state.snippets.isEmpty()) {
                    ExpressiveEmptyState(
                        icon = Icons.Rounded.Bolt,
                        title = "No Snippets Saved",
                        description = "Create reusable command runbooks in Settings to execute them in any active shell.",
                        outerShape = ExpressiveBadgeShape.Cookie9Sided,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        itemsIndexed(state.snippets, key = { _, snip -> snip.id }) { index, snip ->
                            ExpressiveGroupedItem(
                                index = index,
                                count = state.snippets.size,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                onClick = { viewModel.triggerSnippet(snip) },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ExpressiveIconTile(
                                        icon = Icons.Rounded.Bolt,
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(
                                            text = snip.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = snip.commandTemplate,
                                            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
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

    state.pendingSnippetVariables?.let { (snippet, vars) ->
        val values = remember(snippet.id) { mutableStateMapOf<String, String>() }
        AlertDialog(
            onDismissRequest = viewModel::dismissSnippetVars,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    text = "Fill Parameters · ${snippet.name}",
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    vars.forEach { key ->
                        OutlinedTextField(
                            value = values[key].orEmpty(),
                            onValueChange = { values[key] = it },
                            label = { Text(key) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.submitSnippetWithVars(values.toMap()) },
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Run")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSnippetVars) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ExtraKeyButton(
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit,
) {
    val design = LocalGlassrackDesign.current
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
