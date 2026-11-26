package com.raulshma.grack.core.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase 6 streamed binary transfers: the bounded-buffer loop, the `SftpProgressMonitor`
 * progress path, and the `.bak`-on-overwrite semantics (backup failure aborts; new file =
 * no backup). Hermetic: [FakeSftpStreams] mirrors the JSch contract (monitor wired into
 * the streams, `SftpException` ids) without any sockets.
 */
class BinaryTransferStreamsTest {

    /** In-memory remote file system mirroring the JSch monitor/exception contract. */
    private class FakeSftpStreams(initialFiles: Map<String, ByteArray> = emptyMap()) : SftpStreams {
        val files = mutableMapOf<String, ByteArray>().apply { putAll(initialFiles) }
        val callLog = mutableListOf<String>()

        /** Path -> exception thrown by [openRead] (drives backup-abort branches). */
        var readError: Pair<String, SftpException>? = null

        /** Path -> exception thrown by [openWrite] (drives the backup-write abort branch). */
        var writeError: Pair<String, SftpException>? = null

        override fun openRead(remotePath: String, monitor: SftpProgressMonitor?): InputStream {
            readError?.let { (path, error) -> if (path == remotePath) throw error }
            val content = files[remotePath]
                ?: throw SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "no such file: $remotePath")
            callLog += "openRead:$remotePath"
            // JSch's monitor-wired get() reports the remote size via init(), then counts.
            monitor?.init(SftpProgressMonitor.GET, remotePath, remotePath, content.size.toLong())
            return ChunkedInputStream(content, monitor)
        }

        override fun openWrite(remotePath: String, monitor: SftpProgressMonitor?, sizeBytes: Long): OutputStream {
            writeError?.let { (path, error) -> if (path == remotePath) throw error }
            callLog += "openWrite:$remotePath"
            monitor?.init(SftpProgressMonitor.PUT, remotePath, remotePath, sizeBytes)
            return RecordingOutputStream(remotePath, files, monitor)
        }

