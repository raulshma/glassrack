package com.raulshma.grack.feature.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.database.GlassrackDatabase
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.database.PreferencesStore
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.security.NotificationGate
import com.raulshma.grack.core.security.SecureVault
import com.raulshma.grack.core.ssh.FileTransferProgress
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.core.ssh.SshExecResult
import com.raulshma.grack.core.ssh.SshSessionClient
import com.raulshma.grack.core.ssh.SshShellSession
import com.raulshma.grack.core.ssh.TransferSink
import com.raulshma.grack.core.ssh.TransferSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 8 known-hosts manager, Settings side: "Forget" clears the node's saved
 * fingerprint (so the next connect re-triggers the TOFU challenge) and lands a
 * security-relevant CONFIG audit entry. Hermetic: in-memory Room + real controller over a
 * stub transport, all on the test scheduler.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class SettingsKnownHostsTest {

    private val server = Server(
        id = "srv-1",
        name = "node-a",
        primaryHost = "10.0.0.5",
        hostKeyFingerprint = "SHA256:trusted",
    )

    private data class Harness(
        val repository: GlassrackRepository,
        val controller: HomelabController,
        val viewModel: SettingsViewModel,
        val store: ViewModelStore,
        val dataStoreScope: CoroutineScope,
    )

    private inline fun withHarness(scope: TestScope, block: (Harness) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(scope.testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queryContext = scope.coroutineContext.minusKey(kotlinx.coroutines.Job)
        val prefsPath = File(System.getProperty("java.io.tmpdir"))
            .resolve("grack-knownhosts-test-${UUID.randomUUID()}.preferences_pb")
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
        val controller = HomelabController(repository, StubSshClient(), scope.coroutineContext + SupervisorJob())
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(
                        repository = repository,
                        controller = controller,
                        notificationGate = NotificationGate(context),
                    ) as T
            },
        )[SettingsViewModel::class.java]
        val collector = scope.launch { viewModel.uiState.collect { } }
        scope.launch { repository.upsertServer(server) }
        scope.testScheduler.runCurrent()
        val harness = Harness(repository, controller, viewModel, store, dataStoreScope)
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

    /** Nothing in this suite performs SSH; the stub keeps the controller hermetic. */
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

    @Test
    fun forgetClearsTheFingerprintAndAuditsConfig() = runTest {
        withHarness(this) { h ->
            assertEquals("SHA256:trusted", h.repository.getServerById(server.id)!!.hostKeyFingerprint)

            h.viewModel.forgetHostKey(h.repository.getServerById(server.id)!!)
            testScheduler.runCurrent()

            // The stored fingerprint is gone: the next connect re-runs the TOFU check.
            assertNull(h.repository.getServerById(server.id)!!.hostKeyFingerprint)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.first()
            assertEquals(AuditCategory.CONFIG, audit.category)
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals("Forgot Host Key: node-a", audit.actionTitle)
            assertTrue(audit.commandSanitized.contains("forget-host-key 10.0.0.5"), audit.commandSanitized)
            // The Settings banner confirms the reset.
            assertTrue(
                h.viewModel.uiState.value.statusMessage?.contains("verifies it again") == true,
                h.viewModel.uiState.value.statusMessage ?: "",
            )
        }
    }
}
