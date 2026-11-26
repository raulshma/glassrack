package com.raulshma.grack.core.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * The SFTP surface the streamed binary transfer engine needs, kept as an interface so the
 * backup-abort and bounded-buffer logic is unit-testable without sockets: the real adapter
 * (in [PlatformSshClient]) is a thin JSch wrapper, tests drive [SftpStreams] directly.
 */
internal interface SftpStreams {
    /**
     * Opens [remotePath] for reading, wiring [monitor] so JSch reports progress through
     * [SftpProgressMonitor.count] (and the remote size via `init`); missing files raise
     * `SftpException(SSH_FX_NO_SUCH_FILE)`. Pass `null` for an unmonitored read (backups).
     */
    fun openRead(remotePath: String, monitor: SftpProgressMonitor?): InputStream

    /**
     * Opens [remotePath] for writing with `OVERWRITE` semantics, wiring [monitor] so JSch
     * reports progress through [SftpProgressMonitor.count]. [sizeBytes] (-1 when unknown)
     * lets JSch report a real total; pass `null` for [monitor] to transfer unmonitored.
     */
    fun openWrite(remotePath: String, monitor: SftpProgressMonitor?, sizeBytes: Long): OutputStream

    /**
     * Current size of [remotePath], `null` only when the file does not exist; every other
     * failure is rethrown so a transient stat error can never silently skip the backup.
     */
    fun statSizeOrNull(remotePath: String): Long?
}

internal data class BinaryTransferResult(
    val bytesTransferred: Long,
    val backupCaptured: Boolean,
)

/** Fixed-size transfer buffer: uploads and downloads move through it, never a full read. */
internal const val TRANSFER_BUFFER_BYTES: Int = 64 * 1024

/** Progress is throttled to one emission per megabyte (plus the final one). */
internal const val TRANSFER_PROGRESS_EMIT_INTERVAL_BYTES: Long = 1024 * 1024

/**
 * JSch reports transfer progress through [SftpProgressMonitor] callbacks on the transfer
 * thread; this bridge accumulates them so the coroutine loop can read a running count and
 * the reported total without touching JSch internals.
 */
private class ProgressMonitorBridge : SftpProgressMonitor {
    private val counted = AtomicLong(0L)

    @Volatile
    private var total: Long = SftpProgressMonitor.UNKNOWN_SIZE

    val countedBytes: Long get() = counted.get()
    val totalBytes: Long get() = total

    override fun init(op: Int, src: String?, dst: String?, max: Long) {
        total = max
    }

    override fun count(count: Long): Boolean {
        counted.addAndGet(count)
        return true
    }

    override fun end() = Unit
}

/**
 * Streams [remotePath] into [destination] with a fixed-size buffer loop — bounded memory no
 * matter how large the file is. Progress comes from the JSch monitor wired into the read
 * stream and is throttled to [TRANSFER_PROGRESS_EMIT_INTERVAL_BYTES] steps plus a final
 * emission. Cancellation is observed between buffer reads; a blocking read only ever
 * unblocks because the caller (PlatformSshClient) disconnected the channel. The sink is
 * always closed, whatever the outcome. Returns the bytes transferred.
 */
internal suspend fun streamingDownload(
    streams: SftpStreams,
    remotePath: String,
    destination: TransferSink,
    onProgress: suspend (FileTransferProgress) -> Unit,
): Long {
    val bridge = ProgressMonitorBridge()
    try {
        streams.openRead(remotePath, bridge).use { input ->
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
            var lastEmitted = Long.MIN_VALUE
            while (true) {
                // Between buffer reads: after the caller cancelled, stop writing immediately.
                if (!currentCoroutineContext().isActive) throw CancellationException("Transfer cancelled")
                val read = input.read(buffer, 0, buffer.size)
                if (read <= 0) break
                destination.write(buffer, 0, read)
                val counted = bridge.countedBytes
                if (counted - lastEmitted >= TRANSFER_PROGRESS_EMIT_INTERVAL_BYTES) {
                    onProgress(FileTransferProgress(counted, bridge.totalBytes))
                    lastEmitted = counted
                }
            }
        }
        onProgress(FileTransferProgress(bridge.countedBytes, bridge.totalBytes))
        return bridge.countedBytes
    } finally {
        runCatching { destination.close() }
    }
}

/**
 * Streams [source] up to [remotePath] with the same bounded loop as [streamingDownload].
 * Overwriting an existing remote file captures `<remotePath>.bak` first (chunk-wise): a
 * failed backup aborts before the target is touched — identical semantics to the text
 * save path — and a brand-new file simply skips the backup. The source is always closed.
 */
internal suspend fun streamingUpload(
    streams: SftpStreams,
    source: TransferSource,
    remotePath: String,
    totalBytes: Long,
    onProgress: suspend (FileTransferProgress) -> Unit,
): BinaryTransferResult {
    try {
        val backupCaptured = captureRemoteBackup(streams, remotePath)
        val bridge = ProgressMonitorBridge()
        streams.openWrite(remotePath, bridge, totalBytes).use { output ->
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
            var lastEmitted = 0L
            while (true) {
                if (!currentCoroutineContext().isActive) throw CancellationException("Transfer cancelled")
                val read = source.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                val counted = bridge.countedBytes
                if (counted - lastEmitted >= TRANSFER_PROGRESS_EMIT_INTERVAL_BYTES) {
                    onProgress(FileTransferProgress(counted, bridge.totalBytes))
                    lastEmitted = counted
                }
            }
            output.flush()
        }
        onProgress(FileTransferProgress(bridge.countedBytes, bridge.totalBytes))
        return BinaryTransferResult(bridge.countedBytes, backupCaptured)
    } finally {
        runCatching { source.close() }
    }
}

/**
 * Copies the existing [remotePath] to `<remotePath>.bak` chunk by chunk (memory-bounded for
 * arbitrarily large originals). Returns false when the file does not exist yet (new file, no
 * backup); permission and other failures abort the upload exactly like the text save path.
 */
private suspend fun captureRemoteBackup(streams: SftpStreams, remotePath: String): Boolean {
    val currentSize = streams.statSizeOrNull(remotePath) ?: return false
    val backupPath = "$remotePath.bak"
    return try {
        streams.openRead(remotePath, monitor = null).use { input ->
            streams.openWrite(backupPath, monitor = null, sizeBytes = currentSize).use { output ->
                val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                while (true) {
                    if (!currentCoroutineContext().isActive) throw CancellationException("Transfer cancelled")
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
        true
    } catch (failed: SftpException) {
        when (failed.id) {
            ChannelSftp.SSH_FX_NO_SUCH_FILE -> false
            ChannelSftp.SSH_FX_PERMISSION_DENIED -> throw FilePermissionDeniedException(
                "Permission denied reading or backing up $remotePath; upload aborted so the original is preserved",
            )
            else -> throw IllegalStateException(
                "Backup write failed for $remotePath; upload aborted so the original is preserved",
                failed,
            )
        }
    }
}
