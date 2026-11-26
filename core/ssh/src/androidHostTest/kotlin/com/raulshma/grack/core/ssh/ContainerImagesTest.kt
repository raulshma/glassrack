package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.ContainerImageInfo
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 3A item 2: image listing script shape, parser fixtures and the destructive image actions. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class ContainerImagesTest {

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

    /** Paste-style fixture of `docker images --format '{{.ID}}|{{.Repository}}|{{.Tag}}|{{.Digest}}|{{.Size}}|{{.CreatedSince}}'`. */
    private val dockerFixture = """
        ===IMAGES===
        sha256:aa11bb22cc33dd44ee44ff55|nginx|latest|sha256:digest1deadbeef|187MB|2 weeks ago
        sha256:bb22cc33dd44ee44ff55aa11|postgres|16-alpine|sha256:digest2feedface|415MB|3 days ago
        sha256:cc33dd44ee44ff55aa11bb22|<none>|<none>|<none>|0B|40 seconds ago
    """.trimIndent()

    /** Podman prints the same field names; sizes carry decimals and the digest can be empty. */
    private val podmanFixture = """
        ===IMAGES===
        9f1ac1e2b3c4|quay.io/ceph/ceph|v18|sha256:podmandigest|1.24GB|5 hours ago
        7e2bd0f3c4d5|<none>|<none>|<none>|23.4MB|About a minute ago
    """.trimIndent()

    @Test
    fun imagesScriptTargetsDockerByDefaultAndPodmanWhenRequested() {
        val dockerScript = TelemetryProbes.dockerImagesScript(usePodman = false)
        val podmanScript = TelemetryProbes.dockerImagesScript(usePodman = true)

        assertTrue(dockerScript.contains("===IMAGES==="), dockerScript)
        assertTrue(dockerScript.contains("docker images"), dockerScript)
        assertFalse(dockerScript.contains("podman"), dockerScript)
        assertTrue(dockerScript.contains("--format"), dockerScript)

        assertTrue(podmanScript.contains("podman images"), podmanScript)
        assertFalse(podmanScript.contains("docker"), podmanScript)
        assertTrue(podmanScript.contains("{{.Repository}}|{{.Tag}}|{{.Digest}}|{{.Size}}|{{.CreatedSince}}"), podmanScript)
    }

    @Test
    fun parsesDockerImagesFixtureWithDanglingDetection() {
        val images = TelemetryParsers.parseImages(dockerFixture)

        assertEquals(3, images.size)
        val nginx = images[0]
        assertEquals("sha256:aa11bb22cc33dd44ee44ff55", nginx.id)
        assertEquals("nginx", nginx.repository)
        assertEquals("latest", nginx.tag)
        assertEquals("nginx:latest", nginx.repoTag)
        assertEquals("sha256:digest1deadbeef", nginx.digest)
        assertEquals(187_000_000L, nginx.sizeBytes)
        assertEquals("2 weeks ago", nginx.createdSince)
        assertFalse(nginx.dangling)

        val dangling = images[2]
        assertTrue(dangling.dangling)
        assertEquals("<none>:<none>", dangling.repoTag)
        assertEquals(0L, dangling.sizeBytes)
        assertFalse(images[1].dangling)
    }

    @Test
    fun parsesPodmanImagesFixture() {
        val images = TelemetryParsers.parseImages(podmanFixture)

        assertEquals(2, images.size)
        val ceph = images[0]
        assertEquals("quay.io/ceph/ceph:v18", ceph.repoTag)
        assertEquals(1_240_000_000L, ceph.sizeBytes)
        assertEquals("sha256:podmandigest", ceph.digest)
        assertFalse(ceph.dangling)
        assertTrue(images[1].dangling)
        assertEquals(23_400_000L, images[1].sizeBytes)
    }

    @Test
    fun ignoresShortOrBlankImageRows() {
        val images = TelemetryParsers.parseImages(
            """
            ===IMAGES===
            some random noise line
            sha256:onlythree|
            ${""}
            sha256:valid0123456789|redis|7|sha256:dg|10MB|just now
            """.trimIndent(),
        )
        assertEquals(1, images.size)
        assertEquals("redis:7", images.single().repoTag)
    }

    @Test
    fun fetchImagesUsesPodmanScriptOnAPodmanOnlyNode() = runTest {
        withController { h ->
            h.fake.executeHandler = { request ->
                assertTrue(request.command.contains("podman images"), request.command)
                SshExecResult(0, podmanFixture, "", 1L)
            }

            val images = h.controller.fetchImages(podmanOnlyServer)

            assertEquals(2, images.size)
        }
    }

    @Test
    fun fetchImagesUsesDockerScriptOnADockerNode() = runTest {
        withController { h ->
            h.fake.executeHandler = { request ->
                assertTrue(request.command.contains("docker images"), request.command)
                SshExecResult(0, dockerFixture, "", 1L)
            }

            val images = h.controller.fetchImages(dockerServer)

            assertEquals(3, images.size)
        }
    }

    @Test
    fun imageRemoveFollowsTheNodeRuntimeAndIsAudited() = runTest {
        withController { h ->
            val image = ContainerImageInfo(
                id = "sha256:cc33dd44ee44ff55aa11bb22",
                repository = "<none>",
                tag = "<none>",
                repoTag = "<none>:<none>",
                dangling = true,
            )

            val result = h.controller.performImageRemove(podmanOnlyServer, image)

            assertEquals(0, result.exitCode)
            assertEquals("podman rmi 'sha256:cc33dd44ee44ff55aa11bb22'", h.fake.executeRequests.single().command)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals("Remove image: <none>:<none>", audit.actionTitle)
        }
    }

    @Test
    fun danglingImagePruneFollowsTheNodeRuntimeAndIsAudited() = runTest {
        withController { h ->
            h.controller.performDanglingImagePrune(dockerServer)

            assertEquals("docker image prune -f", h.fake.executeRequests.single().command)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals("Prune dangling images", audit.actionTitle)
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
        }
    }

    @Test
    fun containersParserReadsTheConfigFilesLabel() {
        // Columns: id|name|image|state|status|project|service|workingDir|configFiles|ports
        val psFixture = """
            ===PS===
            abcdef123456|web|nginx:1.27|running|Up 3 hours|webstack|web|/opt/webstack|/opt/webstack/compose.yaml,/opt/webstack/compose.override.yaml|0.0.0.0:8080->80/tcp
        """.trimIndent()

        val containers = TelemetryParsers.parseContainers(psFixture)

        val web = containers.single()
        assertEquals("/opt/webstack/compose.yaml,/opt/webstack/compose.override.yaml", web.composeConfigFiles)
        assertEquals("0.0.0.0:8080->80/tcp", web.portsSummary)
        assertEquals("webstack", web.composeProject)

        // The stack grouping carries the label through so the update path can use it.
        val (stacks, _) = TelemetryParsers.groupComposeStacks(containers)
        assertEquals(web.composeConfigFiles, stacks.single().configFiles)
    }
}
