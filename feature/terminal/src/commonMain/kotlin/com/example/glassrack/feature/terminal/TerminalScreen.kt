package com.example.glassrack.feature.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.glassrack.core.designsystem.LocalGlassrackDesign
import com.example.glassrack.core.designsystem.RackBladeCard
import com.example.glassrack.core.terminal.ExtraKeyCode
import com.example.glassrack.core.terminal.TerminalSurface
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
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
        // Session Tab Bar
        Surface(color = design.rackSurfaceElevated) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.tabs.forEach { tab ->
                    val selected = tab.tabId == state.activeTabId
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.selectTab(tab.tabId) },
                        label = { Text(tab.title, fontFamily = FontFamily.Monospace) },
                        trailingIcon = {
                            IconButton(
                                onClick = { viewModel.closeTab(tab.tabId) },
                                modifier = Modifier.size(18.dp),
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Close tab", modifier = Modifier.size(14.dp))
                            }
                        },
                    )
                }
                IconButton(onClick = { viewModel.setShowServerPicker(true) }) {
                    Icon(Icons.Rounded.Add, contentDescription = "New SSH Tab")
                }
                Spacer(Modifier.weight(1f, fill = false))
                FilterChip(
                    selected = state.broadcastEnabled,
                    onClick = viewModel::toggleBroadcast,
                    leadingIcon = { Icon(Icons.Rounded.CastConnected, null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Broadcast") },
                )
                IconButton(onClick = { viewModel.setShowSnippets(true) }) {
                    Icon(Icons.Rounded.Bolt, contentDescription = "Snippets")
                }
            }
        }

        state.errorMessage?.let { err ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
                }
            }
        }

        val activeTab = state.tabs.firstOrNull { it.tabId == state.activeTabId }
        if (activeTab == null) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Rounded.Terminal, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("No Active SSH Sessions", style = MaterialTheme.typography.titleLarge)
                    Text("Pick a node below to launch a multiplexed PTY shell.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.servers.forEach { srv ->
                            Button(onClick = { viewModel.openSessionForServer(srv.id) }) {
                                Text(srv.name)
                            }
                        }
                    }
                }
            }
        } else {
            TerminalSurface(
                controller = activeTab.controller,
                appearance = state.preferences.terminalAppearance,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            // Customizable 2-Row Extra-Keys Toolbar
            Surface(color = design.rackSurfaceElevated, tonalElevation = 3.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ExtraKeyButton("ESC") { viewModel.sendExtraKey(ExtraKeyCode.ESC) }
                        ExtraKeyButton("TAB") { viewModel.sendExtraKey(ExtraKeyCode.TAB) }
                        ExtraKeyButton("CTRL") { viewModel.sendExtraKey(ExtraKeyCode.CTRL) }
                        ExtraKeyButton("ALT") { viewModel.sendExtraKey(ExtraKeyCode.ALT) }
                        ExtraKeyButton("↑") { viewModel.sendExtraKey(ExtraKeyCode.UP) }
                        ExtraKeyButton("↓") { viewModel.sendExtraKey(ExtraKeyCode.DOWN) }
                        ExtraKeyButton("←") { viewModel.sendExtraKey(ExtraKeyCode.LEFT) }
                        ExtraKeyButton("→") { viewModel.sendExtraKey(ExtraKeyCode.RIGHT) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ExtraKeyButton("|") { viewModel.sendExtraKey(ExtraKeyCode.PIPE) }
                        ExtraKeyButton("~") { viewModel.sendExtraKey(ExtraKeyCode.TILDE) }
                        ExtraKeyButton("/") { viewModel.sendExtraKey(ExtraKeyCode.SLASH) }
                        ExtraKeyButton("-") { viewModel.sendExtraKey(ExtraKeyCode.DASH) }
                        ExtraKeyButton(":") { viewModel.sendExtraKey(ExtraKeyCode.COLON) }
                        ExtraKeyButton("Ctrl+C") { viewModel.sendExtraKey(ExtraKeyCode.CTRL_C) }
                        ExtraKeyButton("Ctrl+D") { viewModel.sendExtraKey(ExtraKeyCode.CTRL_D) }
                        OutlinedButton(
                            onClick = {
                                val text = clipboard.getText()?.text
                                if (!text.isNullOrEmpty()) viewModel.sendText(text)
                            },
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Paste", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(72.dp))
    }

    if (state.showServerPicker) {
        ModalBottomSheet(onDismissRequest = { viewModel.setShowServerPicker(false) }) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Open SSH Tab", style = MaterialTheme.typography.titleLarge)
                state.servers.forEach { srv ->
                    RackBladeCard(onClick = {
                        viewModel.setShowServerPicker(false)
                        viewModel.openSessionForServer(srv.id)
                    }) {
                        Text(srv.name, style = MaterialTheme.typography.titleMedium)
                        Text("${srv.username}@${srv.primaryHost}:${srv.port}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (state.showSnippetsDrawer) {
        ModalBottomSheet(onDismissRequest = { viewModel.setShowSnippets(false) }) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Runbooks & Snippets", style = MaterialTheme.typography.titleLarge)
                LazyColumn(modifier = Modifier.height(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.snippets, key = { it.id }) { snip ->
                        RackBladeCard(onClick = { viewModel.triggerSnippet(snip) }) {
                            Text(snip.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(snip.commandTemplate, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    state.pendingSnippetVariables?.let { (snippet, vars) ->
        val values = remember(snippet.id) { mutableStateMapOf<String, String>() }
        AlertDialog(
            onDismissRequest = viewModel::dismissSnippetVars,
            title = { Text("Fill Parameters · ${snippet.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vars.forEach { key ->
                        OutlinedTextField(
                            value = values[key].orEmpty(),
                            onValueChange = { values[key] = it },
                            label = { Text(key) },
                            singleLine = true,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.submitSnippetWithVars(values.toMap()) }) { Text("Run") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSnippetVars) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ExtraKeyButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}
