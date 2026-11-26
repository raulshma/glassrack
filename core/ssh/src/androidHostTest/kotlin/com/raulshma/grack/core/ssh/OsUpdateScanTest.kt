package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.DistroFamily
import com.raulshma.grack.core.model.NodeUpdateReport
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.OsScanStatus
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.distroFamilyForDistroId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 3B item 1: OS update scanning. Script shapes per distro family, parser fixtures,
 * and the controller path that persists the report into `servers.updateReportJson`.
 *
 * SYNTHETIC FIXTURES — verify against a real node before release. These outputs were
 * written from the documented formats of each tool, not captured from live hosts:
 * - `apt list --upgradable` (Debian): `name/repo candidate arch [upgradable from: current]`,
 *   preceded by a `Listing...` banner.
 * - `dnf -q check-update` (RHEL): aligned columns `name.arch evr repo`, exit code 100 when
 *   updates exist (success-with-updates), 1 on error.
 * - `checkupdates` (Arch, pacman-contrib): `name current -> candidate`; exit 2/no output
 *   when current.
 * - `apk version -l '<'` (Alpine): `name-version-rN < name-version-rN [(repo)]`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class OsUpdateScanTest {

    // ------------------------------------------------------------------
    // Synthetic fixtures (see class KDoc)
    // ------------------------------------------------------------------

    private val aptFixture = """
        ===OSUPD===
        __APT_UPDATE_EXIT__=0
        Listing... Done
        curl/jammy-updates 7.81.0-1ubuntu1.16 amd64 [upgradable from: 7.81.0-1ubuntu1.4]
        openssl/jammy-updates,jammy-security 3.0.2-0ubuntu1.15 amd64 [upgradable from: 3.0.2-0ubuntu1.10]
        nginx/nabian 1.25.3-1~jammy amd64 [upgradable from: 1.24.0-1~jammy]
    """.trimIndent()

    private val dnfFixtureExit100 = """
        ===OSUPD===
        kernel-core.x86_64                5.14.0-427.13.1.el9_4        rhel-9-for-x86_64-baseos-rpms
        curl.x86_64                       8.0.1-8.el9                  fedora-updates
        openssl-libs.x86_64               1:3.0.7-27.el9               fedora-security
        __DNF_EXIT__=100
    """.trimIndent()

    private val dnfFixtureError = """
        ===OSUPD===
        error: Failed to download metadata for repo 'appstream'
        __DNF_EXIT__=1
    """.trimIndent()

    private val checkUpdatesFixture = """
        ===OSUPD===
        linux 6.1.1.arch1-1 -> 6.1.2.arch1-1
        firefox 107.0-1 -> 108.0-2
        __CHECKUPDATES_EXIT__=0
    """.trimIndent()

    private val checkUpdatesMissingFixture = """
        ===OSUPD===
        __CHECKUPDATES_MISSING__
    """.trimIndent()

    private val apkFixture = """
        ===OSUPD===
        busybox-1.36.1-r5 < busybox-1.36.1-r7
        alpine-baselayout-3.4.3-r2 < alpine-baselayout-3.4.3-r3 (http://dl-cdn.alpinelinux.org/alpine/v3.18/main)
    """.trimIndent()

    private val unsupportedFixture = """
        ===OSUPD===
        __UPDATE_UNSUPPORTED__
    """.trimIndent()

    // ------------------------------------------------------------------
    // Script shape per distro family
    // ------------------------------------------------------------------

    @Test
    fun debianFamilyScriptRunsQuietUpdateThenUpgradableList() {
        val script = UpdateProbes.updateCheckScript(OsCapabilities(distroId = "debian"))
        assertTrue(script.contains("===OSUPD==="), script)
        assertTrue(script.contains("apt-get update -qq"), script)
        assertTrue(script.contains("apt list --upgradable"), script)
        assertTrue(script.contains("__APT_UPDATE_EXIT__"), script)
    }

    @Test
    fun ubuntuRaspbianAndMintMapToTheDebianFamily() {
        assertEquals(DistroFamily.DEBIAN, distroFamilyForDistroId("ubuntu"))
        assertEquals(DistroFamily.DEBIAN, distroFamilyForDistroId("raspbian"))
        assertEquals(DistroFamily.DEBIAN, distroFamilyForDistroId("linuxmint"))
    }

    @Test
    fun rhelFamilyScriptTreatsExit100AsData() {
        val script = UpdateProbes.updateCheckScript(OsCapabilities(distroId = "fedora"))
        assertTrue(script.contains("dnf -q check-update"), script)
        assertTrue(script.contains("__DNF_EXIT__"), script)
        assertEquals(DistroFamily.RHEL, distroFamilyForDistroId("rocky"))
        assertEquals(DistroFamily.RHEL, distroFamilyForDistroId("rhel"))
    }

    @Test
    fun archFamilyScriptGuardsCheckupdatesAvailability() {
        val script = UpdateProbes.updateCheckScript(OsCapabilities(distroId = "arch"))
        assertTrue(script.contains("command -v checkupdates"), script)
        assertTrue(script.contains("checkupdates"), script)
        assertTrue(script.contains("__CHECKUPDATES_MISSING__"), script)
        assertEquals(DistroFamily.ARCH, distroFamilyForDistroId("manjaro"))
    }

    @Test
    fun alpineFamilyScriptRefreshesIndexThenListsDowngrades() {
        val script = UpdateProbes.updateCheckScript(OsCapabilities(distroId = "alpine"))
        assertTrue(script.contains("apk update"), script)
        assertTrue(script.contains("apk version -l '<'"), script)
        assertEquals(DistroFamily.ALPINE, distroFamilyForDistroId("alpine"))
    }

    @Test
    fun unknownDistroIdProducesTheUnsupportedMarker() {
        val script = UpdateProbes.updateCheckScript(OsCapabilities(distroId = "nixos"))
        assertTrue(script.contains("__UPDATE_UNSUPPORTED__"), script)
        assertEquals(DistroFamily.UNSUPPORTED, distroFamilyForDistroId("nixos"))
        assertEquals(DistroFamily.UNSUPPORTED, distroFamilyForDistroId(""))
    }

    // ------------------------------------------------------------------
    // Parser fixtures per family
    // ------------------------------------------------------------------

    @Test
    fun parsesAptUpgradableFixtureWithCurrentAndCandidate() {
        val section = UpdateParsers.parseOsUpdateSection(aptFixture, DistroFamily.DEBIAN)

        assertEquals(OsScanStatus.OK, section.status)
        assertEquals(3, section.packages.size)
        val curl = section.packages[0]
        assertEquals("curl", curl.packageName)
        assertEquals("7.81.0-1ubuntu1.4", curl.currentVersion)
        assertEquals("7.81.0-1ubuntu1.16", curl.candidateVersion)
        val openssl = section.packages[1]
        assertEquals("openssl", openssl.packageName)
        assertEquals("3.0.2-0ubuntu1.10", openssl.currentVersion)
    }

    @Test
    fun parsesDnfFixtureExit100AsSuccessWithUpdates() {
        val section = UpdateParsers.parseOsUpdateSection(dnfFixtureExit100, DistroFamily.RHEL)

        assertEquals(OsScanStatus.OK, section.status)
        assertEquals(3, section.packages.size)
        assertEquals("kernel-core", section.packages[0].packageName)
        assertEquals("5.14.0-427.13.1.el9_4", section.packages[0].candidateVersion)
        assertEquals("openssl-libs", section.packages[2].packageName)
        // check-update carries no installed version; epoch prefixes survive on candidates.
        assertEquals(null, section.packages[2].currentVersion)
        assertEquals("1:3.0.7-27.el9", section.packages[2].candidateVersion)
    }

    @Test
    fun dnfExitOneIsAFailureNotAnEmptyList() {
        val section = UpdateParsers.parseOsUpdateSection(dnfFixtureError, DistroFamily.RHEL)
        assertEquals(OsScanStatus.FAILED, section.status)
        assertTrue(section.packages.isEmpty())
    }

    @Test
    fun parsesCheckUpdatesFixtureNameArrowCandidate() {
        val section = UpdateParsers.parseOsUpdateSection(checkUpdatesFixture, DistroFamily.ARCH)

        assertEquals(OsScanStatus.OK, section.status)
        assertEquals(2, section.packages.size)
        val linux = section.packages[0]
        assertEquals("linux", linux.packageName)
        assertEquals("6.1.1.arch1-1", linux.currentVersion)
        assertEquals("6.1.2.arch1-1", linux.candidateVersion)
    }

    @Test
    fun missingCheckupdatesBinaryIsUnsupported() {
        val section = UpdateParsers.parseOsUpdateSection(checkUpdatesMissingFixture, DistroFamily.ARCH)
        assertEquals(OsScanStatus.UNSUPPORTED, section.status)
        assertTrue(section.packages.isEmpty())
    }

    @Test
    fun parsesApkVersionFixtureSplittingNameFromVersionRN() {
        val section = UpdateParsers.parseOsUpdateSection(apkFixture, DistroFamily.ALPINE)

        assertEquals(OsScanStatus.OK, section.status)
        assertEquals(2, section.packages.size)
        val busybox = section.packages[0]
        assertEquals("busybox", busybox.packageName)
        assertEquals("1.36.1-r5", busybox.currentVersion)
        assertEquals("1.36.1-r7", busybox.candidateVersion)
        val baselayout = section.packages[1]
        assertEquals("alpine-baselayout", baselayout.packageName)
        assertEquals("3.4.3-r3", baselayout.candidateVersion)
    }

    @Test
    fun unsupportedMarkerMapsToUnsupportedStatus() {
        val section = UpdateParsers.parseOsUpdateSection(unsupportedFixture, DistroFamily.UNSUPPORTED)
        assertEquals(OsScanStatus.UNSUPPORTED, section.status)
        assertTrue(section.packages.isEmpty())
    }

    // ------------------------------------------------------------------
    // Controller path: scan + persistence
    // ------------------------------------------------------------------

    private val debianServer = Server(
        id = "srv-deb",
        name = "deb-node",
        primaryHost = "10.0.0.5",
        capabilities = OsCapabilities(distroId = "debian"),
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

    @Test
    fun scanOsUpdatesParsesTheFixtureAndPersistsTheReport() = runTest {
        withController { h ->
            h.fake.executeHandler = { request ->
                assertTrue(request.command.contains("===OSUPD==="), request.command)
                SshExecResult(0, aptFixture, "", 1L)
            }

            val section = h.controller.scanOsUpdates(debianServer)

            assertEquals(OsScanStatus.OK, section.status)
            assertEquals(DistroFamily.DEBIAN, section.family)
            assertEquals(3, section.packages.size)
            assertEquals(0, h.fake.streamRequests.size, "the scan is not streamed")
        }
    }

    @Test
    fun connectionFailureBecomesAFailedSectionNotAnException() = runTest {
        withController { h ->
            h.fake.executeHandler = { _ -> throw IllegalStateException("connection dropped") }

            val section = h.controller.scanOsUpdates(debianServer)

            assertEquals(OsScanStatus.FAILED, section.status)
            assertEquals(DistroFamily.DEBIAN, section.family)
        }
    }

    @Test
    fun scanNodeUpdatesMergesOsAndContainersAndPersistsJson() = runTest {
        withController { h ->
            // Images: nginx and redis repo digests differ from the remote index (updates),
            // myapp has no RepoDigests (local build), the third ref is digest-pinned.
            h.fake.executeHandler = { request ->
                when {
                    request.command.contains("===OSUPD===") -> SshExecResult(0, aptFixture, "", 1L)
                    request.command.contains("===PS===") -> SshExecResult(0, ContainerUpdateScanTest.ContainerUpdateScanFixtures.psFixture, "", 1L)
                    request.command.contains("===IMAGES===") -> SshExecResult(0, ContainerUpdateScanTest.ContainerUpdateScanFixtures.imagesFixture, "", 1L)
                    request.command.contains("buildx imagetools inspect") ->
                        SshExecResult(0, ContainerUpdateScanTest.ContainerUpdateScanFixtures.buildxProbeFixture("sha256:remoteindexdigest9999"), "", 1L)
                    else -> SshExecResult(0, "", "", 1L)
                }
            }
            val dockerServer = debianServer.copy(
                id = "srv-docker",
                capabilities = OsCapabilities(distroId = "debian", hasDocker = true),
            )
            // The report is persisted into the existing row; a scan never invents one.
            h.repository.upsertServer(dockerServer)

            val report = h.controller.scanNodeUpdates(dockerServer)

            assertEquals(3, report.osUpdateCount)
            // nginx and redis both mismatch the remote index digest; myapp is LOCAL, pinned is PINNED.
            assertEquals(2, report.containerUpdateCount)
            assertTrue(report.hasAnyUpdates)

            // Persisted into the schema-v2 column and readable back through the model.
            val stored = h.repository.getServersOnce().first { it.id == dockerServer.id }
            assertNotNull(stored.updateReportJson)
            val parsed = NodeUpdateReport.fromJson(stored.updateReportJson)
            assertNotNull(parsed)
            assertEquals(report.osUpdateCount, parsed.osUpdateCount)
            assertEquals(report.containerUpdateCount, parsed.containerUpdateCount)
            assertEquals(2, parsed.containers.count { it.status == com.raulshma.grack.core.model.ContainerUpdateStatus.UPDATE_AVAILABLE })
        }
    }

    @Test
    fun containerScanIsSkippedOnNodesWithoutDocker() = runTest {
        withController { h ->
            h.fake.executeHandler = { request ->
                when {
                    request.command.contains("===OSUPD===") -> SshExecResult(0, aptFixture, "", 1L)
                    else -> SshExecResult(0, "", "", 1L)
                }
            }

            val report = h.controller.scanNodeUpdates(debianServer)

            assertEquals(3, report.osUpdateCount)
            assertTrue(report.containers.isEmpty())
            assertTrue(h.fake.executeRequests.none { it.command.contains("===PS===") })
        }
    }
}
