package com.raulshma.grack.feature.servers

import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.ssh.FileTransferProgress
import com.raulshma.grack.core.ssh.SshExecResult
import com.raulshma.grack.core.ssh.SshSessionClient
import com.raulshma.grack.core.ssh.SshShellSession
import com.raulshma.grack.core.ssh.TransferSink
import com.raulshma.grack.core.ssh.TransferSource

/** Answer-everything [SshSessionClient] for view-model tests that never reach a node. */
internal class StubSshClient : SshSessionClient {
    override suspend fun execute(server: Server, allServers: List<Server>, command: String, timeoutMs: Long, stdinText: String?): SshExecResult =
        SshExecResult(0, "", "", 1L)

    override suspend fun executeStreaming(
        server: Server,
        allServers: List<Server>,
        command: String,
        timeoutMs: Long?,
        stdinText: String?,
        onChunk: suspend (String) -> Unit,
    ): SshExecResult = SshExecResult(0, "", "", 1L)

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
