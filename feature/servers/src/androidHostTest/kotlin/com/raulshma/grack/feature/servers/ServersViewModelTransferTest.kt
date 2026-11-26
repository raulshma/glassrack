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
import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.security.BiometricGate
import com.raulshma.grack.core.security.SecureVault
import com.raulshma.grack.core.ssh.FileTransferProgress
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.core.ssh.SshExecResult
import com.raulshma.grack.core.ssh.SshSessionClient
import com.raulshma.grack.core.ssh.SshShellSession
import com.raulshma.grack.core.ssh.TransferSink
import com.raulshma.grack.core.ssh.TransferSource
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Path.Companion.toPath
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 6 file transfers in [ServersViewModel]: progress rows in
 * [NetworkAndFilesState.activeTransfers], the 100 MB confirmation gate, and cancel-mid-
 * transfer leaving a CANCELLED audit entry with no RUNNING job left in the state.
 * Hermetic: a scriptable [SshSessionClient] fake and in-memory Room under Robolectric;
 * everything runs on the test scheduler (Main and the injected IO dispatcher included).
 * Every test stops the controller and clears the ViewModel in `finally`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class ServersViewModelTransferTest {

    private val server = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5")

    /** Minimal scriptable transport: only the binary transfers are ever exercised here. */
    private class TransferTestSshClient : SshSessionClient {
        data class DownloadRequest(
            val remotePath: String,
            val timeoutMs: Long?,
            val destination: TransferSink,
            val onProgress: suspend (FileTransferProgress) -> Unit,
        )

        data class UploadRequest(
            val remotePath: String,
            val totalBytes: Long,
            val timeoutMs: Long?,
            val onProgress: suspend (FileTransferProgress) -> Unit,
        )

        val downloadRequests = mutableListOf<DownloadRequest>()
        val uploadRequests = mutableListOf<UploadRequest>()
        var downloadHandler: suspend (DownloadRequest) -> Long = { 0L }
        var uploadHandler: suspend (UploadRequest) -> Long = { 0L }

        override suspend fun downloadFile(
            server: Server,
            allServers: List<Server>,
            remotePath: String,
            destination: TransferSink,
            timeoutMs: Long?,
            onProgress: suspend (FileTransferProgress) -> Unit,
        ): Long {
            val request = DownloadRequest(remotePath, timeoutMs, destination, onProgress)
            downloadRequests += request
            return downloadHandler(request)
        }

        override suspend fun uploadFile(
            server: Server,
            allServers: List<Server>,
            localSource: TransferSource,
            remotePath: String,
            totalBytes: Long,
            timeoutMs: Long?,
            onProgress: suspend (FileTransferProgress) -> Unit,
        ): Long {
            val request = UploadRequest(remotePath, totalBytes, timeoutMs, onProgress)
            uploadRequests += request
            return uploadHandler(request)
        }

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

        override suspend fun uploadFileText(
            server: Server,
            allServers: List<Server>,
            remotePath: String,
            content: String,
            timeoutMs: Long,
        ) = Unit

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

    private class RecordingSink : TransferSink {
        val collected = ByteArrayOutputStream()
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            collected.write(buffer, offset, length)
        }

        override fun flush() = Unit
        override fun close() = Unit
    }

    private class RecordingSource : TransferSource {
        override fun read(buffer: ByteArray): Int = -1
        override fun close() = Unit
    }

    private class TestTransferStreams : TransferStreams {
        var pickedName = "picked.bin"
        var pickedSize = 4096L
        val sinks = mutableListOf<RecordingSink>()
        override fun displayName(file: PlatformFile): String = pickedName
        override fun sizeOf(file: PlatformFile): Long = pickedSize
        override fun openSink(destination: PlatformFile): TransferSink = RecordingSink().also { sinks += it }
        override fun openSource(source: PlatformFile): TransferSource = RecordingSource()
    }

    private data class Harness(
        val repository: GlassrackRepository,
        val controller: HomelabController,
        val fake: TransferTestSshClient,
        val streams: TestTransferStreams,
        val viewModel: ServersViewModel,
    )

    private inline fun withHarness(scope: TestScope, block: (Harness) -> Unit) {
        // viewModelScope runs on Dispatchers.Main; route it (and the injected IO dispatcher)
        // through the test scheduler so every transfer step is virtual-time deterministic.
        val mainDispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queryContext = scope.coroutineContext.minusKey(Job)
        val prefsPath = File(System.getProperty("java.io.tmpdir"))
            .resolve("grack-transfer-test-${UUID.randomUUID()}.preferences_pb")
            .absolutePath
            .toPath()
        val vault = SecureVault(context)
        val db = Room.inMemoryDatabaseBuilder(context, GlassrackDatabase::class.java)
            .setQueryCoroutineContext(queryContext)
            .build()
        // DataStore defaults to a real Dispatchers.IO scope, which would outrun the test
        // scheduler; give it the scheduler's context so preference reads are virtual too.
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
        val fake = TransferTestSshClient()
        val controller = HomelabController(repository, fake, scope.coroutineContext + SupervisorJob())
        val streams = TestTransferStreams()
        // Built through a ViewModelStore so the viewModelScope can be cancelled in `finally`
        // (ViewModel.clear() is internal; store.clear() invokes onCleared the same way the
        // real lifecycle owner does).
        val store = androidx.lifecycle.ViewModelStore()
        val viewModel = androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    ServersViewModel(
                        repository = repository,
                        controller = controller,
                        vault = vault,
                        biometricGate = BiometricGate(context),
                        transferStreams = streams,
                        ioDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
                    ) as T
            },
        )[ServersViewModel::class.java]
        val harness = Harness(repository, controller, fake, streams, viewModel)
        try {
            block(harness)
        } finally {
            // Stop controller loops and the ViewModel scope so runTest's quiescence
            // drain never spins on a parked transfer or a delay loop.
            harness.controller.stop()
            store.clear()
            dataStoreScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun entry(fullPath: String, sizeBytes: Long) = RemoteFileEntry(
        name = fullPath.substringAfterLast('/'),
        fullPath = fullPath,
        isDirectory = false,
        sizeBytes = sizeBytes,
        permissions = "-rw-r--r--",
        modifiedEpochSec = 0L,
    )

    private fun Harness.status(): String = viewModel.netFilesState.value.statusMessage.orEmpty()

    private fun Harness.activeTransfers() = viewModel.netFilesState.value.activeTransfers

    // --- Downloads ------------------------------------------------------------------------------

    @Test
    fun downloadProgressReachesTheStateAndTheRowClearsOnCompletion() = runTest {
        withHarness(this) { h ->
            val gate = CompletableDeferred<Unit>()
            h.fake.downloadHandler = { request ->
                request.onProgress(FileTransferProgress(1024, 4096))
                gate.await() // transfer mid-flight: one row is live in the state
                request.onProgress(FileTransferProgress(4096, 4096))
                4096L
            }

            h.viewModel.prepareFileDownload(server, entry("/var/data.bin", 4096L), PlatformFile(File("/tmp/data.bin")))
            testScheduler.runCurrent()

            val active = h.activeTransfers().single()
            assertEquals(FileTransferDirection.DOWNLOAD, active.direction)
            assertEquals("/var/data.bin", active.remotePath)
            assertEquals("data.bin", active.localFileName)
            assertEquals(1024L, active.bytesTransferred)
            assertEquals(0.25f, active.fraction)
            assertEquals(25, active.percent)
            assertEquals(1, h.streams.sinks.size)

            gate.complete(Unit)
            testScheduler.runCurrent()

            assertTrue(h.activeTransfers().isEmpty(), h.activeTransfers().toString())
            assertTrue(h.status().contains("Downloaded data.bin"), h.status())
        }
    }

    @Test
    fun cancelMidTransferLeavesCancelledAuditAndNoRunningJob() = runTest {
        withHarness(this) { h ->
            h.fake.downloadHandler = { request ->
                request.onProgress(FileTransferProgress(1024, 4096))
                awaitCancellation() // in-flight read: only cancellation (channel disconnect) ends it
            }

            h.viewModel.prepareFileDownload(server, entry("/var/stream.bin", 4096L), PlatformFile(File("/tmp/stream.bin")))
            testScheduler.runCurrent()
            val transferId = h.activeTransfers().single().id

            h.viewModel.cancelFileTransfer(transferId)
            testScheduler.runCurrent()

            // No RUNNING job: the row is gone and the ledger says CANCELLED (NonCancellable write).
            assertTrue(h.activeTransfers().isEmpty(), h.activeTransfers().toString())
            assertTrue(h.status().contains("cancelled"), h.status())
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.CANCELLED, audit.outcome)
            assertEquals("Downloaded File: /var/stream.bin", audit.actionTitle)
        }
    }

    @Test
    fun downloadAboveHundredMBParksForConfirmationUntilConfirmed() = runTest {
        withHarness(this) { h ->
            h.fake.downloadHandler = { 512L }
            val bigSize = 100L * 1024 * 1024 + 1

            h.viewModel.prepareFileDownload(server, entry("/srv/big.iso", bigSize), PlatformFile(File("/tmp/big.iso")))
            testScheduler.runCurrent()

            // Nothing started; the dialog state holds the facts.
            assertTrue(h.activeTransfers().isEmpty(), h.activeTransfers().toString())
            val pending = h.viewModel.netFilesState.value.pendingTransferConfirmation
            assertNotNull(pending)
            assertEquals(FileTransferDirection.DOWNLOAD, pending.direction)
            assertEquals(bigSize, pending.totalBytes)
            assertEquals("big.iso", pending.localName)

            h.viewModel.confirmPendingTransfer()
            testScheduler.runCurrent()

            assertEquals(null, h.viewModel.netFilesState.value.pendingTransferConfirmation)
            assertTrue(h.activeTransfers().isEmpty(), h.activeTransfers().toString())
            assertTrue(h.status().contains("Downloaded big.iso"), h.status())
            assertEquals(1, h.fake.downloadRequests.size)
        }
    }

    @Test
    fun exactlyAtTheThresholdDownloadsStartImmediately() = runTest {
        withHarness(this) { h ->
            h.fake.downloadHandler = { 64L }

            h.viewModel.prepareFileDownload(
                server,
                entry("/srv/edge.bin", LARGE_TRANSFER_CONFIRM_BYTES),
                PlatformFile(File("/tmp/edge.bin")),
            )
            testScheduler.runCurrent()

            assertEquals(null, h.viewModel.netFilesState.value.pendingTransferConfirmation)
            assertTrue(h.activeTransfers().isEmpty(), h.activeTransfers().toString())
            assertTrue(h.status().contains("Downloaded edge.bin"), h.status())
        }
    }

    // --- Uploads --------------------------------------------------------------------------------

    @Test
    fun uploadBelowThresholdStreamsIntoTheBrowsedDirectory() = runTest {
        withHarness(this) { h ->
            h.streams.pickedSize = 8192L
            h.fake.uploadHandler = { request ->
                assertEquals(8192L, request.totalBytes)
                request.onProgress(FileTransferProgress(8192, 8192))
                8192L
            }

            h.viewModel.prepareFileUpload(server, PlatformFile(File("/tmp/picked.bin")))
            testScheduler.runCurrent()

            assertTrue(h.activeTransfers().isEmpty(), h.activeTransfers().toString())
            val request = h.fake.uploadRequests.single()
            assertEquals("/picked.bin", request.remotePath)
            assertTrue(h.status().contains("Uploaded picked.bin to /picked.bin"), h.status())
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals("Uploaded File: /picked.bin", audit.actionTitle)
        }
    }

    @Test
    fun uploadAboveHundredMBRequiresExplicitConfirmation() = runTest {
        withHarness(this) { h ->
            h.streams.pickedName = "huge.img"
            h.streams.pickedSize = 150L * 1024 * 1024
            h.fake.uploadHandler = { 128L }

            h.viewModel.prepareFileUpload(server, PlatformFile(File("/tmp/huge.img")))
            testScheduler.runCurrent()

            assertTrue(h.fake.uploadRequests.isEmpty())
            val pending = h.viewModel.netFilesState.value.pendingTransferConfirmation
            assertNotNull(pending)
            assertEquals(FileTransferDirection.UPLOAD, pending.direction)
            assertEquals("/huge.img", pending.remotePath)
            assertEquals("huge.img", pending.localName)

            h.viewModel.confirmPendingTransfer()
            testScheduler.runCurrent()

            assertEquals(null, h.viewModel.netFilesState.value.pendingTransferConfirmation)
            assertEquals("/huge.img", h.fake.uploadRequests.single().remotePath)
            assertTrue(h.status().contains("Uploaded huge.img"), h.status())
        }
    }

    @Test
    fun dismissingTheConfirmationNeverStartsTheTransfer() = runTest {
        withHarness(this) { h ->
            h.streams.pickedName = "huge.img"
            h.streams.pickedSize = 200L * 1024 * 1024

            h.viewModel.prepareFileUpload(server, PlatformFile(File("/tmp/huge.img")))
            testScheduler.runCurrent()
            assertNotNull(h.viewModel.netFilesState.value.pendingTransferConfirmation)

            h.viewModel.dismissPendingTransfer()
            testScheduler.runCurrent()

            assertEquals(null, h.viewModel.netFilesState.value.pendingTransferConfirmation)
            assertTrue(h.fake.uploadRequests.isEmpty())
            assertTrue(h.fake.downloadRequests.isEmpty())
        }
    }
}
