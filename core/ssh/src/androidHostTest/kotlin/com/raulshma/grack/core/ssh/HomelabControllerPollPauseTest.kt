package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Polling hygiene (Phase 1C item 1): with the gate closed the per-server poll loops issue
 * no SSH traffic, resuming restarts them, and one-shot worker-driven polls bypass the gate.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class HomelabControllerPollPauseTest {

    private val server = Server(
        id = "srv-1",
        name = "node-a",
        primaryHost = "10.0.0.5",
        pollIntervalSec = 2,
        capabilities = OsCapabilities(distro = "Debian GNU/Linux 12"),
    )

    private fun harness(scope: TestScope) = ControllerHarness(
        context = ApplicationProvider.getApplicationContext(),
        testScope = scope,
    )

    @Test
    fun pausedLoopsDoNotPollAndResumeRestartsThem() = runTest {
        val h = harness(this)
        // Pause before any node exists so the loop can never start open.
        h.controller.pollingGate.pause("background")
        h.repository.upsertServer(server)

        // Let the servers flow emit and the poller start; give several poll intervals of
        // virtual time — nothing must be executed while paused.
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0, h.fake.executeRequests.size)

        h.controller.pollingGate.resume("background")
        runCurrent()
        assertTrue(h.fake.executeRequests.isNotEmpty())

        h.controller.stop()
    }

    @Test
    fun oneShotWorkerPollsBypassTheGate() = runTest {
        val h = harness(this)
        h.repository.upsertServer(server)
        h.controller.pollingGate.pause("metered")

        // TelemetryRefreshWorker calls pollServerOnce directly — it must keep working.
        h.controller.pollServerOnce(server)
        runCurrent()
        assertTrue(h.fake.executeRequests.isNotEmpty())

        h.controller.stop()
    }

    @Test
    fun multiplePauseReasonsKeepLoopsSuspendedUntilAllCleared() = runTest {
        val h = harness(this)
        h.controller.pollingGate.pause("background")
        h.controller.pollingGate.pause("metered")
        h.repository.upsertServer(server)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0, h.fake.executeRequests.size)

        h.controller.pollingGate.resume("background")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0, h.fake.executeRequests.size)

        h.controller.pollingGate.resume("metered")
        runCurrent()
        assertTrue(h.fake.executeRequests.isNotEmpty())

        h.controller.stop()
    }
}
