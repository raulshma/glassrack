package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.ContainerRuntime
import com.raulshma.grack.core.model.JobPhase
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3A item 1: `logs -f` follow runs on the untimed streaming variant, feeds chunks into
 * the job's live output, and cancelling it leaves a CANCELLED job/audit — never RUNNING.
 *
 * Every test stops the controller in `finally`: its re-arming virtual-time loops (pollers,
 * idle prune) must be cancelled before runTest's quiescence drain, or the drain never ends.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class FollowLogsTest {

    private val server = Server(
        id = "srv-1",
        name = "node-a",
        primaryHost = "10.0.0.5",
        capabilities = OsCapabilities(hasDocker = true),
    )

    private fun harness(scope: TestScope) = ControllerHarness(
        context = ApplicationProvider.getApplicationContext(),
        testScope = scope,
    )

    private val container = ContainerInfo(
        id = "abc123", shortId = "abc123", name = "jellyfin", image = "jellyfin/jellyfin",
        runtime = ContainerRuntime.DOCKER,
    )

    @Test
    fun followUsesTimestampedTailFollowOnTheContainersRuntimeAndIsUntimed() = runTest {
        val h = harness(this)
        try {
            h.controller.followContainerLogs(server, container) { }

            val request = h.fake.streamRequests.single()
            assertEquals("docker logs -f --tail 200 --timestamps 'jellyfin' 2>&1", request.command)
            assertNull(request.timeoutMs, "Follow streams must be untimed (null timeoutMs)")
            assertEquals(200, HomelabController.FOLLOW_LOGS_TAIL)
        } finally {
            h.controller.stop()
        }
    }

    @Test
    fun followOnAPodmanContainerUsesPodman() = runTest {
        val h = harness(this)
        try {
            val podmanContainer = container.copy(runtime = ContainerRuntime.PODMAN)

            h.controller.followContainerLogs(server, podmanContainer) { }

            assertEquals("podman logs -f --tail 200 --timestamps 'jellyfin' 2>&1", h.fake.streamRequests.single().command)
        } finally {
            h.controller.stop()
        }
    }

    @Test
    fun followDeliversStreamedChunksAndAStreamEndIsAuditedAsSuccess() = runTest {
        val h = harness(this)
        try {
            h.fake.streamChunkSource = { onChunk ->
                onChunk("2026-01-01T10:00:00 first line\n")
                onChunk("2026-01-01T10:00:01 second line\n")
            }
            val received = mutableListOf<String>()

            h.controller.followContainerLogs(server, container) { chunk -> received += chunk }

            // Both streamed chunks reached the caller (the UI appends them to the sheet).
            assertEquals(2, received.size)
            assertTrue(received[1].contains("second line"), received.toString())

            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals("Follow logs: jellyfin", audit.actionTitle)
            assertEquals(JobPhase.SUCCEEDED, h.repository.activeJobs.value.single().phase)
        } finally {
            h.controller.stop()
        }
    }

    @Test
    fun cancellingAFollowLeavesACancelledJobAndAuditNeverRunning() = runTest {
        val h = harness(this)
        try {
            h.fake.streamHandler = { awaitCancellation() }

            val follow = launch {
                h.controller.followContainerLogs(server, container) { }
            }
            runCurrent()
            runCurrent() // let the job insert land; the follow now hangs on the fake stream

            follow.cancelAndJoin()

            val job = h.repository.activeJobs.value.single()
            assertEquals(JobPhase.CANCELLED, job.phase, "A cancelled follow must not be left RUNNING")
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.CANCELLED, audit.outcome)
        } finally {
            h.controller.stop()
        }
    }

    @Test
    fun timedOutFollowIsRecordedAsTimeoutWhenTheTransportEnforcesADeadline() = runTest {
        // Even untimed upstream, a dead remote can wedge the channel; a transport-level
        // timeout (defence in depth) must still close the job with a TIMEOUT audit.
        val h = harness(this)
        try {
            h.fake.streamChunkSource = { _ ->
                kotlinx.coroutines.withTimeout(500L) {
                    kotlinx.coroutines.awaitCancellation()
                }
            }

            val result = h.controller.followContainerLogs(server, container) { }

            assertEquals(-1, result.exitCode)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.TIMEOUT, audit.outcome)
            assertEquals(JobPhase.FAILED, h.repository.activeJobs.value.single().phase)
        } finally {
            h.controller.stop()
        }
    }
}
