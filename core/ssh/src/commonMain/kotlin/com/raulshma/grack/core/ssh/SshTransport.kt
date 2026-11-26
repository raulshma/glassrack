package com.raulshma.grack.core.ssh

import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.security.SecureVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Default budget for streamed binary transfers (image archives, backups); the transfer
 * functions accept `null` for an untimed transfer that only ends on completion or cancel.
 */
const val DEFAULT_TRANSFER_TIMEOUT_MS: Long = 15 * 60_000L

data class SshExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
)

/**
 * Progress of a streamed binary transfer. [totalBytes] is `-1` when the size could not be
 * determined up front (e.g. an upload of unknown length), in which case [fraction] and
 * [percent] are `null` and the UI shows an indeterminate indicator.
 */
data class FileTransferProgress(
    val bytesTransferred: Long,
    val totalBytes: Long = -1L,
) {
    /** 0..1 when [totalBytes] is known and positive, `null` otherwise. Clamped at both ends. */
    val fraction: Float?
        get() = if (totalBytes > 0) (bytesTransferred.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else null

    /** 0..100 when [totalBytes] is known and positive, `null` otherwise. */
    val percent: Int?
        get() = fraction?.let { (it * 100f).toInt().coerceIn(0, 100) }
}

/**
 * Local end of a streamed binary download. Platform code wraps its file stream (on Android
 * a ContentResolver-backed output stream for SAF destinations). The transport owns closing:
 * the sink is always closed when the transfer ends, whatever the outcome.
 */
interface TransferSink {
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun flush()
    fun close()
}

/**
 * Local end of a streamed binary upload. [read] follows the `java.io.InputStream` contract:
 * it fills [buffer] and returns the number of bytes read, or `-1` when the source is
 * exhausted. The transport owns closing.
 */
interface TransferSource {
    fun read(buffer: ByteArray): Int
    fun close()
}

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

    /**
     * Streams [remotePath] from the node into [destination] with a fixed-size buffer loop —
     * never the whole file in memory. Progress is reported through [onProgress] as counted
     * by a JSch `SftpProgressMonitor`; the total is taken from the remote file attributes.
     * Cancelling the calling coroutine disconnects the SFTP channel, which unblocks an
     * in-flight read; the loop also re-checks cancellation between buffer reads so it never
     * keeps writing after the caller gave up. Returns the number of bytes transferred.
     * [timeoutMs] follows [executeStreaming] semantics: `null` means untimed. The sink is
     * closed when the transfer ends (success, failure, timeout or cancellation).
     */
    suspend fun downloadFile(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        destination: TransferSink,
        timeoutMs: Long? = DEFAULT_TRANSFER_TIMEOUT_MS,
        onProgress: suspend (FileTransferProgress) -> Unit = {},
    ): Long

    /**
     * Streams a local [localSource] up to [remotePath] with the same bounded-memory loop as
     * [downloadFile]. When the remote file already exists it is first captured at
     * `<remotePath>.bak` (chunk-wise, so arbitrarily large originals stay memory-bounded);
     * a failed backup aborts the upload so the original is never lost, and a brand-new file
     * simply skips the backup. Overwriting a binary transfer does NOT escalate to the
     * privileged save path on permission errors — that path is text-editor-only.
     * [totalBytes] (-1 when unknown) feeds the `SftpProgressMonitor` total; [onProgress]
     * and cancellation semantics match [downloadFile]. Returns bytes uploaded.
     */
    suspend fun uploadFile(
        server: Server,
        allServers: List<Server>,
        localSource: TransferSource,
        remotePath: String,
        totalBytes: Long = -1L,
        timeoutMs: Long? = DEFAULT_TRANSFER_TIMEOUT_MS,
        onProgress: suspend (FileTransferProgress) -> Unit = {},
    ): Long

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
