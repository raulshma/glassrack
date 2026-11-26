package com.raulshma.grack.core.ssh

import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.security.SecureVault

actual class PlatformSshClient actual constructor(
    private val vault: SecureVault,
    private val repository: GlassrackRepository,
) : SshSessionClient {
    override suspend fun testConnection(
        server: Server,
        allServers: List<Server>,
        trustFingerprintOnFirstConnect: Boolean,
        transientPassword: String?,
    ): Result<HostKeyChallenge?> = Result.failure(UnsupportedOperationException("Native iOS SSH transport pending libssh2 integration"))

    override suspend fun execute(
        server: Server,
        allServers: List<Server>,
        command: String,
        timeoutMs: Long,
        stdinText: String?,
    ): SshExecResult =
        SshExecResult(1, "", "Live SSH is not yet supported on iOS; use Android for live SSH transport.", 0L)

    override suspend fun executeStreaming(
        server: Server,
        allServers: List<Server>,
        command: String,
        timeoutMs: Long,
        stdinText: String?,
        onChunk: suspend (String) -> Unit,
    ): SshExecResult = execute(server, allServers, command, timeoutMs, stdinText)

    override suspend fun openShell(server: Server, allServers: List<Server>, cols: Int, rows: Int): SshShellSession =
        throw UnsupportedOperationException("Live SSH is not yet supported on iOS; use Android for live SSH transport.")

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
    ): String = ""

    override suspend fun writeFileTextWithBackup(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        newContent: String,
        timeoutMs: Long,
    ): Boolean = false

    override suspend fun deployPublicKey(server: Server, allServers: List<Server>, publicKeyOpenSsh: String): SshExecResult =
        execute(server, allServers, "")

    override suspend fun openLocalPortForward(tunnelId: String, server: Server, allServers: List<Server>, localPort: Int, remoteHost: String, remotePort: Int): Int = localPort

    override suspend fun closePortForward(tunnelId: String) = Unit

    override suspend fun disconnect(serverId: String) = Unit

    override suspend fun disconnectIdle(maxIdleMs: Long) = Unit
}

actual object WakeOnLanSender {
    actual suspend fun sendDirectMagicPacket(macAddress: String, broadcastIp: String, port: Int) = Unit
}
