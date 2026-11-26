package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.ServerHealth
import com.raulshma.grack.core.model.Snippet
import com.raulshma.grack.core.model.SCHEDULE_STATUS_BLOCKED_CONFIRMATION
import com.raulshma.grack.core.model.SCHEDULE_STATUS_SKIPPED_OFFLINE
import com.raulshma.grack.core.model.SCHEDULE_STATUS_SUCCEEDED
import com.raulshma.grack.core.model.TriggerSource
import com.raulshma.grack.core.model.UpdatePolicy
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.ssh.AutoUpdateOutcome
import com.raulshma.grack.core.ssh.ScheduledRunCoordinator.Companion.SNIPPET_TARGET_BUDGET_MS
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 4 scheduler body ([ScheduledRunCoordinator]): scheduled snippet execution with
 * `TriggerSource.SCHEDULED`, last-run persistence, the 3-failure stop, the
 * confirmation-rejection guard, per-target isolation/time budgets, and the AUTO update
 * cycle (scan + enabled-category applies under the existing controller paths).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class ScheduledRunCoordinatorTest {

    private val nodeA = Server(id = "srv-a", name = "node-a", primaryHost = "10.0.0.5")
    private val nodeB = Server(id = "srv-b", name = "node-b", primaryHost = "10.0.0.6")

    /**
     * Harness with the controller's continuous poll loops frozen: seeding server rows into
     * the repository arms per-node pollers, and on the virtual-time scheduler a looping
     * poller would spin forever and never let runTest reach quiescence.
     * `pollServerOnce` bypasses the gate, so the offline-skip tests still work.
     */
    private fun harness(scope: TestScope): ControllerHarness {
        val h = ControllerHarness(
            context = ApplicationProvider.getApplicationContext(),
            testScope = scope,
        )
        h.controller.pollingGate.pause("scheduled-runs-test")
        return h
    }

    /** Runs [block] with the controller's background loops always stopped afterwards. */
    private fun coordinatorTest(block: suspend (ControllerHarness) -> Unit) = runTest {
        val h = harness(this)
        try {
            block(h)
        } finally {
            h.controller.stop()
        }
    }

    private suspend fun ControllerHarness.seedSnippet(
        vararg servers: Server,
        spec: String? = "every:15m",
        requiresConfirmation: Boolean = false,
        lastRunStatus: String? = null,
    ): Snippet {
        servers.forEach { repository.upsertServer(it) }
        val snippet = Snippet(
            id = "snip-1",
            name = "Nightly Uptime",
            commandTemplate = "uptime",
            requiresConfirmation = requiresConfirmation,
            scheduleSpec = spec,
            lastRunStatus = lastRunStatus,
        )
        repository.upsertSnippet(snippet)
        return snippet
    }

    private suspend fun ControllerHarness.snippetRow(id: String = "snip-1"): Snippet =
        repository.getSnippetById(id)!!

    private fun ControllerHarness.coordinator() = ScheduledRunCoordinator(
        repository = repository,
        controller = controller,
        vault = vault,
    )

    // ------------------------------------------------------------------
    // SNIPPET
    // ------------------------------------------------------------------

    @Test
    fun scheduledSnippetRunsAuditsWithScheduledSourceAndPersistsLastRun() = coordinatorTest { h ->
        val snippet = h.seedSnippet(nodeA, nodeB)

        val outcome = h.coordinator().runScheduledSnippet(snippet.id)

        assertEquals(SCHEDULE_STATUS_SUCCEEDED, outcome.status)
        assertEquals(2, outcome.succeededTargets)
        assertEquals(false, outcome.stopSchedule)
        val audits = h.repository.auditEntries.first { it.isNotEmpty() }
            .filter { it.actionTitle.startsWith("Scheduled:") }
        assertEquals(2, audits.size)
        assertTrue(audits.all { it.triggerSource == TriggerSource.SCHEDULED })
        assertTrue(audits.all { it.outcome == AuditOutcome.SUCCESS })
        assertTrue(audits.all { it.category == AuditCategory.SSH })
        val stored = h.snippetRow()
        assertEquals(SCHEDULE_STATUS_SUCCEEDED, stored.lastRunStatus)
        assertTrue((stored.lastRunEpochMs ?: 0L) > 0L)
    }

    @Test
    fun failureStreakStopsTheScheduleAfterThreeConsecutiveFailures() = coordinatorTest { h ->
        val snippet = h.seedSnippet(nodeA)
        // Scheduled snippet runs go through the streaming path, like broadcasts.
        h.fake.streamHandler = { SshExecResult(1, "", "command not found", 1L) }
        val coordinator = h.coordinator()

        val first = coordinator.runScheduledSnippet(snippet.id)
        assertEquals("FAILED", first.status)
        assertEquals(false, first.stopSchedule)

        val second = coordinator.runScheduledSnippet(snippet.id)
        assertEquals("FAILED_2", second.status)
        assertEquals(false, second.stopSchedule)

        val third = coordinator.runScheduledSnippet(snippet.id)
        assertEquals("FAILED_3", third.status)
        assertEquals(true, third.stopSchedule)
        assertEquals("FAILED_3", h.snippetRow().lastRunStatus)
    }

    @Test
    fun successAfterFailuresResetsTheStreak() = coordinatorTest { h ->
        val snippet = h.seedSnippet(nodeA, lastRunStatus = "FAILED_2")
        h.fake.streamHandler = { SshExecResult(1, "", "boom", 1L) }
        val coordinator = h.coordinator()

        val failed = coordinator.runScheduledSnippet(snippet.id)
        assertEquals("FAILED_3", failed.status)
        assertEquals(true, failed.stopSchedule)

        // Schedule saved fresh after a fix: the lastRun streak bookkeeping resets.
        h.repository.updateSnippetLastRun(snippet.id, 0L, null)
        h.fake.streamHandler = { SshExecResult(0, "up 3 days", "", 1L) }
        val recovered = coordinator.runScheduledSnippet(snippet.id)
        assertEquals(SCHEDULE_STATUS_SUCCEEDED, recovered.status)
        assertEquals(false, recovered.stopSchedule)
    }

    @Test
    fun confirmationRequiringSnippetIsRejectedByTheWorkerGuard() = coordinatorTest { h ->
        val snippet = h.seedSnippet(nodeA, requiresConfirmation = true)

        val outcome = h.coordinator().runScheduledSnippet(snippet.id)

        assertTrue(outcome.blockedConfirmation)
        assertEquals(true, outcome.stopSchedule)
        assertEquals(SCHEDULE_STATUS_BLOCKED_CONFIRMATION, h.snippetRow().lastRunStatus)
        // The guard runs before any SSH execution: not a single command was sent.
        assertTrue(h.fake.executeRequests.isEmpty())
        assertTrue(h.fake.streamRequests.isEmpty())
    }

    @Test
    fun removedOrClearedScheduleReportsStopWithoutRunning() = coordinatorTest { h ->
        val snippet = h.seedSnippet(nodeA, spec = null)

        val outcome = h.coordinator().runScheduledSnippet(snippet.id)

        assertEquals(true, outcome.scheduleRemoved)
        assertEquals(true, outcome.stopSchedule)
        assertTrue(h.fake.executeRequests.isEmpty())
    }

    @Test
    fun allOfflineTargetsAreSkippedWithoutCountingAsFailure() = coordinatorTest { h ->
        val snippet = h.seedSnippet(nodeA, lastRunStatus = "FAILED")
        // Mark the node offline exactly as a failed poll does.
        h.fake.executeHandler = { throw IOException("node down") }
        h.controller.pollServerOnce(nodeA)
        assertEquals(ServerHealth.OFFLINE, h.controller.runtimeStates.value[nodeA.id]?.health)
        h.fake.executeHandler = { SshExecResult(0, "ok", "", 1L) }

        val outcome = h.coordinator().runScheduledSnippet(snippet.id)

        // Skipped is not a failure: the previous FAILED streak is preserved untouched.
        assertEquals("FAILED", outcome.status)
        assertEquals(1, outcome.skippedTargets)
        assertEquals(false, outcome.stopSchedule)
        assertEquals("FAILED", h.snippetRow().lastRunStatus)

        // Without a prior streak the skip itself is what the row shows.
        h.repository.updateSnippetLastRun(snippet.id, 0L, null)
        val freshSkip = h.coordinator().runScheduledSnippet(snippet.id)
        assertEquals(SCHEDULE_STATUS_SKIPPED_OFFLINE, freshSkip.status)
        assertEquals(SCHEDULE_STATUS_SKIPPED_OFFLINE, h.snippetRow().lastRunStatus)
    }

    @Test
    fun perTargetBudgetIsolatesSlowNodes() = coordinatorTest { h ->
        val snippet = h.seedSnippet(nodeA, nodeB)
        // node-a hangs past its budget; the controller's internal timeout turns it into an
        // honest TIMEOUT audit while node-b completes unaffected (concurrently).
        h.fake.streamHandler = { request ->
            if (request.server.id == nodeA.id) {
                withTimeout(request.timeoutMs ?: Long.MAX_VALUE) {
                    delay((request.timeoutMs ?: 0L) + 5_000)
                    SshExecResult(0, "late", "", 1L)
                }
            } else {
                SshExecResult(0, "fast", "", 1L)
            }
        }

        val outcome = h.coordinator().runScheduledSnippet(snippet.id)

        assertEquals(1, outcome.succeededTargets)
        assertEquals(1, outcome.failedTargets)
        val audits = h.repository.auditEntries.first { it.isNotEmpty() }
            .filter { it.actionTitle.startsWith("Scheduled:") }
        assertEquals(2, audits.size)
        assertEquals(
            AuditOutcome.TIMEOUT,
            audits.first { it.serverId == nodeA.id }.outcome,
        )
        assertEquals(
            AuditOutcome.SUCCESS,
            audits.first { it.serverId == nodeB.id }.outcome,
        )
        // Targets run concurrently: wall clock is one budget, not two back-to-back.
        assertTrue(
            h.scheduler.currentTime <= SNIPPET_TARGET_BUDGET_MS + 1_000,
            "expected concurrent target execution, virtual clock at ${h.scheduler.currentTime}",
        )
    }

    // ------------------------------------------------------------------
    // AUTO_UPDATE
    // ------------------------------------------------------------------

    private val debianNode = Server(
        id = "srv-deb",
        name = "debian-node",
        primaryHost = "10.0.0.7",
        capabilities = OsCapabilities(
            distro = "Debian GNU/Linux 12",
            distroId = "debian",
            isRoot = true,
            probeVersion = TelemetryParsers.CAPABILITIES_PROBE_VERSION,
        ),
        updatePolicy = UpdatePolicy.AUTO,
        updateOs = true,
        updateContainers = true,
    )

    /** Serves the OS update-check and reboot probes; the apply goes through the stream handler. */
    private fun ControllerHarness.serveUpdateProbes(withUpdates: Boolean) {
        fake.executeHandler = { request ->
            when {
                request.command.contains("===OSUPD===") -> SshExecResult(
                    exitCode = 0,
                    stdout = buildString {
                        appendLine("===OSUPD===")
                        appendLine("__APT_UPDATE_EXIT__=0")
                        if (withUpdates) {
                            appendLine("coreutils/bookworm 9.1-1 amd64 [upgradable from: 9.0-1]")
                            appendLine("curl/bookworm 8.5.0-1 amd64 [upgradable from: 8.4.0-1]")
                        }
                    },
                    stderr = "",
                    durationMs = 1L,
                )
                request.command.contains("===REBOOTCHK===") -> SshExecResult(
                    exitCode = 0,
                    stdout = buildString {
                        appendLine("===REBOOTCHK===")
                        appendLine("REBOOT_REQUIRED=0")
                        appendLine("__NR_EXIT__=skip")
                    },
                    stderr = "",
                    durationMs = 1L,
                )
                else -> SshExecResult(0, "ok", "", 1L)
            }
        }
        fake.streamHandler = { SshExecResult(0, "upgrade complete", "", 5L) }
    }

    @Test
    fun autoUpdateScansAndAppliesEnabledCategoriesWithScheduledSource() = coordinatorTest { h ->
        h.repository.upsertServer(debianNode)
        h.serveUpdateProbes(withUpdates = true)

        val outcome = h.coordinator().runScheduledAutoUpdate(debianNode.id)

        val summary = (outcome as AutoUpdateOutcome.Ran).summary
        assertTrue(summary.osApplyAttempted)
        assertEquals(0, summary.osApplyExitCode)
        assertEquals(2, summary.osUpdateCount)
        assertTrue(summary.appliedAnything)
        assertEquals(false, summary.failedAnything)
        // The apply ran the Debian non-interactive upgrade through the privileged path.
        val applies = h.fake.streamRequests.filter { it.command.contains("apt-get -y") }
        assertEquals(1, applies.size)
        val audit = h.repository.auditEntries.first { it.isNotEmpty() }
            .last { it.actionTitle == "Apply OS updates" }
        assertEquals(TriggerSource.SCHEDULED, audit.triggerSource)
        assertEquals(AuditOutcome.SUCCESS, audit.outcome)
        assertEquals(AuditCategory.SYSTEM, audit.category)
        // Post-apply rescan refreshed the persisted report.
        val scanRuns = h.fake.executeRequests.count { it.command.contains("===OSUPD===") }
        assertEquals(2, scanRuns)
    }

    @Test
    fun autoUpdateWithDisabledOsCategoryDoesNotApply() = coordinatorTest { h ->
        h.repository.upsertServer(debianNode.copy(updateOs = false))
        h.serveUpdateProbes(withUpdates = true)

        val outcome = h.coordinator().runScheduledAutoUpdate(debianNode.id)

        val summary = (outcome as AutoUpdateOutcome.Ran).summary
        assertEquals(false, summary.osApplyAttempted)
        assertEquals(false, summary.appliedAnything)
        assertTrue(h.fake.streamRequests.none { it.command.contains("apt-get -y") })
    }

    @Test
    fun autoUpdateWithoutPendingUpdatesStaysSilent() = coordinatorTest { h ->
        h.repository.upsertServer(debianNode)
        h.serveUpdateProbes(withUpdates = false)

        val outcome = h.coordinator().runScheduledAutoUpdate(debianNode.id)

        val summary = (outcome as AutoUpdateOutcome.Ran).summary
        assertEquals(false, summary.appliedAnything)
        assertEquals(false, summary.failedAnything)
        assertTrue(h.fake.streamRequests.none { it.command.contains("apt-get -y") })
    }

    @Test
    fun manualPolicyReportsNotApplicable() = coordinatorTest { h ->
        h.repository.upsertServer(debianNode.copy(updatePolicy = UpdatePolicy.MANUAL))

        val outcome = h.coordinator().runScheduledAutoUpdate(debianNode.id)

        assertEquals(AutoUpdateOutcome.NotApplicable, outcome)
        assertTrue(h.fake.executeRequests.isEmpty())
    }

    @Test
    fun safeReadOnlyModeBlocksTheUnattendedApply() = coordinatorTest { h ->
        h.repository.upsertServer(debianNode)
        h.repository.updatePreferences { it.copy(safeReadOnlyMode = true) }
        assertEquals(UserPreferences().copy(safeReadOnlyMode = true), h.repository.preferences.first())

        val outcome = h.coordinator().runScheduledAutoUpdate(debianNode.id)

        assertEquals(AutoUpdateOutcome.BlockedSafeReadOnly, outcome)
        val audit = h.repository.auditEntries.first { it.isNotEmpty() }.single()
        assertEquals(AuditOutcome.BLOCKED, audit.outcome)
        assertEquals(TriggerSource.SCHEDULED, audit.triggerSource)
        assertTrue(h.fake.executeRequests.isEmpty())
    }

    @Test
    fun offlineAutoNodeIsSkipped() = coordinatorTest { h ->
        h.repository.upsertServer(debianNode)
        h.fake.executeHandler = { throw IOException("node down") }
        h.controller.pollServerOnce(debianNode)
        h.fake.executeHandler = { SshExecResult(0, "ok", "", 1L) }

        val outcome = h.coordinator().runScheduledAutoUpdate(debianNode.id)

        assertEquals(AutoUpdateOutcome.SkippedOffline, outcome)
    }

    @Test
    fun failedOsApplySurfacesInSummary() = coordinatorTest { h ->
        h.repository.upsertServer(debianNode)
        h.serveUpdateProbes(withUpdates = true)
        h.fake.streamHandler = { SshExecResult(100, "", "dpkg lock held", 5L) }

        val outcome = h.coordinator().runScheduledAutoUpdate(debianNode.id)

        val summary = (outcome as AutoUpdateOutcome.Ran).summary
        assertEquals(100, summary.osApplyExitCode)
        assertEquals(true, summary.failedAnything)
        assertEquals(false, summary.appliedAnything)
        assertTrue(summary.toNotificationText().contains("OS updates exited 100"))
    }
}
