package com.raulshma.grack.core.ssh

import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.security.SecureVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class SshExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
)

class HostKeyChallengeException(
    val challenge: HostKeyChallenge,
) : Exception(
    if (challenge.isMismatch) {
        "Host key mismatch for ${challenge.host}:${challenge.port}! Expected ${challenge.expectedFingerprint}, got ${challenge.fingerprintSha256}"
    } else {
        "Verify host key fingerprint ${challenge.fingerprintSha256} for ${challenge.host}:${challenge.port}"
    },
)

interface SshShellSession {
    val outputFlow: Flow<ByteArray>
    val isConnected: StateFlow<Boolean>
    suspend fun sendBytes(bytes: ByteArray)
    suspend fun resizePty(cols: Int, rows: Int)
    suspend fun close()
}

interface SshSessionClient {
    suspend fun testConnection(
        server: Server,
        allServers: List<Server>,
        trustFingerprintOnFirstConnect: Boolean = false,
        transientPassword: String? = null,
    ): Result<HostKeyChallenge?>

    suspend fun execute(
        server: Server,
        allServers: List<Server>,
        command: String,
        timeoutMs: Long = 15_000L,
        stdinText: String? = null,
    ): SshExecResult

    /**
     * Runs [command] streaming stdout/stderr chunks to [onChunk] as they arrive.
     *
     * [timeoutMs] is nullable: `null` means untimed, for long-lived streams such as
     * `logs -f` that only end when the caller cancels. Cancellation (of the calling
     * coroutine, or of a non-null timeout) always disconnects the channel in `finally`,
     * so a cancelled stream leaves no leaked channel behind.
     */
    suspend fun executeStreaming(
        server: Server,
        allServers: List<Server>,
        command: String,
        timeoutMs: Long? = 60_000L,
        stdinText: String? = null,
        onChunk: suspend (String) -> Unit,
    ): SshExecResult

    suspend fun openShell(
        server: Server,
        allServers: List<Server>,
        cols: Int = 80,
        rows: Int = 24,
    ): SshShellSession

    suspend fun listFiles(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        timeoutMs: Long = 15_000L,
    ): List<RemoteFileEntry>

    /**
     * Reads [remotePath] as text. Refused with [TextFileGuardException] when the file is
     * larger than 1 MiB or contains NUL bytes (binary payloads must not enter the editor).
     */
    suspend fun readFileText(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        timeoutMs: Long = 15_000L,
    ): String

    /**
     * Writes [newContent] to [remotePath], first capturing the previous content at
     * `<remotePath>.bak`. Returns true when a `.bak` snapshot was taken (false when the
     * target did not exist yet, so there was nothing to back up). If the backup itself
     * fails, nothing is written and an exception is thrown, so the original file is never
     * lost. Payloads refused by [TextFileGuard] (over 1 MiB or containing NUL bytes) are
     * rejected before any byte reaches the host. Timeout semantics of [timeoutMs] apply.
     *
     * When the write is refused with `SSH_FX_PERMISSION_DENIED`, [FilePermissionDeniedException]
     * is thrown so callers can fall back to the privileged save path (temp upload + `install`).
     */
    suspend fun writeFileTextWithBackup(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        newContent: String,
        timeoutMs: Long = 15_000L,
    ): Boolean

    /**
     * Uploads [content] to [remotePath] over SFTP without any backup semantics: the caller
     * owns placement (used by the privileged save for its temp file). Payloads refused by
     * [TextFileGuard] are rejected before any byte reaches the host.
     */
    suspend fun uploadFileText(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        content: String,
        timeoutMs: Long = 15_000L,
    )

    suspend fun deployPublicKey(
        server: Server,
        allServers: List<Server>,
        publicKeyOpenSsh: String,
    ): SshExecResult

    suspend fun openLocalPortForward(
        tunnelId: String,
        server: Server,
        allServers: List<Server>,
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
    ): Int

    suspend fun closePortForward(tunnelId: String)

    suspend fun disconnect(serverId: String)
    suspend fun disconnectIdle(maxIdleMs: Long)
}

expect class PlatformSshClient(
    vault: SecureVault,
    repository: GlassrackRepository,
) : SshSessionClient

expect object WakeOnLanSender {
    suspend fun sendDirectMagicPacket(
        macAddress: String,
        broadcastIp: String = "255.255.255.255",
        port: Int = 9,
    )
}
