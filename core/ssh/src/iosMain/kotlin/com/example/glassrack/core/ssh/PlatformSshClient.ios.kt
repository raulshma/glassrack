package com.example.glassrack.core.ssh

import com.example.glassrack.core.database.GlassrackRepository
import com.example.glassrack.core.model.HostKeyChallenge
import com.example.glassrack.core.model.RemoteFileEntry
import com.example.glassrack.core.model.Server
import com.example.glassrack.core.security.SecureVault

actual class PlatformSshClient actual constructor(
    private val vault: SecureVault,
    private val repository: GlassrackRepository,
) : SshSessionClient {
    override suspend fun testConnection(
        server: Server,
        allServers: List<Server>,
        trustFingerprintOnFirstConnect: Boolean,
    ): Result<HostKeyChallenge?> = Result.failure(UnsupportedOperationException("Native iOS SSH transport pending libssh2 integration"))

    override suspend fun execute(server: Server, allServers: List<Server>, command: String, timeoutMs: Long): SshExecResult =
        SshExecResult(1, "", "Live SSH is not yet supported on iOS; use Android for live SSH transport.", 0L)

    override suspend fun executeStreaming(
        server: Server,
        allServers: List<Server>,
        command: String,
        timeoutMs: Long,
        onChunk: suspend (String) -> Unit,
    ): SshExecResult = execute(server, allServers, command, timeoutMs)

    override suspend fun openShell(server: Server, allServers: List<Server>, cols: Int, rows: Int): SshShellSession =
        throw UnsupportedOperationException("Live SSH is not yet supported on iOS; use Android for live SSH transport.")

    override suspend fun listFiles(server: Server, allServers: List<Server>, remotePath: String): List<RemoteFileEntry> = emptyList()

    override suspend fun readFileText(server: Server, allServers: List<Server>, remotePath: String): String = ""

    override suspend fun writeFileTextWithBackup(server: Server, allServers: List<Server>, remotePath: String, newContent: String) = Unit

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
