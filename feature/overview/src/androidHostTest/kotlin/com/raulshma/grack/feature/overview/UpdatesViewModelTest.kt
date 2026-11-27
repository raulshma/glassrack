package com.raulshma.grack.feature.overview

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.database.GlassrackDatabase
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.database.PreferencesStore
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuditEntry
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.ComposeStack
import com.raulshma.grack.core.model.ConfirmationLevel
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.ContainerUpdateStatus
import com.raulshma.grack.core.model.DistroFamily
import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.NodeUpdateReport
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.OsPackageUpdate
import com.raulshma.grack.core.model.OsScanStatus
import com.raulshma.grack.core.model.OsUpdateSection
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.SudoAuth
import com.raulshma.grack.core.model.sudoSecretVaultId
import com.raulshma.grack.core.security.SecureVault
import com.raulshma.grack.core.ssh.FileTransferProgress
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.core.ssh.SshExecResult
import com.raulshma.grack.core.ssh.SshSessionClient
import com.raulshma.grack.core.ssh.SshShellSession
import com.raulshma.grack.core.ssh.TransferSink
import com.raulshma.grack.core.ssh.TransferSource
import com.raulshma.grack.core.ssh.TelemetryParsers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Path.Companion.toPath
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Updates card's flow logic: what a scan tells the user per node (a stale package index
 * and an unanswered registry are problems, never silence or "up to date"), that the OS half is
 * visible before the registry pass ends, that an apply's banner survives its rescan, that a
 * failed apply says why in plain words, and that every refused apply — Safe Read-Only Mode, a
 * dismissed confirmation — reaches the ledger as BLOCKED. Hermetic: scripted transport fake,
 * in-memory Room, all on the test scheduler.
 *
 * FIXTURE PROVENANCE: the apt listings, the rejected-sudo stderr and the reboot-probe outputs
 * are REAL captures from 2026-11-27 (their full story is in `OsUpdateScanTest`). The
 * container rows, registry answers and the apply stdout are SYNTHETIC — only the verdict and
 * banner logic consumes them, marked again where they appear.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class UpdatesViewModelTest {

    /** REAL: `apt list --upgradable` on Debian 12 against a stale index. */
    private val debianListing = """
        ===OSUPD===
        Listing...
        libpam-modules-bin/oldstable-security 1.5.2-6+deb12u3 amd64 [upgradable from: 1.5.2-6+deb12u2]
        libpam-modules/oldstable-security 1.5.2-6+deb12u3 amd64 [upgradable from: 1.5.2-6+deb12u2]
        libpam-runtime/oldstable-security 1.5.2-6+deb12u3 all [upgradable from: 1.5.2-6+deb12u2]
        libpam0g/oldstable-security 1.5.2-6+deb12u3 amd64 [upgradable from: 1.5.2-6+deb12u2]
        __APT_LIST_EXIT__=0
    """.trimIndent()

    /** REAL: the same Debian 12 right after its upgrade — apt's banner and nothing else. */
    private val nothingListed = "===OSUPD===\nListing...\n__APT_LIST_EXIT__=0"

    /** REAL: `sudo -S -p ''` on Linux Mint 22.2 after it was given the wrong password. */
    private val sudoRejectedPassword = """
        Sorry, try again.

        sudo: no password was provided
        sudo: 1 incorrect password attempt
    """.trimIndent()

    /**
     * Capabilities as a finished capability probe leaves them. With a blank `distro` (or an
     * older probe version) the controller's poller re-probes them on the next poll and
     * overwrites the row with what the node says — the scripted transport is not a node.
     */
    private fun probedCapabilities(hasDocker: Boolean = false) = OsCapabilities(
        distroId = "debian",
        distro = "Debian GNU/Linux 12 (bookworm)",
        hasDocker = hasDocker,
        probeVersion = TelemetryParsers.CAPABILITIES_PROBE_VERSION,
    )

    private val piNode = Server(
        id = "srv-pi",
        name = "pi-node",
        primaryHost = "10.0.0.5",
        username = "pi-user",
        capabilities = probedCapabilities(),
    )

    private val noPrivilegePath =
        "Package index not refreshed — 'pi-user' is neither root nor allowed passwordless sudo, and no sudo password is " +
            "stored for this node — set one under Edit node → Privilege Escalation"

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    /** Scriptable transport: one handler answers every command, streamed or not, and records what was sent. */
    private class ScriptedSshClient : SshSessionClient {
        var handler: suspend (Server, String) -> SshExecResult = { _, _ -> SshExecResult(0, "ok", "", 1L) }

        /** Every command with the stdin it was given. */
        val sent = mutableListOf<Pair<String, String?>>()

        private suspend fun run(server: Server, command: String, stdinText: String?): SshExecResult {
            sent += command to stdinText
            return handler(server, command)
        }

        override suspend fun execute(server: Server, allServers: List<Server>, command: String, timeoutMs: Long, stdinText: String?): SshExecResult =
            run(server, command, stdinText)

        override suspend fun executeStreaming(
            server: Server,
            allServers: List<Server>,
            command: String,
            timeoutMs: Long?,
            stdinText: String?,
            onChunk: suspend (String) -> Unit,
        ): SshExecResult = run(server, command, stdinText)

        override suspend fun testConnection(
            server: Server,
            allServers: List<Server>,
            trustFingerprintOnFirstConnect: Boolean,
            transientPassword: String?,
        ): Result<HostKeyChallenge?> = Result.success(null)

        override suspend fun openShell(server: Server, allServers: List<Server>, cols: Int, rows: Int): SshShellSession =
            throw UnsupportedOperationException("not used in tests")

        override suspend fun listFiles(server: Server, allServers: List<Server>, remotePath: String, timeoutMs: Long): List<RemoteFileEntry> =
            emptyList()

        override suspend fun readFileText(server: Server, allServers: List<Server>, remotePath: String, timeoutMs: Long): String = ""

        override suspend fun writeFileTextWithBackup(
            server: Server,
            allServers: List<Server>,
            remotePath: String,
            newContent: String,
            timeoutMs: Long,
        ): Boolean = true

        override suspend fun uploadFileText(server: Server, allServers: List<Server>, remotePath: String, content: String, timeoutMs: Long) = Unit

        override suspend fun downloadFile(
            server: Server,
            allServers: List<Server>,
            remotePath: String,
            destination: TransferSink,
            timeoutMs: Long?,
            onProgress: suspend (FileTransferProgress) -> Unit,
        ): Long = 0L

        override suspend fun uploadFile(
            server: Server,
            allServers: List<Server>,
            localSource: TransferSource,
            remotePath: String,
            totalBytes: Long,
            timeoutMs: Long?,
            onProgress: suspend (FileTransferProgress) -> Unit,
        ): Long = 0L

        override suspend fun deployPublicKey(server: Server, allServers: List<Server>, publicKeyOpenSsh: String): SshExecResult =
            SshExecResult(0, "", "", 0L)

        override suspend fun openLocalPortForward(
            tunnelId: String,
            server: Server,
            allServers: List<Server>,
            localPort: Int,
            remoteHost: String,
            remotePort: Int,
        ): Int = localPort

        override suspend fun closePortForward(tunnelId: String) = Unit

        override suspend fun disconnect(serverId: String) = Unit

        override suspend fun disconnectIdle(maxIdleMs: Long) = Unit
    }

    private class Harness(
        val repository: GlassrackRepository,
        val controller: HomelabController,
        val vault: SecureVault,
        val ssh: ScriptedSshClient,
        val viewModel: UpdatesViewModel,
        val store: ViewModelStore,
        val dataStoreScope: CoroutineScope,
    )

    private suspend fun TestScope.withHarness(servers: List<Server> = listOf(piNode), block: suspend (Harness) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queryContext = coroutineContext.minusKey(Job)
        val prefsPath = File(System.getProperty("java.io.tmpdir"))
            .resolve("grack-updates-test-${UUID.randomUUID()}.preferences_pb")
            .absolutePath
            .toPath()
        val vault = SecureVault(context)
        val db = Room.inMemoryDatabaseBuilder(context, GlassrackDatabase::class.java)
            .setQueryCoroutineContext(queryContext)
            .build()
        val dataStoreScope = CoroutineScope(queryContext + SupervisorJob())
        val repository = GlassrackRepository(
            db = db,
            vault = vault,
            preferencesStore = PreferencesStore(
                PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope, produceFile = { prefsPath }),
            ),
        )
        val ssh = ScriptedSshClient()
        val controller = HomelabController(repository, ssh, coroutineContext + SupervisorJob())
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    UpdatesViewModel(repository = repository, controller = controller, vault = vault) as T
            },
        )[UpdatesViewModel::class.java]
        // Subscribe so the combines run and uiState.value carries fresh state.
        val collector = launch { viewModel.uiState.collect { } }
        launch { servers.forEach { repository.upsertServer(it) } }
        testScheduler.runCurrent()
        val harness = Harness(repository, controller, vault, ssh, viewModel, store, dataStoreScope)
        try {
            block(harness)
        } finally {
            collector.cancel()
            controller.stop()
            store.clear()
            dataStoreScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun Harness.state() = viewModel.uiState.value

    /** Runs the scheduler until [condition] holds, giving real-thread work (the device vault) time to answer. */
    private fun TestScope.awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            testScheduler.runCurrent()
            Thread.sleep(10)
        }
        testScheduler.runCurrent()
        assertTrue(condition(), "condition was not met within ${timeoutMs}ms")
    }

    /** What reached the node towards an apply. The controller's poller talks to the node too; that is not under test. */
    private fun Harness.applyCommands() = ssh.sent.map { it.first }.filter {
        it.contains("apt-get -y") || it.contains("compose pull") || it.contains("pull '")
    }

    private fun Harness.osListing(listing: String, probeExit: Int = 1) {
        ssh.handler = { _, command ->
            when {
                command == HomelabController.SUDO_PROBE_COMMAND -> SshExecResult(probeExit, "", "", 1L)
                command.contains("update -qq") -> SshExecResult(0, "", "", 1L)
                command.contains("===OSUPD===") -> SshExecResult(0, listing, "", 1L)
                else -> SshExecResult(0, "", "", 1L)
            }
        }
    }

    private suspend fun Harness.audits() = repository.auditEntries.first { it.isNotEmpty() }

    // ------------------------------------------------------------------
    // What a scan tells the user
    // ------------------------------------------------------------------

    @Test
    fun aStaleIndexIsAProblemLineNamingWhatToChange() = runTest {
        withHarness { h ->
            h.osListing(debianListing, probeExit = 1)

            h.viewModel.scanNow()
            testScheduler.runCurrent()

            val state = h.state()
            assertEquals(OsScanStatus.STALE, state.reports.getValue(piNode.id).os?.status)
            assertEquals(4, state.totalOsUpdates)
            assertTrue(state.hasUnreliableCounts)
            assertFalse(state.isScanning)
            assertEquals(
                "pi-node: $noPrivilegePath. The 4 listed come from the old index: a lower bound, not the full count",
                state.statusBanner,
            )
            // And the ledger has the scan, as FAILED with the same reason.
            val audit = h.audits().single()
            assertEquals("Scan for updates", audit.actionTitle)
            assertEquals(AuditOutcome.FAILED, audit.outcome)
            assertTrue(audit.outputSnippet.contains("STALE index"), audit.outputSnippet)
        }
    }

    @Test
    fun anUnreachableNodeIsAProblemLineNotASilentZero() = runTest {
        withHarness { h ->
            h.ssh.handler = { _, command ->
                if (command.contains("update -qq") || command.contains("===OSUPD===")) throw IOException("connection dropped")
                SshExecResult(1, "", "", 1L)
            }

            h.viewModel.scanNow()
            testScheduler.runCurrent()

            val state = h.state()
            assertEquals(OsScanStatus.FAILED, state.reports.getValue(piNode.id).os?.status)
            assertEquals("pi-node: OS scan failed (connection dropped)", state.statusBanner)
            assertTrue(state.hasUnreliableCounts)
            assertFalse(state.hasAnyUpdates)
        }
    }

    @Test
    fun anUnansweredRegistryIsNotReportedAsUpToDate() = runTest {
        val dockerNode = piNode.copy(
            id = "srv-docker",
            name = "docker-node",
            capabilities = probedCapabilities(hasDocker = true),
        )
        withHarness(listOf(dockerNode)) { h ->
            h.osListing(nothingListed, probeExit = 0)
            val os = h.ssh.handler
            // SYNTHETIC container half: a fake nginx row and a refused registry probe — the
            // verdict logic under test never compares these against a real registry answer.
            h.ssh.handler = { server, command ->
                when {
                    command.contains("===PS===") -> SshExecResult(0, "===PS===\nabc|web|nginx:1.27|running|Up 2 hours|||||\n", "", 1L)
                    command.contains("===IMAGES===") -> SshExecResult(0, "===IMAGES===\nsha256:1111|nginx|1.27|sha256:local|187MB|2 weeks ago\n", "", 1L)
                    command.contains("===BUILDX===") -> SshExecResult(0, "===BUILDX===\n__BUILDX__=ok\n__PROBE__|1||nginx:1.27|ERROR: boom\n===DRIVER===\noverlay2\n", "", 1L)
                    command.contains("===MANIFESTV===") -> SshExecResult(0, "===MANIFESTV===\n__MANIFEST_BEGIN__|nginx:1.27\n__MANIFEST_EXIT__=1\n__MANIFEST_END__|nginx:1.27\n", "", 1L)
                    else -> os(server, command)
                }
            }

            h.viewModel.scanNow()
            testScheduler.runCurrent()

            val report = h.state().reports.getValue(dockerNode.id)
            assertEquals(ContainerUpdateStatus.UNKNOWN, report.containers.single().status)
            assertTrue(h.state().hasUnreliableCounts)
            assertEquals("docker-node: no updates found — 1 of 1 registry lookups failed: ERROR: boom", h.state().statusBanner)
        }
    }

    @Test
    fun aCleanScanListsEachNodeWithItsCounts() = runTest {
        val mintNode = piNode.copy(id = "srv-mint", name = "mint-node")
        withHarness(listOf(piNode, mintNode)) { h ->
            h.osListing(nothingListed, probeExit = 0)
            val os = h.ssh.handler
            h.ssh.handler = { server, command ->
                if (command.contains("===OSUPD===")) {
                    SshExecResult(0, if (server.id == piNode.id) debianListing else nothingListed, "", 1L)
                } else {
                    os(server, command)
                }
            }

            h.viewModel.scanNow()
            testScheduler.runCurrent()

            val state = h.state()
            // One line per node, in the order the card lists them — whatever node answered first.
            assertEquals(
                state.servers.joinToString("\n") { server ->
                    "${server.name}: " + if (server.id == piNode.id) "4 OS" else "up to date"
                },
                state.statusBanner,
            )
            assertFalse(state.hasUnreliableCounts)
            assertEquals(4, state.totalOsUpdates)
        }
    }

    @Test
    fun theOsHalfIsVisibleBeforeTheRegistryPassEnds() = runTest {
        val dockerNode = piNode.copy(
            id = "srv-docker",
            name = "docker-node",
            capabilities = probedCapabilities(hasDocker = true),
        )
        withHarness(listOf(dockerNode)) { h ->
            h.osListing(debianListing, probeExit = 0)
            val registryGate = CompletableDeferred<Unit>()
            val os = h.ssh.handler
            // SYNTHETIC container half (see [anUnansweredRegistryIsNotReportedAsUpToDate]).
            h.ssh.handler = { server, command ->
                when {
                    command.contains("===PS===") -> SshExecResult(0, "===PS===\nabc|web|nginx:1.27|running|Up 2 hours|||||\n", "", 1L)
                    command.contains("===IMAGES===") -> SshExecResult(0, "===IMAGES===\nsha256:1111|nginx|1.27|sha256:local|187MB|2 weeks ago\n", "", 1L)
                    // A slow registry: the batch only answers once the test lets it.
                    command.contains("===BUILDX===") -> {
                        registryGate.await()
                        SshExecResult(0, "===BUILDX===\n__BUILDX__=ok\n__PROBE__|0|sha256:local|nginx:1.27|\n===DRIVER===\noverlay2\n", "", 1L)
                    }
                    else -> os(server, command)
                }
            }

            h.viewModel.scanNow()
            testScheduler.runCurrent()

            // The registry has not answered, the wave is still running, and the packages are already on screen.
            val early = h.state()
            assertTrue(early.isScanning)
            assertEquals(setOf(dockerNode.id), early.busyServerIds)
            assertEquals(4, early.reports.getValue(dockerNode.id).osUpdateCount)
            assertNull(early.statusBanner)

            registryGate.complete(Unit)
            testScheduler.runCurrent()

            val done = h.state()
            assertFalse(done.isScanning)
            assertEquals(ContainerUpdateStatus.UP_TO_DATE, done.reports.getValue(dockerNode.id).containers.single().status)
            assertEquals("docker-node: 4 OS", done.statusBanner)
            // Persisted too, so the card shows the packages after a restart.
            assertEquals(4, NodeUpdateReport.fromJson(h.repository.getServerById(dockerNode.id)?.updateReportJson)?.osUpdateCount)
        }
    }

    @Test
    fun scanningWithNoNodesEndsTheScanAndSaysSo() = runTest {
        withHarness(servers = emptyList()) { h ->
            h.viewModel.scanNow()
            testScheduler.runCurrent()

            assertFalse(h.state().isScanning)
            assertEquals("No nodes to scan yet", h.state().statusBanner)
            assertTrue(h.ssh.sent.isEmpty())
        }
    }

    @Test
    fun aScanOutlivesTheViewModelThatAskedForIt() = runTest {
        withHarness { h ->
            h.osListing(nothingListed, probeExit = 0)
            val listingGate = CompletableDeferred<Unit>()
            val os = h.ssh.handler
            h.ssh.handler = { node, command ->
                when {
                    command.contains("===OSUPD===") -> {
                        listingGate.await()
                        os(node, command)
                    }
                    else -> os(node, command)
                }
            }

            h.viewModel.scanNow()
            testScheduler.runCurrent()
            assertTrue(h.state().isScanning)

            // The user navigates away: the Overview back-stack entry is popped, the VM cleared.
            h.store.clear()
            testScheduler.runCurrent()

            listingGate.complete(Unit)
            testScheduler.runCurrent()

            // The scan finished in the controller's own scope: the report persisted…
            var persistedReport: NodeUpdateReport? = null
            val rows = launch {
                h.repository.servers.collect { list ->
                    persistedReport = list.firstOrNull { it.id == piNode.id }
                        ?.let { NodeUpdateReport.fromJson(it.updateReportJson) }
                }
            }
            awaitUntil { persistedReport?.os?.status == OsScanStatus.OK }
            // …and the ledger got its entry.
            var scanAudit: AuditEntry? = null
            val auditRows = launch {
                h.repository.auditEntries.collect { list -> scanAudit = list.firstOrNull() }
            }
            awaitUntil { scanAudit != null }
            rows.cancel()
            auditRows.cancel()
            assertEquals("Scan for updates", scanAudit?.actionTitle)
            assertEquals(AuditOutcome.SUCCESS, scanAudit?.outcome)
        }
    }

    // ------------------------------------------------------------------
    // Applies: banner and refusals
    // ------------------------------------------------------------------

    /** REAL: the post-apply reboot probe on Debian 12, without and with `/var/run/reboot-required` (needs-restarting absent). */
    private val rebootNotRequired = "===REBOOTCHK===\nREBOOT_REQUIRED=0\n__NR_EXIT__=skip"
    private val rebootRequired = "===REBOOTCHK===\nREBOOT_REQUIRED=1\n__NR_EXIT__=skip"

    /** Applies then run without a confirmation dialog; the preference is read when the apply is requested. */
    private suspend fun TestScope.disableConfirmations(h: Harness) {
        h.repository.updatePreferences { it.copy(destructiveConfirmation = ConfirmationLevel.NONE) }
        testScheduler.runCurrent()
    }

    @Test
    fun aRejectedSudoPasswordIsExplainedInTheBanner() = runTest {
        val server = piNode.copy(sudoAuth = SudoAuth.SEPARATE_PASSWORD)
        withHarness(listOf(server)) { h ->
            h.vault.putSecret(sudoSecretVaultId(server.id), "wrong-password")
            disableConfirmations(h)
            h.osListing(nothingListed, probeExit = 1)
            val os = h.ssh.handler
            h.ssh.handler = { node, command ->
                // REAL: what sudo printed on a Mint box after the wrong password.
                if (command.contains("apt-get -y")) SshExecResult(1, "", sudoRejectedPassword, 900L) else os(node, command)
            }

            h.viewModel.requestOsApply(server)
            // The stored sudo password is read through the device vault, which does its work on a
            // real IO thread; the test scheduler only sees the apply once that has answered.
            awaitUntil { h.state().statusBanner != null }

            assertEquals(
                "OS updates on pi-node failed: sudo rejected the stored sudo password for 'pi-user'. sudo asks for the " +
                    "SSH login user's password, not root's — correct it under Edit node → Privilege Escalation, or allow " +
                    "passwordless sudo on the node.",
                h.state().statusBanner,
            )
            // The apply itself is in the ledger as FAILED — never a bare "exit 1" for the user, though.
            val audit = h.audits().single { it.actionTitle == "Apply OS updates" }
            assertEquals(AuditOutcome.FAILED, audit.outcome)
            assertFalse(h.state().busyServerIds.contains(server.id))
        }
    }

    @Test
    fun aSuccessfulApplyKeepsItsBannerThroughTheRescan() = runTest {
        withHarness { h ->
            disableConfirmations(h)
            h.osListing(nothingListed, probeExit = 0)
            val os = h.ssh.handler
            h.ssh.handler = { node, command ->
                when {
                    // SYNTHETIC: apply stdout — the banner logic under test does not parse apt's output.
                    command.contains("apt-get -y") -> SshExecResult(0, "upgraded", "", 900L)
                    command.contains("===REBOOTCHK===") -> SshExecResult(0, rebootNotRequired, "", 1L)
                    else -> os(node, command)
                }
            }

            h.viewModel.requestOsApply(piNode)
            testScheduler.runCurrent()

            // The rescan that follows would otherwise overwrite the apply's verdict with its own line.
            assertEquals("OS updates on pi-node: completed", h.state().statusBanner)
            assertFalse(h.state().rebootRecommended[piNode.id] == true)
            assertEquals(1, h.audits().count { it.actionTitle == "Apply OS updates" && it.outcome == AuditOutcome.SUCCESS })
            assertEquals(1, h.audits().count { it.actionTitle == "Scan for updates" })
        }
    }

    @Test
    fun aRescanThatCannotRefreshTheIndexAppendsItsWarningToTheApplyBanner() = runTest {
        withHarness { h ->
            disableConfirmations(h)
            h.osListing(nothingListed, probeExit = 0)
            val os = h.ssh.handler
            h.ssh.handler = { node, command ->
                when {
                    // SYNTHETIC: apply stdout (see [aSuccessfulApplyKeepsItsBannerThroughTheRescan]).
                    command.contains("apt-get -y") -> SshExecResult(0, "upgraded", "", 900L)
                    command.contains("===REBOOTCHK===") -> SshExecResult(0, rebootRequired, "", 1L)
                    // After the apply the passwordless right is gone, so the rescan runs on a stale index.
                    command == HomelabController.SUDO_PROBE_COMMAND -> SshExecResult(1, "", "", 1L)
                    else -> os(node, command)
                }
            }

            h.viewModel.requestOsApply(piNode)
            testScheduler.runCurrent()

            assertEquals(
                "OS updates on pi-node: completed · reboot recommended\npi-node: $noPrivilegePath. " +
                    "Nothing is listed, but that only reflects the old index — the node may still have updates",
                h.state().statusBanner,
            )
            assertEquals(true, h.state().rebootRecommended[piNode.id])
            assertEquals(OsScanStatus.STALE, h.state().reports.getValue(piNode.id).os?.status)
        }
    }

    @Test
    fun safeReadOnlyModeRefusalsAreAuditedAsBlocked() = runTest {
        val server = piNode.copy(
            id = "srv-docker",
            name = "docker-node",
            capabilities = probedCapabilities(hasDocker = true),
        )
        withHarness(listOf(server)) { h ->
            h.repository.updatePreferences { it.copy(safeReadOnlyMode = true) }
            testScheduler.runCurrent()

            h.viewModel.requestOsApply(server)
            h.viewModel.requestStackUpdate(server, ComposeStack(projectName = "webstack", workingDir = "/opt/webstack"))
            h.viewModel.requestPull(
                server,
                ContainerInfo(id = "abc", shortId = "abc", name = "web", image = "nginx:1.27"),
            )
            testScheduler.runCurrent()

            assertEquals("Blocked: Safe Read-Only Mode is active", h.state().statusBanner)
            assertTrue(h.applyCommands().isEmpty(), "no apply may reach the node in read-only mode")

            val audits = h.audits()
            assertEquals(3, audits.size, audits.toString())
            fun blocked(title: String) = audits.single { it.actionTitle == title }
            assertEquals(AuditOutcome.BLOCKED, blocked("Apply OS updates").outcome)
            assertEquals(AuditCategory.SYSTEM, blocked("Apply OS updates").category)
            assertEquals(
                "DEBIAN_FRONTEND=noninteractive apt-get -y -o DPkg::Lock::Timeout=600 " +
                    "-o Dpkg::Options::=\"--force-confdef\" -o Dpkg::Options::=\"--force-confold\" upgrade",
                blocked("Apply OS updates").commandSanitized,
            )
            assertEquals(AuditOutcome.BLOCKED, blocked("Compose update: webstack").outcome)
            assertEquals(AuditCategory.CONTAINER, blocked("Compose update: webstack").category)
            assertEquals("docker compose pull webstack && docker compose up -d webstack", blocked("Compose update: webstack").commandSanitized)
            assertEquals(AuditOutcome.BLOCKED, blocked("Container pull: web").outcome)
            assertEquals("docker pull 'nginx:1.27'", blocked("Container pull: web").commandSanitized)
            assertTrue(audits.all { it.outputSnippet == "Blocked: Safe Read-Only Mode is active" })
        }
    }

    @Test
    fun dismissingAConfirmationIsAuditedAsBlockedAndNothingRuns() = runTest {
        val server = piNode.copy(
            id = "srv-docker",
            name = "docker-node",
            capabilities = probedCapabilities(hasDocker = true),
        )
        withHarness(listOf(server)) { h ->
            h.viewModel.requestOsApply(server)
            h.viewModel.requestStackUpdate(server, ComposeStack(projectName = "webstack", workingDir = null))
            h.viewModel.requestPull(server, ContainerInfo(id = "abc", shortId = "abc", name = "web", image = "nginx:1.27"))
            testScheduler.runCurrent()

            assertNotNull(h.state().pendingOsApply)
            assertNotNull(h.state().pendingStackUpdate)
            assertNotNull(h.state().pendingPull)
            assertTrue(h.applyCommands().isEmpty(), "a pending confirmation runs nothing")

            h.viewModel.dismissOsApply()
            h.viewModel.dismissStackUpdate()
            h.viewModel.dismissPull()
            testScheduler.runCurrent()

            assertNull(h.state().pendingOsApply)
            assertNull(h.state().pendingStackUpdate)
            assertNull(h.state().pendingPull)
            assertTrue(h.applyCommands().isEmpty(), "a dismissed confirmation runs nothing")
            val audits = h.audits()
            assertEquals(
                listOf("Apply OS updates", "Compose update: webstack", "Container pull: web").toSet(),
                audits.map { it.actionTitle }.toSet(),
            )
            assertTrue(audits.all { it.outcome == AuditOutcome.BLOCKED && it.outputSnippet == "Dismissed the confirmation" }, audits.toString())
        }
    }

    @Test
    fun confirmingRunsTheApply() = runTest {
        withHarness { h ->
            h.osListing(nothingListed, probeExit = 0)
            val os = h.ssh.handler
            h.ssh.handler = { node, command ->
                when {
                    // SYNTHETIC: apply stdout (see [aSuccessfulApplyKeepsItsBannerThroughTheRescan]).
                    command.contains("apt-get -y") -> SshExecResult(0, "upgraded", "", 900L)
                    command.contains("===REBOOTCHK===") -> SshExecResult(0, rebootNotRequired, "", 1L)
                    else -> os(node, command)
                }
            }

            h.viewModel.requestOsApply(piNode)
            testScheduler.runCurrent()
            assertNotNull(h.state().pendingOsApply)
            assertTrue(h.applyCommands().isEmpty())

            h.viewModel.confirmOsApply()
            testScheduler.runCurrent()

            assertNull(h.state().pendingOsApply)
            assertTrue(h.ssh.sent.any { it.first.contains("sudo -n bash -c 'DEBIAN_FRONTEND=noninteractive apt-get -y") })
            assertTrue(h.audits().none { it.outcome == AuditOutcome.BLOCKED })
            assertEquals("OS updates on pi-node: completed", h.state().statusBanner)
        }
    }

    @Test
    fun anApplyOutlivesTheViewModelThatAskedForIt() = runTest {
        withHarness { h ->
            disableConfirmations(h)
            h.osListing(nothingListed, probeExit = 0)
            val applyGate = CompletableDeferred<Unit>()
            val os = h.ssh.handler
            h.ssh.handler = { node, command ->
                when {
                    // SYNTHETIC: apply stdout (see [aSuccessfulApplyKeepsItsBannerThroughTheRescan]).
                    command.contains("apt-get -y") -> {
                        applyGate.await()
                        SshExecResult(0, "upgraded", "", 900L)
                    }
                    command.contains("===REBOOTCHK===") -> SshExecResult(0, rebootNotRequired, "", 1L)
                    else -> os(node, command)
                }
            }

            h.viewModel.requestOsApply(piNode)
            testScheduler.runCurrent()
            assertTrue(h.applyCommands().isNotEmpty())

            // The user navigates away mid-upgrade — exactly when cancelling the apply would
            // leave the node half-upgraded.
            h.store.clear()
            testScheduler.runCurrent()

            applyGate.complete(Unit)
            testScheduler.runCurrent()

            // The upgrade finished and its ledger entry was written by the controller itself.
            var applyAudit: AuditEntry? = null
            val auditRows = launch {
                h.repository.auditEntries.collect { list ->
                    applyAudit = list.firstOrNull { it.actionTitle == "Apply OS updates" }
                }
            }
            awaitUntil { applyAudit?.outcome == AuditOutcome.SUCCESS }
            auditRows.cancel()
        }
    }

    // ------------------------------------------------------------------
    // The banner's pure parts
    // ------------------------------------------------------------------

    @Test
    fun problemLinesComeFirstAndKeepTheirOwnOrder() {
        val notes = listOf(
            ScanNote("a: up to date", isProblem = false),
            ScanNote("b: OS scan failed", isProblem = true),
            ScanNote("c: 2 OS", isProblem = false),
            ScanNote("d: stale index", isProblem = true),
        )

        assertEquals("b: OS scan failed\nd: stale index\na: up to date\nc: 2 OS", composeScanBanner(notes))
        assertNull(composeScanBanner(emptyList()))
    }

    @Test
    fun scanNotesReadEveryStatus() {
        fun report(os: OsUpdateSection?, containers: List<com.raulshma.grack.core.model.ContainerUpdateEntry> = emptyList(), detail: String = "") =
            NodeUpdateReport(scannedAtEpochMs = 1L, os = os, containers = containers, containerScanDetail = detail)
        val ok = OsUpdateSection(DistroFamily.DEBIAN, OsScanStatus.OK)
        fun entry(status: ContainerUpdateStatus) =
            com.raulshma.grack.core.model.ContainerUpdateEntry(containerName = "web", image = "nginx", status = status)

        assertEquals("n: up to date", scanNote(piNode.copy(name = "n"), report(ok)).text)
        assertFalse(scanNote(piNode.copy(name = "n"), report(ok)).isProblem)
        assertEquals(
            "n: 3 OS · 1 container update",
            scanNote(piNode.copy(name = "n"), report(ok.copy(packages = List(3) { OsPackageUpdate("p") }), listOf(entry(ContainerUpdateStatus.UPDATE_AVAILABLE)))).text,
        )
        assertEquals(
            "n: 2 container updates — 1 container undetermined",
            scanNote(piNode.copy(name = "n"), report(ok, listOf(entry(ContainerUpdateStatus.UPDATE_AVAILABLE), entry(ContainerUpdateStatus.UPDATE_AVAILABLE), entry(ContainerUpdateStatus.UNKNOWN)))).text,
        )
        val undetermined = scanNote(piNode.copy(name = "n"), report(ok, listOf(entry(ContainerUpdateStatus.UNKNOWN))))
        assertEquals("n: no updates found — 1 container undetermined", undetermined.text)
        assertTrue(undetermined.isProblem)
        val incomplete = scanNote(piNode.copy(name = "n"), report(ok, listOf(entry(ContainerUpdateStatus.PINNED)), detail = "registry probes timed out after 45s"))
        assertEquals("n: no updates found — registry probes timed out after 45s", incomplete.text)
        assertTrue(incomplete.isProblem)
        assertTrue(scanNote(piNode.copy(name = "n"), report(null)).isProblem)
        assertEquals("n: OS updates unsupported (Check tool missing (install pacman-contrib)) · 2 OS", scanNote(piNode.copy(name = "n"), report(OsUpdateSection(DistroFamily.ARCH, OsScanStatus.UNSUPPORTED, detail = "Check tool missing (install pacman-contrib)", packages = List(2) { OsPackageUpdate("p") }))).text)
        assertFalse(scanNote(piNode.copy(name = "n"), report(OsUpdateSection(DistroFamily.ARCH, OsScanStatus.UNSUPPORTED))).isProblem)
    }

    @Test
    fun unreliableCountsCoverTheOsAndTheContainerHalf() {
        fun ui(report: NodeUpdateReport) = UpdatesUiState(reports = mapOf("s" to report))
        val ok = OsUpdateSection(DistroFamily.DEBIAN, OsScanStatus.OK)
        val unknown = com.raulshma.grack.core.model.ContainerUpdateEntry(containerName = "web", image = "nginx", status = ContainerUpdateStatus.UNKNOWN)

        assertFalse(ui(NodeUpdateReport(1L, os = ok)).hasUnreliableCounts)
        assertTrue(ui(NodeUpdateReport(1L, os = ok.copy(status = OsScanStatus.STALE))).hasUnreliableCounts)
        assertTrue(ui(NodeUpdateReport(1L, os = ok.copy(status = OsScanStatus.FAILED))).hasUnreliableCounts)
        assertFalse(ui(NodeUpdateReport(1L, os = ok.copy(status = OsScanStatus.UNSUPPORTED))).hasUnreliableCounts)
        assertTrue(ui(NodeUpdateReport(1L, os = ok, containers = listOf(unknown))).hasUnreliableCounts)
        assertEquals(1, NodeUpdateReport(1L, os = ok, containers = listOf(unknown)).undeterminedContainerCount)
    }
}
