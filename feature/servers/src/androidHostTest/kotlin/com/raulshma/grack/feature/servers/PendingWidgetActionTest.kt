package com.raulshma.grack.feature.servers

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.database.GlassrackDatabase
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.database.PreferencesStore
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.ContainerAction
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.ContainerRuntime
import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.TriggerSource
import com.raulshma.grack.core.security.BiometricGate
import com.raulshma.grack.core.security.SecureVault
import com.raulshma.grack.core.ssh.ContainerStateCache
import com.raulshma.grack.core.ssh.FileTransferProgress
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.core.ssh.SshExecResult
import com.raulshma.grack.core.ssh.SshSessionClient
import com.raulshma.grack.core.ssh.SshShellSession
import com.raulshma.grack.core.ssh.TransferSink
import com.raulshma.grack.core.ssh.TransferSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 8: destructive widget taps no longer audit BLOCKED+silence — they deep-link into
 * the node detail with a staged [WidgetPendingContainerAction]. This exercises the flow
 * logic: staging, confirm executing the audited container action from the in-app side,
 * dismiss recording BLOCKED, the Safe Read-Only Mode block, and stale-stage cleanup.
 * Hermetic: scriptable transport fake, in-memory Room, in-memory container cache, all on
 * the test scheduler.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class PendingWidgetActionTest {

    private val server = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5")

    /** Records only what the flow under test needs; everything else is a stub. */
    private class StubSshClient : SshSessionClient {
        override suspend fun execute(
            server: Server,
            allServers: List<Server>,
            command: String,
            timeoutMs: Long,
            stdinText: String?,
        ): SshExecResult = SshExecResult(0, "ok", "", 1L)

        override suspend fun executeStreaming(
            server: Server,
            allServers: List<Server>,
            command: String,
            timeoutMs: Long?,
            stdinText: String?,
            onChunk: suspend (String) -> Unit,
        ): SshExecResult = SshExecResult(0, "ok", "", 1L)

        override suspend fun deployPublicKey(server: Server, allServers: List<Server>, publicKeyOpenSsh: String): SshExecResult =
            SshExecResult(0, "", "", 0L)

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

    private class MemoryContainerCache : ContainerStateCache {
        val rows = mutableMapOf<String, List<ContainerInfo>>()
        override suspend fun recordContainers(serverId: String, containers: List<ContainerInfo>, fetchedAtEpochMs: Long) {
            rows[serverId] = containers
        }

        override suspend fun containersFor(serverId: String): List<ContainerInfo> = rows[serverId].orEmpty()
        override suspend fun allEntries(): List<com.raulshma.grack.core.model.WidgetCachedContainers> = emptyList()
    }

    private data class Harness(
        val repository: GlassrackRepository,
        val controller: HomelabController,
        val cache: MemoryContainerCache,
        val viewModel: ServersViewModel,
        val store: ViewModelStore,
        val dataStoreScope: CoroutineScope,
    )

    private inline fun withHarness(scope: TestScope, block: (Harness) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(scope.testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queryContext = scope.coroutineContext.minusKey(kotlinx.coroutines.Job)
        val prefsPath = File(System.getProperty("java.io.tmpdir"))
            .resolve("grack-widget-test-${UUID.randomUUID()}.preferences_pb")
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
                PreferenceDataStoreFactory.createWithPath(
                    scope = dataStoreScope,
                    produceFile = { prefsPath },
                ),
            ),
        )
        val cache = MemoryContainerCache()
        val controller = HomelabController(repository, StubSshClient(), scope.coroutineContext + SupervisorJob())
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ServersViewModel(
                        repository = repository,
                        controller = controller,
                        vault = vault,
                        biometricGate = BiometricGate(context),
                        transferStreams = null,
                        ioDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
                        containerStateCache = cache,
                    ) as T
            },
        )[ServersViewModel::class.java]
        // Subscribe so the staged combines run and uiState.value carries fresh state.
        val collector = scope.launch { viewModel.uiState.collect { } }
        scope.launch { repository.upsertServer(server) }
        scope.testScheduler.runCurrent()
        val harness = Harness(repository, controller, cache, viewModel, store, dataStoreScope)
        try {
            block(harness)
        } finally {
            collector.cancel()
            harness.controller.stop()
            store.clear()
            dataStoreScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun Harness.pending() = viewModel.uiState.value.pendingWidgetContainerAction

    private suspend fun Harness.newestAudit() = repository.auditEntries.first { it.isNotEmpty() }.first()

    @Test
    fun stagingHoldsTheActionUntilTheDialogResolves() = runTest {
        withHarness(this) { h ->
            h.viewModel.stagePendingWidgetAction(server.id, "web", ContainerAction.STOP.name)
            testScheduler.runCurrent()

            val pending = h.pending()
            assertNotNull(pending)
            assertEquals(server.id, pending.serverId)
            assertEquals("web", pending.containerName)
            assertEquals(ContainerAction.STOP, pending.action)
        }
    }

    @Test
    fun stagingAnUnknownActionNameIsIgnored() = runTest {
        withHarness(this) { h ->
            h.viewModel.stagePendingWidgetAction(server.id, "web", "NOT_AN_ACTION")
            testScheduler.runCurrent()
            assertNull(h.pending())
        }
    }

    @Test
    fun confirmExecutesFromTheInAppSideWithWidgetTriggerSource() = runTest {
        withHarness(this) { h ->
            h.cache.rows[server.id] = listOf(
                ContainerInfo(id = "abc", shortId = "abc", name = "web", image = "nginx:1.27", runtime = ContainerRuntime.DOCKER),
            )
            h.viewModel.stagePendingWidgetAction(server.id, "web", ContainerAction.STOP.name)
            testScheduler.runCurrent()

            h.viewModel.confirmPendingWidgetAction(server)
            testScheduler.runCurrent()

            // The staged action is consumed and the audited execution carries WIDGET.
            assertNull(h.pending())
            val audit = h.newestAudit()
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals("Container Stop: web", audit.actionTitle)
            assertEquals(TriggerSource.WIDGET, audit.triggerSource)
            assertTrue(audit.commandSanitized.contains("docker stop web"), audit.commandSanitized)
        }
    }

    @Test
    fun dismissAuditsBlockedAndExecutesNothing() = runTest {
        withHarness(this) { h ->
            h.viewModel.stagePendingWidgetAction(server.id, "web", ContainerAction.RESTART.name)
            testScheduler.runCurrent()

            h.viewModel.dismissPendingWidgetAction(server)
            testScheduler.runCurrent()

            assertNull(h.pending())
            val audit = h.newestAudit()
            assertEquals(AuditOutcome.BLOCKED, audit.outcome)
            assertEquals(TriggerSource.WIDGET, audit.triggerSource)
            assertEquals("Dismissed the in-app confirmation", audit.outputSnippet)
        }
    }

    @Test
    fun confirmUnderSafeReadOnlyModeRecordsBlockedWithoutExecuting() = runTest {
        withHarness(this) { h ->
            h.repository.updatePreferences { it.copy(safeReadOnlyMode = true) }
            h.cache.rows[server.id] = listOf(
                ContainerInfo(id = "abc", shortId = "abc", name = "web", image = "nginx:1.27"),
            )
            h.viewModel.stagePendingWidgetAction(server.id, "web", ContainerAction.STOP.name)
            testScheduler.runCurrent()

            h.viewModel.confirmPendingWidgetAction(server)
            testScheduler.runCurrent()

            assertNull(h.pending())
            val audit = h.newestAudit()
            assertEquals(AuditOutcome.BLOCKED, audit.outcome)
            assertTrue(audit.outputSnippet.contains("Safe Read-Only Mode"), audit.outputSnippet)
            // The status banner tells the user why nothing happened.
            assertTrue(h.viewModel.netFilesState.value.statusMessage == null)
            assertTrue(
                h.viewModel.uiState.value.testStatusMessage?.contains("Safe Read-Only Mode") == true,
                h.viewModel.uiState.value.testStatusMessage ?: "",
            )
        }
    }

    @Test
    fun confirmWithVanishedCacheEntryConsumesAndExplainsWithoutExecuting() = runTest {
        withHarness(this) { h ->
            h.viewModel.stagePendingWidgetAction(server.id, "ghost", ContainerAction.STOP.name)
            testScheduler.runCurrent()

            h.viewModel.confirmPendingWidgetAction(server)
            testScheduler.runCurrent()

            assertNull(h.pending())
            assertTrue(
                h.viewModel.uiState.value.testStatusMessage?.contains("no longer in the cached state") == true,
                h.viewModel.uiState.value.testStatusMessage ?: "",
            )
        }
    }

    @Test
    fun stalePendingActionForADeletedNodeIsDroppedQuietly() = runTest {
        withHarness(this) { h ->
            h.viewModel.stagePendingWidgetAction("srv-deleted", "web", ContainerAction.STOP.name)
            testScheduler.runCurrent()
            assertNotNull(h.pending())

            // A staged-set without the pending node drops it; one containing it keeps it.
            h.viewModel.clearStalePendingWidgetAction(setOf(server.id))
            testScheduler.runCurrent()
            assertNull(h.pending())

            h.viewModel.stagePendingWidgetAction(server.id, "web", ContainerAction.STOP.name)
            testScheduler.runCurrent()
            h.viewModel.clearStalePendingWidgetAction(setOf(server.id))
            testScheduler.runCurrent()
            assertNotNull(h.pending())
        }
    }

    @Test
    fun mismatchedServerConfirmAndDismissAreNoOps() = runTest {
        withHarness(this) { h ->
            h.viewModel.stagePendingWidgetAction(server.id, "web", ContainerAction.STOP.name)
            testScheduler.runCurrent()
            val other = Server(id = "srv-other", name = "node-b", primaryHost = "10.0.0.6")

            h.viewModel.confirmPendingWidgetAction(other)
            h.viewModel.dismissPendingWidgetAction(other)
            testScheduler.runCurrent()

            // Still staged for the right node; no audit was written.
            assertNotNull(h.pending())
            assertTrue(h.repository.auditEntries.first().isEmpty())
        }
    }
}
