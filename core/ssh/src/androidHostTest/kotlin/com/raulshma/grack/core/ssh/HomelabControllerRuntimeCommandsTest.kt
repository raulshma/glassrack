package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.ContainerAction
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.ContainerRuntime
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase 1B: every container command follows the node's (or container's) runtime, not a hardcoded docker. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class HomelabControllerRuntimeCommandsTest {

    private fun harness(scope: TestScope) = ControllerHarness(
        context = ApplicationProvider.getApplicationContext(),
        testScope = scope,
    )

    private val podmanOnlyServer = Server(
        id = "srv-podman",
        name = "podman-node",
        primaryHost = "10.0.0.7",
        capabilities = OsCapabilities(hasPodman = true, hasDocker = false),
    )

    private val dockerServer = Server(
        id = "srv-docker",
        name = "docker-node",
        primaryHost = "10.0.0.5",
        capabilities = OsCapabilities(hasDocker = true, hasPodman = false),
    )

    @Test
    fun composeStackActionUsesPodmanComposeOnAPodmanNode() = runTest {
        val h = harness(this)

        h.controller.performComposeStackAction(podmanOnlyServer, "webstack", "/opt/webstack", "down")

        val command = h.fake.streamRequests.single().command
        assertTrue(command.contains("podman compose down"), command)
        assertTrue(!command.contains("docker"), command)
        h.controller.stop()
    }

    @Test
    fun composeStackActionKeepsDockerOnADockerNode() = runTest {
        val h = harness(this)

        h.controller.performComposeStackAction(dockerServer, "webstack", null, "ps")

        val command = h.fake.streamRequests.single().command
        assertTrue(command.contains("docker compose -p 'webstack' ps"), command)
        h.controller.stop()
    }

    @Test
    fun containerLogsFollowTheContainersOwnRuntime() = runTest {
        val h = harness(this)
        val podmanContainer = ContainerInfo(
            id = "p1", shortId = "p1", name = "jellyfin", image = "jellyfin/jellyfin",
            runtime = ContainerRuntime.PODMAN,
        )
        val dockerContainer = ContainerInfo(
            id = "d1", shortId = "d1", name = "caddy", image = "caddy",
            runtime = ContainerRuntime.DOCKER,
        )

        h.controller.fetchContainerLogs(podmanOnlyServer, podmanContainer)
        h.controller.fetchContainerLogs(dockerServer, dockerContainer)

        assertEquals("podman logs --tail 200 'jellyfin' 2>&1", h.fake.executeRequests[0].command)
        assertEquals("docker logs --tail 200 'caddy' 2>&1", h.fake.executeRequests[1].command)
        h.controller.stop()
    }

    @Test
    fun pullRecreateUsesPodmanComposeForComposeManagedPodmanContainers() = runTest {
        val h = harness(this)
        val stackContainer = ContainerInfo(
            id = "p2", shortId = "p2", name = "web", image = "nginx",
            runtime = ContainerRuntime.PODMAN,
            composeProject = "webstack",
            composeService = "web",
            composeWorkingDir = "/opt/webstack",
        )

        h.controller.performContainerAction(podmanOnlyServer, stackContainer, ContainerAction.PULL_RECREATE)

        val command = h.fake.streamRequests.single().command
        assertTrue(command.contains("cd '/opt/webstack' && podman compose pull 'web'"), command)
        assertTrue(command.contains("podman compose up -d 'web'"), command)
        h.controller.stop()
    }

    @Test
    fun containerRuntimeForPrefersDockerWhenBothRuntimesExist() = runTest {
        val h = harness(this)
        val both = Server(id = "srv-both", name = "both", primaryHost = "10.0.0.8", capabilities = OsCapabilities(hasDocker = true, hasPodman = true))
        assertEquals(ContainerRuntime.DOCKER, h.controller.containerRuntimeFor(both))
        assertEquals(ContainerRuntime.PODMAN, h.controller.containerRuntimeFor(podmanOnlyServer))
        h.controller.stop()
    }
}
