package com.example.glassrack.feature.audit

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.glassrack.core.designsystem.LocalGlassrackDesign
import com.example.glassrack.core.designsystem.RackBladeCard
import com.example.glassrack.core.model.AuditOutcome
import com.example.glassrack.core.model.Formatters
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(
    modifier: Modifier = Modifier,
    viewModel: AuditViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val design = LocalGlassrackDesign.current
    val scope = rememberCoroutineScope()
    var showExportMenu by remember { mutableStateOf(false) }
    var pendingExportFormat by remember { mutableStateOf("json") }

    val saverLauncher = rememberFileSaverLauncher(
        dialogSettings = FileKitDialogSettings.createDefault(),
    ) { file ->
        if (file != null) {
            scope.launch {
                val payload = viewModel.buildExportContent(pendingExportFormat)
                file.writeString(payload)
                viewModel.setFeedback("Exported audit ledger (${pendingExportFormat.uppercase()})")
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Audit Ledger", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${state.entries.size} entries · Secret-scrubbed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row {
                IconButton(onClick = viewModel::verifyChain) {
                    Icon(Icons.Rounded.VerifiedUser, contentDescription = "Verify Hash Chain")
                }
                OutlinedButton(onClick = { showExportMenu = true }) {
                    Icon(Icons.Rounded.FileDownload, null, modifier = Modifier.size(16.dp))
                    Text("Export")
                }
                DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                    listOf("json" to "JSON", "csv" to "CSV", "md" to "Markdown").forEach { (ext, label) ->
                        DropdownMenuItem(
                            text = { Text("Export as $label") },
                            onClick = {
                                showExportMenu = false
                                pendingExportFormat = ext
                                saverLauncher.launch(suggestedName = "glassrack-audit", extension = ext)
                            },
                        )
                    }
                }
            }
        }

        state.verification?.let { ver ->
            Surface(
                color = if (ver.isValid) design.statusOk.copy(alpha = 0.14f) else design.statusFault.copy(alpha = 0.16f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (ver.isValid) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                        contentDescription = null,
                        tint = if (ver.isValid) design.statusOk else design.statusFault,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (ver.isValid) {
                            "SHA-256 Chain Intact · ${ver.totalEntries} verified"
                        } else {
                            "Tamper Warning at ${ver.brokenEntryId}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        state.exportFeedback?.let { msg ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.setFeedback(null) }) { Text("OK") }
                }
            }
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearch,
            placeholder = { Text("Search action, server, or sanitized command...") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AuditFilterChip.entries.forEach { chip ->
                FilterChip(
                    selected = state.selectedFilter == chip,
                    onClick = { viewModel.setFilter(chip) },
                    label = { Text(chip.label) },
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(design.cardSpacing),
        ) {
            items(state.entries, key = { it.id }) { entry ->
                val accent = when (entry.outcome) {
                    AuditOutcome.SUCCESS -> design.statusOk
                    AuditOutcome.BLOCKED, AuditOutcome.CANCELLED -> design.statusWarn
                    else -> design.statusFault
                }
                RackBladeCard(
                    accentColor = accent,
                    onClick = { viewModel.inspectEntry(entry) },
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(entry.actionTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            text = "${entry.outcome.name} · ${Formatters.formatDuration(entry.durationMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                    Text(
                        text = "${entry.serverNameSnapshot} · via ${entry.triggerSource.name} · ${entry.category.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$ ${entry.commandSanitized}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.exitCode?.let {
                        Text("Exit code: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    state.inspectedEntry?.let { entry ->
        ModalBottomSheet(onDismissRequest = { viewModel.inspectEntry(null) }) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.actionTitle, style = MaterialTheme.typography.titleLarge)
                Text("Server: ${entry.serverNameSnapshot} · Source: ${entry.triggerSource.name}", style = MaterialTheme.typography.bodySmall)
                Text("Command: ${entry.commandSanitized}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (entry.outputSnippet.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Text(entry.outputSnippet, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                }
                Text("Prev Hash: ${entry.prevHash.take(24)}…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                Text("Entry Hash: ${entry.entryHash.take(24)}…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
