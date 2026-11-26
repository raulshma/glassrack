package com.raulshma.grack.core.ssh

import co.touchlab.kermit.Logger
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.ContainerRuntime
import com.raulshma.grack.core.model.ContainerUpdateStatus
import com.raulshma.grack.core.model.MAX_CONSECUTIVE_SCHEDULE_FAILURES
import com.raulshma.grack.core.model.SCHEDULE_STATUS_BLOCKED_CONFIRMATION
import com.raulshma.grack.core.model.SCHEDULE_STATUS_SKIPPED_OFFLINE
import com.raulshma.grack.core.model.SCHEDULE_STATUS_SUCCEEDED
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.ServerHealth
import com.raulshma.grack.core.model.Snippet
import com.raulshma.grack.core.model.TriggerSource
import com.raulshma.grack.core.model.UpdatePolicy
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.model.currentTimeEpochMs
import com.raulshma.grack.core.model.parseScheduleSpec
import com.raulshma.grack.core.model.scheduleFailureStatus
import com.raulshma.grack.core.model.scheduleFailureStreak
import com.raulshma.grack.core.model.snippetTargetServers
import com.raulshma.grack.core.security.SecureVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The body of the Phase 4 scheduled runs (request #2 and AUTO), kept free of WorkManager
 * types so the logic is testable against the fake SSH client: the Android
 * `ScheduledRunWorker` delegates here and maps the outcomes to WorkManager results,
 * cancellations and notifications.
 *
 * Time budgets (the worker must finish within about 9 minutes):
 * - SNIPPET: targets run concurrently, each under [SNIPPET_TARGET_BUDGET_MS] — one slow or
 *   offline node consumes its own budget, never another target's (per-target isolation).
 * - AUTO_UPDATE: the whole scan+apply cycle is bounded by [AUTO_RUN_BUDGET_MS].
 *
 * Failure handling: a snippet's consecutive-failure streak is persisted in
 * `snippets.lastRunStatus` (see [com.raulshma.grack.core.model.scheduleFailureStreak]);
 * when the streak reaches [MAX_CONSECUTIVE_SCHEDULE_FAILURES] the outcome tells the worker
 * to cancel the unique work. AUTO updates have no last-run columns on `servers`; a failed
 * unattended apply is surfaced through the summary notification instead of silently
 * stopping the schedule.
 *
 * Targeting: scheduled snippets use [Snippet.scheduleTargets] when set, else fall back to
 * the broadcast [Snippet.targetTags] semantics (empty targets = every node). Nodes whose
 * runtime health is explicitly OFFLINE are skipped; every other health value is attempted,
 * because a freshly started worker process has UNKNOWN health for all nodes and schedules
 * must still run.
 */
class ScheduledRunCoordinator(
    private val repository: GlassrackRepository,
    private val controller: HomelabController,
    private val vault: SecureVault,
    private val nowEpochMs: () -> Long = ::currentTimeEpochMs,
) {

    // ------------------------------------------------------------------
    // SNIPPET
    // ------------------------------------------------------------------

    /**
     * Runs one scheduled snippet across its targets. Persists `lastRunEpochMs` /
     * `lastRunStatus` and audits every per-target execution with
     * [TriggerSource.SCHEDULED] through the same audited path the Broadcast uses.
     */
    suspend fun runScheduledSnippet(snippetId: String): SnippetRunOutcome {
        val snippet = runCatching { repository.getSnippetById(snippetId) }
            .onFailure { Logger.w(it) { "Scheduled snippet lookup failed" } }
            .getOrNull()
            ?: return SnippetRunOutcome(snippetId = snippetId, scheduleRemoved = true, stopSchedule = true)

        val spec = parseScheduleSpec(snippet.scheduleSpec)
        if (spec == null) {
            // The schedule was cleared (or the spec is unparseable): nothing to run and the
            // reconciler will drop the work; cancel defensively so nothing lingers.
            return SnippetRunOutcome(snippetId = snippetId, scheduleRemoved = true, stopSchedule = true)
        }

        // Worker-level guard (mirrors the UI validator): a snippet that requires
        // confirmation must never execute unattended.
        if (snippet.requiresConfirmation) {
            repository.updateSnippetLastRun(snippet.id, nowEpochMs(), SCHEDULE_STATUS_BLOCKED_CONFIRMATION)
            return SnippetRunOutcome(
                snippetId = snippetId,
                status = SCHEDULE_STATUS_BLOCKED_CONFIRMATION,
                stopSchedule = true,
                blockedConfirmation = true,
            )
        }

        val servers = runCatching { repository.getServersOnce() }.getOrDefault(emptyList())
        val targets = snippetTargetServers(servers, snippet.scheduleTargets ?: snippet.targetTags)
        val reachable = targets.filter { server ->
            controller.runtimeStates.value[server.id]?.health != ServerHealth.OFFLINE
        }
        if (reachable.isEmpty()) {
            // Not the snippet's fault: a skip neither fails nor resets the streak. An
            // existing failure streak stays visible in lastRunStatus; otherwise the skip
            // itself is recorded so the row reflects what happened.
            if (scheduleFailureStreak(snippet.lastRunStatus) == 0) {
                repository.updateSnippetLastRun(snippet.id, nowEpochMs(), SCHEDULE_STATUS_SKIPPED_OFFLINE)
            }
            return SnippetRunOutcome(
                snippetId = snippetId,
                status = if (scheduleFailureStreak(snippet.lastRunStatus) == 0) {
                    SCHEDULE_STATUS_SKIPPED_OFFLINE
                } else {
                    snippet.lastRunStatus
                },
                skippedTargets = targets.size,
            )
        }

        val results = coroutineScope {
            reachable.map { server ->
                async { runScheduledSnippetOnTarget(snippet, server) }
            }
        }.awaitAll()

        val succeeded = results.count { it.exitCode == 0 }
        val failed = results.size - succeeded
        val previousStreak = scheduleFailureStreak(snippet.lastRunStatus)
        val streak = if (failed == 0) 0 else previousStreak + 1
        val status = if (failed == 0) SCHEDULE_STATUS_SUCCEEDED else scheduleFailureStatus(streak)
        val stop = failed > 0 && streak >= MAX_CONSECUTIVE_SCHEDULE_FAILURES
        repository.updateSnippetLastRun(snippet.id, nowEpochMs(), status)
        return SnippetRunOutcome(
            snippetId = snippetId,
            status = status,
            succeededTargets = succeeded,
            failedTargets = failed,
            skippedTargets = targets.size - reachable.size,
            stopSchedule = stop,
        )
    }

    /**
     * One target's scheduled execution, isolated by its own time budget so a hung node
     * cannot eat another target's window. [withTimeoutOrNull] (rather than catching
     * TimeoutCancellationException) keeps genuine outer cancellation distinguishable from
     * this target's budget expiry.
     */
    private suspend fun runScheduledSnippetOnTarget(snippet: Snippet, server: Server): SshExecResult {
        val result = withTimeoutOrNull(SNIPPET_TARGET_BUDGET_MS) {
            runCatching {
                // Same execution path as the Broadcast feature (streaming, audited, honest
                // TIMEOUT/CANCELLED bookkeeping) — only the trigger source differs.
                controller.executeAuditedAction(
                    server = server,
                    category = AuditCategory.SSH,
                    actionTitle = "Scheduled: ${snippet.name}",
                    command = snippet.commandTemplate,
                    triggerSource = TriggerSource.SCHEDULED,
                    streaming = true,
                    timeoutMs = SNIPPET_TARGET_BUDGET_MS,
                )
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                Logger.w(throwable) { "Scheduled snippet '${snippet.name}' failed on ${server.name}" }
                SshExecResult(-1, "", throwable.message ?: "Scheduled run failed", 0)
            }
        }
        return result ?: SshExecResult(
            exitCode = -1,
            stdout = "",
            stderr = "Target budget of ${SNIPPET_TARGET_BUDGET_MS / 60_000} minutes exceeded",
            durationMs = SNIPPET_TARGET_BUDGET_MS,
        )
    }

    // ------------------------------------------------------------------
    // AUTO_UPDATE
    // ------------------------------------------------------------------

    /**
     * Runs the unattended update cycle for one AUTO node: the Phase 3B scan, then the
     * enabled categories ([com.raulshma.grack.core.model.Server.updateOs] /
     * [Server.updateContainers]) applied under the per-server apply mutex through the
     * same controller paths the manual applies use, audited with
     * [TriggerSource.SCHEDULED]. Compose stacks update via `applyStackUpdate`, standalone
     * containers via `applyContainerPull`.
     */
    suspend fun runScheduledAutoUpdate(serverId: String): AutoUpdateOutcome {
        val server = runCatching { repository.getServerById(serverId) }
            .onFailure { Logger.w(it) { "Scheduled auto-update server lookup failed" } }
            .getOrNull()
            ?: return AutoUpdateOutcome.NotApplicable
        if (server.updatePolicy != UpdatePolicy.AUTO) return AutoUpdateOutcome.NotApplicable

        if (controller.runtimeStates.value[serverId]?.health == ServerHealth.OFFLINE) {
            return AutoUpdateOutcome.SkippedOffline
        }
        val prefs = runCatching { repository.preferences.first() }.getOrDefault(UserPreferences())
        if (prefs.safeReadOnlyMode) {
            controller.recordBlockedAction(
                server = server,
                category = AuditCategory.SYSTEM,
                actionTitle = "Scheduled auto-update",
                command = "",
                reason = "Safe Read-Only Mode is active",
                triggerSource = TriggerSource.SCHEDULED,
            )
            return AutoUpdateOutcome.BlockedSafeReadOnly
        }

        val summary = withTimeoutOrNull(AUTO_RUN_BUDGET_MS) {
            try {
                executeAutoUpdateCycle(server)
            } catch (failure: Throwable) {
                // Cancellation (outer worker cancel or this cycle's budget expiry) must
                // propagate so withTimeoutOrNull can translate its own timeout into null;
                // genuine outer cancellation keeps cancelling the run.
                if (failure is CancellationException) throw failure
                Logger.w(failure) { "Scheduled auto-update failed for ${server.name}" }
                null
            }
        }
        return when {
            summary != null -> AutoUpdateOutcome.Ran(summary)
            // Budget expiry and hard failure both surface as a failed summary; the schedule
            // keeps running and the notification is the signal.
            else -> AutoUpdateOutcome.Ran(
                AutoUpdateRunSummary(
                    serverId = server.id,
                    serverName = server.name,
                    runFailed = true,
                    failureMessage = "Auto-update run exceeded its ${AUTO_RUN_BUDGET_MS / 60_000} minute budget or failed",
                ),
            )
        }
    }

    private suspend fun executeAutoUpdateCycle(server: Server): AutoUpdateRunSummary {
        val report = controller.scanNodeUpdates(server)

        var osApplyAttempted = false
        var osApplyExitCode: Int? = null
        var rebootRecommended = false
        var runFailed = false
        var failureMessage: String? = null
        val stacksUpdated = mutableListOf<String>()
        val stacksFailed = mutableListOf<String>()
        val containersPulled = mutableListOf<String>()
        val containersFailed = mutableListOf<String>()

        if (server.updateOs && report.osUpdateCount > 0) {
            val os = controller.applyOsUpdates(server, vault, TriggerSource.SCHEDULED)
            osApplyAttempted = true
            osApplyExitCode = os.exec.exitCode
            if (os.exec.exitCode != 0) {
                runFailed = true
                failureMessage = "OS updates exited ${os.exec.exitCode}"
            }
            rebootRecommended = os.rebootRecommended
        }

        if (server.updateContainers && report.containerUpdateCount > 0) {
            val updatable = report.containers.filter { it.status == ContainerUpdateStatus.UPDATE_AVAILABLE }
            val stackEntries = updatable.filter { it.composeProject != null }.distinctBy { it.composeProject }
            val standalone = updatable.filter { it.composeProject == null }
            // applyOsUpdates / applyStackUpdate / applyContainerPull each take the per-server
            // apply mutex internally, so applies here never interleave with a manual apply —
            // and must NOT be wrapped in another withUpdateApplyLock (that mutex is not
            // reentrant; a nested acquire would deadlock).
            stackEntries.forEach { entry ->
                val project = entry.composeProject ?: return@forEach
                val result = controller.applyStackUpdate(
                    server = server,
                    projectName = project,
                    workingDir = entry.composeWorkingDir,
                    configFiles = entry.composeConfigFiles,
                    triggerSource = TriggerSource.SCHEDULED,
                )
                if (result.exitCode == 0) {
                    stacksUpdated += project
                } else {
                    stacksFailed += "$project (exit ${result.exitCode})"
                    runFailed = true
                    failureMessage = failureMessage ?: "Stack $project update exited ${result.exitCode}"
                }
            }
            standalone.forEach { entry ->
                val result = controller.applyContainerPull(
                    server = server,
                    container = ContainerInfo(
                        id = entry.containerName,
                        shortId = entry.containerName,
                        name = entry.containerName,
                        image = entry.image,
                        runtime = controller.containerRuntimeFor(server),
                    ),
                    triggerSource = TriggerSource.SCHEDULED,
                )
                if (result.exitCode == 0) {
                    containersPulled += entry.containerName
                } else {
                    containersFailed += "${entry.containerName} (exit ${result.exitCode})"
                    runFailed = true
                    failureMessage = failureMessage ?: "Container ${entry.containerName} pull exited ${result.exitCode}"
                }
            }
        }

        // Refresh the persisted report so the Overview card reflects the new state.
        runCatching { controller.scanNodeUpdates(server) }
            .onFailure { Logger.w(it) { "Post-apply rescan failed for ${server.name}" } }

        return AutoUpdateRunSummary(
            serverId = server.id,
            serverName = server.name,
            osUpdateCount = report.osUpdateCount,
            containerUpdateCount = report.containerUpdateCount,
            osApplyAttempted = osApplyAttempted,
            osApplyExitCode = osApplyExitCode,
            stacksUpdated = stacksUpdated,
            stacksFailed = stacksFailed,
            containersPulled = containersPulled,
            containersFailed = containersFailed,
            rebootRecommended = rebootRecommended,
            runFailed = runFailed,
            failureMessage = failureMessage,
        )
    }

    companion object {
        /**
         * Per-target wall-clock budget for a scheduled snippet. Targets run concurrently,
         * so the snippet's total runtime stays within this bound; the ~9-minute worker
         * bound has a minute of headroom over it.
         */
        const val SNIPPET_TARGET_BUDGET_MS: Long = 8 * 60_000L

        /**
         * Whole-run budget for an AUTO update cycle (scan + applies). Deliberately shorter
         * than the manual applies' 30-minute ceiling: an unattended run must not occupy the
         * background worker for half an hour.
         */
        const val AUTO_RUN_BUDGET_MS: Long = 9 * 60_000L
    }
}

/** Outcome of one scheduled snippet run; [stopSchedule] tells the worker to cancel the unique work. */
data class SnippetRunOutcome(
    val snippetId: String,
    /** The `lastRunStatus` label that was persisted, when one was written. */
    val status: String? = null,
    val succeededTargets: Int = 0,
    val failedTargets: Int = 0,
    val skippedTargets: Int = 0,
    /** Cancel the unique work: failure streak maxed out, guard tripped, or schedule removed. */
    val stopSchedule: Boolean = false,
    /** The snippet or its schedule no longer exists. */
    val scheduleRemoved: Boolean = false,
    /** A confirmation-requiring snippet was rejected by the worker guard. */
    val blockedConfirmation: Boolean = false,
)

/** Outcome of one scheduled AUTO update run. */
sealed interface AutoUpdateOutcome {
    /** The node's policy is no longer AUTO (or the node is gone): cancel the unique work. */
    data object NotApplicable : AutoUpdateOutcome

    /** Node explicitly OFFLINE; keep the schedule and wait for the next cycle. */
    data object SkippedOffline : AutoUpdateOutcome

    /** Safe Read-Only Mode blocked the run; keep the schedule. */
    data object BlockedSafeReadOnly : AutoUpdateOutcome

    /** The cycle ran; [summary] drives the notification. */
    data class Ran(val summary: AutoUpdateRunSummary) : AutoUpdateOutcome
}

/**
 * What one AUTO update cycle did — the content of the summary notification.
 * A cycle "failed" when any enabled apply exited non-zero, the budget was blown, or the
 * cycle threw; failures keep the schedule running (the notification is the signal).
 */
data class AutoUpdateRunSummary(
    val serverId: String,
    val serverName: String,
    val osUpdateCount: Int = 0,
    val containerUpdateCount: Int = 0,
    val osApplyAttempted: Boolean = false,
    val osApplyExitCode: Int? = null,
    val stacksUpdated: List<String> = emptyList(),
    val stacksFailed: List<String> = emptyList(),
    val containersPulled: List<String> = emptyList(),
    val containersFailed: List<String> = emptyList(),
    val rebootRecommended: Boolean = false,
    val runFailed: Boolean = false,
    val failureMessage: String? = null,
) {
    val appliedAnything: Boolean
        get() = (osApplyAttempted && osApplyExitCode == 0) ||
            stacksUpdated.isNotEmpty() ||
            containersPulled.isNotEmpty()

    val failedAnything: Boolean
        get() = runFailed || stacksFailed.isNotEmpty() || containersFailed.isNotEmpty() ||
            (osApplyAttempted && osApplyExitCode != 0)

    /** One-line human summary for the notification. */
    fun toNotificationText(): String {
        if (runFailed && !appliedAnything) {
            return failureMessage ?: "Auto-update run failed"
        }
        val parts = buildList {
            if (osApplyAttempted) {
                add(
                    if (osApplyExitCode == 0) "OS updates applied" else "OS updates failed (exit $osApplyExitCode)",
                )
            }
            if (stacksUpdated.isNotEmpty()) add("Stacks updated: ${stacksUpdated.joinToString()}")
            if (containersPulled.isNotEmpty()) add("Containers pulled: ${containersPulled.joinToString()}")
            if (stacksFailed.isNotEmpty()) add("Stack updates failed: ${stacksFailed.joinToString()}")
            if (containersFailed.isNotEmpty()) add("Container pulls failed: ${containersFailed.joinToString()}")
            if (isEmpty()) add("No updates were pending")
        }
        val base = parts.joinToString(" · ")
        return if (rebootRecommended) "$base · reboot recommended" else base
    }
}
