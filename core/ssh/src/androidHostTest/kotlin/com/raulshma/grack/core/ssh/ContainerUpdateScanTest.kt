package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.ContainerUpdateStatus
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 3B item 2: store-aware container update scanning. Covers the verdict matrix:
 * digest-pinned -> PINNED, no RepoDigests -> LOCAL, buildx index-digest match/mismatch ->
 * UP_TO_DATE / UPDATE_AVAILABLE, no-buildx classic-store fallback via `manifest inspect -v`
 * config digests, containerd store without buildx -> UNKNOWN, registry error -> UNKNOWN,
 * Podman -> UNKNOWN. Also asserts `ContainerInfo.updateAvailable` is set from the result.
 *
 * SYNTHETIC FIXTURES — verify against a real node before release. Format sources:
 * - `docker ps --format` rows: current 10-column layout (Phase 3A `config_files` label in
 *   column 9, ports in column 10).
 * - `docker images --format '{{.ID}}|{{.Repository}}|{{.Tag}}|{{.Digest}}|...'`: `{{.Digest}}`
 *   is the RepoDigest of pulled images and empty for locally built ones.
 * - `docker buildx imagetools inspect --format '{{.Manifest.Digest}}'`: one `sha256:...`
 *   line — the registry's current index (manifest list) digest, the only value comparable
 *   to RepoDigests across image stores.
 * - `docker manifest inspect -v`: JSON array of per-platform entries; per the docker/cli
 *   source (`cli/manifest/types/types.go`, `ImageManifest.Ref`/`Descriptor`), each entry
 *   carries its own manifest digest and `SchemaV2Manifest.config.digest` — the index digest
 *   does not appear, which is why the no-buildx fallback compares the local image id (a
 *   config digest on the classic store) against the remote config digests.
 * - `docker info --format '{{.Driver}}'`: `overlayfs` = containerd image store, `overlay2`
 *   = classic.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class ContainerUpdateScanTest {

    /** Shared fixtures for the container-scan suites (also used by [OsUpdateScanTest]). */
    internal object ContainerUpdateScanFixtures {
        // Current probe layout: id|name|image|state|status|project|service|workingDir|configFiles|ports
        val psFixture = """
            ===PS===
            abcdef123456|web|nginx:1.27|running|Up 3 hours|webstack|web|/opt/webstack|/opt/webstack/compose.yaml|0.0.0.0:8080->80/tcp
            fedcba654321|cache|redis:7|running|Up 3 days|||||
            111222333444|pinned|registry.example.com/custom/app@sha256:pinneddigest5555|running|Up 1 hour|||||
            555666777888|local|myapp:latest|running|Up 2 hours|||||
        """.trimIndent()

        val imagesFixture = """
            ===IMAGES===
            sha256:aaaaaaaa1111|nginx|1.27|sha256:nginxlocaldigest|187MB|2 weeks ago
            sha256:bbbbbbbb2222|redis|7|sha256:redislocaldigest|415MB|3 days ago
            sha256:cccccccc3333|myapp|latest||10MB|just now
        """.trimIndent()

        /** buildx answers with the registry's index digest; driver overlay2 = classic store. */
        fun buildxProbeFixture(remoteIndexDigest: String, buildxExit: Int = 0) = """
            ===BUILDX===
            ${if (buildxExit == 0) remoteIndexDigest else ""}
            __BUILDX_EXIT__=$buildxExit
            ===MANIFESTV===
            __MANIFEST_EXIT__=1
            ===DRIVER===
            overlay2
        """.trimIndent()

        /** buildx missing; the manifest fallback path (classic store, config digests listed).
         *  Digests are hex like real registry digests (`sha256:<64 hex>`). */

        fun manifestProbeFixture(driver: String, manifestExit: Int = 0) = """
            ===BUILDX===
            __BUILDX_EXIT__=127
            ===MANIFESTV===
            [
            	{
            		"ref": "docker.io/library/nginx:1.27@sha256:a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
            		"Descriptor": {
            			"mediaType": "application/vnd.docker.distribution.manifest.v2+json",
            			"digest": "sha256:a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
            			"size": 1570,
            			"platform": {"architecture": "amd64", "os": "linux"}
            		},
            		"SchemaV2Manifest": {
            			"schemaVersion": 2,
            			"config": {
            				"mediaType": "application/vnd.docker.container.image.v1+json",
            				"digest": "sha256:111122223333444455556666777788889999aaaabbbbccccddddeeeeffff0000",
            				"size": 7025
            			}
            		}
            	},
            	{
            		"ref": "docker.io/library/nginx:1.27@sha256:b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1",
            		"Descriptor": {
            			"digest": "sha256:b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1",
            			"platform": {"architecture": "arm64", "os": "linux"}
            		},
            		"SchemaV2Manifest": {
            			"config": {
            				"digest": "sha256:22223333444455556666777788889999aaaabbbbccccddddeeeeffff00001111",
            				"size": 7021
            			}
            		}
            	}
            ]
            __MANIFEST_EXIT__=$manifestExit
            ===DRIVER===
            $driver
        """.trimIndent()
    }

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
        capabilities = OsCapabilities(hasDocker = false, hasPodman = true),
    )

    private fun harness(scope: TestScope) = ControllerHarness(
        context = ApplicationProvider.getApplicationContext(),
        testScope = scope,
    )

    private suspend fun TestScope.withController(block: suspend (ControllerHarness) -> Unit) {
        val h = harness(this)
        try {
            block(h)
        } finally {
            h.controller.stop()
        }
    }

    /** Handler routing the fake's exec calls to the fixtures keyed by probe markers. */
    private fun fakeHandlerFor(
        probeFixtureFor: (imageRef: String) -> String,
        ps: String = ContainerUpdateScanFixtures.psFixture,
        images: String = ContainerUpdateScanFixtures.imagesFixture,
    ): suspend (FakeSshSessionClient.ExecRequest) -> SshExecResult = { request ->
        when {
            request.command.contains("===PS===") -> SshExecResult(0, ps, "", 1L)
            request.command.contains("===IMAGES===") -> SshExecResult(0, images, "", 1L)
            request.command.contains("buildx imagetools inspect") -> {
                val ref = request.command
                    .substringAfter("--format '{{.Manifest.Digest}}'").trim().trim('\'')
                SshExecResult(0, probeFixtureFor(ref), "", 1L)
            }
            else -> SshExecResult(0, "", "", 1L)
        }
    }

    // ------------------------------------------------------------------
    // Verdict matrix
    // ------------------------------------------------------------------

    @Test
    fun digestPinnedIsPinnedWithoutAnyProbe() = runTest {
        withController { h ->
            h.fake.executeHandler = fakeHandlerFor(
                probeFixtureFor = { _ -> ContainerUpdateScanFixtures.buildxProbeFixture("sha256:whatever") },
            )

            val result = h.controller.scanContainerUpdates(dockerServer)

            val pinned = result.entries.single { it.containerName == "pinned" }
            assertEquals(ContainerUpdateStatus.PINNED, pinned.status)
            assertEquals("sha256:pinneddigest5555", pinned.localRepoDigest)
            assertFalse(result.containers.single { it.name == "pinned" }.updateAvailable)
            // A pinned reference is never sent to the registry.
            assertTrue(
                h.fake.executeRequests.none { it.command.contains("registry.example.com/custom/app") },
                h.fake.executeRequests.map { it.command }.toString(),
            )
        }
    }

    @Test
    fun noRepoDigestsIsLocal() = runTest {
        withController { h ->
            h.fake.executeHandler = fakeHandlerFor(
                probeFixtureFor = { _ -> ContainerUpdateScanFixtures.buildxProbeFixture("sha256:anything") },
            )

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertEquals(ContainerUpdateStatus.LOCAL, result.entries.single { it.containerName == "local" }.status)
            // Locally built images have no registry identity to compare; not probed.
            assertTrue(
                h.fake.executeRequests.none { it.command.contains("'myapp:latest'") },
                h.fake.executeRequests.map { it.command }.toString(),
            )
        }
    }

    @Test
    fun buildxIndexDigestMismatchMeansUpdateAvailable() = runTest {
        withController { h ->
            h.fake.executeHandler = fakeHandlerFor(
                probeFixtureFor = { _ -> ContainerUpdateScanFixtures.buildxProbeFixture("sha256:nginxremotedigestNEW") },
            )

            val result = h.controller.scanContainerUpdates(dockerServer)

            val web = result.entries.single { it.containerName == "web" }
            assertEquals(ContainerUpdateStatus.UPDATE_AVAILABLE, web.status)
            assertEquals("sha256:nginxlocaldigest", web.localRepoDigest)
            assertEquals("sha256:nginxremotedigestNEW", web.remoteDigest)
            // The verdict lands on the container the UI already reads.
            assertTrue(result.containers.single { it.name == "web" }.updateAvailable)
            // Stack coordinates survive for the Update-stack action.
            assertEquals("webstack", web.composeProject)
            assertEquals("/opt/webstack", web.composeWorkingDir)
            assertEquals("/opt/webstack/compose.yaml", web.composeConfigFiles)
        }
    }

    @Test
    fun buildxIndexDigestMatchIsUpToDate() = runTest {
        withController { h ->
            h.fake.executeHandler = fakeHandlerFor(
                probeFixtureFor = { _ -> ContainerUpdateScanFixtures.buildxProbeFixture("sha256:redislocaldigest") },
            )

            val result = h.controller.scanContainerUpdates(dockerServer)

            val cache = result.entries.single { it.containerName == "cache" }
            assertEquals(ContainerUpdateStatus.UP_TO_DATE, cache.status)
            assertFalse(result.containers.single { it.name == "cache" }.updateAvailable)
        }
    }

    @Test
    fun withoutBuildxClassicStoreComparesConfigDigestsFromManifestInspect() = runTest {
        withController { h ->
            // On the classic store the nginx image id IS a config digest; make it the
            // amd64 config digest from the remote manifest list so the fallback matches.
            val amd64Config = "sha256:111122223333444455556666777788889999aaaabbbbccccddddeeeeffff0000"
            val images = ContainerUpdateScanFixtures.imagesFixture.replace(
                "sha256:aaaaaaaa1111",
                amd64Config,
            )
            h.fake.executeHandler = fakeHandlerFor(
                probeFixtureFor = { _ -> ContainerUpdateScanFixtures.manifestProbeFixture(driver = "overlay2") },
                images = images,
            )

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertEquals(ContainerUpdateStatus.UP_TO_DATE, result.entries.single { it.containerName == "web" }.status)
            assertFalse(result.containers.single { it.name == "web" }.updateAvailable)
        }
    }

    @Test
    fun withoutBuildxClassicStoreConfigDigestAbsentMeansUpdate() = runTest {
        withController { h ->
            h.fake.executeHandler = fakeHandlerFor(
                probeFixtureFor = { _ -> ContainerUpdateScanFixtures.manifestProbeFixture(driver = "overlay2") },
            )

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertEquals(ContainerUpdateStatus.UPDATE_AVAILABLE, result.entries.single { it.containerName == "web" }.status)
            assertTrue(result.containers.single { it.name == "web" }.updateAvailable)
        }
    }

    @Test
    fun containerdStoreWithoutBuildxIsUnknownNotAGuess() = runTest {
        withController { h ->
            h.fake.executeHandler = fakeHandlerFor(
                probeFixtureFor = { _ -> ContainerUpdateScanFixtures.manifestProbeFixture(driver = "overlayfs") },
            )

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertEquals(ContainerUpdateStatus.UNKNOWN, result.entries.single { it.containerName == "web" }.status)
            assertFalse(result.containers.single { it.name == "web" }.updateAvailable)
        }
    }

    @Test
    fun registryErrorIsUnknown() = runTest {
        withController { h ->
            h.fake.executeHandler = fakeHandlerFor(
                probeFixtureFor = { _ -> ContainerUpdateScanFixtures.buildxProbeFixture(remoteIndexDigest = "", buildxExit = 1) },
            )

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertEquals(ContainerUpdateStatus.UNKNOWN, result.entries.single { it.containerName == "web" }.status)
        }
    }

    @Test
    fun podmanNodesAreUnknownAcrossTheBoard() = runTest {
        withController { h ->
            val podmanPs = """
                ===PS===
                9f1ac1e2b3c4|web|quay.io/ceph/ceph:v18|running|Up 3 hours|||||
            """.trimIndent()
            h.fake.executeHandler = { request ->
                when {
                    request.command.contains("===PS===") -> SshExecResult(0, podmanPs, "", 1L)
                    request.command.contains("buildx imagetools inspect") ->
                        throw AssertionError("podman must not be digest-probed")
                    else -> SshExecResult(0, "", "", 1L)
                }
            }

            val result = h.controller.scanContainerUpdates(podmanOnlyServer)

            assertEquals(listOf(ContainerUpdateStatus.UNKNOWN), result.entries.map { it.status })
            assertFalse(result.containers.single().updateAvailable)
        }
    }

    @Test
    fun imageMissingFromTheLocalStoreIsUnknown() = runTest {
        withController { h ->
            h.fake.executeHandler = fakeHandlerFor(
                probeFixtureFor = { _ -> ContainerUpdateScanFixtures.buildxProbeFixture("sha256:x") },
                images = "===IMAGES===\n",
            )

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertEquals(ContainerUpdateStatus.UNKNOWN, result.entries.single { it.containerName == "web" }.status)
        }
    }

    // ------------------------------------------------------------------
    // Parser unit cases
    // ------------------------------------------------------------------

    @Test
    fun digestProbeParserReadsBuildxDigestExitManifestDigestsAndDriver() {
        val probe = UpdateParsers.parseContainerDigestProbe(ContainerUpdateScanFixtures.manifestProbeFixture(driver = "overlay2"))

        assertEquals("", probe.remoteIndexDigest)
        assertEquals(127, probe.buildxExit)
        assertEquals(0, probe.manifestExit)
        assertEquals(
            listOf(
                "sha256:a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
                "sha256:b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1",
                "sha256:111122223333444455556666777788889999aaaabbbbccccddddeeeeffff0000",
                "sha256:22223333444455556666777788889999aaaabbbbccccddddeeeeffff00001111",
            ).sorted(),
            probe.manifestDigests.sorted(),
        )
        assertEquals("overlay2", probe.driver)
        assertFalse(probe.errored)
    }

    @Test
    fun digestProbeParserFlagsNullOutputAsErrored() {
        assertTrue(UpdateParsers.parseContainerDigestProbe(null).errored)
        assertTrue(UpdateParsers.parseContainerDigestProbe("").errored)
    }

    @Test
    fun digestComparisonNormalizesCaseAndPrefix() {
        assertEquals("abc", UpdateParsers.normalizeDigest("sha256:ABC"))
        val decision = UpdateParsers.decideContainerUpdate(
            localRepoDigest = "sha256:SameDigest",
            localImageId = "",
            probe = ContainerDigestProbe(remoteIndexDigest = "sha256:samedigest", buildxExit = 0),
        )
        assertEquals(ContainerUpdateStatus.UP_TO_DATE, decision.status)
    }

    @Test
    fun emptyContainerListShortCircuitsTheScan() = runTest {
        withController { h ->
            h.fake.executeHandler = { _ -> SshExecResult(0, "===PS===\n", "", 1L) }

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertTrue(result.entries.isEmpty())
            assertTrue(result.containers.isEmpty())
            assertTrue(h.fake.executeRequests.none { it.command.contains("===IMAGES===") })
        }
    }

    @Test
    fun unparsableProbeOutputIsUnknown() = runTest {
        withController { h ->
            h.fake.executeHandler = fakeHandlerFor(
                probeFixtureFor = { _ -> "total garbage without markers" },
            )

            val result = h.controller.scanContainerUpdates(dockerServer)

            // No sections -> errored probe -> UNKNOWN (never a false UPDATE).
            assertEquals(ContainerUpdateStatus.UNKNOWN, result.entries.single { it.containerName == "web" }.status)
        }
    }
}
