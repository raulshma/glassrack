@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.raulshma.grack.feature.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raulshma.grack.core.designsystem.ExpressiveCard
import com.raulshma.grack.core.designsystem.ExpressiveSectionHeader
import com.raulshma.grack.core.designsystem.ExpressiveShapeBadge
import com.raulshma.grack.core.designsystem.ExpressiveBadgeShape
import com.raulshma.grack.core.designsystem.LocalGlassrackDesign
import com.raulshma.grack.core.designsystem.monoNumericStyle
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.ContainerUpdateStatus
import com.raulshma.grack.core.model.DistroFamily
import com.raulshma.grack.core.model.Formatters
import com.raulshma.grack.core.model.NodeUpdateReport
import com.raulshma.grack.core.model.OsScanStatus
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.ServerHealth
import com.raulshma.grack.core.model.currentTimeEpochMs

/**
 * Overview-tab Updates card and its list sheet (Phase 3B item 3): per-node counts of
 * pending OS packages and container images, "Scan now", and the apply actions.
 */
@Composable
fun UpdatesCard(
    state: UpdatesUiState,
    onScanNow: () -> Unit,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val design = LocalGlassrackDesign.current
    val scanning = state.isScanning || state.busyServerIds.isNotEmpty()
    ExpressiveCard(
        modifier = modifier,
        tonalContainerColor = if (state.hasAnyUpdates) {
            design.statusWarnContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        onClick = onOpenSheet,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    ExpressiveShapeBadge(
                        shape = ExpressiveBadgeShape.SoftBurst,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        size = 36.dp,
                        icon = Icons.Rounded.SystemUpdateAlt,
                    )
                    Column {
                        Text(
                            text = "Updates",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (state.totalOsUpdates == 0 && state.totalContainerUpdates == 0) {
                                "No pending updates found"
                            } else {
                                "${state.totalOsUpdates} OS · ${state.totalContainerUpdates} containers"
                            },
                            style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (scanning) {
                    LoadingIndicator(modifier = Modifier.size(26.dp))
                } else {
                    Surface(
                        onClick = onScanNow,
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "Scan now",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            if (state.servers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.servers.take(3).forEach { server ->
                        val report = state.reports[server.id] ?: return@forEach
                        val reboot = state.rebootRecommended[server.id] == true
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = server.name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (reboot) {
                                RebootRecommendedChip()
                            }
                            Text(
                                text = if (report.scannedAtEpochMs == 0L) {
                                    "not scanned"
                                } else {
                                    "${report.osUpdateCount} OS · ${report.containerUpdateCount} ctr"
                                },
                                style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.servers.size > 3) {
                        Text(
                            text = "+ ${state.servers.size - 3} more nodes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RebootRecommendedChip() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.RestartAlt,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = "Reboot recommended",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The list sheet: per node, the found OS packages and container verdicts, with the apply
 * actions — "Apply OS updates" (privileged + gated), per-stack Update, per-container Pull.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesSheet(
    state: UpdatesUiState,
    onDismiss: () -> Unit,
    onScanNow: () -> Unit,
    onApplyOs: (Server) -> Unit,
    onUpdateStack: (Server, projectName: String, workingDir: String?, configFiles: String?) -> Unit,
    onPullContainer: (Server, ContainerInfo) -> Unit,
) {
    val design = LocalGlassrackDesign.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveSectionHeader(
                title = "Pending Updates",
                count = state.totalOsUpdates + state.totalContainerUpdates,
                actionLabel = if (state.isScanning) null else "Scan now",
                onAction = onScanNow,
            )

            if (state.servers.isEmpty()) {
                Text(
                    text = "Add a node to scan for updates.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.servers, key = { it.id }) { server ->
                        val report = state.reports[server.id]
                        val health = state.serverHealth[server.id]
                        val busy = server.id in state.busyServerIds
                        val reachable = health == ServerHealth.ONLINE || health == ServerHealth.WARNING
                        NodeUpdatesSection(
                            server = server,
                            report = report,
                            reachable = reachable,
                            busy = busy,
                            rebootRecommended = state.rebootRecommended[server.id] == true,
                            onApplyOs = { onApplyOs(server) },
                            onUpdateStack = { project, dir, files -> onUpdateStack(server, project, dir, files) },
                            onPullContainer = { container -> onPullContainer(server, container) },
                        )
                    }
                }
            }

            state.statusBanner?.let { banner ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = banner,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeUpdatesSection(
    server: Server,
    report: NodeUpdateReport?,
    reachable: Boolean,
    busy: Boolean,
    rebootRecommended: Boolean,
    onApplyOs: () -> Unit,
    onUpdateStack: (project: String, workingDir: String?, configFiles: String?) -> Unit,
    onPullContainer: (ContainerInfo) -> Unit,
) {
    val design = LocalGlassrackDesign.current
    ExpressiveCard(tonalContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = report?.let { r ->
                        if (r.scannedAtEpochMs == 0L) {
                            "never scanned"
                        } else {
                            Formatters.formatWidgetAge(r.scannedAtEpochMs, currentTimeEpochMs())
                        }
                    } ?: "never scanned",
                    style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LoadingIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = "Working...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (report == null || report.scannedAtEpochMs == 0L) {
                Text(
                    text = "Tap \"Scan now\" to check this node.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                report.os?.let { os ->
                    when (os.status) {
                        OsScanStatus.OK -> {
                            if (os.packages.isEmpty()) {
                                SectionLine("OS packages: up to date (${familyLabel(os.family)})")
                            } else {
                                SectionLine("OS packages (${familyLabel(os.family)}): ${os.packages.size} pending")
                                os.packages.take(6).forEach { pkg ->
                                    Text(
                                        text = "· ${pkg.packageName} ${pkg.currentVersion.orEmpty()} " +
                                            "→ ${pkg.candidateVersion.orEmpty()}",
                                        style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                                if (os.packages.size > 6) {
                                    Text(
                                        text = "+ ${os.packages.size - 6} more packages",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                        OsScanStatus.UNSUPPORTED -> SectionLine("OS updates: unsupported (${os.detail})")
                        OsScanStatus.FAILED -> SectionLine("OS scan failed (${os.detail})")
                    }
                }

                val containerUpdates = report.containers.filter {
                    it.status == ContainerUpdateStatus.UPDATE_AVAILABLE
                }
                val otherContainers = report.containers.filter {
                    it.status != ContainerUpdateStatus.UPDATE_AVAILABLE
                }
                if (report.containers.isEmpty()) {
                    SectionLine("Containers: nothing to check on this node")
                } else if (containerUpdates.isEmpty()) {
                    SectionLine("Containers: ${otherContainers.size} checked, all current or skipped")
                } else {
                    SectionLine("Containers: ${containerUpdates.size} with updates")
                    containerUpdates.forEach { entry ->
                        Text(
                            text = "· ${entry.containerName} (${entry.image})",
                            style = MaterialTheme.typography.bodySmall.merge(design.monoNumericStyle),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (rebootRecommended) {
                        RebootRecommendedChip()
                    }
                    val os = report.os
                    val osPending = os != null && os.status == OsScanStatus.OK && os.packages.isNotEmpty()
                    if (osPending && reachable) {
                        FilledTonalButton(
                            onClick = onApplyOs,
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Rounded.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Apply OS updates")
                        }
                    }
                    containerUpdates
                        .filter { !it.composeProject.isNullOrBlank() }
                        .groupBy { it.composeProject!! }
                        .forEach { (project, entries) ->
                            val dir = entries.firstNotNullOfOrNull { it.composeWorkingDir }
                            val files = entries.firstNotNullOfOrNull { it.composeConfigFiles }
                            FilledTonalButton(
                                onClick = { onUpdateStack(project, dir, files) },
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Icon(Icons.Rounded.ViewInAr, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Update stack: $project")
                            }
                        }
                    containerUpdates
                        .filter { it.composeProject.isNullOrBlank() }
                        .forEach { entry ->
                            FilledTonalButton(
                                onClick = {
                                    onPullContainer(
                                        ContainerInfo(
                                            id = entry.containerName,
                                            shortId = entry.containerName,
                                            name = entry.containerName,
                                            image = entry.image,
                                        ),
                                    )
                                },
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Pull ${entry.containerName}")
                            }
                        }
                }
                if (!reachable) {
                    Text(
                        text = "Node is offline; apply actions are unavailable.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Short package-manager label for the OS section header. */
private fun familyLabel(family: DistroFamily): String = when (family) {
    DistroFamily.DEBIAN -> "apt"
    DistroFamily.RHEL -> "dnf"
    DistroFamily.ARCH -> "pacman"
    DistroFamily.ALPINE -> "apk"
    DistroFamily.UNSUPPORTED -> "unknown"
}
