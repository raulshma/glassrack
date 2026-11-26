package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AlertMetric
import com.raulshma.grack.core.model.AlertRule
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.ServerHealth
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class HomelabControllerOfflineAlertsTest {

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
    fun offlineRulesRaiseAfterThreeConsecutiveFailuresAndClearOnSuccess() = runTest {
        val h = harness(this)
        h.repository.upsertAlertRule(
            AlertRule(id = "rule-off", name = "Node unreachable", serverId = null, metric = AlertMetric.SERVER_OFFLINE, threshold = 1f),
        )
        runCurrent() // controller collected the rule

        h.fake.executeHandler = { throw IOException("connection refused") }

        h.controller.pollServerOnce(server)
        runCurrent()
        h.controller.pollServerOnce(server)
        runCurrent()
        assertTrue(h.controller.activeAlerts.value.isEmpty(), "no alert before 3 consecutive failures")

        h.controller.pollServerOnce(server)
        runCurrent()
        val raised = h.controller.activeAlerts.value
        assertEquals(1, raised.size)
        assertEquals("rule-off-srv-1", raised.single().id)
        assertEquals(ServerHealth.OFFLINE, raised.single().severity)
        assertTrue(raised.single().message.contains("3 consecutive failed polls"))

        // Still failing: the alert stays (single entry, not duplicated).
        h.controller.pollServerOnce(server)
        runCurrent()
        assertEquals(1, h.controller.activeAlerts.value.size)

        // Success clears the offline alert and resets the failure counter.
        h.fake.executeHandler = { SshExecResult(0, "", "", 5L) }
        h.controller.pollServerOnce(server)
        runCurrent()
        assertEquals(emptyList(), h.controller.activeAlerts.value)

        // Counter was reset: two more failures stay below the threshold.
        h.fake.executeHandler = { throw IOException("connection refused") }
        h.controller.pollServerOnce(server)
        runCurrent()
        h.controller.pollServerOnce(server)
        runCurrent()
        assertTrue(h.controller.activeAlerts.value.isEmpty())

        h.controller.pollServerOnce(server)
        runCurrent()
        assertEquals(ServerHealth.OFFLINE, h.controller.activeAlerts.value.single().severity)
        h.controller.stop()
    }

    @Test
    fun offlineRuleForOtherServerDoesNotRaise() = runTest {
        val h = harness(this)
        h.repository.upsertAlertRule(
            AlertRule(id = "rule-other", name = "Backup node down", serverId = "srv-other", metric = AlertMetric.SERVER_OFFLINE, threshold = 1f),
        )
        runCurrent()

        h.fake.executeHandler = { throw IOException("connection refused") }
        repeat(4) {
            h.controller.pollServerOnce(server)
            runCurrent()
        }
        assertTrue(h.controller.activeAlerts.value.isEmpty())
        h.controller.stop()
    }
}
