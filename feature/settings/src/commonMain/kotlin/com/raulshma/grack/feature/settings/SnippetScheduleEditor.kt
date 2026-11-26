package com.raulshma.grack.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raulshma.grack.core.designsystem.LocalGlassrackDesign
import com.raulshma.grack.core.designsystem.monoNumericStyle
import com.raulshma.grack.core.model.ScheduleSpec
import com.raulshma.grack.core.model.ScheduleValidation
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.Snippet
import com.raulshma.grack.core.model.parseScheduleSpec
import com.raulshma.grack.core.model.snippetTargetServers
import com.raulshma.grack.core.model.serializeScheduleSpec
import com.raulshma.grack.core.model.validateSnippetSchedule

/**
 * Schedule editor for a Command Runbook (Phase 4). The interval pill row maps directly to
 * the documented `scheduleSpec` strings; node targeting reuses the Broadcast feature's
 * tag-matching semantics via [NodeTagMultiSelectField]. Confirmation-requiring snippets
 * are rejected with an explanatory message ([validateSnippetSchedule]) — they can never
 * run unattended.
 */
@Composable
fun SnippetScheduleEditorDialog(
    snippet: Snippet,
    servers: List<Server>,
    onSave: (specRaw: String?, targets: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val design = LocalGlassrackDesign.current
    var specDraft by remember { mutableStateOf(parseScheduleSpec(snippet.scheduleSpec)) }
    var targetsDraft by remember { mutableStateOf(snippet.scheduleTargets ?: snippet.targetTags) }
    val validation = validateSnippetSchedule(snippet, specDraft)
    val changed = specDraft != parseScheduleSpec(snippet.scheduleSpec) ||
        targetsDraft != (snippet.scheduleTargets ?: snippet.targetTags)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Schedule '${snippet.name}'", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Run interval",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ScheduleSpecPill(
                        label = "Off",
                        selected = specDraft == null,
                        onClick = { specDraft = null },
                    )
                    SCHEDULE_INTERVAL_OPTIONS.forEach { (label, spec) ->
                        ScheduleSpecPill(
                            label = label,
                            selected = specDraft == spec,
                            onClick = { specDraft = spec },
                        )
                    }
                }
                Text(
                    text = when (val spec = specDraft) {
                        null -> "No schedule: the runbook only runs when broadcast manually."
                        is ScheduleSpec.Interval ->
                            "Runs every ${spec.minutes} minutes per node while the phone has network " +
                                "and battery is not low. Requires the GRack app to have run recently."
                        ScheduleSpec.Daily ->
                            "Runs once a day per node, re-armed after each run. Requires the GRack " +
                                "app to have run recently."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Target nodes",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                NodeTagMultiSelectField(
                    servers = servers,
                    selectedTags = targetsDraft,
                    onSelectionChange = { targetsDraft = it },
                )

                when (validation) {
                    is ScheduleValidation.Invalid -> Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = validation.reason,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                    ScheduleValidation.Valid -> Unit
                }

                Text(
                    text = "Scheduled runs execute unattended (audited as SCHEDULED) — no " +
                        "confirmation is asked at run time.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                snippet.lastRunStatus?.let { status ->
                    Text(
                        text = "Last scheduled run: $status",
                        style = MaterialTheme.typography.labelSmall.merge(design.monoNumericStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        specDraft?.let { serializeScheduleSpec(it) },
                        targetsDraft,
                    )
                    onDismiss()
                },
                enabled = changed && validation is ScheduleValidation.Valid,
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(if (specDraft == null) "Clear Schedule" else "Save Schedule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(999.dp)) {
                Text("Cancel")
            }
        },
    )
}

/** Interval presets the editor offers; each serializes to its documented spec string. */
private val SCHEDULE_INTERVAL_OPTIONS: List<Pair<String, ScheduleSpec>> = listOf(
    "15m" to ScheduleSpec.Interval(15),
    "30m" to ScheduleSpec.Interval(30),
    "1h" to ScheduleSpec.Interval(60),
    "6h" to ScheduleSpec.Interval(360),
    "12h" to ScheduleSpec.Interval(720),
    "Daily" to ScheduleSpec.Daily,
)

@Composable
private fun ScheduleSpecPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * The Broadcast feature's node-targeting multi-select: one chip per distinct node tag,
 * multiple selectable, with the live "runs on N of M node(s)" count from
 * [snippetTargetServers] (empty selection = every node, exactly like broadcast).
 */
@Composable
fun NodeTagMultiSelectField(
    servers: List<Server>,
    selectedTags: List<String>,
    onSelectionChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allTags = servers.flatMap { it.tags }.distinct().sorted()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val allSelected = selectedTags.isEmpty()
            Surface(
                onClick = { onSelectionChange(emptyList()) },
                shape = RoundedCornerShape(999.dp),
                color = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (allSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = "All nodes",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            allTags.forEach { tag ->
                val selected = tag in selectedTags
                Surface(
                    onClick = {
                        onSelectionChange(
                            if (selected) selectedTags - tag else selectedTags + tag,
                        )
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            if (allTags.isEmpty()) {
                Text(
                    text = "No tags in use — every node matches",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Runs on ${snippetTargetServers(servers, selectedTags).size} of ${servers.size} node(s) — " +
                "same matching as broadcasts (assign tags on a node's edit sheet)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
