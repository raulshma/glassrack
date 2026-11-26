package com.raulshma.grack.core.ssh

import android.content.Context
import androidx.room3.Room
import com.raulshma.grack.core.database.GlassrackDatabase
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.database.PreferencesStore
import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.security.SecureVault
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import okio.Path.Companion.toPath

/** Scriptable [SshSessionClient] fake; hermetic, no sockets are opened. */
internal class FakeSshSessionClient : SshSessionClient {

    data class ExecRequest(
        val server: Server,
        val command: String,
        val timeoutMs: Long,
        val stdinText: String?,
    )

    data class StreamRequest(
        val server: Server,
        val command: String,
        val timeoutMs: Long,
        val stdinText: String?,
    )

    data class WriteRequest(
        val server: Server,
        val remotePath: String,
        val newContent: String,
        val timeoutMs: Long,
    )

    data class UploadRequest(
        val server: Server,
        val remotePath: String,
        val content: String,
        val timeoutMs: Long,
    )

    val executeRequests = mutableListOf<ExecRequest>()
    val streamRequests = mutableListOf<StreamRequest>()
    val writeRequests = mutableListOf<WriteRequest>()
    val uploadRequests = mutableListOf<UploadRequest>()
    val disconnectIdleCalls = mutableListOf<Long>()

    var executeHandler: suspend (ExecRequest) -> SshExecResult = { SshExecResult(0, "ok", "", 1L) }
    var streamHandler: suspend (StreamRequest) -> SshExecResult = { SshExecResult(0, "ok", "", 1L) }
    var writeFileHandler: suspend (WriteRequest) -> Boolean = { true }
    var uploadFileHandler: suspend (UploadRequest) -> Unit = { }
    var readFileHandler: suspend (server: Server, remotePath: String) -> String = { _, _ -> "" }
    var testConnectionHandler: suspend (server: Server, transientPassword: String?) -> Result<HostKeyChallenge?> = { _, _ -> Result.success(null) }

    override suspend fun execute(
        server: Server,
        allServers: List<Server>,
        command: String,
        timeoutMs: Long,
        stdinText: String?,
    ): SshExecResult {
        val request = ExecRequest(server, command, timeoutMs, stdinText)
        executeRequests += request
        return executeHandler(request)
    }

    override suspend fun executeStreaming(
        server: Server,
        allServers: List<Server>,
        command: String,
        timeoutMs: Long,
        stdinText: String?,
        onChunk: suspend (String) -> Unit,
    ): SshExecResult {
        val request = StreamRequest(server, command, timeoutMs, stdinText)
        streamRequests += request
        return streamHandler(request)
    }

    override suspend fun testConnection(
        server: Server,
        allServers: List<Server>,
        trustFingerprintOnFirstConnect: Boolean,
        transientPassword: String?,
    ): Result<HostKeyChallenge?> = testConnectionHandler(server, transientPassword)

    override suspend fun openShell(
        server: Server,
        allServers: List<Server>,
        cols: Int,
        rows: Int,
    ): SshShellSession = throw UnsupportedOperationException("not used in tests")

    override suspend fun listFiles(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        timeoutMs: Long,
    ): List<RemoteFileEntry> = emptyList()

    override suspend fun readFileText(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        timeoutMs: Long,
    ): String = readFileHandler(server, remotePath)

    override suspend fun writeFileTextWithBackup(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        newContent: String,
        timeoutMs: Long,
    ): Boolean {
        val request = WriteRequest(server, remotePath, newContent, timeoutMs)
        writeRequests += request
        return writeFileHandler(request)
    }

    override suspend fun uploadFileText(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        content: String,
        timeoutMs: Long,
    ) {
        val request = UploadRequest(server, remotePath, content, timeoutMs)
        uploadRequests += request
        uploadFileHandler(request)
    }

    override suspend fun deployPublicKey(
        server: Server,
        allServers: List<Server>,
        publicKeyOpenSsh: String,
    ): SshExecResult = SshExecResult(0, "", "", 0L)

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

    override suspend fun disconnectIdle(maxIdleMs: Long) {
        disconnectIdleCalls += maxIdleMs
    }
}

/**
 * Real [GlassrackRepository] over an in-memory Room database whose query context runs on the
 * test scheduler, so tests are deterministic under virtual time. No network, no filesystem DB.
 */
internal class ControllerHarness(context: Context, testScope: TestScope) {
    val scheduler: TestCoroutineScheduler = testScope.testScheduler

    /** Supervisor scope on the test scheduler; its delay loops follow virtual time. */
    private val controllerContext = testScope.coroutineContext + SupervisorJob()

    /** Pure dispatcher/scheduler context (no job) for Room query coroutines. */
    private val queryContext = testScope.coroutineContext.minusKey(Job)

    private val prefsPath = File(System.getProperty("java.io.tmpdir"))
        .resolve("grack-test-${UUID.randomUUID()}.preferences_pb")
        .absolutePath
        .toPath()

    /** Real vault (Tink file-backed under Robolectric) so sudo secrets can be staged in tests. */
    val vault: SecureVault = SecureVault(context)

    val db: GlassrackDatabase = Room.inMemoryDatabaseBuilder(context, GlassrackDatabase::class.java)
        .setQueryCoroutineContext(queryContext)
        .build()

    val repository: GlassrackRepository = GlassrackRepository(
        db = db,
        vault = vault, // real Tink-backed vault; tests store sudo secrets through it
        preferencesStore = PreferencesStore(
            PreferenceDataStoreFactory.createWithPath(produceFile = { prefsPath }),
        ),
    )

    val fake = FakeSshSessionClient()

    val controller = HomelabController(repository, fake, controllerContext)
}
