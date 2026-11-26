package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.TriggerSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3A item 3: one stack-update path. The `update` pseudo-verb expands to
 * `pull && compose up -d`, honours the stack's `config_files` label (`-f` args), and
 * [com.raulshma.grack.core.ssh.HomelabController.performComposeStackAction]'s new
 * `triggerSource` / `timeoutMs` parameters pass through with defaults preserving behaviour.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class StackUpdatePathTest {

    private val dockerServer = Server(
        id = "srv-docker",
        name = "docker-node",
        primaryHost = "10.0.0.5",
        capabilities = OsCapabilities(hasDocker = true, hasPodman = false),
    )

    private val podmanOnlyServer = Server(
        id = "srv-podman",
        name = "podman-node",
        primaryHost = "10.0.0.7",
        capabilities = OsCapabilities(hasPodman = true, hasDocker = false),
    )

    private fun harness(scope: TestScope) = ControllerHarness(
        context = ApplicationProvider.getApplicationContext(),
        testScope = scope,
    )

    /** Runs [block] and always stops the controller: its virtual-time loops must not outlive the test. */
    private suspend fun TestScope.withController(block: suspend (ControllerHarness) -> Unit) {
        val h = harness(this)
        try {
            block(h)
        } finally {
            h.controller.stop()
        }
    }

    @Test
    fun updateVerbBuildsPullThenUpChainInTheWorkingDir() = runTest {
        withController { h ->
            h.controller.performComposeStackAction(dockerServer, "webstack", "/opt/webstack", "update")

            val request = h.fake.streamRequests.single()
            assertEquals(
                "cd '/opt/webstack' && docker compose pull && docker compose up -d",
                request.command,
            )
            assertEquals(HomelabController.COMPOSE_UPDATE_TIMEOUT_MS, request.timeoutMs)
        }
    }

    @Test
    fun updateVerbOnAPodmanNodeUsesPodmanCompose() = runTest {
        withController { h ->
            h.controller.performComposeStackAction(podmanOnlyServer, "webstack", null, "update")

            val request = h.fake.streamRequests.single()
            assertEquals(
                "podman compose -p 'webstack' pull && podman compose -p 'webstack' up -d",
                request.command,
            )
        }
    }

    @Test
    fun updateVerbHonoursTheConfigFilesLabelWithFArgumentsOnBothHalves() = runTest {
        withController { h ->
            val files = "/opt/webstack/compose.yaml,/opt/webstack/compose.override.yaml"

            h.controller.performComposeStackAction(
                dockerServer,
                "webstack",
                "/opt/webstack",
                "update",
                configFiles = files,
            )

            val request = h.fake.streamRequests.single()
            assertEquals(
                "cd '/opt/webstack' && docker compose " +
                    "-f '/opt/webstack/compose.yaml' -f '/opt/webstack/compose.override.yaml' pull && " +
                    "docker compose " +
                    "-f '/opt/webstack/compose.yaml' -f '/opt/webstack/compose.override.yaml' up -d",
                request.command,
            )
        }
    }

    @Test
    fun explicitTimeoutOverridesThePerVerbDefault() = runTest {
        withController { h ->
            h.controller.performComposeStackAction(
                dockerServer,
                "webstack",
                "/opt/webstack",
                "update",
                timeoutMs = 60_000L,
            )

            assertEquals(60_000L, h.fake.streamRequests.single().timeoutMs)
        }
    }

    @Test
    fun triggerSourceIsRecordedOnTheAuditEntry() = runTest {
        withController { h ->
            h.controller.performComposeStackAction(
                dockerServer,
                "webstack",
                "/opt/webstack",
                "update",
                triggerSource = TriggerSource.BACKGROUND_WORKER,
            )

            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(TriggerSource.BACKGROUND_WORKER, audit.triggerSource)
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals("Compose update: webstack", audit.actionTitle)
        }
    }

    @Test
    fun regularVerbsKeepTheirLegacyShapeAndActionTimeout() = runTest {
        withController { h ->
            h.controller.performComposeStackAction(dockerServer, "webstack", "/opt/webstack", "restart")
            h.controller.performComposeStackAction(podmanOnlyServer, "webstack", null, "down")

            val restart = h.fake.streamRequests[0]
            val down = h.fake.streamRequests[1]
            assertEquals("cd '/opt/webstack' && docker compose restart", restart.command)
            assertEquals(HomelabController.COMPOSE_ACTION_TIMEOUT_MS, restart.timeoutMs)
            assertEquals("podman compose -p 'webstack' down", down.command)
            assertEquals(HomelabController.COMPOSE_ACTION_TIMEOUT_MS, down.timeoutMs)
        }
    }

    @Test
    fun configFilesOnAPlainVerbInjectFArgumentsWithoutChangingTheVerb() = runTest {
        withController { h ->
            h.controller.performComposeStackAction(
                dockerServer,
                "webstack",
                null,
                "down",
                configFiles = "/srv/compose.yaml",
            )

            val request = h.fake.streamRequests.single()
            assertTrue(request.command.contains("-f '/srv/compose.yaml'"), request.command)
            assertTrue(request.command.endsWith("down"), request.command)
            assertEquals(HomelabController.COMPOSE_ACTION_TIMEOUT_MS, request.timeoutMs)
        }
    }
}
