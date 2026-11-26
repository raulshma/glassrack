package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.ContainerAction
import com.raulshma.grack.core.model.ContainerInfo
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class HomelabControllerAuditTest {

    private val server = Server(
        id = "srv-1",
        name = "node-a",
        primaryHost = "10.0.0.5",
        capabilities = OsCapabilities(distro = "Debian GNU/Linux 12", probeVersion = TelemetryParsers.CAPABILITIES_PROBE_VERSION),
    )

    private fun harness(scope: TestScope) = ControllerHarness(
        context = ApplicationProvider.getApplicationContext(),
        testScope = scope,
    )

    @Test
    fun timedOutActionIsAuditedAsTimeoutAndJobIsClosed() = runTest {
        val h = harness(this)
        h.fake.executeHandler = { request ->
            // Emulates the real client: an internal withTimeout around a hung command.
            withTimeout(request.timeoutMs) {
                delay(request.timeoutMs + 5_000)
                SshExecResult(0, "late output", "", 1L)
            }
        }

        val result = h.controller.executeAuditedAction(
            server = server,
            category = AuditCategory.SERVICE,
            actionTitle = "Service restart: nginx",
            command = "systemctl restart nginx",
            timeoutMs = 500L,
        )

        assertEquals(-1, result.exitCode)
        val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
        assertEquals(AuditOutcome.TIMEOUT, audit.outcome)
        assertEquals("Service restart: nginx", audit.actionTitle)
        val job = h.repository.activeJobs.value.single()
        assertEquals(JobPhase.FAILED, job.phase) // never left RUNNING
        h.controller.stop()
    }

    @Test
    fun cancelledActionIsAuditedAsCancelledAndRethrown() = runTest {
        val h = harness(this)
        h.fake.executeHandler = { awaitCancellation() }

        val job = launch {
            h.controller.executeAuditedAction(
                server = server,
                category = AuditCategory.SERVICE,
                actionTitle = "Service stop: nginx",
                command = "systemctl stop nginx",
            )
        }
        runCurrent()
        job.cancelAndJoin()

        val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
        assertEquals(AuditOutcome.CANCELLED, audit.outcome)
        val stored = h.repository.activeJobs.value.single()
        assertEquals(JobPhase.CANCELLED, stored.phase)
        h.controller.stop()
    }

    @Test
    fun droppedConnectionDuringContainerActionStillProducesAuditEntry() = runTest {
        val h = harness(this)
        h.fake.streamHandler = { throw IOException("connection dropped") }
        val container = ContainerInfo(id = "abc123", shortId = "abc", name = "web", image = "nginx:1.27")

        val result = h.controller.performContainerAction(server, container, ContainerAction.STOP)

        assertEquals(-1, result.exitCode)
        val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
        assertEquals(AuditOutcome.FAILED, audit.outcome)
        assertEquals("Container Stop: web", audit.actionTitle)
        val job = h.repository.activeJobs.value.single()
        assertEquals(JobPhase.FAILED, job.phase)
        h.controller.stop()
    }

    @Test
    fun composePullAndUpdateVerbsGetFifteenMinuteTimeout() = runTest {
        val h = harness(this)

        h.controller.performComposeStackAction(server, "webstack", "/opt/webstack", "pull")
        h.controller.performComposeStackAction(server, "webstack", "/opt/webstack", "up -d")
        h.controller.performComposeStackAction(server, "webstack", "/opt/webstack", "ps")

        val timeouts = h.fake.streamRequests.map { it.command.substringAfterLast(" ") to it.timeoutMs }
        assertEquals(
            listOf(
                "pull" to HomelabController.COMPOSE_UPDATE_TIMEOUT_MS,
                "-d" to HomelabController.COMPOSE_UPDATE_TIMEOUT_MS,
                "ps" to HomelabController.COMPOSE_ACTION_TIMEOUT_MS,
            ),
            timeouts,
        )
        assertTrue(timeouts.all { it.second == 15 * 60_000L || it.second == 120_000L })
        h.controller.stop()
    }

    @Test
    fun executeAuditedActionPassesTimeoutToTheClient() = runTest {
        val h = harness(this)
        h.controller.executeAuditedAction(
            server = server,
            category = AuditCategory.SERVICE,
            actionTitle = "Custom",
            command = "uptime",
            timeoutMs = 4321L,
        )
        assertEquals(4321L, h.fake.executeRequests.single().timeoutMs)
        h.controller.stop()
    }
}
