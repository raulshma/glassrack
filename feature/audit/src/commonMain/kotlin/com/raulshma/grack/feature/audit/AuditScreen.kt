package com.raulshma.grack.feature.audit

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
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.raulshma.grack.core.designsystem.ExpressiveHeroCard
import com.raulshma.grack.core.designsystem.ExpressiveIconTile
import com.raulshma.grack.core.designsystem.ExpressiveSearchField
import com.raulshma.grack.core.designsystem.ExpressiveSectionHeader
import com.raulshma.grack.core.designsystem.LocalGlassrackDesign
import com.raulshma.grack.core.designsystem.expressiveHorizontalGroupShape
import com.raulshma.grack.core.designsystem.monoNumericStyle
import com.raulshma.grack.core.model.AuditEntry
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.ConfirmationLevel
import com.raulshma.grack.core.model.Formatters
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
    var showPruneConfirm by remember { mutableStateOf(false) }

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
        // Editorial Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "Audit Log",
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
                        text = "${state.entries.size} entries",
                        style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Box {
                FilledTonalButton(
                    onClick = { showExportMenu = true },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export")
                }
                DropdownMenu(
                    expanded = showExportMenu,
                    onDismissRequest = { showExportMenu = false },
                ) {
                    listOf("json" to "JSON", "csv" to "CSV", "md" to "Markdown").forEach { (ext, label) ->
                        DropdownMenuItem(
                            text = { Text("Export as $label") },
                            onClick = {
                                showExportMenu = false
                                pendingExportFormat = ext
                                saverLauncher.launch(suggestedName = "grack-audit", extension = ext)
                            },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = state.exportFeedback != null) {
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.exportFeedback.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.setFeedback(null) }) { Text("Dismiss") }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item(key = "integrity-hero") {
                val verification = state.verification
                val isChainValid = verification?.isValid != false
                val heroBg = if (isChainValid) {
                    design.statusOkContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
                val heroFg = if (isChainValid) {
                    design.onStatusOkContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
                val headline = if (isChainValid) "Chain Verified" else "Tamper Alert"
                val subtitle = when {
                    verification == null -> "Verifying cryptographic SHA-256 chain..."
                    verification.isValid -> "${verification.totalEntries} SHA-256 chained events intact"
                    else -> "Hash mismatch at entry ${verification.brokenEntryId ?: "unknown"} (${verification.totalEntries} scanned)"
                }

                ExpressiveHeroCard(
                    eyebrow = "CRYPTOGRAPHIC LEDGER",
                    headline = headline,
                    subtitle = subtitle,
                    containerColor = heroBg,
                    contentColor = heroFg,
                    badgeIcon = if (isChainValid) Icons.Rounded.VerifiedUser else Icons.Rounded.Warning,
                    badgeShape = if (isChainValid) ExpressiveBadgeShape.Cookie9Sided else ExpressiveBadgeShape.SoftBurst,
                    actions = {
                        HeroConnectedPillAction(
                            label = "Verify",
                            icon = Icons.Rounded.VerifiedUser,
                            shape = expressiveHorizontalGroupShape(0, 2),
                            containerColor = heroFg.copy(alpha = 0.14f),
                            contentColor = heroFg,
                            onClick = viewModel::verifyChain,
                            modifier = Modifier.weight(1f),
                        )
                        HeroConnectedPillAction(
                            label = "Prune",
                            icon = Icons.Rounded.DeleteSweep,
                            shape = expressiveHorizontalGroupShape(1, 2),
                            containerColor = heroFg.copy(alpha = 0.14f),
                            contentColor = heroFg,
                            onClick = { showPruneConfirm = true },
                            modifier = Modifier.weight(1f),
                        )
                    },
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            item(key = "search-and-filters") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ExpressiveSearchField(
                        query = state.searchQuery,
                        onQueryChange = viewModel::setSearch,
                        placeholder = "Search action, server, or sanitized command...",
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AuditFilterChip.entries.forEach { chip ->
                            val selected = state.selectedFilter == chip
                            Surface(
                                onClick = { viewModel.setFilter(chip) },
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
                                    text = chip.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (state.entries.isEmpty()) {
                item(key = "audit-empty") {
                    val hasEntries = state.totalEntryCount > 0
                    ExpressiveEmptyState(
                        icon = Icons.Rounded.History,
                        title = if (hasEntries) "No Matching Audit Events" else "No Audit Events Yet",
                        description = if (hasEntries) {
                            "No logged events match your current search or category filter."
                        } else {
                            "Executed commands, container actions, and host key verifications are cryptographically logged here."
                        },
                        actionLabel = if (hasEntries) "Clear Filters" else null,
                        onAction = if (hasEntries) {
                            {
                                viewModel.setSearch("")
                                viewModel.setFilter(AuditFilterChip.ALL)
                            }
                        } else null,
                        outerShape = ExpressiveBadgeShape.Cookie9Sided,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                item(key = "timeline-header") {
                    ExpressiveSectionHeader(
                        title = "Timeline",
                        count = state.entries.size,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                    )
                }
                itemsIndexed(state.entries, key = { _, entry -> entry.id }) { index, entry ->
                    AuditTimelineRow(
                        index = index,
                        count = state.entries.size,
                        entry = entry,
                        onClick = { viewModel.inspectEntry(entry) },
                    )
                }
            }
        }
    }

    if (showPruneConfirm) {
        ActionSafetyDialog(
            title = "Prune Expired Audit Events?",
            description = "Removes audit log entries older than ${state.retentionDays} days and re-anchors the remaining SHA-256 chain.",
            confirmationLevel = ConfirmationLevel.DIALOG,
            onConfirm = {
                showPruneConfirm = false
                viewModel.pruneOldEntries()
            },
            onDismiss = { showPruneConfirm = false },
        )
    }

    state.inspectedEntry?.let { entry ->
        val visual = outcomeVisuals(entry)
        ModalBottomSheet(onDismissRequest = { viewModel.inspectEntry(null) }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ExpressiveIconTile(
                        icon = visual.icon,
                        containerColor = visual.containerColor,
                        contentColor = visual.contentColor,
                        size = 44.dp,
                        iconSize = 22.dp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.actionTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${entry.serverNameSnapshot} · ${entry.triggerSource.name} · ${Formatters.formatTimestamp(entry.timestampEpochMs)}",
                            style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AuditPill(
                        text = visual.label,
                        containerColor = visual.containerColor,
                        contentColor = visual.contentColor,
                    )
                }

                ExpressiveSectionHeader(title = "Sanitized Command")
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "$ ${entry.commandSanitized}",
                        style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(14.dp),
                    )
                }

                ExpressiveSectionHeader(title = "Output Snippet")
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = entry.outputSnippet.ifBlank { "No stdout/stderr captured for this event." },
                        style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                        color = if (entry.outputSnippet.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(14.dp),
                    )
                }

                ExpressiveSectionHeader(title = "SHA-256 Chain Proof")
                ExpressiveGroupedCard {
                    val hashRows = listOf(
                        "Entry ID" to entry.id,
                        "Duration" to Formatters.formatDuration(entry.durationMs),
                        "Previous Hash" to entry.prevHash,
                        "Entry Hash" to entry.entryHash,
                    )
                    hashRows.forEachIndexed { idx, (label, value) ->
                        ExpressiveGroupedItem(
                            index = idx,
                            count = hashRows.size,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AuditTimelineRow(
    index: Int,
    count: Int,
    entry: AuditEntry,
    onClick: () -> Unit,
) {
    val design = LocalGlassrackDesign.current
    val visual = outcomeVisuals(entry)

    ExpressiveGroupedItem(
        index = index,
        count = count,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveIconTile(
                icon = visual.icon,
                containerColor = visual.containerColor,
                contentColor = visual.contentColor,
                size = 40.dp,
                iconSize = 20.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = entry.actionTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${entry.serverNameSnapshot} · ${entry.triggerSource.name} · ${Formatters.formatTimestamp(entry.timestampEpochMs)}",
                    style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "$ ${entry.commandSanitized}",
            style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuditPill(
                text = visual.label,
                containerColor = visual.containerColor,
                contentColor = visual.contentColor,
            )
            AuditPill(
                text = Formatters.formatDuration(entry.durationMs),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AuditPill(
                text = entry.category.name,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun HeroConnectedPillAction(
    label: String,
    icon: ImageVector,
    shape: androidx.compose.ui.graphics.Shape,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AuditPill(
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

private data class OutcomeVisual(
    val icon: ImageVector,
    val label: String,
    val containerColor: Color,
    val contentColor: Color,
)

@Composable
private fun outcomeVisuals(entry: AuditEntry): OutcomeVisual {
    val design = LocalGlassrackDesign.current
    return when (entry.outcome) {
        AuditOutcome.SUCCESS -> OutcomeVisual(
            icon = Icons.Rounded.CheckCircle,
            label = "OK",
            containerColor = design.statusOkContainer,
            contentColor = design.onStatusOkContainer,
        )
        AuditOutcome.BLOCKED -> OutcomeVisual(
            icon = Icons.Rounded.Block,
            label = "BLOCKED",
            containerColor = design.statusWarnContainer,
            contentColor = design.onStatusWarnContainer,
        )
        AuditOutcome.CANCELLED -> OutcomeVisual(
            icon = Icons.Rounded.Block,
            label = "CANCELLED",
            containerColor = design.statusWarnContainer,
            contentColor = design.onStatusWarnContainer,
        )
        AuditOutcome.TIMEOUT -> OutcomeVisual(
            icon = Icons.Rounded.Error,
            label = "TIMEOUT",
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        AuditOutcome.FAILED -> OutcomeVisual(
            icon = Icons.Rounded.Error,
            label = entry.exitCode?.let { "EXIT $it" } ?: "FAILED",
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