        override fun statSizeOrNull(remotePath: String): Long? = files[remotePath]?.size?.toLong()
    }

    /** Delivers the payload in 256 KiB reads like JSch's internal buffer would. */
    private class ChunkedInputStream(
        private val data: ByteArray,
        private val monitor: SftpProgressMonitor?,
    ) : InputStream() {
        private var position = 0

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= data.size) return -1
            val chunk = minOf(len, data.size - position, 256 * 1024)
            data.copyInto(b, off, position, position + chunk)
            position += chunk
            monitor?.count(chunk.toLong())
            return chunk
        }

        override fun read(): Int =
            if (position >= data.size) -1 else (data[position++].toInt() and 0xFF)
    }

    private class RecordingOutputStream(
        private val path: String,
        private val files: MutableMap<String, ByteArray>,
        private val monitor: SftpProgressMonitor?,
    ) : OutputStream() {
        private val collected = ByteArrayOutputStream()

        override fun write(b: ByteArray, off: Int, len: Int) {
            monitor?.count(len.toLong())
            collected.write(b, off, len)
        }

        override fun write(b: Int) {
            collected.write(b)
        }

        override fun flush() = Unit

        override fun close() {
            files[path] = collected.toByteArray()
        }
    }

    private class RecordingSink : TransferSink {
        val collected = ByteArrayOutputStream()
        val writeLengths = mutableListOf<Int>()

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            collected.write(buffer, offset, length)
            writeLengths += length
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    private class FixedSource(private val payload: ByteArray) : TransferSource {
        private var position = 0
        var reads = 0
            private set
        var closed = false
            private set

        override fun read(buffer: ByteArray): Int {
            reads += 1
            if (position >= payload.size) return -1
            val chunk = minOf(buffer.size, payload.size - position)
            payload.copyInto(buffer, 0, position, position + chunk)
            position += chunk
            return chunk
        }

        override fun close() {
            closed = true
        }
    }

    // --- Downloads ------------------------------------------------------------------------------

    @Test
    fun downloadDeliversChunkWiseWithoutOversizedWrites() = runTest {
        val payload = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        val streams = FakeSftpStreams(mapOf("/media/big.bin" to payload))
        val sink = RecordingSink()
        val progress = mutableListOf<FileTransferProgress>()

        val transferred = streamingDownload(streams, "/media/big.bin", sink) { progress += it }

        assertEquals(payload.size.toLong(), transferred)
        assertEquals(payload.size.toLong(), sink.collected.size().toLong())
        assertContentEquals(payload, sink.collected.toByteArray())
        // Bounded memory: the loop forwards through the fixed-size buffer, never the file.
        assertTrue(sink.writeLengths.size >= (payload.size / TRANSFER_BUFFER_BYTES), sink.writeLengths.size.toString())
        assertTrue(sink.writeLengths.max() <= TRANSFER_BUFFER_BYTES, "largest write ${sink.writeLengths.max()}")
    }

    @Test
    fun downloadProgressComesFromTheMonitorAndEndsAtHundredPercent() = runTest {
        val payload = ByteArray(3 * 1024 * 1024 + 11) { 42 }
        val streams = FakeSftpStreams(mapOf("/media/app.img" to payload))
        val progress = mutableListOf<FileTransferProgress>()

        streamingDownload(streams, "/media/app.img", RecordingSink()) { progress += it }

        assertTrue(progress.isNotEmpty())
        assertEquals(payload.size.toLong(), progress.last().bytesTransferred)
        assertEquals(payload.size.toLong(), progress.last().totalBytes)
        assertEquals(100, progress.last().percent)
        assertTrue(progress.zipWithNext().all { (a, b) -> a.bytesTransferred <= b.bytesTransferred })
    }

    // --- Uploads --------------------------------------------------------------------------------

    @Test
    fun uploadOverwriteCapturesBakBeforeWritingTheTarget() = runTest {
        val original = ByteArray(2048) { 7 }
        val incoming = ByteArray(700 * 1024 + 17) { 3 }
        val streams = FakeSftpStreams(mapOf("/srv/app.iso" to original))

        val result = streamingUpload(streams, FixedSource(incoming), "/srv/app.iso", incoming.size.toLong()) { }

        assertTrue(result.backupCaptured)
        assertEquals(incoming.size.toLong(), result.bytesTransferred)
        assertContentEquals(original, streams.files["/srv/app.iso.bak"])
        assertContentEquals(incoming, streams.files["/srv/app.iso"])
        // Backup happens first: the original content is captured before the target is touched.
        val backupIndex = streams.callLog.indexOf("openWrite:/srv/app.iso.bak")
        val targetIndex = streams.callLog.indexOf("openWrite:/srv/app.iso")
        assertTrue(backupIndex in 0 until targetIndex, streams.callLog.toString())
    }

    @Test
    fun uploadNewFileSkipsTheBackup() = runTest {
        val incoming = ByteArray(1024) { 5 }
        val streams = FakeSftpStreams()

        val result = streamingUpload(streams, FixedSource(incoming), "/srv/new.bin", incoming.size.toLong()) { }

        assertTrue(!result.backupCaptured)
        assertTrue(!streams.files.containsKey("/srv/new.bin.bak"))
        assertContentEquals(incoming, streams.files["/srv/new.bin"])
    }

    @Test
    fun failedBackupWriteAbortsUploadWithTargetUntouched() = runTest {
        val original = ByteArray(512) { 9 }
        val streams = FakeSftpStreams(mapOf("/srv/app.iso" to original))
        streams.writeError = "/srv/app.iso.bak" to SftpException(ChannelSftp.SSH_FX_FAILURE, "disk quota exceeded")

        val error = assertFailsWith<IllegalStateException> {
            streamingUpload(streams, FixedSource(ByteArray(4096)), "/srv/app.iso", 4096L) { }
        }
        assertTrue(error.message!!.contains("aborted"), error.message)
        // The target still holds the original bytes; nothing was overwritten.
        assertContentEquals(original, streams.files["/srv/app.iso"])
        assertTrue(!streams.callLog.contains("openWrite:/srv/app.iso"), streams.callLog.toString())
    }

    @Test
    fun permissionDeniedBackupAbortsAsFilePermissionDeniedWithoutEscalation() = runTest {
        val original = ByteArray(512) { 4 }
        val streams = FakeSftpStreams(mapOf("/etc/app.conf" to original))
        streams.readError = "/etc/app.conf" to SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "permission denied")

        assertFailsWith<FilePermissionDeniedException> {
            streamingUpload(streams, FixedSource(ByteArray(1024)), "/etc/app.conf", 1024L) { }
        }
        assertContentEquals(original, streams.files["/etc/app.conf"])
        assertTrue(!streams.callLog.contains("openWrite:/etc/app.conf"), streams.callLog.toString())
    }

    @Test
    fun vanishedRemoteFileDuringBackupReadIsTreatedAsNewFile() = runTest {
        val streams = FakeSftpStreams()
        // statSizeOrNull sees a file, the read loses the race: upload proceeds, no backup.
        streams.files["/srv/raced.bin"] = ByteArray(16) { 1 }
        streams.readError = "/srv/raced.bin" to SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "vanished")

        val incoming = ByteArray(128) { 2 }
        val result = streamingUpload(streams, FixedSource(incoming), "/srv/raced.bin", incoming.size.toLong()) { }

        assertTrue(!result.backupCaptured)
        assertContentEquals(incoming, streams.files["/srv/raced.bin"])
    }

    // --- Cancellation ---------------------------------------------------------------------------

    @Test
    fun uploadLoopStopsBetweenBufferReadsWhenCancelledAndClosesTheSource() = runTest {
        val incoming = ByteArray(2 * 1024 * 1024) { 6 }
        val streams = FakeSftpStreams()
        val source = FixedSource(incoming)

        val job = launch {
            streamingUpload(streams, source, "/canc.bin", incoming.size.toLong()) { progress ->
                // Park mid-transfer (first megabyte emission) like a stalled onProgress consumer.
                if (progress.bytesTransferred >= 1024 * 1024) awaitCancellation()
            }
        }
        runCurrent()
        assertTrue(source.reads > 0)

        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        // The transfer loop stopped: source closed by the transport, partial bytes only.
        assertTrue(source.closed)
        val partial = streams.files["/canc.bin"]!!.size.toLong()
        assertTrue(partial in 1 until incoming.size.toLong(), "partial=$partial")
    }
}
